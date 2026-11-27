use super::{components, BAD_MESSAGE_BAN_TIME};
use crate::protocols::{Peers, Status, StatusCode};
use crate::storage::scan::{ScanState, ScanStorage};
use crate::storage::{LightClientStorage, Storage};
use crate::types::{Duration, Instant, RwLock};
use crate::utils::network::prove_or_download_matched_blocks;
use crate::{read_lock, write_lock};
use ckb_constant::sync::INIT_BLOCKS_IN_TRANSIT_PER_PEER;
use ckb_network::{
    async_trait, bytes::Bytes, BoxedCKBProtocolContext, CKBProtocolHandler, PeerIndex,
};
use ckb_types::{core::BlockNumber, packed, prelude::*};
use golomb_coded_set::{GCSFilterReader, SipHasher24Builder, M, P};
use log::{debug, info, log_enabled, trace, warn, Level};
use rand::seq::SliceRandom as _;
use std::io::Cursor;
use std::sync::Arc;

pub(crate) const GET_BLOCK_FILTERS_TOKEN: u64 = 0;
pub(crate) const GET_BLOCK_FILTER_HASHES_TOKEN: u64 = 1;
pub(crate) const GET_BLOCK_FILTER_CHECK_POINTS_TOKEN: u64 = 2;

pub(crate) const GET_BLOCK_FILTERS_DURATION: Duration = Duration::from_secs(3);
pub(crate) const GET_BLOCK_FILTER_HASHES_DURATION: Duration = Duration::from_secs(10);
pub(crate) const GET_BLOCK_FILTER_CHECK_POINTS_DURATION: Duration = Duration::from_secs(30);

const GET_BLOCK_FILTERS_TIMEOUT: Duration = Duration::from_secs(15);

pub struct FilterProtocol {
    pub(crate) storage: Storage,
    pub(crate) peers: Arc<Peers>,
    pub(crate) windows: RwLock<super::windows::FilterWindows>,
    hash_downloads: RwLock<super::windows::HashDownloads>,
    pub(crate) scheduler: RwLock<super::scheduler::ScanScheduler>,
    pub(crate) last_ask_time: Arc<RwLock<Option<Instant>>>,
}

impl FilterProtocol {
    pub fn new(storage: Storage, peers: Arc<Peers>) -> Self {
        Self {
            storage,
            peers,
            windows: Default::default(),
            hash_downloads: Default::default(),
            scheduler: Default::default(),
            last_ask_time: Arc::new(Default::default()),
        }
    }

    pub fn check_filters_data(
        &self,
        block_filters: packed::BlockFilters,
        limit: usize,
    ) -> Result<Vec<packed::Byte32>, Status> {
        let start_number: BlockNumber = block_filters.start_number().unpack();
        let script_hashes = self
            .storage
            .get_scripts_hash(start_number + limit as BlockNumber);
        self.check_filters_for_hashes(block_filters, limit, &script_hashes)
    }

    pub(crate) fn check_filters_for_hashes(
        &self,
        block_filters: packed::BlockFilters,
        limit: usize,
        script_hashes: &[packed::Byte32],
    ) -> Result<Vec<packed::Byte32>, Status> {
        let reader = GCSFilterReader::new(SipHasher24Builder::new(0, 0), M, P);
        let mut matched = Vec::new();
        for (index, block_filter) in block_filters.filters().into_iter().take(limit).enumerate() {
            let mut input = Cursor::new(block_filter.raw_data());
            let is_match = reader
                .match_any(&mut input, &mut script_hashes.iter().map(|v| v.as_slice()))
                .map_err(|e| {
                    let errmsg = format!("GCSFilterReader#match_any failed: {}", e);
                    StatusCode::MalformedProtocolMessage.with_context(errmsg)
                })?;
            if is_match {
                let block_hash = match block_filters.block_hashes().get(index) {
                    Some(h) => h,
                    None => {
                        let errmsg = format!(
                            "block_hashes index {} out of bounds (len: {})",
                            index,
                            block_filters.block_hashes().len()
                        );
                        return Err(StatusCode::MalformedProtocolMessage.with_context(errmsg));
                    }
                };
                info!("check_filters_data matched, block_hash: {:#x}", block_hash);
                matched.push(block_hash);
            } else {
                match block_filters.block_hashes().get(index) {
                    Some(h) => trace!("check_filters_data not matched, block_hash: {:#x}", h),
                    None => {
                        let errmsg = format!(
                            "block_hashes index {} out of bounds (len: {})",
                            index,
                            block_filters.block_hashes().len()
                        );
                        return Err(StatusCode::MalformedProtocolMessage.with_context(errmsg));
                    }
                }
            }
        }
        Ok(matched)
    }

    async fn should_ask(&self, immediately: bool) -> bool {
        let last_ask = read_lock!(self.last_ask_time);
        !self.storage.is_filter_scripts_empty()
            && (immediately
                || last_ask.is_none()
                || last_ask.unwrap().elapsed() > GET_BLOCK_FILTERS_TIMEOUT)
    }
    pub async fn update_min_filtered_block_number(&self, block_number: BlockNumber) {
        self.storage.update_min_filtered_block_number(block_number);
        self.peers
            .update_min_filtered_block_number(block_number)
            .await;
        write_lock!(self.last_ask_time).replace(Instant::now());
    }
    pub(crate) async fn try_send_get_block_filters(
        &self,
        nc: BoxedCKBProtocolContext,
        immediately: bool,
    ) {
        let scan_states = {
            let mut matched = self.peers.matched_blocks().write().await;
            let result = crate::utils::scans::retry_missing(&self.storage, &self.peers)
                .and_then(|()| crate::utils::scans::settle_ready(&self.storage, &mut matched));
            if let Ok(Some(states)) = &result {
                crate::utils::scans::service_proofs(
                    states,
                    Arc::clone(&self.peers),
                    &self.storage.get_tip_header(),
                    &matched,
                    Arc::clone(&nc),
                    INIT_BLOCKS_IN_TRANSIT_PER_PEER,
                )
                .await;
            }
            result
        };
        match scan_states {
            Ok(Some(states)) => {
                // Each script has its own contiguous cursor and outstanding
                // request. Historical proof debt must not hold this selection.
                let mut proved = self.peers.get_all_prove_states();
                proved.sort_by_key(|(_, state)| {
                    std::cmp::Reverse(state.get_last_header().total_difficulty())
                });
                let Some((_, proof)) = proved.first() else {
                    return;
                };
                let tip = proof.get_last_header().header().number();
                let now = ckb_systemtime::unix_time_as_millis();
                let (checkpoint, _) = self.storage.get_last_check_point();
                for _ in 0..states.len() {
                    let selected = write_lock!(self.scheduler).select(&states, tip, now);
                    let Some(mut request) = selected else {
                        break;
                    };
                    let ready = if request.start <= self.peers.calc_check_point_number(checkpoint) {
                        {
                            request.window = self.scoped_window(request.start, request.epoch).await;
                            request.window.is_some()
                        }
                    } else {
                        self.peers
                            .could_request_more_block_filters(checkpoint, request.start - 1)
                            .await
                    };
                    if !ready {
                        continue;
                    }
                    let eligible: Vec<_> = proved
                        .iter()
                        .filter(|(_, state)| {
                            state.get_last_header().header().number() >= request.start
                        })
                        .map(|(peer, _)| *peer)
                        .collect();
                    let Some(peer) = read_lock!(self.scheduler).choose_peer(&request, &eligible)
                    else {
                        continue;
                    };
                    write_lock!(self.scheduler).sent(&request, peer, now);
                    self.send_get_block_filters(Arc::clone(&nc), peer, request.start);
                    break;
                }
                self.request_scoped_hashes(&states, nc).await;
                return;
            }
            Err(error) => {
                warn!(
                    "scoped scan metadata unavailable; refusing unscoped fallback: {}",
                    error
                );
                return;
            }
            Ok(None) => {}
        }
        let min_filtered_block_number = self.storage.get_min_filtered_block_number();
        let start_number = min_filtered_block_number + 1;
        let (finalized_check_point_index, _) = self.storage.get_last_check_point();
        let could_ask_more = self
            .peers
            .could_request_more_block_filters(
                finalized_check_point_index,
                min_filtered_block_number,
            )
            .await;
        if log_enabled!(Level::Trace) {
            let finalized_check_point_number = self
                .peers
                .calc_check_point_number(finalized_check_point_index);
            let (cached_check_point_index, cached_hashes) =
                self.peers.get_cached_block_filter_hashes().await;

            let cached_check_point_number =
                self.peers.calc_check_point_number(cached_check_point_index);
            let next_cached_check_point_number = self
                .peers
                .calc_check_point_number(cached_check_point_index + 1);
            trace!(
                "could request block filters from {} or not: {}, \
                finalized: index {}, number {}; \
                cached: index {}, number {}, length {}; \
                next cached: number {}",
                start_number,
                could_ask_more,
                finalized_check_point_index,
                finalized_check_point_number,
                cached_check_point_index,
                cached_check_point_number,
                cached_hashes.len(),
                next_cached_check_point_number
            );
        }
        if let Some((peer, _prove_state)) = self
            .peers
            .get_all_prove_states()
            .iter()
            .max_by_key(|(_, prove_state)| prove_state.get_last_header().total_difficulty())
        {
            debug!("found best proved peer {}", peer);

            let mut matched_blocks = self.peers.matched_blocks().write().await;
            if let Some(db_matched_blocks) = self.storage.get_earliest_matched_blocks() {
                debug!(
                    "try recover matched blocks from storage, start_number={}, \
                             blocks_count={}, matched_count: {}",
                    db_matched_blocks.start_number,
                    db_matched_blocks.blocks_count,
                    matched_blocks.len(),
                );
                if db_matched_blocks.blocks.iter().any(|block| {
                    self.peers
                        .get_header_fetch_info(&block.hash)
                        .is_some_and(|(_, _, missing)| missing)
                }) {
                    // A surviving in-memory subset cannot settle this durable identity.
                    self.storage
                        .remove_matched_blocks(db_matched_blocks.start_number);
                    for block in &db_matched_blocks.blocks {
                        self.peers.remove_fetching_header(&block.hash);
                    }
                    matched_blocks.clear();
                    return;
                }
                if matched_blocks.is_empty() {
                    let filtered_blocks = db_matched_blocks
                        .blocks
                        .iter()
                        .map(|b| (b.hash.clone(), b.proved))
                        .collect();
                    // recover matched blocks from storage (only non-missing ones)
                    self.peers
                        .add_matched_blocks(&mut matched_blocks, filtered_blocks);
                    let tip_header = self.storage.get_tip_header();
                    prove_or_download_matched_blocks(
                        Arc::clone(&self.peers),
                        &tip_header,
                        &matched_blocks,
                        nc.as_ref(),
                        INIT_BLOCKS_IN_TRANSIT_PER_PEER,
                    );
                    if could_ask_more {
                        debug!(
                            "send get block filters to {}, start_number={}",
                            peer, start_number
                        );
                        self.send_get_block_filters(nc, *peer, start_number);
                    }
                } else {
                    debug!("matched blocks are not empty:");
                    matched_blocks.iter().for_each(|matched_block| {
                        debug!("matched_block: {}, {:?}", matched_block.0, matched_block.1);
                    });
                }
            } else if self.should_ask(immediately).await && could_ask_more {
                debug!(
                    "send get block filters to {}, start_number={}",
                    peer, start_number
                );
                self.send_get_block_filters(nc, *peer, start_number);
            } else {
                trace!("no block filters is required to download");
            }
        } else {
            debug!("cannot find peers which are proved");
        }
    }

    pub(crate) async fn scoped_window(
        &self,
        start: u64,
        epoch: [u8; 16],
    ) -> Option<Arc<super::windows::Window>> {
        let interval = self.peers.calc_check_point_number(1);
        let wanted = u32::try_from(start.checked_sub(1)? / interval).ok()?;
        let (active, hashes) = self.peers.get_cached_block_filter_hashes().await;
        let anchors = self.storage.get_check_points(active, 2);
        if anchors.len() == 2 {
            write_lock!(self.windows).insert(
                epoch,
                active,
                anchors[0].clone(),
                anchors[1].clone(),
                hashes,
                interval as usize,
            );
        }
        let anchors = self.storage.get_check_points(wanted, 2);
        if anchors.len() != 2 {
            return None;
        }
        read_lock!(self.windows).get(epoch, wanted, &anchors[0], &anchors[1])
    }

    async fn request_scoped_hashes(&self, states: &[ScanState], nc: BoxedCKBProtocolContext) {
        let Some(first) = states.first() else {
            return;
        };
        let finalized = self.storage.get_last_check_point().0;
        let finalized_number = self.peers.calc_check_point_number(finalized);
        let (active, hashes) = self.peers.get_cached_block_filter_hashes().await;
        let now = ckb_systemtime::unix_time_as_millis();
        let interval = self.peers.calc_check_point_number(1);
        let anchors = self.storage.get_check_points(active, 2);
        if anchors.len() == 2 {
            write_lock!(self.hash_downloads).remember(super::windows::Window {
                epoch: first.epoch,
                index: active,
                parent: anchors[0].clone(),
                end: anchors[1].clone(),
                hashes: hashes.clone(),
            });
        }
        let mut missing = Vec::new();
        let mut needed = Vec::new();
        for state in states.iter().filter(|s| s.settled < finalized_number) {
            let Ok(index) = u32::try_from(state.settled / interval) else {
                continue;
            };
            let anchors = self.storage.get_check_points(index, 2);
            if anchors.len() == 2 {
                needed.push((index, anchors[0].clone(), anchors[1].clone()));
            }
            if state.pending.is_none()
                && self
                    .scoped_window(state.settled + 1, first.epoch)
                    .await
                    .is_none()
            {
                missing.push(index);
            }
        }
        write_lock!(self.hash_downloads).retain(first.epoch, &needed);
        let next = write_lock!(self.hash_downloads).next(active, hashes.len(), &missing, now);
        if let Some(next) = next {
            let anchors = self.storage.get_check_points(next, 2);
            if anchors.len() == 2 {
                let hashes = write_lock!(self.hash_downloads).restore(
                    first.epoch,
                    next,
                    &anchors[0],
                    &anchors[1],
                    now,
                );
                self.peers
                    .update_min_filtered_block_number(self.peers.calc_check_point_number(next))
                    .await;
                self.peers.update_cached_block_filter_hashes(hashes).await;
            }
        }
        if let Some(start) = self
            .peers
            .if_cached_block_filter_hashes_require_update(finalized)
            .await
        {
            if let Some((peer, _)) = self
                .peers
                .get_all_proved_check_points()
                .into_iter()
                .find(|(_, (index, _))| *index >= finalized)
            {
                self.send_get_block_filter_hashes(Arc::clone(&nc), peer, start);
            }
        }
        for (peer, start) in self
            .peers
            .get_peers_which_require_more_latest_block_filter_hashes(finalized)
        {
            self.send_get_block_filter_hashes(Arc::clone(&nc), peer, start);
        }
    }

    pub(crate) async fn try_send_get_block_filter_hashes(&self, nc: BoxedCKBProtocolContext) {
        match self.storage.scan_states() {
            Ok(Some(states)) => {
                self.request_scoped_hashes(&states, nc).await;
                return;
            }
            Err(error) => {
                warn!("scoped hashes unavailable: {}", error);
                return;
            }
            Ok(None) => {}
        }
        let min_filtered_block_number = self.storage.get_min_filtered_block_number();
        self.peers
            .update_min_filtered_block_number(min_filtered_block_number)
            .await;

        let finalized_check_point_index = self.storage.get_max_check_point_index();
        let cached_check_point_index = self.peers.get_cached_block_filter_hashes().await.0;

        if let Some(start_number) = self
            .peers
            .if_cached_block_filter_hashes_require_update(finalized_check_point_index)
            .await
        {
            let best_peers = self
                .peers
                .get_all_proved_check_points()
                .into_iter()
                .filter_map(|(peer_index, (cpindex, _check_points))| {
                    if cpindex >= finalized_check_point_index {
                        Some(peer_index)
                    } else {
                        None
                    }
                })
                .collect::<Vec<_>>();
            if let Some(peer) = best_peers.choose(&mut rand::thread_rng()).cloned() {
                self.send_get_block_filter_hashes(Arc::clone(&nc), peer, start_number);
            }
        } else if cached_check_point_index >= finalized_check_point_index {
            let peers = self
                .peers
                .get_peers_which_require_more_latest_block_filter_hashes(
                    finalized_check_point_index,
                );
            for (peer, start_number) in peers {
                self.send_get_block_filter_hashes(Arc::clone(&nc), peer, start_number);
            }
        }
    }
}

impl FilterProtocol {
    async fn try_process(
        &self,
        nc: BoxedCKBProtocolContext,
        peer: PeerIndex,
        message: packed::BlockFilterMessageUnionReader<'_>,
    ) -> Status {
        match message {
            packed::BlockFilterMessageUnionReader::BlockFilterCheckPoints(reader) => {
                components::BlockFilterCheckPointsProcess::new(reader, self, nc, peer).execute()
            }
            packed::BlockFilterMessageUnionReader::BlockFilterHashes(reader) => {
                components::BlockFilterHashesProcess::new(reader, self, nc, peer)
                    .execute()
                    .await
            }
            packed::BlockFilterMessageUnionReader::BlockFilters(reader) => {
                components::BlockFiltersProcess::new(reader, self, nc, peer)
                    .execute()
                    .await
            }
            _ => StatusCode::UnexpectedProtocolMessage.into(),
        }
    }

    pub(crate) fn send_get_block_filters(
        &self,
        nc: BoxedCKBProtocolContext,
        peer: PeerIndex,
        start_number: BlockNumber,
    ) {
        debug!(
            "request block filter from peer {}, starts at {}",
            peer, start_number
        );
        let content = packed::GetBlockFilters::new_builder()
            .start_number(start_number)
            .build();
        let message = packed::BlockFilterMessage::new_builder()
            .set(content)
            .build();
        if let Err(err) = nc.send_message_to(peer, message.as_bytes()) {
            let error_message = format!("nc.send_message GetBlockFilters, error: {:?}", err);
            info!("{}", error_message);
        }
    }

    pub(crate) fn send_get_block_filter_hashes(
        &self,
        nc: BoxedCKBProtocolContext,
        peer: PeerIndex,
        start_number: BlockNumber,
    ) {
        trace!(
            "request block filter hashes from peer {}, starts at {}",
            peer,
            start_number
        );
        let content = packed::GetBlockFilterHashes::new_builder()
            .start_number(start_number)
            .build();
        let message = packed::BlockFilterMessage::new_builder()
            .set(content)
            .build();
        if let Err(err) = nc.send_message_to(peer, message.as_bytes()) {
            let error_message = format!("nc.send_message GetBlockFilterHashes, error: {:?}", err);
            info!("{}", error_message);
        }
    }

    pub(crate) fn send_get_block_filter_check_points(
        &self,
        nc: BoxedCKBProtocolContext,
        peer: PeerIndex,
        start_number: BlockNumber,
    ) {
        trace!(
            "request check points from peer {}, starts at {}",
            peer,
            start_number
        );
        let content = packed::GetBlockFilterCheckPoints::new_builder()
            .start_number(start_number)
            .build();
        let message = packed::BlockFilterMessage::new_builder()
            .set(content)
            .build();
        if let Err(err) = nc.send_message_to(peer, message.as_bytes()) {
            let error_message = format!(
                "nc.send_message GetBlockFilterCheckPoints, error: {:?}",
                err
            );
            info!("{}", error_message);
        }
    }
}

#[async_trait]
impl CKBProtocolHandler for FilterProtocol {
    async fn init(&mut self, nc: BoxedCKBProtocolContext) {
        nc.set_notify(GET_BLOCK_FILTERS_DURATION, GET_BLOCK_FILTERS_TOKEN)
            .await
            .expect("set_notify should be ok");
        nc.set_notify(
            GET_BLOCK_FILTER_HASHES_DURATION,
            GET_BLOCK_FILTER_HASHES_TOKEN,
        )
        .await
        .expect("set_notify should be ok");
        nc.set_notify(
            GET_BLOCK_FILTER_CHECK_POINTS_DURATION,
            GET_BLOCK_FILTER_CHECK_POINTS_TOKEN,
        )
        .await
        .expect("set_notify should be ok");
    }

    async fn connected(&mut self, _nc: BoxedCKBProtocolContext, peer: PeerIndex, version: &str) {
        debug!("FilterProtocol({}).connected peer={}", version, peer);
    }

    async fn disconnected(&mut self, _nc: BoxedCKBProtocolContext, peer: PeerIndex) {
        debug!("FilterProtocol.disconnected peer={}", peer);
    }

    async fn received(&mut self, nc: BoxedCKBProtocolContext, peer: PeerIndex, data: Bytes) {
        let msg = match packed::BlockFilterMessageReader::from_slice(&data) {
            Ok(msg) => msg.to_enum(),
            _ => {
                warn!(
                    "FilterProtocol.received a malformed message from Peer({})",
                    peer
                );
                nc.ban_peer(
                    peer,
                    BAD_MESSAGE_BAN_TIME,
                    String::from("send us a malformed message"),
                );
                return;
            }
        };

        let item_name = msg.item_name();
        let status = self.try_process(Arc::clone(&nc), peer, msg).await;
        status
            .process(nc, &self.peers, peer, "BlockFilter", item_name)
            .await;
    }

    async fn notify(&mut self, nc: BoxedCKBProtocolContext, token: u64) {
        match token {
            GET_BLOCK_FILTERS_TOKEN => {
                self.try_send_get_block_filters(nc, false).await;
            }
            GET_BLOCK_FILTER_HASHES_TOKEN => {
                self.try_send_get_block_filter_hashes(nc).await;
            }
            GET_BLOCK_FILTER_CHECK_POINTS_TOKEN => {
                let peers = self.peers.get_peers_which_require_more_check_points();
                for (peer, start_number) in peers {
                    self.send_get_block_filter_check_points(Arc::clone(&nc), peer, start_number);
                }
            }
            _ => unreachable!(),
        }
    }
}
