//! Query APIs for JNI bridge
//!
//! Provides 17 query APIs matching WASM implementation.
//! All functions return JSON strings for complex types, or null on error.

use super::panic_guard::guard_jni;
use super::types::*;
use crate::bridge_core::query as bridge_query;
use crate::service::{
    Cell, CellType, CellsCapacity, FetchStatus, Pagination, ScriptType, SearchKey, Tx, TxWithCell,
};
use crate::storage::{extract_raw_data, Direction, IteratorMode, Key, KeyPrefix};
use crate::verify::verify_tx;
use ckb_jsonrpc_types::{JsonBytes, Transaction};
use ckb_systemtime::unix_time_as_millis;
use ckb_types::{core, packed, prelude::{*, IntoHeaderView, IntoTransactionView}, H256};
use jni::objects::{JClass, JString};
use jni::sys::jstring;
use jni::JNIEnv;
use log::{debug, error, warn};
use std::ptr;
use std::str::FromStr;
use std::sync::Arc;

/// Helper to check running state and return null if not running
macro_rules! check_running {
    ($env:expr) => {
        if !is_running() {
            warn!("Light client not running, current state: {}", get_state());
            return ptr::null_mut();
        }
    };
}

/// Sentinel prefix marking a send-transaction error string returned to Kotlin.
/// Success returns the JSON-quoted tx hash ("0x..."); an error returns
/// `__SEND_ERROR__:<reason>` so the caller can surface the real cause instead
/// of collapsing a null into a misleading "check your network" message.
const SEND_ERROR_PREFIX: &str = "__SEND_ERROR__:";

/// Build the sentinel error jstring for nativeSendTransaction failures.
fn send_error_jstring(env: &mut JNIEnv, reason: &str) -> jstring {
    match env.new_string(format!("{}{}", SEND_ERROR_PREFIX, reason)) {
        Ok(s) => s.into_raw(),
        Err(e) => {
            error!("Failed to create send-error JString: {}", e);
            ptr::null_mut()
        }
    }
}

/// Helper to read a `JString` argument, logging and reporting failure as `None`.
fn jstring_to_string(env: &mut JNIEnv, value: &JString, context: &str) -> Option<String> {
    match env.get_string(value) {
        Ok(s) => Some(s.into()),
        Err(e) => {
            error!("{}: failed to read string argument: {}", context, e);
            None
        }
    }
}

/// Helper to create a JString from a JSON string produced by `bridge_core`
fn json_to_jstring(env: &mut JNIEnv, json: &str) -> jstring {
    match env.new_string(json) {
        Ok(s) => s.into_raw(),
        Err(e) => {
            error!("Failed to create JString: {}", e);
            ptr::null_mut()
        }
    }
}

/// Helper to create JString from serde result
fn to_jstring<T: serde::Serialize>(env: &mut JNIEnv, value: &T) -> jstring {
    match serde_json::to_string(value) {
        Ok(json) => match env.new_string(json) {
            Ok(s) => s.into_raw(),
            Err(e) => {
                error!("Failed to create JString: {}", e);
                ptr::null_mut()
            }
        },
        Err(e) => {
            error!("Failed to serialize to JSON: {}", e);
            ptr::null_mut()
        }
    }
}

/// Get tip header
#[no_mangle]
pub extern "C" fn Java_com_nervosnetwork_ckblightclient_LightClientNative_nativeGetTipHeader(
    mut env: JNIEnv,
    _class: JClass,
) -> jstring {
    guard_jni(std::ptr::null_mut(), move || {
        match bridge_query::get_tip_header() {
            Ok(json) => json_to_jstring(&mut env, &json),
            Err(_) => ptr::null_mut(),
        }
    })
}

/// Get genesis block
#[no_mangle]
pub extern "C" fn Java_com_nervosnetwork_ckblightclient_LightClientNative_nativeGetGenesisBlock(
    mut env: JNIEnv,
    _class: JClass,
) -> jstring {
    guard_jni(std::ptr::null_mut(), move || {
        match bridge_query::get_genesis_block() {
            Ok(json) => json_to_jstring(&mut env, &json),
            Err(_) => ptr::null_mut(),
        }
    })
}

/// Get header by hash
#[no_mangle]
pub extern "C" fn Java_com_nervosnetwork_ckblightclient_LightClientNative_nativeGetHeader(
    mut env: JNIEnv,
    _class: JClass,
    hash: JString,
) -> jstring {
    guard_jni(std::ptr::null_mut(), move || {
        let hash_str = match jstring_to_string(&mut env, &hash, "nativeGetHeader") {
            Some(s) => s,
            None => return ptr::null_mut(),
        };

        match bridge_query::get_header(&hash_str) {
            Ok(json) => json_to_jstring(&mut env, &json),
            Err(_) => ptr::null_mut(),
        }
    })
}

/// Get header by block number (two-hop lookup: BlockNumber → BlockHash → Header)
/// Only works for blocks that the light client has processed (matched transactions).
#[no_mangle]
pub extern "C" fn Java_com_nervosnetwork_ckblightclient_LightClientNative_nativeGetHeaderByNumber(
    mut env: JNIEnv,
    _class: JClass,
    block_number: JString,
) -> jstring {
    guard_jni(std::ptr::null_mut(), move || {
        let number_str =
            match jstring_to_string(&mut env, &block_number, "nativeGetHeaderByNumber") {
                Some(s) => s,
                None => return ptr::null_mut(),
            };

        match bridge_query::get_header_by_number(&number_str) {
            Ok(json) => json_to_jstring(&mut env, &json),
            Err(_) => ptr::null_mut(),
        }
    })
}

/// Fetch header (with fetch status)
#[no_mangle]
pub extern "C" fn Java_com_nervosnetwork_ckblightclient_LightClientNative_nativeFetchHeader(
    mut env: JNIEnv,
    _class: JClass,
    hash: JString,
) -> jstring {
    guard_jni(std::ptr::null_mut(), move || {
        let hash_str = match jstring_to_string(&mut env, &hash, "nativeFetchHeader") {
            Some(s) => s,
            None => return ptr::null_mut(),
        };

        match bridge_query::fetch_header(&hash_str) {
            Ok(json) => json_to_jstring(&mut env, &json),
            Err(_) => ptr::null_mut(),
        }
    })
}

/// Set scripts
#[no_mangle]
pub extern "C" fn Java_com_nervosnetwork_ckblightclient_LightClientNative_nativeSetScripts(
    mut env: JNIEnv,
    _class: JClass,
    scripts_json: JString,
    command: i32,
) -> jni::sys::jboolean {
    guard_jni(jni::sys::JNI_FALSE, move || {
        let scripts_str = match jstring_to_string(&mut env, &scripts_json, "nativeSetScripts") {
            Some(s) => s,
            None => return jni::sys::JNI_FALSE,
        };

        match bridge_query::set_scripts(&scripts_str, command) {
            Ok(()) => jni::sys::JNI_TRUE,
            Err(_) => jni::sys::JNI_FALSE,
        }
    })
}

/// Get scripts
#[no_mangle]
pub extern "C" fn Java_com_nervosnetwork_ckblightclient_LightClientNative_nativeGetScripts(
    mut env: JNIEnv,
    _class: JClass,
) -> jstring {
    guard_jni(std::ptr::null_mut(), move || {
        match bridge_query::get_scripts() {
            Ok(json) => json_to_jstring(&mut env, &json),
            Err(_) => ptr::null_mut(),
        }
    })
}

/// Get local node info
#[no_mangle]
pub extern "C" fn Java_com_nervosnetwork_ckblightclient_LightClientNative_nativeLocalNodeInfo(
    mut env: JNIEnv,
    _class: JClass,
) -> jstring {
    guard_jni(std::ptr::null_mut(), move || {
        match bridge_query::local_node_info() {
            Ok(json) => json_to_jstring(&mut env, &json),
            Err(_) => ptr::null_mut(),
        }
    })
}

/// Get peers
#[no_mangle]
pub extern "C" fn Java_com_nervosnetwork_ckblightclient_LightClientNative_nativeGetPeers(
    mut env: JNIEnv,
    _class: JClass,
) -> jstring {
    guard_jni(std::ptr::null_mut(), move || {
        match bridge_query::get_peers() {
            Ok(json) => json_to_jstring(&mut env, &json),
            Err(_) => ptr::null_mut(),
        }
    })
}

// TODO: Implement remaining 10 APIs:
// - nativeGetCells
// - nativeGetTransactions
// - nativeGetCellsCapacity
// - nativeSendTransaction
// - nativeGetTransaction
// - nativeFetchTransaction
// - nativeEstimateCycles
// (Plus the 3 already implemented: GetTipHeader, GetGenesisBlock, GetHeader, FetchHeader,
// SetScripts, GetScripts, LocalNodeInfo, GetPeers)

// Placeholder implementations for remaining APIs
// These return null for now and can be implemented as needed

#[no_mangle]
pub extern "C" fn Java_com_nervosnetwork_ckblightclient_LightClientNative_nativeGetCells(
    mut env: JNIEnv,
    _class: JClass,
    search_key_json: JString,
    order_jstr: JString,
    limit: jni::sys::jint,
    cursor_jstr: JString,
) -> jstring {
    guard_jni(std::ptr::null_mut(), move || {
    check_running!(env);

    let search_key_str: String = match env.get_string(&search_key_json) {
        Ok(s) => s.into(),
        Err(e) => {
            error!("Failed to get search_key string: {}", e);
            return ptr::null_mut();
        }
    };

    let search_key: SearchKey = match serde_json::from_str(&search_key_str) {
        Ok(s) => s,
        Err(e) => {
            error!("Failed to parse search_key JSON: {}", e);
            return ptr::null_mut();
        }
    };

    let order_str: String = match env.get_string(&order_jstr) {
        Ok(s) => s.into(),
        Err(e) => {
            error!("Failed to get order string: {}", e);
            return ptr::null_mut();
        }
    };

    let direction = if order_str == "asc" {
        Direction::Forward
    } else {
        Direction::Reverse
    };

    let cursor_str: String = match env.get_string(&cursor_jstr) {
        Ok(s) => s.into(),
        Err(_) => "".to_string(),
    };

    let swc = match STORAGE_WITH_DATA.get() {
        Some(s) => s,
        None => {
            error!("Storage not initialized");
            return ptr::null_mut();
        }
    };

    // Build prefix based on script type (use Cell prefix, not Tx prefix)
    let mut prefix = match search_key.script_type {
        ScriptType::Lock => vec![KeyPrefix::CellLockScript as u8],
        ScriptType::Type => vec![KeyPrefix::CellTypeScript as u8],
    };
    let script: packed::Script = search_key.script.clone().into();
    prefix.extend_from_slice(extract_raw_data(&script).as_slice());

    // Determine from_key based on cursor
    let (from_key, skip): (Vec<u8>, usize) = if cursor_str.is_empty() {
        if matches!(direction, Direction::Forward) {
            (prefix.clone(), 0)
        } else {
            let mut key = prefix.clone();
            key.extend(vec![0xff; 100]); // Max key for reverse iteration
            (key, 0)
        }
    } else {
        // Cursor round-trip fix: `last_cursor` is emitted as a JsonBytes, which
        // reaches us as a bare `0x..` hex string. serde_json::from_str on the
        // raw hex fails (it wants a QUOTED JSON string) and silently fell back
        // to `prefix`, so every page-2 fetch returned empty and reads capped at
        // 100 items. Wrap it as the JSON string JsonBytes deserializes from.
        match serde_json::from_str::<JsonBytes>(&format!("\"{}\"", cursor_str)) {
            Ok(cursor) => (cursor.as_bytes().to_vec(), 1),
            Err(_) => (prefix.clone(), 0),
        }
    };

    let mode = IteratorMode::From(&from_key, direction);
    let items = swc.storage().iterator_collect(mode, |(key, _)| key.starts_with(&prefix));
    let iter = items.into_iter().skip(skip);

    let mut last_key = Vec::new();
    let cells: Vec<Cell> = iter
        .filter_map(|(key, value)| {
            let tx_hash = packed::Byte32::from_slice(&value).ok()?;
            let output_index = u32::from_be_bytes(
                key[key.len() - 4..]
                    .try_into()
                    .ok()?
            );

            // Get the transaction to extract output details
            let tx_data = swc.storage().get(Key::TxHash(&tx_hash).into_vec()).ok()??;
            let tx = packed::Transaction::from_slice(&tx_data[12..]).ok()?;

            let output = tx.raw().outputs().get(output_index as usize)?;
            let output_data = tx.raw().outputs_data().get(output_index as usize);

            // Extract block number from key
            // Key structure: prefix + script_data + block_number(8) + tx_index(4) + output_index(4)
            let key_len = key.len();
            let block_number = u64::from_be_bytes(
                key[key_len - 16..key_len - 8]
                    .try_into()
                    .ok()?
            );
            let tx_index = u32::from_be_bytes(
                key[key_len - 8..key_len - 4]
                    .try_into()
                    .ok()?
            );

            last_key = key.to_vec();

            Some(Cell {
                output: output.into(),
                output_data: output_data.map(|d| JsonBytes::from_bytes(d.raw_data())),
                out_point: ckb_jsonrpc_types::OutPoint {
                    tx_hash: tx_hash.unpack(),
                    index: output_index.into(),
                },
                block_number: block_number.into(),
                tx_index: tx_index.into(),
            })
        })
        .take(limit.max(0) as usize)
        .collect();

    let result = Pagination {
        objects: cells,
        last_cursor: JsonBytes::from_vec(last_key),
    };

    to_jstring(&mut env, &result)
    })
}

#[no_mangle]
pub extern "C" fn Java_com_nervosnetwork_ckblightclient_LightClientNative_nativeGetTransactions(
    mut env: JNIEnv,
    _class: JClass,
    search_key_json: JString,
    order_jstr: JString,
    limit: jni::sys::jint,
    cursor_jstr: JString,
) -> jstring {
    guard_jni(std::ptr::null_mut(), move || {

    check_running!(env);

    let search_key_str: String = match env.get_string(&search_key_json) {
        Ok(s) => s.into(),
        Err(e) => {
            error!("Failed to get search_key string: {}", e);
            return ptr::null_mut();
        }
    };

    let search_key: SearchKey = match serde_json::from_str(&search_key_str) {
        Ok(s) => s,
        Err(e) => {
            error!("Failed to parse search_key JSON: {}", e);
            return ptr::null_mut();
        }
    };

    let order_str: String = match env.get_string(&order_jstr) {
        Ok(s) => s.into(),
        Err(e) => {
            error!("Failed to get order string: {}", e);
            return ptr::null_mut();
        }
    };

    let direction = if order_str == "asc" {
        Direction::Forward
    } else {
        Direction::Reverse
    };

    let cursor_str: String = match env.get_string(&cursor_jstr) {
        Ok(s) => s.into(),
        Err(_) => "".to_string(),
    };

    let swc = match STORAGE_WITH_DATA.get() {
        Some(s) => s,
        None => {
            error!("Storage not initialized");
            return ptr::null_mut();
        }
    };

    // Build prefix based on script type
    let mut prefix = match search_key.script_type {
        ScriptType::Lock => vec![KeyPrefix::TxLockScript as u8],
        ScriptType::Type => vec![KeyPrefix::TxTypeScript as u8],
    };
    let script: packed::Script = search_key.script.clone().into();
    prefix.extend_from_slice(extract_raw_data(&script).as_slice());

    // Determine from_key
    let (from_key, skip): (Vec<u8>, usize) = if cursor_str.is_empty() {
        if matches!(direction, Direction::Forward) {
            (prefix.clone(), 0)
        } else {
            let mut key = prefix.clone();
            key.extend(vec![0xff; 100]); // Max key
            (key, 0)
        }
    } else {
        // Cursor round-trip fix: `last_cursor` is emitted as a JsonBytes, which
        // reaches us as a bare `0x..` hex string. serde_json::from_str on the
        // raw hex fails (it wants a QUOTED JSON string) and silently fell back
        // to `prefix`, so every page-2 fetch returned empty and reads capped at
        // 100 items. Wrap it as the JSON string JsonBytes deserializes from.
        match serde_json::from_str::<JsonBytes>(&format!("\"{}\"", cursor_str)) {
            Ok(cursor) => (cursor.as_bytes().to_vec(), 1),
            Err(_) => (prefix.clone(), 0),
        }
    };

    let mode = IteratorMode::From(&from_key, direction);
    let items = swc.storage().iterator_collect(mode, |(key, _)| key.starts_with(&prefix));
    let iter = items.into_iter().skip(skip);

    let mut last_key = Vec::new();
    let txs: Vec<Tx> = iter
        .filter_map(|(key, value)| {
            let tx_hash = packed::Byte32::from_slice(&value).ok()?;
            let tx = packed::Transaction::from_slice(
                &swc.storage()
                    .get(Key::TxHash(&tx_hash).into_vec())
                    .ok()??[12..],
            )
            .ok()?;

            let block_number = u64::from_be_bytes(
                key[key.len() - 17..key.len() - 9]
                    .try_into()
                    .ok()?
            );
            let tx_index = u32::from_be_bytes(
                key[key.len() - 9..key.len() - 5]
                    .try_into()
                    .ok()?
            );
            let io_index = u32::from_be_bytes(
                key[key.len() - 5..key.len() - 1]
                    .try_into()
                    .ok()?
            );
            let io_type = if *key.last()? == 0 {
                CellType::Input
            } else {
                CellType::Output
            };

            let io_capacity = if io_type == CellType::Input {
                // For input, io_index indexes into the inputs array; the spent
                // cell's capacity lives in the output that created it, so we
                // resolve it from the previous transaction.
                let input = tx.raw().inputs().get(io_index as usize)?;
                let out_point = input.previous_output();
                let prev_index: u32 = out_point.index().unpack();
                let prev_hash: H256 = out_point.tx_hash().unpack();
                let cur_hash: H256 = tx_hash.unpack();

                // The Ok(None) arm below is a near-unreachable defensive guard,
                // NOT the source of wrong activity amounts. `filter_block`
                // (storage/db/native.rs) only writes an input's index entry
                // inside `if let Some(prev_tx) = self.get_transaction(prev_hash)`
                // — it resolves the spent cell's lock from the locally-stored
                // previous tx to decide the input is ours — and nothing prunes
                // stored txs (the only delete is reorg rollback_to_block). So
                // whenever an input IS indexed, its previous tx is in storage
                // permanently, and this lookup resolves. Ok(None) can therefore
                // only happen on DB corruption or a mid-read reorg; we log and
                // drop that row rather than trust a bogus capacity.
                //
                // The genuine amount error lives at the WRITE layer, upstream of
                // this code and unfixable here: a cell funded BEFORE the sync
                // start block is never in storage, so filter_block never matches
                // or indexes the input that spends it. A tx mixing such a
                // pre-window input with an in-window output to us then shows only
                // the output, so its net looks positive and — because the Kotlin
                // side classifies direction by net sign — a send can render as a
                // receive. The spent cell's lock is genuinely unknowable from
                // local data, so no computation here recovers it. The remedy is
                // sync COVERAGE: sync from at/before the wallet's first funding
                // block (the Custom height option) so no cell is ever pre-window
                // and every amount is correct by construction. See
                // docs/SYNC_COVERAGE_AND_AMOUNTS.md.
                //
                // The malformed-data arm reports 0 (behavior preserved); both
                // arms log so anything unexpected is diagnosable in logcat.
                let prev_tx_bytes = match swc
                    .storage()
                    .get(Key::TxHash(&out_point.tx_hash()).into_vec())
                {
                    Ok(Some(bytes)) => bytes,
                    Ok(None) => {
                        warn!(
                            "nativeGetTransactions: indexed input references prev tx {:#x} \
                             not in storage (unexpected: DB corruption or mid-read reorg, since \
                             filter_block only indexes inputs whose prev tx it stored); \
                             input {}:{} of tx {:#x} dropped",
                            prev_hash, prev_index, io_index, cur_hash
                        );
                        return None;
                    }
                    Err(e) => {
                        error!(
                            "nativeGetTransactions: storage error resolving input capacity \
                             for tx {:#x}: {}",
                            cur_hash, e
                        );
                        return None;
                    }
                };
                prev_tx_bytes
                    .get(12..) // Skip block number (8) and tx index (4) in Value::Transaction
                    .and_then(|data| {
                        let prev_tx = packed::Transaction::from_slice(data).ok()?;
                        prev_tx.raw().outputs().get(prev_index as usize)
                    })
                    .map(|output: packed::CellOutput| Unpack::<core::Capacity>::unpack(&output.capacity()).as_u64())
                    .unwrap_or_else(|| {
                        warn!(
                            "nativeGetTransactions: malformed prev tx {:#x} for input {}:{} \
                             of tx {:#x}; capacity reported as 0",
                            prev_hash, prev_index, io_index, cur_hash
                        );
                        0
                    })
            } else {
                // For output, read capacity directly from the current transaction.
                let cur_hash: H256 = tx_hash.unpack();
                tx.raw()
                    .outputs()
                    .get(io_index as usize)
                    .map(|output: packed::CellOutput| Unpack::<core::Capacity>::unpack(&output.capacity()).as_u64())
                    .unwrap_or_else(|| {
                        warn!(
                            "nativeGetTransactions: output io_index {} out of range for tx {:#x}; \
                             capacity reported as 0",
                            io_index, cur_hash
                        );
                        0
                    })
            };

            last_key = key.to_vec();
            Some(Tx::Ungrouped(TxWithCell {
                transaction: tx.into_view().into(),
                block_number: block_number.into(),
                tx_index: tx_index.into(),
                io_index: io_index.into(),
                io_type,
                io_capacity: io_capacity.into(),
            }))
        })
        .take(limit.max(0) as usize)
        .collect();

    let result = Pagination {
        objects: txs,
        last_cursor: JsonBytes::from_vec(last_key),
    };

    to_jstring(&mut env, &result)
    })
}

#[no_mangle]
pub extern "C" fn Java_com_nervosnetwork_ckblightclient_LightClientNative_nativeGetCellsCapacity(
    mut env: JNIEnv,
    _class: JClass,
    search_key_json: JString,
) -> jstring {
    guard_jni(std::ptr::null_mut(), move || {

    check_running!(env);

    let search_key_str: String = match env.get_string(&search_key_json) {
        Ok(s) => s.into(),
        Err(e) => {
            error!("Failed to get search_key string: {}", e);
            return ptr::null_mut();
        }
    };

    let search_key: SearchKey = match serde_json::from_str(&search_key_str) {
        Ok(s) => s,
        Err(e) => {
            error!("Failed to parse search_key JSON: {}", e);
            return ptr::null_mut();
        }
    };

    let swc = match STORAGE_WITH_DATA.get() {
        Some(s) => s,
        None => {
            error!("Storage not initialized");
            return ptr::null_mut();
        }
    };

    // Build prefix based on script type
    let mut prefix = match search_key.script_type {
        ScriptType::Lock => vec![KeyPrefix::CellLockScript as u8],
        ScriptType::Type => vec![KeyPrefix::CellTypeScript as u8],
    };
    let script: packed::Script = search_key.script.clone().into();
    prefix.extend_from_slice(extract_raw_data(&script).as_slice());

    // Iterate over cells and sum capacity
    let mode = IteratorMode::From(prefix.as_ref(), Direction::Forward);
    let items = swc.storage().iterator_collect(mode, |(key, _)| key.starts_with(&prefix));

    let capacity: u64 = items
        .into_iter()
        .filter_map(|(key, value)| {
            let tx_hash = packed::Byte32::from_slice(&value).ok()?;
            let output_index = u32::from_be_bytes(
                key[key.len() - 4..]
                    .try_into()
                    .ok()?
            );

            let tx = packed::Transaction::from_slice(
                &swc.storage()
                    .get(Key::TxHash(&tx_hash).into_vec())
                    .ok()??[12..],
            )
            .ok()?;
            let output = tx
                .raw()
                .outputs()
                .get(output_index as usize)?;

            Some(Unpack::<core::Capacity>::unpack(&output.capacity()).as_u64())
        })
        .sum();

    // Get tip header for block info
    let tip_header = swc.storage().get_tip_header();
    let tip_view = tip_header.into_view();

    let result = CellsCapacity {
        capacity: capacity.into(),
        block_hash: tip_view.hash().unpack(),
        block_number: tip_view.number().into(),
    };

    to_jstring(&mut env, &result)
    })
}

#[no_mangle]
pub extern "C" fn Java_com_nervosnetwork_ckblightclient_LightClientNative_nativeSendTransaction(
    mut env: JNIEnv,
    _class: JClass,
    tx_json: JString,
) -> jstring {
    guard_jni(std::ptr::null_mut(), move || {
    check_running!(env);

    let tx_str: String = match env.get_string(&tx_json) {
        Ok(s) => s.into(),
        Err(e) => {
            error!("Failed to get transaction string: {}", e);
            return send_error_jstring(&mut env, &format!("could not read transaction: {}", e));
        }
    };

    let tx: Transaction = match serde_json::from_str(&tx_str) {
        Ok(t) => t,
        Err(e) => {
            error!("Failed to parse transaction JSON: {}", e);
            return send_error_jstring(&mut env, &format!("malformed transaction: {}", e));
        }
    };

    let swc = match STORAGE_WITH_DATA.get() {
        Some(s) => s,
        None => {
            error!("Storage not initialized");
            return send_error_jstring(&mut env, "light client not ready (storage not initialized)");
        }
    };

    let consensus = match CONSENSUS.get() {
        Some(c) => Arc::clone(c),
        None => {
            error!("Consensus not initialized");
            return send_error_jstring(&mut env, "light client not ready (consensus not initialized)");
        }
    };

    // Convert to packed transaction and view
    let packed_tx: packed::Transaction = tx.into();
    let tx_view = packed_tx.into_view();

    // Verify the transaction
    let last_state = swc.storage().get_last_state().1.into_view();
    let cycles = match verify_tx(tx_view.clone(), swc, consensus, &last_state) {
        Ok(c) => c,
        Err(e) => {
            // Return the real reason (e.g. Unknown(OutPoint) for an
            // unresolvable input, Dead, capacity, or a script error) so the
            // caller can show it instead of a misleading network message.
            error!("Transaction verification failed: {:?}", e);
            return send_error_jstring(&mut env, &format!("verification failed: {:?}", e));
        }
    };

    // Add to pending transactions. Recover from poisoning rather than
    // double-panic on a lock that was poisoned by a prior panic — a
    // re-panic across the FFI boundary is undefined behavior.
    let mut pending_write = match swc.pending_txs().write() {
        Ok(g) => g,
        Err(poisoned) => {
            error!("pending_txs write lock poisoned; recovering: {:?}", poisoned);
            poisoned.into_inner()
        }
    };
    pending_write.push(tx_view.clone(), cycles);
    drop(pending_write);

    debug!("Transaction added to pending pool: {}", tx_view.hash());

    // Return the transaction hash
    let tx_hash: H256 = tx_view.hash().unpack();
    to_jstring(&mut env, &tx_hash)
    })
}

#[no_mangle]
pub extern "C" fn Java_com_nervosnetwork_ckblightclient_LightClientNative_nativeGetTransaction(
    mut env: JNIEnv,
    _class: JClass,
    hash: JString,
) -> jstring {
    guard_jni(std::ptr::null_mut(), move || {
    check_running!(env);

    let hash_str: String = match env.get_string(&hash) {
        Ok(s) => s.into(),
        Err(e) => {
            error!("nativeGetTransaction: failed to get hash string: {}", e);
            return ptr::null_mut();
        }
    };

    // Strip 0x prefix if present — Kotlin often passes "0xabc..." but H256::from_str expects no prefix
    let hash_hex = hash_str.strip_prefix("0x").unwrap_or(&hash_str);

    let tx_hash = match H256::from_str(hash_hex) {
        Ok(h) => h,
        Err(e) => {
            warn!("nativeGetTransaction: H256 parse failed for '{}': {}", hash_str, e);
            return ptr::null_mut();
        }
    };
    let byte32 = match packed::Byte32::from_slice(tx_hash.as_bytes()) {
        Ok(b) => b,
        Err(e) => {
            error!("Byte32 conversion failed: {}", e);
            return ptr::null_mut();
        }
    };

    let swc = match STORAGE_WITH_DATA.get() {
        Some(s) => s,
        None => {
            error!("nativeGetTransaction: storage not initialized");
            return ptr::null_mut();
        }
    };

    // Recover from poisoning rather than double-panic across the FFI boundary.
    let pending_read = match swc.pending_txs().read() {
        Ok(g) => g,
        Err(poisoned) => {
            error!("nativeGetTransaction: pending_txs read lock poisoned; recovering");
            poisoned.into_inner()
        }
    };

    let result = if let Some((transaction, header)) = swc.storage().get_transaction_with_header(&byte32) {
        debug!("nativeGetTransaction: found committed tx {}", hash_str);
        crate::service::TransactionWithStatus {
            transaction: Some(transaction.into_view().into()),
            cycles: None,
            tx_status: crate::service::TxStatus {
                block_hash: Some(header.into_view().hash().unpack()),
                status: crate::service::Status::Committed,
            },
        }
    } else if let Some((transaction, cycles, _)) = pending_read.get(&byte32) {
        debug!("nativeGetTransaction: found pending tx {}", hash_str);
        crate::service::TransactionWithStatus {
            transaction: Some(transaction.into_view().into()),
            cycles: Some(cycles.into()),
            tx_status: crate::service::TxStatus {
                block_hash: None,
                status: crate::service::Status::Pending,
            },
        }
    } else {
        warn!("nativeGetTransaction: tx not found in storage or pending: {}", hash_str);
        crate::service::TransactionWithStatus {
            transaction: None,
            cycles: None,
            tx_status: crate::service::TxStatus {
                block_hash: None,
                status: crate::service::Status::Unknown,
            },
        }
    };

    to_jstring(&mut env, &result)
    })
}

#[no_mangle]
pub extern "C" fn Java_com_nervosnetwork_ckblightclient_LightClientNative_nativeFetchTransaction(
    mut env: JNIEnv,
    _class: JClass,
    hash: JString,
) -> jstring {
    guard_jni(std::ptr::null_mut(), move || {
    check_running!(env);

    let hash_str: String = match env.get_string(&hash) {
        Ok(s) => s.into(),
        Err(e) => {
            error!("nativeFetchTransaction: failed to get hash string: {}", e);
            return ptr::null_mut();
        }
    };

    let hash_hex = hash_str.strip_prefix("0x").unwrap_or(&hash_str);

    let tx_hash = match H256::from_str(hash_hex) {
        Ok(h) => h,
        Err(e) => {
            warn!("nativeFetchTransaction: H256 parse failed for '{}': {}", hash_str, e);
            return ptr::null_mut();
        }
    };
    let byte32 = match packed::Byte32::from_slice(tx_hash.as_bytes()) {
        Ok(b) => b,
        Err(e) => {
            error!("Byte32 conversion failed: {}", e);
            return ptr::null_mut();
        }
    };

    let swc = match STORAGE_WITH_DATA.get() {
        Some(s) => s,
        None => {
            error!("nativeFetchTransaction: storage not initialized");
            return ptr::null_mut();
        }
    };

    // 1. Check if tx is already in local storage (committed)
    if let Some((transaction, header)) = swc.storage().get_transaction_with_header(&byte32) {
        debug!("nativeFetchTransaction: tx {} already in storage", hash_str);
        let tws = crate::service::TransactionWithStatus {
            transaction: Some(transaction.into_view().into()),
            cycles: None,
            tx_status: crate::service::TxStatus {
                block_hash: Some(header.into_view().hash().unpack()),
                status: crate::service::Status::Committed,
            },
        };
        let fetch_status: FetchStatus<crate::service::TransactionWithStatus> =
            FetchStatus::Fetched { data: tws };
        return to_jstring(&mut env, &fetch_status);
    }

    // 2. Check if tx is in pending pool. Recover from poisoning to avoid
    // double-panic across the FFI boundary.
    let pending_read = match swc.pending_txs().read() {
        Ok(g) => g,
        Err(poisoned) => {
            error!("nativeFetchTransaction: pending_txs read lock poisoned; recovering");
            poisoned.into_inner()
        }
    };
    if let Some((transaction, cycles, _)) = pending_read.get(&byte32) {
        debug!("nativeFetchTransaction: tx {} is pending", hash_str);
        let tws = crate::service::TransactionWithStatus {
            transaction: Some(transaction.into_view().into()),
            cycles: Some(cycles.into()),
            tx_status: crate::service::TxStatus {
                block_hash: None,
                status: crate::service::Status::Pending,
            },
        };
        let fetch_status: FetchStatus<crate::service::TransactionWithStatus> =
            FetchStatus::Fetched { data: tws };
        return to_jstring(&mut env, &fetch_status);
    }

    // 3. Check fetch queue status or add to fetch queue
    // Verify network controller is available before queuing fetches
    let _net_controller = match NET_CONTROL.get() {
        Some(nc) => nc,
        None => {
            error!("nativeFetchTransaction: network controller not initialized");
            return ptr::null_mut();
        }
    };

    let now = unix_time_as_millis();
    let fetch_status: FetchStatus<crate::service::TransactionWithStatus> =
        if let Some((added_ts, first_sent, missing)) = swc.get_tx_fetch_info(&tx_hash) {
            if missing {
                // Previously missing — re-add to fetch queue for retry.
                // Return NotFound (not Added) to mirror WASM/RPC behavior (rpc.rs:876-879):
                // the caller learns the tx was not found on the previous attempt.
                // On subsequent polls the status will progress to Added → Fetching → Fetched.
                debug!("nativeFetchTransaction: tx {} was missing, re-adding to fetch queue", hash_str);
                swc.add_fetch_tx(tx_hash, now);
                FetchStatus::NotFound
            } else if first_sent > 0 {
                debug!("nativeFetchTransaction: tx {} is being fetched", hash_str);
                FetchStatus::Fetching {
                    first_sent: first_sent.into(),
                }
            } else {
                debug!("nativeFetchTransaction: tx {} is queued for fetch", hash_str);
                FetchStatus::Added {
                    timestamp: added_ts.into(),
                }
            }
        } else {
            // Not in fetch queue — add it
            debug!("nativeFetchTransaction: adding tx {} to fetch queue", hash_str);
            swc.add_fetch_tx(tx_hash, now);
            FetchStatus::Added {
                timestamp: now.into(),
            }
        };

    to_jstring(&mut env, &fetch_status)
    })
}

#[no_mangle]
pub extern "C" fn Java_com_nervosnetwork_ckblightclient_LightClientNative_nativeEstimateCycles(
    _env: JNIEnv,
    _class: JClass,
    _tx_json: JString,
) -> jstring {
    guard_jni(std::ptr::null_mut(), move || {
    // TODO: Implement
    warn!("nativeEstimateCycles not yet implemented");
    ptr::null_mut()
    })
}
