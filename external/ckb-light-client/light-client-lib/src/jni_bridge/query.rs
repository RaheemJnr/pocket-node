//! Query APIs for JNI bridge
//!
//! Provides 17 query APIs matching WASM implementation.
//! All functions return JSON strings for complex types, or null on error.

use super::panic_guard::guard_jni;
use super::types::*;
use crate::service::{
    Cell, CellType, CellsCapacity, FetchStatus, LocalNode, Pagination, RemoteNode, 
    ScriptType, SearchKey, SetScriptsCommand, Tx, TxWithCell,
};
use crate::storage::{self, extract_raw_data, Key, KeyPrefix, Direction, IteratorMode};
use crate::verify::verify_tx;
use ckb_jsonrpc_types::{BlockView, HeaderView, JsonBytes, Transaction};
use ckb_network::extract_peer_id;
use ckb_systemtime::unix_time_as_millis;
use ckb_traits::HeaderProvider;
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
    check_running!(env);

    let swc = match STORAGE_WITH_DATA.get() {
        Some(s) => s,
        None => {
            error!("Storage not initialized");
            return ptr::null_mut();
        }
    };

    let tip_header = swc.storage().get_tip_header();
    let header_view: HeaderView = tip_header.into_view().into();

    to_jstring(&mut env, &header_view)
    })
}

/// Get genesis block
#[no_mangle]
pub extern "C" fn Java_com_nervosnetwork_ckblightclient_LightClientNative_nativeGetGenesisBlock(
    mut env: JNIEnv,
    _class: JClass,
) -> jstring {
    guard_jni(std::ptr::null_mut(), move || {
    check_running!(env);

    let swc = match STORAGE_WITH_DATA.get() {
        Some(s) => s,
        None => {
            error!("Storage not initialized");
            return ptr::null_mut();
        }
    };

    let genesis_block = swc.storage().get_genesis_block();

    // Convert packed::Block to BlockView via core::BlockView
    let core_block_view: ckb_types::core::BlockView = genesis_block.into_view();
    let block_view: BlockView = core_block_view.into();
    to_jstring(&mut env, &block_view)
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
    check_running!(env);

    let hash_str: String = match env.get_string(&hash) {
        Ok(s) => s.into(),
        Err(e) => {
            error!("Failed to get hash string: {}", e);
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

    let hash_hex = hash_str.strip_prefix("0x").unwrap_or(&hash_str);
    let h256 = match H256::from_str(hash_hex) {
        Ok(h) => h,
        Err(e) => {
            error!("nativeGetHeader: invalid hash '{}': {}", hash_str, e);
            return ptr::null_mut();
        }
    };

    // H256 is always 32 bytes so the conversion cannot fail in practice;
    // pattern-match anyway to avoid an FFI panic landmine.
    let hash = match packed::Byte32::from_slice(h256.as_bytes()) {
        Ok(h) => h,
        Err(e) => {
            error!("nativeGetHeader: Byte32 conversion failed: {}", e);
            return ptr::null_mut();
        }
    };

    match swc.storage().get_header(&hash) {
        Some(header) => {
            let header_view: HeaderView = header.into();
            to_jstring(&mut env, &header_view)
        }
        None => ptr::null_mut(),
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
    check_running!(env);

    let number_str: String = match env.get_string(&block_number) {
        Ok(s) => s.into(),
        Err(e) => {
            error!("nativeGetHeaderByNumber: failed to get string: {}", e);
            return ptr::null_mut();
        }
    };

    // Strip 0x prefix if present and parse as u64
    let num_hex = number_str.strip_prefix("0x").unwrap_or(&number_str);
    let block_num: u64 = match u64::from_str_radix(num_hex, if number_str.starts_with("0x") { 16 } else { 10 }) {
        Ok(n) => n,
        Err(e) => {
            warn!("nativeGetHeaderByNumber: invalid block number '{}': {}", number_str, e);
            return ptr::null_mut();
        }
    };

    let swc = match STORAGE_WITH_DATA.get() {
        Some(s) => s,
        None => {
            error!("nativeGetHeaderByNumber: storage not initialized");
            return ptr::null_mut();
        }
    };

    // Hop 1: BlockNumber → BlockHash
    let block_hash_bytes = match swc.storage().get(Key::BlockNumber(block_num).into_vec()) {
        Ok(Some(bytes)) => bytes,
        Ok(None) => {
            debug!("nativeGetHeaderByNumber: no block hash for number {}", block_num);
            return ptr::null_mut();
        }
        Err(e) => {
            error!("nativeGetHeaderByNumber: db error for block {}: {}", block_num, e);
            return ptr::null_mut();
        }
    };

    let block_hash = match packed::Byte32::from_slice(&block_hash_bytes) {
        Ok(h) => h,
        Err(e) => {
            error!("nativeGetHeaderByNumber: malformed block hash for block {}: {}", block_num, e);
            return ptr::null_mut();
        }
    };

    // Hop 2: BlockHash → Header
    match swc.storage().get_header(&block_hash) {
        Some(header) => {
            let header_view: HeaderView = header.into();
            to_jstring(&mut env, &header_view)
        }
        None => {
            warn!("nativeGetHeaderByNumber: block hash found but header missing for number {}", block_num);
            ptr::null_mut()
        }
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
    check_running!(env);

    let hash_str: String = match env.get_string(&hash) {
        Ok(s) => s.into(),
        Err(e) => {
            error!("Failed to get hash string: {}", e);
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

    let peers = match PEERS.get() {
        Some(p) => p,
        None => {
            error!("Peers not initialized");
            return ptr::null_mut();
        }
    };

    let hash_hex = hash_str.strip_prefix("0x").unwrap_or(&hash_str);
    let h256 = match H256::from_str(hash_hex) {
        Ok(h) => h,
        Err(e) => {
            error!("nativeFetchHeader: invalid hash '{}': {}", hash_str, e);
            return ptr::null_mut();
        }
    };

    let hash = match packed::Byte32::from_slice(h256.as_bytes()) {
        Ok(h) => h,
        Err(e) => {
            error!("nativeFetchHeader: Byte32 conversion failed: {}", e);
            return ptr::null_mut();
        }
    };

    let fetch_status: FetchStatus<HeaderView> =
        if let Some(header) = swc.storage().get_header(&hash) {
            FetchStatus::Fetched {
                data: header.into(),
            }
        } else if peers.fetching_headers().contains_key(&hash) {
            FetchStatus::Fetching {
                first_sent: 0.into(),
            }
        } else {
            // Add to fetch queue
            let _net_controller = match NET_CONTROL.get() {
                Some(nc) => nc,
                None => {
                    error!("Network controller not initialized");
                    return ptr::null_mut();
                }
            };

            let timestamp = unix_time_as_millis();
            peers.add_fetch_header(hash.clone(), timestamp);

            FetchStatus::Added {
                timestamp: timestamp.into(),
            }
        };

    to_jstring(&mut env, &fetch_status)
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
    if !is_running() {
        warn!("Light client not running");
        return jni::sys::JNI_FALSE;
    }

    let scripts_str: String = match env.get_string(&scripts_json) {
        Ok(s) => s.into(),
        Err(e) => {
            error!("Failed to get scripts JSON: {}", e);
            return jni::sys::JNI_FALSE;
        }
    };

    let scripts_json: Vec<crate::service::ScriptStatus> = match serde_json::from_str(&scripts_str) {
        Ok(s) => s,
        Err(e) => {
            error!("Failed to parse scripts JSON: {}", e);
            return jni::sys::JNI_FALSE;
        }
    };

    // Convert service::ScriptStatus to storage::ScriptStatus
    let scripts: Vec<storage::ScriptStatus> = scripts_json
        .into_iter()
        .map(|s| storage::ScriptStatus {
            script: s.script.into(),
            script_type: match s.script_type {
                crate::service::ScriptType::Lock => storage::ScriptType::Lock,
                crate::service::ScriptType::Type => storage::ScriptType::Type,
            },
            block_number: s.block_number.into(),
        })
        .collect();

    let cmd = match command {
        0 => SetScriptsCommand::All,
        1 => SetScriptsCommand::Partial,
        2 => SetScriptsCommand::Delete,
        _ => {
            error!("Invalid command: {}", command);
            return jni::sys::JNI_FALSE;
        }
    };

    let swc = match STORAGE_WITH_DATA.get() {
        Some(s) => s,
        None => {
            error!("Storage not initialized");
            return jni::sys::JNI_FALSE;
        }
    };

    swc.storage().update_filter_scripts(scripts, cmd.into());

    // Clear matched blocks when scripts change
    let peers = match PEERS.get() {
        Some(p) => p,
        None => {
            error!("Peers not initialized");
            return jni::sys::JNI_FALSE;
        }
    };

    // Lock matched_blocks and clear them
    let mut matched_blocks = peers.matched_blocks().blocking_write();
    peers.clear_matched_blocks(&mut matched_blocks);

    jni::sys::JNI_TRUE
    })
}

/// Get scripts
#[no_mangle]
pub extern "C" fn Java_com_nervosnetwork_ckblightclient_LightClientNative_nativeGetScripts(
    mut env: JNIEnv,
    _class: JClass,
) -> jstring {
    guard_jni(std::ptr::null_mut(), move || {
    check_running!(env);

    let swc = match STORAGE_WITH_DATA.get() {
        Some(s) => s,
        None => {
            error!("Storage not initialized");
            return ptr::null_mut();
        }
    };

    let scripts = swc.storage().get_filter_scripts();
    // Convert storage::ScriptStatus to service::ScriptStatus for serialization
    let scripts: Vec<crate::service::ScriptStatus> = scripts
        .into_iter()
        .map(|s| crate::service::ScriptStatus {
            script: s.script.into(),
            script_type: match s.script_type {
                storage::ScriptType::Lock => crate::service::ScriptType::Lock,
                storage::ScriptType::Type => crate::service::ScriptType::Type,
            },
            block_number: s.block_number.into(),
        })
        .collect();
    to_jstring(&mut env, &scripts)
    })
}

/// Get local node info
#[no_mangle]
pub extern "C" fn Java_com_nervosnetwork_ckblightclient_LightClientNative_nativeLocalNodeInfo(
    mut env: JNIEnv,
    _class: JClass,
) -> jstring {
    guard_jni(std::ptr::null_mut(), move || {
    check_running!(env);

    let net_controller = match NET_CONTROL.get() {
        Some(nc) => nc,
        None => {
            error!("Network controller not initialized");
            return ptr::null_mut();
        }
    };

    let _consensus = match CONSENSUS.get() {
        Some(c) => c,
        None => {
            error!("Consensus not initialized");
            return ptr::null_mut();
        }
    };

    let node_id = net_controller.node_id();

    let node_info = LocalNode {
        active: is_running(),
        addresses: vec![], // TODO: get actual addresses
        connections: (net_controller.connected_peers().len() as u64).into(),
        node_id,
        protocols: vec![], // TODO: get actual protocols
        version: env!("CARGO_PKG_VERSION").to_owned(),
    };

    to_jstring(&mut env, &node_info)
    })
}

/// Get peers
#[no_mangle]
pub extern "C" fn Java_com_nervosnetwork_ckblightclient_LightClientNative_nativeGetPeers(
    mut env: JNIEnv,
    _class: JClass,
) -> jstring {
    guard_jni(std::ptr::null_mut(), move || {
    check_running!(env);

    let net_controller = match NET_CONTROL.get() {
        Some(nc) => nc,
        None => {
            error!("Network controller not initialized");
            return ptr::null_mut();
        }
    };

    let mut remote_nodes = Vec::new();

    // connected_peers() returns Vec<(SessionId, Peer)>
    for (_session_id, peer) in net_controller.connected_peers() {
        // Extract peer_id from the connected address
        let node_id = extract_peer_id(&peer.connected_addr)
            .map(|id| id.to_base58())
            .unwrap_or_else(|| "unknown".to_owned());

        // Calculate connection duration in milliseconds
        let connected_duration_ms = peer.connected_time.elapsed().as_millis() as u64;

        let remote_node = RemoteNode {
            version: peer
                .identify_info
                .as_ref()
                .map(|info| info.client_version.clone())
                .unwrap_or_else(|| "unknown".to_owned()),
            node_id,
            addresses: vec![], // TODO: get actual addresses
            connected_duration: connected_duration_ms.into(),
            sync_state: None,  // TODO: get sync state
            protocols: vec![], // TODO: get actual protocols
        };

        remote_nodes.push(remote_node);
    }

    to_jstring(&mut env, &remote_nodes)
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
                // For input, the io_index is the index into the inputs array
                let input = tx.raw().inputs().get(io_index as usize)?;
                let out_point = input.previous_output();
                // We need to look up the capacity of the spent cell from storage
                swc.storage()
                    .get(Key::TxHash(&out_point.tx_hash()).into_vec())
                    .ok()??
                    .get(12..) // Skip block number (8) and tx index (4) in Value::Transaction
                    .and_then(|data| {
                        let tx = packed::Transaction::from_slice(data).ok()?;
                        let index: u32 = out_point.index().unpack();
                        tx.raw().outputs().get(index as usize)
                    })
                    .map(|output: packed::CellOutput| Unpack::<core::Capacity>::unpack(&output.capacity()).as_u64())
                    .unwrap_or(0)
            } else {
                // For output, we can get it directly from the current transaction
                tx.raw()
                    .outputs()
                    .get(io_index as usize)
                    .map(|output: packed::CellOutput| Unpack::<core::Capacity>::unpack(&output.capacity()).as_u64())
                    .unwrap_or(0)
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
