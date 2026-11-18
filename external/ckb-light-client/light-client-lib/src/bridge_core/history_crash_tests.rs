//! Real SQLite commit-boundary interruption fixtures for matched indexing debt.
use super::*;
use crate::storage::{
    BatchWriter, FilterMapFn, IteratorDirection, IteratorStart, KVPair, LightClientStorage,
    ScriptStatus, ScriptType, SetScriptsCommand, StorageBackend, TakeWhileFn,
};
use ckb_types::core::{BlockBuilder, TransactionBuilder};
use std::sync::{
    atomic::{AtomicUsize, Ordering},
    Arc,
};

struct CrashStorage {
    inner: Storage,
    commits: Arc<AtomicUsize>,
    stop: usize,
    after: bool,
}
struct CrashBatch {
    inner: <Storage as StorageBackend>::Batch,
    commits: Arc<AtomicUsize>,
    stop: usize,
    after: bool,
}
impl BatchWriter for CrashBatch {
    fn put(&mut self, key: &[u8], value: &[u8]) {
        self.inner.put(key, value);
    }
    fn delete(&mut self, key: &[u8]) {
        self.inner.delete(key);
    }
    fn compare_and_put(
        &mut self,
        key: &[u8],
        expected: Option<&[u8]>,
        value: &[u8],
    ) -> crate::error::Result<()> {
        self.inner.compare_and_put(key, expected, value)
    }
    fn commit(self) -> crate::error::Result<()> {
        let at = self.commits.fetch_add(1, Ordering::SeqCst) + 1;
        assert!(
            at != self.stop || self.after,
            "interrupted before commit {at}"
        );
        self.inner.commit()?;
        assert!(
            at != self.stop || !self.after,
            "interrupted after commit {at}"
        );
        Ok(())
    }
}
impl StorageBackend for CrashStorage {
    type Batch = CrashBatch;
    fn get(&self, key: Vec<u8>) -> crate::error::Result<Option<Vec<u8>>> {
        StorageBackend::get(&self.inner, key)
    }
    fn put(&self, key: Vec<u8>, value: Vec<u8>) -> crate::error::Result<()> {
        StorageBackend::put(&self.inner, key, value)
    }
    fn delete(&self, key: &[u8]) -> crate::error::Result<()> {
        StorageBackend::delete(&self.inner, key)
    }
    fn batch(&self) -> Self::Batch {
        CrashBatch {
            inner: StorageBackend::batch(&self.inner),
            commits: self.commits.clone(),
            stop: self.stop,
            after: self.after,
        }
    }
    fn collect_iterator(
        &self,
        start: IteratorStart,
        direction: IteratorDirection,
        take: TakeWhileFn,
        map: FilterMapFn,
        limit: usize,
    ) -> Vec<KVPair> {
        StorageBackend::collect_iterator(&self.inner, start, direction, take, map, limit)
    }
}
impl LightClientStorage for CrashStorage {}

struct Fixture {
    script: packed::Script,
    blocks: Vec<packed::Block>,
    funding: packed::Transaction,
    live: packed::Transaction,
    identity: RecoveryIdentity,
    chain: ChainSnapshot,
}
fn setup(storage: &Storage) -> Fixture {
    storage.init_genesis_block(BlockBuilder::default().build().data());
    let script = packed::Script::default();
    storage.update_filter_scripts(
        vec![ScriptStatus {
            script: script.clone(),
            script_type: ScriptType::Lock,
            block_number: 0,
        }],
        SetScriptsCommand::All,
    );
    let output = |capacity| {
        packed::CellOutput::new_builder()
            .capacity(capacity)
            .lock(script.clone())
            .build()
    };
    let funding = TransactionBuilder::default()
        .output(output(100u64))
        .output_data(Vec::<u8>::new().pack())
        .build()
        .data();
    let spent = TransactionBuilder::default()
        .input(
            packed::CellInput::new_builder()
                .previous_output(
                    packed::OutPoint::new_builder()
                        .tx_hash(funding.calc_tx_hash())
                        .index(0u32)
                        .build(),
                )
                .build(),
        )
        .build()
        .data();
    let live = TransactionBuilder::default()
        .output(output(101u64))
        .output_data(Vec::<u8>::new().pack())
        .build()
        .data();
    let blocks: Vec<_> = [funding.clone(), spent, live.clone()]
        .into_iter()
        .enumerate()
        .map(|(index, tx)| {
            BlockBuilder::default()
                .epoch(ckb_types::core::EpochNumberWithFraction::new(0, 0, 100).full_value())
                .number(10u64 + index as u64)
                .transaction(tx.into_view())
                .build()
                .data()
        })
        .collect();
    let chain = ChainSnapshot {
        anchor: blocks[2].header(),
    };
    storage.update_last_state(&ckb_types::U256::zero(), &chain.anchor, &[]);
    storage.add_matched_blocks(
        10,
        3,
        blocks
            .iter()
            .map(|b| (b.calc_header_hash(), true))
            .collect(),
    );
    storage.update_min_filtered_block_number(12);
    Fixture {
        script,
        blocks,
        funding,
        live,
        identity: RecoveryIdentity {
            genesis_hash: packed::Byte32::default(),
            store_generation: "crash-fixture".into(),
            run_nonce: [1; 16],
        },
        chain,
    }
}
fn cell(storage: &Storage, script: &packed::Script, number: u64) -> Option<Vec<u8>> {
    StorageBackend::get(
        storage,
        Key::CellLockScript(script, number, 0, 0).into_vec(),
    )
    .unwrap()
}
fn assert_final_and_undo(storage: &Storage, f: &Fixture) {
    let proof = snapshot(storage, &f.script, &f.identity, &f.chain, "TESTNET").unwrap();
    assert_eq!(proof["hashes"].as_array().unwrap().len(), 3);
    assert_eq!(proof["startHeight"], "0");
    assert_eq!(proof["durableCoverageVersion"], 1);
    assert!(
        cell(storage, &f.script, 10).is_none(),
        "spent funding must not resurrect on replay"
    );
    assert_eq!(
        cell(storage, &f.script, 12).unwrap(),
        f.live.calc_tx_hash().as_slice()
    );
    storage.rollback_to_block(11);
    assert_eq!(
        cell(storage, &f.script, 10).unwrap(),
        f.funding.calc_tx_hash().as_slice()
    );
    assert!(cell(storage, &f.script, 12).is_none());
    storage.rollback_to_block(10);
    assert!(cell(storage, &f.script, 10).is_none());
    assert!(cell(storage, &f.script, 12).is_none());
    assert!(snapshot(storage, &f.script, &f.identity, &f.chain, "TESTNET").is_err());
}

#[test]
fn range_reopens_before_and_after_each_index_and_final_ack_commit() {
    // Commits 1..3 index funding, spend and new live output; commit 4 atomically
    // advances progress and deletes the debt. Each case closes every handle.
    for stop in 1..=4 {
        for after in [false, true] {
            let (storage, dir) = crate::tests::utils::new_storage("matched-range-crash");
            let f = setup(&storage);
            let crashing = CrashStorage {
                inner: storage,
                commits: Arc::new(AtomicUsize::new(0)),
                stop,
                after,
            };
            let mut blocks = f.blocks.clone();
            blocks.reverse();
            assert!(std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
                crashing.index_matched_range(10, 3, blocks);
            }))
            .is_err());
            drop(crashing);
            let storage = Storage::new(dir.path().join("store.db"));
            if stop == 4 && after {
                assert!(storage.get_earliest_matched_blocks().is_none());
            } else {
                assert!(
                    storage.get_earliest_matched_blocks().is_some(),
                    "debt lost at commit {stop}, after={after}"
                );
                assert!(snapshot(&storage, &f.script, &f.identity, &f.chain, "TESTNET").is_err());
                let prefix = [
                    vec![KeyPrefix::CellLockScript as u8],
                    extract_raw_data(&f.script),
                ]
                .concat();
                assert!(
                    cells_ready(&storage, &prefix, &f.chain).is_err(),
                    "partial live cells cannot authorize spending"
                );
                storage.index_matched_range(10, 3, f.blocks.clone());
            }
            assert_final_and_undo(&storage, &f);
        }
    }
}

#[test]
fn discarded_ranges_reopen_with_coherent_rewind_before_and_after_commit() {
    for after in [false, true] {
        let (storage, dir) = crate::tests::utils::new_storage("discard-range-crash");
        let f = setup(&storage);
        storage.add_matched_blocks(5000, 1, vec![(packed::Byte32::default(), false)]);
        storage.filter_block(f.blocks[0].clone());
        storage.filter_block(f.blocks[1].clone());
        let crashing = CrashStorage {
            inner: storage,
            commits: Arc::new(AtomicUsize::new(0)),
            stop: 1,
            after,
        };
        assert!(std::panic::catch_unwind(std::panic::AssertUnwindSafe(
            || crashing.remove_matched_blocks(10)
        ))
        .is_err());
        drop(crashing);
        let storage = Storage::new(dir.path().join("store.db"));
        assert!(snapshot(&storage, &f.script, &f.identity, &f.chain, "TESTNET").is_err());
        if after {
            assert!(storage.get_earliest_matched_blocks().is_none());
            assert!(storage.get_min_filtered_block_number() < 10);
            storage.update_block_number(storage.get_min_filtered_block_number());
            assert!(snapshot(&storage, &f.script, &f.identity, &f.chain, "TESTNET").is_err());
            storage.add_matched_blocks(
                10,
                3,
                f.blocks
                    .iter()
                    .map(|b| (b.calc_header_hash(), true))
                    .collect(),
            );
            storage.update_min_filtered_block_number(12);
        } else {
            assert!(storage.get_earliest_matched_blocks().is_some());
            assert_eq!(
                storage.get_latest_matched_blocks().unwrap().start_number,
                5000
            );
            // Remove only the deliberately unindexed suffix before replaying.
            storage.remove_matched_blocks(5000);
            storage.update_min_filtered_block_number(12);
        }
        storage.index_matched_range(10, 3, f.blocks.clone());
        assert_final_and_undo(&storage, &f);
    }
}

#[test]
fn discarding_before_index_keeps_old_live_cells_unavailable_until_spend_is_rebuilt() {
    let (storage, dir) = crate::tests::utils::new_storage("discard-before-index");
    let mut f = setup(&storage);
    let prior = TransactionBuilder::default()
        .output(
            packed::CellOutput::new_builder()
                .capacity(99u64)
                .lock(f.script.clone())
                .build(),
        )
        .output_data(Vec::<u8>::new().pack())
        .build();
    let prefix_block = BlockBuilder::default()
        .epoch(ckb_types::core::EpochNumberWithFraction::new(0, 0, 100).full_value())
        .number(5u64)
        .transaction(prior.clone())
        .build()
        .data();
    storage.filter_block(prefix_block);
    storage.update_block_number(5);
    let spend = TransactionBuilder::default()
        .input(
            packed::CellInput::new_builder()
                .previous_output(
                    packed::OutPoint::new_builder()
                        .tx_hash(prior.hash())
                        .index(0u32)
                        .build(),
                )
                .build(),
        )
        .build();
    f.blocks[0] = BlockBuilder::default()
        .epoch(ckb_types::core::EpochNumberWithFraction::new(0, 0, 100).full_value())
        .number(10u64)
        .transaction(spend)
        .build()
        .data();
    storage.add_matched_blocks(
        10,
        3,
        f.blocks
            .iter()
            .map(|b| (b.calc_header_hash(), true))
            .collect(),
    );
    storage.remove_matched_blocks(10);
    drop(storage);
    let storage = Storage::new(dir.path().join("store.db"));
    let prefix = [
        vec![KeyPrefix::CellLockScript as u8],
        extract_raw_data(&f.script),
    ]
    .concat();
    assert!(cell(&storage, &f.script, 5).is_some());
    assert!(
        cells_ready(&storage, &prefix, &f.chain).is_err(),
        "a discarded spend obligation must still fence prior live cells"
    );
    storage.add_matched_blocks(
        10,
        3,
        f.blocks
            .iter()
            .map(|b| (b.calc_header_hash(), true))
            .collect(),
    );
    storage.update_min_filtered_block_number(12);
    storage.index_matched_range(10, 3, f.blocks);
    assert!(cell(&storage, &f.script, 5).is_none());
    assert!(cell(&storage, &f.script, 12).is_some());
    assert!(cells_ready(&storage, &prefix, &f.chain).is_ok());
    storage.rollback_to_block(10);
    assert_eq!(
        cell(&storage, &f.script, 5).unwrap(),
        prior.hash().as_slice()
    );
    assert!(cell(&storage, &f.script, 12).is_none());
}

#[test]
fn startup_cleanup_discard_rewinds_missing_and_out_of_range_debt_after_reopen() {
    for missing in [true, false] {
        let (storage, dir) = crate::tests::utils::new_storage("cleanup-index-debt");
        let mut f = setup(&storage);
        if missing {
            let tip = BlockBuilder::default()
                .epoch(ckb_types::core::EpochNumberWithFraction::new(0, 0, 3000).full_value())
                .number(2000u64)
                .build()
                .data()
                .header();
            storage.update_last_state(&ckb_types::U256::zero(), &tip, &[]);
            f.chain.anchor = tip;
        } else {
            storage.add_fetched_header(&crate::storage::HeaderWithExtension {
                header: f.blocks[2].header(),
                extension: None,
            });
            storage.add_matched_blocks(10, 1, vec![(f.blocks[2].calc_header_hash(), true)]);
        }
        storage.cleanup_invalid_matched_blocks();
        drop(storage);
        let storage = Storage::new(dir.path().join("store.db"));
        assert!(storage.get_earliest_matched_blocks().is_none());
        assert!(storage.get_min_filtered_block_number() < 10);
        storage.update_block_number(storage.get_min_filtered_block_number());
        assert!(snapshot(&storage, &f.script, &f.identity, &f.chain, "TESTNET").is_err());
        let prefix = [
            vec![KeyPrefix::CellLockScript as u8],
            extract_raw_data(&f.script),
        ]
        .concat();
        assert!(cells_ready(&storage, &prefix, &f.chain).is_err());
    }
}

#[test]
fn discarded_suffix_cannot_skip_earlier_filter_selection_after_reopen() {
    use crate::protocols::{FilterProtocol, Peers};
    use golomb_coded_set::{GCSFilterWriter, SipHasher24Builder, M, P};
    let (storage, dir) = crate::tests::utils::new_storage("discard-suffix-gap");
    let mut f = setup(&storage);
    // Later debt is beyond an entire normal filter batch from the earlier spend.
    let later_tx = TransactionBuilder::default()
        .output(
            packed::CellOutput::new_builder()
                .capacity(102u64)
                .lock(f.script.clone())
                .build(),
        )
        .output_data(Vec::<u8>::new().pack())
        .build();
    let later = BlockBuilder::default()
        .epoch(ckb_types::core::EpochNumberWithFraction::new(0, 0, 10000).full_value())
        .number(5000u64)
        .transaction(later_tx)
        .build()
        .data();
    f.chain.anchor = later.header();
    storage.update_last_state(&ckb_types::U256::zero(), &f.chain.anchor, &[]);
    storage.add_matched_blocks(5000, 1, vec![(later.calc_header_hash(), true)]);
    storage.update_min_filtered_block_number(5000);
    storage.filter_block(f.blocks[0].clone());
    storage.remove_matched_blocks(10);
    drop(storage);
    let storage = Storage::new(dir.path().join("store.db"));
    // A late response/recovered later range must not mutate indexes or leap cursors.
    assert!(!storage.index_matched_range(5000, 1, vec![later.clone()]));
    assert!(
        storage.get_filter_scripts()[0].block_number < 1000,
        "later debt jumped over the discarded spend before earlier filter selection"
    );
    assert!(
        cell(&storage, &f.script, 5000).is_none(),
        "stale work must not mutate cells"
    );
    let peers = Arc::new(Peers::new(1, 2000, storage.get_last_check_point(), 0));
    let protocol = FilterProtocol::new(storage.clone(), peers);
    let mut bytes = Vec::new();
    let mut writer = GCSFilterWriter::new(&mut bytes, SipHasher24Builder::new(0, 0), M, P);
    writer.add_element(f.script.calc_script_hash().as_slice());
    writer.finish().unwrap();
    let filters = packed::BlockFilters::new_builder()
        .start_number(10u64)
        .block_hashes(vec![f.blocks[1].calc_header_hash()])
        .filters(vec![ckb_types::bytes::Bytes::from(bytes).pack()])
        .build();
    assert_eq!(
        protocol.check_filters_data(filters, 1).unwrap(),
        vec![f.blocks[1].calc_header_hash()],
        "actual earlier GCS selection must retain the wallet script"
    );
    let prefix = [
        vec![KeyPrefix::CellLockScript as u8],
        extract_raw_data(&f.script),
    ]
    .concat();
    assert!(snapshot(&storage, &f.script, &f.identity, &f.chain, "TESTNET").is_err());
    assert!(cells_ready(&storage, &prefix, &f.chain).is_err());
    assert!(
        storage.get_earliest_matched_blocks().is_none(),
        "discard invalidates queued suffix atomically"
    );
    storage.add_matched_blocks(
        10,
        3,
        f.blocks
            .iter()
            .map(|b| (b.calc_header_hash(), true))
            .collect(),
    );
    storage.update_min_filtered_block_number(12);
    storage.index_matched_range(10, 3, f.blocks.clone());
    assert!(
        cell(&storage, &f.script, 10).is_none(),
        "earlier spend rebuilt"
    );
    assert!(cell(&storage, &f.script, 12).is_some());
    assert!(
        cells_ready(&storage, &prefix, &f.chain).is_err(),
        "later replacement coverage remains outstanding"
    );
    storage.add_matched_blocks(5000, 1, vec![(later.calc_header_hash(), true)]);
    storage.update_min_filtered_block_number(5000);
    storage.index_matched_range(5000, 1, vec![later]);
    assert!(snapshot(&storage, &f.script, &f.identity, &f.chain, "TESTNET").is_ok());
    assert!(cells_ready(&storage, &prefix, &f.chain).is_ok());
    storage.rollback_to_block(11);
    assert_eq!(
        cell(&storage, &f.script, 10).unwrap(),
        f.funding.calc_tx_hash().as_slice()
    );
    assert!(cell(&storage, &f.script, 12).is_none());
    assert!(cell(&storage, &f.script, 5000).is_none());
}

#[test]
fn non_earliest_range_cannot_mutate_indexes_or_progress() {
    let (storage, _dir) = crate::tests::utils::new_storage("out-of-order-debt");
    let f = setup(&storage);
    let later = BlockBuilder::default()
        .epoch(ckb_types::core::EpochNumberWithFraction::new(0, 0, 10000).full_value())
        .number(5000u64)
        .transaction(f.live.clone().into_view())
        .build()
        .data();
    storage.add_matched_blocks(5000, 1, vec![(later.calc_header_hash(), true)]);
    storage.update_min_filtered_block_number(5000);
    assert!(!storage.index_matched_range(5000, 1, vec![later]));
    assert_eq!(
        storage.get_filter_scripts()[0].block_number,
        0,
        "non-earliest work cannot advance the script"
    );
    assert!(cell(&storage, &f.script, 5000).is_none());
    assert_eq!(
        storage.get_latest_matched_blocks().unwrap().start_number,
        5000
    );
    storage.update_min_filtered_block_number(9);
    assert!(
        !storage.index_matched_range(10, 3, f.blocks.clone()),
        "unfiltered range cannot commit"
    );
    assert_eq!(storage.get_filter_scripts()[0].block_number, 0);
    assert!(cell(&storage, &f.script, 10).is_none());
    assert_eq!(
        storage.get_earliest_matched_blocks().unwrap().start_number,
        10
    );
    storage.add_matched_blocks(10, 1, vec![(f.blocks[2].calc_header_hash(), true)]);
    storage.update_min_filtered_block_number(12);
    assert!(
        !storage.index_matched_range(10, 1, vec![f.blocks[2].clone()]),
        "body height outside the debt range cannot settle it"
    );
    assert_eq!(storage.get_filter_scripts()[0].block_number, 0);
    assert!(cell(&storage, &f.script, 12).is_none());
    assert_eq!(
        storage.get_earliest_matched_blocks().unwrap().start_number,
        10
    );
}

#[tokio::test]
async fn synchronizer_drops_stale_bodies_and_reloads_only_current_earliest_debt() {
    use crate::protocols::{Peers, SyncProtocol};
    use crate::tests::utils::MockNetworkContext;
    use ckb_network::{CKBProtocolHandler, PeerIndex, SupportProtocols};
    for discard in [false, true] {
        let (storage, _dir) = crate::tests::utils::new_storage("sync-stale-range");
        let f = setup(&storage);
        let later = BlockBuilder::default()
            .epoch(ckb_types::core::EpochNumberWithFraction::new(0, 0, 10000).full_value())
            .number(5000u64)
            .transaction(f.live.clone().into_view())
            .build()
            .data();
        storage.add_matched_blocks(5000, 1, vec![(later.calc_header_hash(), true)]);
        let peers = Arc::new(Peers::new(1, 2000, storage.get_last_check_point(), 0));
        {
            let mut map = peers.matched_blocks().write().await;
            peers.add_matched_blocks(&mut map, vec![(later.calc_header_hash(), true)]);
        }
        if discard {
            storage.remove_matched_blocks(10);
        }
        let mut protocol = SyncProtocol::new(storage.clone(), peers.clone());
        let nc = MockNetworkContext::new(SupportProtocols::Sync);
        let msg = packed::SyncMessage::new_builder()
            .set(packed::SendBlock::new_builder().block(later).build())
            .build();
        protocol
            .received(nc.context(), PeerIndex::new(1), msg.as_bytes())
            .await;
        assert!(cell(&storage, &f.script, 5000).is_none());
        assert_eq!(storage.get_filter_scripts()[0].block_number, 0);
        if discard {
            assert!(
                peers.matched_blocks().read().await.is_empty(),
                "discarded in-memory range must drain"
            );
        } else {
            let map = peers.matched_blocks().read().await;
            assert_eq!(map.len(), f.blocks.len());
            for block in &f.blocks {
                assert!(map.contains_key(&block.calc_header_hash().unpack()));
            }
            drop(map);
            for block in &f.blocks {
                let msg = packed::SyncMessage::new_builder()
                    .set(
                        packed::SendBlock::new_builder()
                            .block(block.clone())
                            .build(),
                    )
                    .build();
                protocol
                    .received(nc.context(), PeerIndex::new(1), msg.as_bytes())
                    .await;
            }
            assert_eq!(
                storage.get_earliest_matched_blocks().unwrap().start_number,
                5000
            );
            assert_eq!(storage.get_filter_scripts()[0].block_number, 12);
            assert!(cell(&storage, &f.script, 10).is_none());
            assert!(cell(&storage, &f.script, 12).is_some());
        }
    }
}

#[tokio::test]
async fn nonzero_registration_reopen_cannot_advance_past_rolled_back_spend() {
    use crate::protocols::{FilterProtocol, Peers};
    use crate::tests::utils::MockNetworkContext;
    use ckb_network::{CKBProtocolHandler, PeerIndex, SupportProtocols};
    use ckb_types::utilities::merkle_mountain_range::VerifiableHeader;
    for partial in [false, true] {
        for after in [false, true] {
            let (storage, dir) = crate::tests::utils::new_storage("registration-crash");
            let f = setup(&storage);
            assert!(storage.index_matched_range(10, 3, f.blocks.clone()));
            assert!(snapshot(&storage, &f.script, &f.identity, &f.chain, "TESTNET").is_ok());
            let crashing = CrashStorage {
                inner: storage,
                commits: Arc::new(AtomicUsize::new(0)),
                stop: 1,
                after,
            };
            assert!(std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
                crashing.update_filter_scripts(
                    vec![ScriptStatus {
                        script: f.script.clone(),
                        script_type: ScriptType::Lock,
                        block_number: 10,
                    }],
                    if partial {
                        SetScriptsCommand::Partial
                    } else {
                        SetScriptsCommand::All
                    },
                );
            }))
            .is_err());
            drop(crashing);
            let storage = Storage::new(dir.path().join("store.db"));
            // Exercise the real out-of-order filter handler immediately on reopen,
            // before any platform registration could hide an incoherent rewind.
            let peers = Arc::new(Peers::new(1, 2000, storage.get_last_check_point(), 0));
            let peer = PeerIndex::new(1);
            peers.add_peer(peer);
            peers
                .mock_prove_state(
                    peer,
                    VerifiableHeader::new(
                        f.chain.anchor.clone().into_view(),
                        Default::default(),
                        None,
                        Default::default(),
                    ),
                )
                .unwrap();
            let mut protocol = FilterProtocol::new(storage.clone(), peers);
            let nc = MockNetworkContext::new(SupportProtocols::Filter);
            let message = packed::BlockFilterMessage::new_builder()
                .set(
                    packed::BlockFilters::new_builder()
                        .start_number(100u64)
                        .build(),
                )
                .build();
            protocol
                .received(nc.context(), peer, message.as_bytes())
                .await;
            let prefix = [
                vec![KeyPrefix::CellLockScript as u8],
                extract_raw_data(&f.script),
            ]
            .concat();
            if after {
                assert!(snapshot(&storage, &f.script, &f.identity, &f.chain, "TESTNET").is_err(),
                    "registration rollback must not certify false spend absence after filter advancement");
                assert!(
                    cells_ready(&storage, &prefix, &f.chain).is_err(),
                    "restored spent output must stay fenced"
                );
                assert_eq!(storage.get_min_filtered_block_number(), 10);
                assert_eq!(storage.get_filter_scripts()[0].block_number, 10);
                assert!(cell(&storage, &f.script, 10).is_some());
                storage.add_matched_blocks(
                    11,
                    2,
                    f.blocks[1..]
                        .iter()
                        .map(|b| (b.calc_header_hash(), true))
                        .collect(),
                );
                storage.update_min_filtered_block_number(12);
                assert!(storage.index_matched_range(11, 2, f.blocks[1..].to_vec()));
            }
            assert!(cells_ready(&storage, &prefix, &f.chain).is_ok());
            assert_final_and_undo(&storage, &f);
        }
    }
}

#[test]
fn scoped_range_reopens_at_every_index_commit_without_losing_debt() {
    use crate::storage::scan::{ScanIdentity, ScanState, ScanStorage};
    for stop in 1..=4 {
        for after in [false, true] {
            let (storage, dir) = crate::tests::utils::new_storage("scoped-range-crash");
            let f = setup(&storage);
            let id = ScanIdentity::registered(&storage, &f.script, ScriptType::Lock).unwrap();
            storage
                .install_scan_states([7; 16], vec![ScanState::settled(id.clone(), [7; 16], 0, 0)])
                .unwrap();
            let range = storage
                .begin_scan_range(
                    &id,
                    [7; 16],
                    1,
                    12,
                    [9; 32],
                    f.blocks
                        .iter()
                        .map(|b| b.calc_header_hash().as_slice().try_into().unwrap())
                        .collect(),
                )
                .unwrap();
            let crashing = CrashStorage {
                inner: storage,
                commits: Arc::new(AtomicUsize::new(0)),
                stop,
                after,
            };
            assert!(std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
                crashing
                    .index_scan_range(&id, [7; 16], range.id, f.blocks.clone())
                    .unwrap();
            }))
            .is_err());
            drop(crashing);
            let storage = Storage::new(dir.path().join("store.db"));
            let state = storage.scan_state(&id).unwrap().unwrap();
            if stop == 4 && after {
                assert_eq!(state.settled, 12);
                assert!(state.pending.is_none());
            } else {
                assert_eq!(
                    state.settled, 0,
                    "partial indexing must not publish coverage"
                );
                assert_eq!(state.pending, Some(range.clone()));
                storage
                    .index_scan_range(&id, [7; 16], range.id, f.blocks.clone())
                    .unwrap();
            }
            assert!(
                cell(&storage, &f.script, 10).is_none(),
                "replay must reapply the spend"
            );
            assert_eq!(
                cell(&storage, &f.script, 12).unwrap(),
                f.live.calc_tx_hash().as_slice()
            );
            assert_eq!(storage.scan_state(&id).unwrap().unwrap().settled, 12);
        }
    }
}

#[test]
fn scoped_history_and_cells_remain_available_while_unrelated_scan_waits() {
    use crate::storage::scan::{ScanIdentity, ScanStorage};
    let (storage, _dir) = crate::tests::utils::new_storage("scoped-availability");
    storage.init_genesis_block(BlockBuilder::default().build().data());
    let live = packed::Script::new_builder().args(vec![1u8].pack()).build();
    let old = packed::Script::new_builder().args(vec![2u8].pack()).build();
    storage.update_filter_scripts(
        vec![
            ScriptStatus {
                script: live.clone(),
                script_type: ScriptType::Lock,
                block_number: 20,
            },
            ScriptStatus {
                script: old.clone(),
                script_type: ScriptType::Lock,
                block_number: 10,
            },
        ],
        SetScriptsCommand::All,
    );
    storage.update_min_filtered_block_number(20);
    storage.prepare_scoped_scans().unwrap();
    let old_id = ScanIdentity::registered(&storage, &old, ScriptType::Lock).unwrap();
    let state = storage.scan_state(&old_id).unwrap().unwrap();
    storage
        .begin_scan_range(&old_id, state.epoch, 11, 20, [9; 32], vec![[1; 32]])
        .unwrap();
    let chain = ChainSnapshot {
        anchor: BlockBuilder::default()
            .epoch(ckb_types::core::EpochNumberWithFraction::new(0, 0, 100).full_value())
            .number(20u64)
            .build()
            .data()
            .header(),
    };
    storage.update_last_state(&ckb_types::U256::zero(), &chain.anchor, &[]);
    let identity = RecoveryIdentity {
        genesis_hash: packed::Byte32::default(),
        store_generation: "scoped".into(),
        run_nonce: [1; 16],
    };
    assert_eq!(
        snapshot(&storage, &live, &identity, &chain, "TESTNET").unwrap()["durableCoverageVersion"],
        2
    );
    let prefix = [
        vec![KeyPrefix::CellLockScript as u8],
        extract_raw_data(&live),
    ]
    .concat();
    assert!(cells_ready(&storage, &prefix, &chain).is_ok());
    assert!(
        cells_ready(&storage, &prefix[..34], &chain).is_err(),
        "prefix overlap must include lagging script debt"
    );
    assert!(snapshot(&storage, &old, &identity, &chain, "TESTNET").is_err());
}

#[test]
fn scoped_migration_reopens_before_and_after_atomic_state_commit() {
    use crate::storage::scan::{ScanIdentity, ScanStorage};
    for after in [false, true] {
        let (storage, dir) = crate::tests::utils::new_storage("scoped-migration-crash");
        let f = setup(&storage);
        storage.filter_block(f.blocks[0].clone());
        let crashing = CrashStorage {
            inner: storage,
            commits: Arc::new(AtomicUsize::new(0)),
            stop: 1,
            after,
        };
        assert!(
            std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| crashing
                .prepare_scoped_scans()
                .unwrap()))
            .is_err()
        );
        drop(crashing);
        let storage = Storage::new(dir.path().join("store.db"));
        storage.prepare_scoped_scans().unwrap();
        let id = ScanIdentity::registered(&storage, &f.script, ScriptType::Lock).unwrap();
        let state = storage.scan_state(&id).unwrap().unwrap();
        assert_eq!(state.settled, 0);
        assert!(
            cell(&storage, &f.script, 10).is_none(),
            "partial legacy index rolled back with progress"
        );
        assert!(storage.get_earliest_matched_blocks().is_none());
    }
}

#[test]
fn scoped_missing_invalidation_reopens_atomically_and_fences_old_ack() {
    use crate::storage::scan::{ScanIdentity, ScanState, ScanStorage};
    for after in [false, true] {
        let (storage, dir) = crate::tests::utils::new_storage("scoped-invalidate-crash");
        let f = setup(&storage);
        let id = ScanIdentity::registered(&storage, &f.script, ScriptType::Lock).unwrap();
        storage
            .install_scan_states([7; 16], vec![ScanState::settled(id.clone(), [7; 16], 0, 0)])
            .unwrap();
        let hashes: Vec<[u8; 32]> = f
            .blocks
            .iter()
            .map(|b| b.calc_header_hash().as_slice().try_into().unwrap())
            .collect();
        let range = storage
            .begin_scan_range(&id, [7; 16], 1, 12, [9; 32], hashes.clone())
            .unwrap();
        let scripts = std::collections::HashSet::from([(f.script.clone(), ScriptType::Lock)]);
        for block in &f.blocks {
            storage
                .filter_block_for_scripts(block.clone(), &scripts)
                .unwrap();
        }
        assert!(cell(&storage, &f.script, 12).is_some());
        let crashing = CrashStorage {
            inner: storage,
            commits: Arc::new(AtomicUsize::new(0)),
            stop: 1,
            after,
        };
        assert!(std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
            crashing
                .invalidate_scan_range(&id, [7; 16], range.id)
                .unwrap();
        }))
        .is_err());
        drop(crashing);
        let storage = Storage::new(dir.path().join("store.db"));
        let state = storage.scan_state(&id).unwrap().unwrap();
        assert_eq!(state.settled, 0);
        assert_eq!(state.pending.is_none(), after);
        assert_eq!(cell(&storage, &f.script, 12).is_none(), after);
        if !after {
            storage
                .invalidate_scan_range(&id, [7; 16], range.id)
                .unwrap();
        }
        assert!(cell(&storage, &f.script, 10).is_none());
        assert!(cell(&storage, &f.script, 12).is_none());
        assert!(storage
            .finish_scan_range(&id, [7; 16], range.id, &hashes)
            .is_err());
        let fresh = storage
            .begin_scan_range(&id, [7; 16], 1, 12, [9; 32], hashes.clone())
            .unwrap();
        assert_ne!(fresh.id, range.id);
        assert!(storage
            .finish_scan_range(&id, [7; 16], range.id, &hashes)
            .is_err());
        storage
            .index_scan_range(&id, [7; 16], fresh.id, f.blocks.clone())
            .unwrap();
        assert_eq!(storage.scan_state(&id).unwrap().unwrap().settled, 12);
        assert!(cell(&storage, &f.script, 10).is_none());
        assert_eq!(
            cell(&storage, &f.script, 12).unwrap(),
            f.live.calc_tx_hash().as_slice()
        );
    }
}
