//! Bounded authoritative history from a consistent SQLite read under the chain guard.
use super::{types::*, BridgeError};
use crate::{
    protocols::light_client::recovery::{ChainSnapshot, RecoveryIdentity},
    storage::{extract_raw_data, Key, KeyPrefix, Storage},
};
use ckb_types::{packed, prelude::*};
use rusqlite::{Connection, OptionalExtension};
use serde_json::{json, Value};
use std::collections::BTreeSet;
const MAX_INTERACTIONS: usize = 20_000;
fn unavailable() -> BridgeError {
    BridgeError::Storage("authoritative history unavailable".into())
}
fn read(conn: &Connection, key: Vec<u8>) -> Result<Vec<u8>, BridgeError> {
    conn.query_row("SELECT value FROM kv WHERE key = ?1", [key], |r| r.get(0))
        .optional()
        .map_err(|_| unavailable())?
        .ok_or_else(unavailable)
}
fn prefixed(
    conn: &Connection,
    prefix: &[u8],
    cap: usize,
) -> Result<Vec<(Vec<u8>, Vec<u8>)>, BridgeError> {
    let mut upper = prefix.to_vec();
    while upper.last() == Some(&255) {
        upper.pop();
    }
    *upper.last_mut().ok_or_else(unavailable)? += 1;
    let mut stmt = conn
        .prepare("SELECT key,value FROM kv WHERE key >= ?1 AND key < ?2 ORDER BY key LIMIT ?3")
        .map_err(|_| unavailable())?;
    let rows = stmt
        .query_map(rusqlite::params![prefix, upper, (cap + 1) as i64], |r| {
            Ok((r.get(0)?, r.get(1)?))
        })
        .map_err(|_| unavailable())?;
    let result = rows
        .collect::<Result<Vec<_>, _>>()
        .map_err(|_| unavailable())?;
    if result.len() > cap {
        return Err(unavailable());
    }
    Ok(result)
}
/// Availability only: rewound cells must not enter coin selection before rebuilding.
/// Caller retains the matched read lock through the subsequent cells/capacity query.
pub(crate) fn cells_ready(storage: &Storage, cell_prefix: &[u8], chain: &ChainSnapshot) -> Result<(), BridgeError> {
    let conn = storage.conn.lock().map_err(|_| unavailable())?;
    let marker_prefix = [Key::Meta("CELL_REWIND_V1").into_vec(), cell_prefix.to_vec()].concat();
    let markers = prefixed(&conn, &marker_prefix, MAX_INTERACTIONS)?;
    if markers.is_empty() { return Ok(()); }
    let state = read(&conn, Key::Meta("LAST_STATE").into_vec())?;
    if state.get(32..) != Some(chain.anchor.as_slice()) { return Err(unavailable()); }
    let tip: u64 = chain.anchor.raw().number().unpack();
    if tip == 0 { return Err(unavailable()); }
    let filtered = read(&conn, Key::Meta("MIN_FILTERED_NUMBER").into_vec())?;
    let filtered = u64::from_le_bytes(filtered.as_slice().try_into().map_err(|_| unavailable())?);
    if !prefixed(&conn, &Key::Meta("MATCHED_BLOCKS").into_vec(), 0)?.is_empty() {
        return Err(unavailable());
    }
    for (key, value) in markers {
        let frontier = u64::from_be_bytes(value.get(..8).ok_or_else(unavailable)?.try_into().map_err(|_| unavailable())?);
        let start = u64::from_be_bytes(value.get(8..16).ok_or_else(unavailable)?.try_into().map_err(|_| unavailable())?);
        let script = packed::Script::from_slice(value.get(16..).ok_or_else(unavailable)?).map_err(|_| unavailable())?;
        let kind = match cell_prefix.first() {
            Some(p) if *p == KeyPrefix::CellLockScript as u8 => 0,
            Some(p) if *p == KeyPrefix::CellTypeScript as u8 => 1,
            _ => return Err(unavailable()),
        };
        let expected_key = [Key::Meta("CELL_REWIND_V1").into_vec(), vec![cell_prefix[0]], extract_raw_data(&script)].concat();
        if key != expected_key || start > frontier { return Err(unavailable()); }
        let cursor = read(&conn, [Key::Meta("FILTER_SCRIPTS").into_vec(), script.as_slice().to_vec(), vec![kind]].concat())?;
        let cursor = u64::from_be_bytes(cursor.as_slice().try_into().map_err(|_| unavailable())?);
        let required = frontier.min(tip);
        if cursor < required || filtered < required { return Err(unavailable()); }
        // Completion is durable: later reorgs again have a coherent undo index.
        // Clear here to avoid forcing another rewind after normal wallet switching.
        conn.execute("DELETE FROM kv WHERE key = ?1", [key]).map_err(|_| unavailable())?;
    }
    Ok(())
}

pub(crate) fn snapshot(
    storage: &Storage,
    script: &packed::Script,
    identity: &RecoveryIdentity,
    chain: &ChainSnapshot,
    network: &str,
) -> Result<Value, BridgeError> {
    // Excludes registrations and every DB write; caller excludes partial rollback/tip updates.
    let conn = storage.conn.lock().map_err(|_| unavailable())?;
    let state = read(&conn, Key::Meta("LAST_STATE").into_vec())?;
    if state.get(32..) != Some(chain.anchor.as_slice()) {
        return Err(unavailable());
    }
    let tip: u64 = chain.anchor.raw().number().unpack();
    let suffix = [script.as_slice(), &[0]].concat();
    let progress = read(
        &conn,
        [Key::Meta("FILTER_SCRIPTS").into_vec(), suffix.clone()].concat(),
    )?;
    let through = u64::from_be_bytes(progress.as_slice().try_into().map_err(|_| unavailable())?);
    // Inclusive rollback removes R but leaves the script cursor at R. The global
    // filter cursor rewinds to R-1, so both cursors must cover the canonical tip.
    let filtered = read(&conn, Key::Meta("MIN_FILTERED_NUMBER").into_vec())?;
    let filtered_through =
        u64::from_le_bytes(filtered.as_slice().try_into().map_err(|_| unavailable())?);
    if filtered_through < tip {
        return Err(unavailable());
    }
    let registration = read(
        &conn,
        [Key::Meta("HISTORY_REGISTRATION_V1").into_vec(), suffix].concat(),
    )?;
    if registration.len() != 24 || through < tip {
        return Err(unavailable());
    }
    let start = u64::from_be_bytes(registration[..8].try_into().map_err(|_| unavailable())?);
    if tip == 0 || start > tip {
        return Err(unavailable());
    }
    if !prefixed(&conn, &Key::Meta("MATCHED_BLOCKS").into_vec(), 0)?.is_empty() {
        return Err(unavailable());
    }
    let prefix = [
        vec![KeyPrefix::TxLockScript as u8],
        extract_raw_data(script),
    ]
    .concat();
    let mut hashes = BTreeSet::new();
    for (key, value) in prefixed(&conn, &prefix, MAX_INTERACTIONS)? {
        if key.len() != prefix.len() + 17 {
            return Err(unavailable());
        }
        let tail = &key[prefix.len()..];
        let height = u64::from_be_bytes(tail[..8].try_into().map_err(|_| unavailable())?);
        if height > tip || tail[16] > 1 {
            return Err(unavailable());
        }
        let hash = packed::Byte32::from_slice(&value).map_err(|_| unavailable())?;
        let stored = read(&conn, Key::TxHash(&hash).into_vec())?;
        if stored.get(..12) != Some(&tail[..12]) {
            return Err(unavailable());
        }
        let tx = packed::Transaction::from_slice(stored.get(12..).ok_or_else(unavailable)?)
            .map_err(|_| unavailable())?;
        if tx.calc_tx_hash() != hash {
            return Err(unavailable());
        }
        let index =
            u32::from_be_bytes(tail[12..16].try_into().map_err(|_| unavailable())?) as usize;
        if (tail[16] == 0 && index >= tx.raw().inputs().len())
            || (tail[16] == 1 && index >= tx.raw().outputs().len())
        {
            return Err(unavailable());
        }
        hashes.insert(format!("{hash:#x}"));
    }
    let hex = |bytes: &[u8]| bytes.iter().map(|b| format!("{b:02x}")).collect::<String>();
    Ok(json!({"version":1,"complete":true,"network":network,
        "genesisHash":format!("{:#x}",identity.genesis_hash),"storeGeneration":identity.store_generation,
        "script":ckb_jsonrpc_types::Script::from(script.clone()),
        "registration":hex(&registration[8..]),"startHeight":start.to_string(),
        "tipHeight":tip.to_string(),"tipHash":format!("{:#x}",chain.anchor.calc_header_hash()),
        "processedThrough":through.to_string(),"hashes":hashes}))
}
pub fn get_history_snapshot(script_json: &str) -> Result<String, BridgeError> {
    if !is_running() {
        return Err(BridgeError::NotInitialized);
    }
    let script: ckb_jsonrpc_types::Script =
        serde_json::from_str(script_json).map_err(|_| unavailable())?;
    let storage = STORAGE_WITH_DATA.get().ok_or_else(unavailable)?;
    let peers = PEERS.get().ok_or_else(unavailable)?;
    // Filter/index batches and registrations hold this write lock. Try-read keeps
    // the synchronous bridge fail-closed and avoids waiting while acquiring authority.
    let _matched = peers
        .matched_blocks()
        .try_read()
        .map_err(|_| unavailable())?;
    let consensus = CONSENSUS.get().ok_or_else(unavailable)?;
    let network = match consensus.id.as_str() {
        "ckb" => "MAINNET",
        "ckb_testnet" => "TESTNET",
        _ => return Err(unavailable()),
    };
    let value = peers
        .recovery()
        .with_chain_snapshot(|identity, chain| {
            snapshot(storage.storage(), &script.into(), identity, chain, network)
        })
        .map_err(|_| unavailable())??;
    serde_json::to_string(&value).map_err(|_| unavailable())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::protocols::light_client::recovery::RecoveryRegistry;
    use crate::storage::{
        LightClientStorage, ScriptStatus, ScriptType, SetScriptsCommand, StorageBackend,
    };
    use ckb_types::{
        core::{BlockBuilder, TransactionBuilder},
        U256,
    };
    #[test]
    fn authoritative_history_rollback_reinclusion_and_horizon() {
        let (storage, _dir) = crate::tests::utils::new_storage("history_snapshot");
        let script = packed::Script::default();
        let tip = BlockBuilder::default()
            .epoch(ckb_types::core::EpochNumberWithFraction::new(0, 0, 100).full_value())
            .number(20u64)
            .build()
            .data()
            .header();
        let mut chain = ChainSnapshot {
            anchor: tip.clone(),
        };
        let identity = RecoveryIdentity {
            genesis_hash: packed::Byte32::default(),
            store_generation: "store".into(),
            run_nonce: [1; 16],
        };
        storage.update_last_state(&U256::zero(), &tip, &[]);
        // Legacy DBs have no invented horizon, and registered but unscanned ranges fail closed.
        assert!(snapshot(&storage, &script, &identity, &chain, "TESTNET").is_err());
        storage.update_filter_scripts(
            vec![ScriptStatus {
                script: script.clone(),
                script_type: ScriptType::Lock,
                block_number: 1,
            }],
            SetScriptsCommand::All,
        );
        assert!(snapshot(&storage, &script, &identity, &chain, "TESTNET").is_err());
        let tx = TransactionBuilder::default()
            .output(
                packed::CellOutput::new_builder()
                    .lock(script.clone())
                    .build(),
            )
            .output_data(Vec::<u8>::new().pack())
            .build();
        storage.filter_block(
            BlockBuilder::default()
                .epoch(ckb_types::core::EpochNumberWithFraction::new(0, 0, 100).full_value())
                .number(10u64)
                .transaction(tx.clone())
                .build()
                .data(),
        );
        storage.update_block_number(20);
        storage.update_min_filtered_block_number(20);
        let before = snapshot(&storage, &script, &identity, &chain, "TESTNET").unwrap();
        assert_eq!(before["hashes"], json!([format!("{:#x}", tx.hash())]));
        assert_eq!(before["startHeight"], "1");
        storage.rollback_to_block(10);
        let replacement_tip = tip.clone().as_builder().nonce(1u128).build();
        storage.update_last_state(&U256::zero(), &replacement_tip, &[]);
        assert!(snapshot(&storage, &script, &identity, &chain, "TESTNET").is_err());
        chain = ChainSnapshot {
            anchor: replacement_tip,
        };
        assert!(snapshot(&storage, &script, &identity, &chain, "TESTNET").is_err());
        storage.update_block_number(20);
        storage.update_min_filtered_block_number(20);
        assert_eq!(
            snapshot(&storage, &script, &identity, &chain, "TESTNET").unwrap()["hashes"],
            json!([])
        );
        storage.filter_block(
            BlockBuilder::default()
                .epoch(ckb_types::core::EpochNumberWithFraction::new(0, 0, 100).full_value())
                .number(11u64)
                .transaction(tx.clone())
                .build()
                .data(),
        );
        assert_eq!(
            snapshot(&storage, &script, &identity, &chain, "TESTNET").unwrap()["hashes"],
            before["hashes"]
        );
        storage.update_filter_scripts(
            vec![ScriptStatus {
                script: script.clone(),
                script_type: ScriptType::Lock,
                block_number: 15,
            }],
            SetScriptsCommand::Partial,
        );
        assert!(snapshot(&storage, &script, &identity, &chain, "TESTNET").is_err());
        storage.update_block_number(20);
        storage.update_min_filtered_block_number(20);
        let after = snapshot(&storage, &script, &identity, &chain, "TESTNET").unwrap();
        assert_eq!(after["startHeight"], "15");
        assert_ne!(before["registration"], after["registration"]);
        storage.add_matched_blocks(20, 1, vec![(packed::Byte32::default(), false)]);
        assert!(snapshot(&storage, &script, &identity, &chain, "TESTNET").is_err());
        storage.remove_matched_blocks(20);
        let key = Key::TxHash(&tx.hash()).into_vec();
        let original_body = StorageBackend::get(&storage, key.clone()).unwrap().unwrap();
        StorageBackend::put(&storage, key.clone(), vec![0u8; 12]).unwrap();
        assert!(snapshot(&storage, &script, &identity, &chain, "TESTNET").is_err());
        StorageBackend::put(&storage, key, original_body).unwrap();
        // A missing body is not silently skipped into a false absence proof.
        StorageBackend::delete(&storage, &Key::TxHash(&tx.hash()).into_vec()).unwrap();
        assert!(snapshot(&storage, &script, &identity, &chain, "TESTNET").is_err());
    }
    #[test]
    fn bounded_read_exact_budget_is_complete_but_extra_row_and_corruption_fail() {
        let conn = Connection::open_in_memory().unwrap();
        conn.execute_batch("CREATE TABLE kv(key BLOB PRIMARY KEY,value BLOB)")
            .unwrap();
        for key in [vec![1u8, 0], vec![1u8, 1]] {
            conn.execute(
                "INSERT INTO kv VALUES (?1, ?2)",
                rusqlite::params![key, vec![0u8]],
            )
            .unwrap();
        }
        assert_eq!(prefixed(&conn, &[1], 2).unwrap().len(), 2);
        assert!(prefixed(&conn, &[1], 1).is_err());
        conn.execute("INSERT INTO kv VALUES (?1, NULL)", [vec![1u8, 2]])
            .unwrap();
        assert!(prefixed(&conn, &[1], 4).is_err());
    }
    #[test]
    fn chain_guard_refuses_snapshot_during_rollback_and_after_shutdown() {
        let identity = RecoveryIdentity {
            genesis_hash: packed::Byte32::new([1; 32]),
            store_generation: "store".into(),
            run_nonce: [1; 16],
        };
        let chain = ChainSnapshot {
            anchor: packed::Header::default(),
        };
        let registry = RecoveryRegistry::new(identity, chain.clone());
        assert!(registry.with_chain_snapshot(|_, _| ()).is_ok());
        // Contended authority fails closed immediately, including reentrant reads.
        assert!(registry
            .with_chain_snapshot(|_, _| registry.with_chain_snapshot(|_, _| ()))
            .unwrap()
            .is_err());
        let update = registry.begin_chain_update().unwrap();
        assert!(registry
            .with_chain_snapshot(|_, _| panic!("must not read mid rollback"))
            .is_err());
        registry.finish_chain_update(update, chain).unwrap();
        assert!(registry.with_chain_snapshot(|_, _| ()).is_ok());
        registry.revoke_run();
        assert!(registry.with_chain_snapshot(|_, _| ()).is_err());
    }
}
