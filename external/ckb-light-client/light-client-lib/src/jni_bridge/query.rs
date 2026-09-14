//! Query APIs for JNI bridge
//!
//! Marshalling only: every export reads its arguments off the JVM, calls the
//! matching function in [`crate::bridge_core::query`] and hands the JSON string
//! back. Failures collapse to null (the historical contract), except
//! `nativeSendTransaction`, which reports the reason behind a sentinel prefix.

use super::panic_guard::guard_jni;
use super::types::*;
use crate::bridge_core::query as bridge_query;
use crate::bridge_core::BridgeError;
use jni::objects::{JClass, JString};
use jni::sys::jstring;
use jni::JNIEnv;
use log::{error, warn};
use std::ptr;

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

/// The detail of a [`BridgeError`], without the variant's Display prefix.
///
/// `nativeSendTransaction` reports failures to Kotlin as `__SEND_ERROR__:<reason>`
/// and the reason has always been the bare message, so unwrap the payload.
fn error_detail(err: &BridgeError) -> String {
    match err {
        BridgeError::NotFound(reason)
        | BridgeError::Config(reason)
        | BridgeError::Storage(reason)
        | BridgeError::Network(reason)
        | BridgeError::Internal(reason) => reason.clone(),
        other => other.to_string(),
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

/// Get live cells matching a search key
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
        if !is_running() {
            warn!("Light client not running, current state: {}", get_state());
            return ptr::null_mut();
        }

        let tx_str: String = match env.get_string(&tx_json) {
            Ok(s) => s.into(),
            Err(e) => {
                error!("Failed to get transaction string: {}", e);
                return send_error_jstring(&mut env, &format!("could not read transaction: {}", e));
            }
        };

        match bridge_query::send_transaction(&tx_str) {
            Ok(json) => json_to_jstring(&mut env, &json),
            // The is_running check above can go stale between here and the
            // core's own check. Kotlin has always seen a plain null for a
            // client that is not running, so keep the sentinel for real send
            // failures only.
            Err(BridgeError::NotInitialized) => ptr::null_mut(),
            // Kotlin reads the reason off the sentinel prefix, so pass the
            // bridge error's detail through rather than its Display form.
            Err(err) => send_error_jstring(&mut env, &error_detail(&err)),
        }
    })
}

#[no_mangle]
pub extern "C" fn Java_com_nervosnetwork_ckblightclient_LightClientNative_nativeGetTransaction(
    mut env: JNIEnv,
    _class: JClass,
    hash: JString,
) -> jstring {
    guard_jni(std::ptr::null_mut(), move || {
        let hash_str = match jstring_to_string(&mut env, &hash, "nativeGetTransaction") {
            Some(s) => s,
            None => return ptr::null_mut(),
        };

        match bridge_query::get_transaction(&hash_str) {
            Ok(json) => json_to_jstring(&mut env, &json),
            Err(_) => ptr::null_mut(),
        }
    })
}

#[no_mangle]
pub extern "C" fn Java_com_nervosnetwork_ckblightclient_LightClientNative_nativeFetchTransaction(
    mut env: JNIEnv,
    _class: JClass,
    hash: JString,
) -> jstring {
    guard_jni(std::ptr::null_mut(), move || {
        let hash_str = match jstring_to_string(&mut env, &hash, "nativeFetchTransaction") {
            Some(s) => s,
            None => return ptr::null_mut(),
        };

        match bridge_query::fetch_transaction(&hash_str) {
            Ok(json) => json_to_jstring(&mut env, &json),
            Err(_) => ptr::null_mut(),
        }
    })
}

#[no_mangle]
pub extern "C" fn Java_com_nervosnetwork_ckblightclient_LightClientNative_nativeEstimateCycles(
    mut env: JNIEnv,
    _class: JClass,
    _tx_json: JString,
) -> jstring {
    guard_jni(std::ptr::null_mut(), move || {
        // The argument stays unread while this is unimplemented, exactly as
        // before: there is no point crossing the JVM boundary for a string the
        // core function ignores.
        match bridge_query::estimate_cycles("") {
            Ok(json) => json_to_jstring(&mut env, &json),
            Err(_) => ptr::null_mut(),
        }
    })
}
