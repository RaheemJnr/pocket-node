//! Shared body cache consumption. The caller holds the matched-work write
//! guard, also used by registration and canonical-chain transitions.
use crate::error::Result;
use crate::storage::{
    scan::{ScanState, ScanStorage},
    Storage,
};
use ckb_types::{packed, prelude::*, H256};
use std::collections::{HashMap, HashSet};

/// Called under the same work guard as settlement and registration. Missing
/// responses must lead to a new filter range, never cursor promotion or a global
/// reset. Collect all affected scopes before resetting shared fetch flags.
pub(crate) fn retry_missing(storage: &Storage, peers: &crate::protocols::Peers) -> Result<()> {
    let Some(states) = storage.scan_states()? else {
        return Ok(());
    };
    let mut missing = Vec::new();
    for state in states {
        let Some(range) = state.pending else {
            continue;
        };
        let hashes: Vec<_> = range
            .matches
            .iter()
            .map(|h| H256::from(*h).pack())
            .filter(|hash| {
                peers
                    .get_header_fetch_info(hash)
                    .is_some_and(|(_, _, absent)| absent)
            })
            .collect();
        if !hashes.is_empty() {
            storage.invalidate_scan_range(&state.identity, state.epoch, range.id)?;
            missing.extend(hashes);
        }
    }
    peers.retry_missing_headers(&missing);
    Ok(())
}

pub(crate) fn settle_ready(
    storage: &Storage,
    matched: &mut HashMap<H256, (bool, Option<packed::Block>)>,
) -> Result<Option<Vec<ScanState>>> {
    let Some(states) = storage.scan_states()? else {
        return Ok(None);
    };
    for state in states {
        let Some(range) = state.pending else {
            continue;
        };
        let bodies: Option<Vec<_>> = range
            .matches
            .iter()
            .map(|hash| {
                matched
                    .get(&H256::from(*hash))
                    .and_then(|(proved, body)| if *proved { body.clone() } else { None })
            })
            .collect();
        if let Some(bodies) = bodies {
            storage.index_scan_range(&state.identity, state.epoch, range.id, bodies)?;
        }
    }
    let states = storage
        .scan_states()?
        .expect("scan epoch cannot disappear under work guard");
    let required: HashSet<_> = states
        .iter()
        .filter_map(|s| s.pending.as_ref())
        .flat_map(|r| r.matches.iter())
        .map(|h| H256::from(*h))
        .collect();
    matched.retain(|hash, _| required.contains(hash));
    // Durable pending work is sufficient to refetch proof and bodies, never to
    // restore the in-memory proved bit across process or canonical transitions.
    for hash in required {
        matched.entry(hash).or_insert((false, None));
    }
    Ok(Some(states))
}

/// Rotate only after actually issuing work; busy peers do not consume another
/// script's turn. Existing proof/body validation and timeout handling are reused.
pub(crate) async fn service_proofs(
    states: &[ScanState],
    peers: std::sync::Arc<crate::protocols::Peers>,
    tip: &packed::Header,
    matched: &HashMap<H256, (bool, Option<packed::Block>)>,
    nc: ckb_network::BoxedCKBProtocolContext,
    limit: usize,
) {
    use crate::{read_lock, write_lock};
    let mut pending: Vec<_> = states
        .iter()
        .filter(|state| state.pending.is_some())
        .collect();
    pending.sort_by(|a, b| {
        b.settled
            .cmp(&a.settled)
            .then_with(|| a.identity.suffix.cmp(&b.identity.suffix))
    });
    let last = read_lock!(peers.scoped_proof_turn).clone();
    if let Some(index) = pending
        .iter()
        .position(|state| Some(&state.identity.suffix) == last.as_ref())
    {
        let length = pending.len();
        pending.rotate_left((index + 1) % length);
    }
    for state in pending {
        let hashes: HashSet<_> = state
            .pending
            .as_ref()
            .expect("pending state")
            .matches
            .iter()
            .map(|h| H256::from(*h))
            .collect();
        let subset: HashMap<_, _> = matched
            .iter()
            .filter(|(h, _)| hashes.contains(*h))
            .map(|(h, v)| (h.clone(), v.clone()))
            .collect();
        if super::network::prove_or_download_matched_blocks(
            peers.clone(),
            tip,
            &subset,
            nc.as_ref(),
            limit,
        ) {
            *write_lock!(peers.scoped_proof_turn) = Some(state.identity.suffix.clone());
            break;
        }
    }
}
