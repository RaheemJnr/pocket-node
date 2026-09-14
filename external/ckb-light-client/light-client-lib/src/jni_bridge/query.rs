//! Query APIs for JNI bridge
//!
//! Provides 17 query APIs matching WASM implementation.
//! All functions return JSON strings for complex types, or null on error.

use super::panic_guard::guard_jni;
use super::types::*;
use crate::bridge_core::query as bridge_query;
use crate::service::FetchStatus;
use crate::verify::verify_tx;
use ckb_jsonrpc_types::Transaction;
use ckb_systemtime::unix_time_as_millis;
use ckb_types::{packed, prelude::{*, IntoHeaderView, IntoTransactionView}, H256};
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

/// Helper to read an optional `JString` argument, treating an unreadable value
/// as an empty string.
fn optional_jstring(env: &mut JNIEnv, value: &JString) -> String {
    match env.get_string(value) {
        Ok(s) => s.into(),
        Err(_) => String::new(),
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
        let search_key_str = match jstring_to_string(&mut env, &search_key_json, "nativeGetCells") {
            Some(s) => s,
            None => return ptr::null_mut(),
        };
        let order_str = match jstring_to_string(&mut env, &order_jstr, "nativeGetCells") {
            Some(s) => s,
            None => return ptr::null_mut(),
        };
        // A missing/unreadable cursor means "first page", matching the
        // historical behaviour of this export.
        let cursor_str = optional_jstring(&mut env, &cursor_jstr);

        match bridge_query::get_cells(&search_key_str, &order_str, limit, &cursor_str) {
            Ok(json) => json_to_jstring(&mut env, &json),
            Err(_) => ptr::null_mut(),
        }
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
        let search_key_str =
            match jstring_to_string(&mut env, &search_key_json, "nativeGetTransactions") {
                Some(s) => s,
                None => return ptr::null_mut(),
            };
        let order_str = match jstring_to_string(&mut env, &order_jstr, "nativeGetTransactions") {
            Some(s) => s,
            None => return ptr::null_mut(),
        };
        let cursor_str = optional_jstring(&mut env, &cursor_jstr);

        match bridge_query::get_transactions(&search_key_str, &order_str, limit, &cursor_str) {
            Ok(json) => json_to_jstring(&mut env, &json),
            Err(_) => ptr::null_mut(),
        }
    })
}

#[no_mangle]
pub extern "C" fn Java_com_nervosnetwork_ckblightclient_LightClientNative_nativeGetCellsCapacity(
    mut env: JNIEnv,
    _class: JClass,
    search_key_json: JString,
) -> jstring {
    guard_jni(std::ptr::null_mut(), move || {
        let search_key_str =
            match jstring_to_string(&mut env, &search_key_json, "nativeGetCellsCapacity") {
                Some(s) => s,
                None => return ptr::null_mut(),
            };

        match bridge_query::get_cells_capacity(&search_key_str) {
            Ok(json) => json_to_jstring(&mut env, &json),
            Err(_) => ptr::null_mut(),
        }
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
