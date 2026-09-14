//! DAO utility JNI functions for Nervos DAO compensation and epoch calculations.
//!
//! Thin marshalling layer over [`crate::bridge_core::dao`]:
//! - Extracting DAO header fields (C, AR, S, U)
//! - Calculating max withdrawable capacity (deposit + compensation)
//! - Calculating unlock epoch (since value for phase 2)

use super::panic_guard::guard_jni;
use crate::bridge_core::dao as bridge_dao;
use jni::objects::{JClass, JString};
use jni::sys::{jlong, jstring};
use jni::JNIEnv;
use log::error;
use std::ptr;

/// Helper: create a JNI string from a Rust string
fn dao_to_jstring(env: &mut JNIEnv, s: &str) -> jstring {
    match env.new_string(s) {
        Ok(js) => js.into_raw(),
        Err(e) => {
            error!("Failed to create JString: {}", e);
            ptr::null_mut()
        }
    }
}

/// Helper: get a Rust String from a JNI JString
fn get_string(env: &mut JNIEnv, input: &JString) -> Option<String> {
    match env.get_string(input) {
        Ok(s) => Some(s.into()),
        Err(e) => {
            error!("Failed to get string from JNI: {}", e);
            None
        }
    }
}

/// Parse 32-byte DAO header field into 4 u64 values (C, AR, S, U).
/// Little-endian byte order: bytes 0-8 = C, 8-16 = AR, 16-24 = S, 24-32 = U.
/// Returns JSON: {"c":"0x...","ar":"0x...","s":"0x...","u":"0x..."}
#[no_mangle]
pub extern "C" fn Java_com_nervosnetwork_ckblightclient_LightClientNative_nativeExtractDaoFields(
    mut env: JNIEnv,
    _class: JClass,
    dao_hex: JString,
) -> jstring {
    guard_jni(std::ptr::null_mut(), move || {
        let dao_str = match get_string(&mut env, &dao_hex) {
            Some(s) => s,
            None => return ptr::null_mut(),
        };

        match bridge_dao::extract_dao_fields(&dao_str) {
            Ok(json) => dao_to_jstring(&mut env, &json),
            Err(_) => ptr::null_mut(),
        }
    })
}

/// Calculate max withdrawable capacity (deposit + compensation).
/// Formula: (capacity - occupied) * AR_withdraw / AR_deposit + occupied
#[no_mangle]
pub extern "C" fn Java_com_nervosnetwork_ckblightclient_LightClientNative_nativeCalculateMaxWithdraw(
    mut env: JNIEnv,
    _class: JClass,
    deposit_header_dao_hex: JString,
    withdraw_header_dao_hex: JString,
    deposit_capacity: jlong,
    occupied_capacity: jlong,
) -> jlong {
    guard_jni(-1, move || {
        let deposit_dao_str = match get_string(&mut env, &deposit_header_dao_hex) {
            Some(s) => s,
            None => return -1,
        };
        let withdraw_dao_str = match get_string(&mut env, &withdraw_header_dao_hex) {
            Some(s) => s,
            None => return -1,
        };

        match bridge_dao::calculate_max_withdraw(
            &deposit_dao_str,
            &withdraw_dao_str,
            deposit_capacity,
            occupied_capacity,
        ) {
            Ok(max_withdraw) => max_withdraw,
            Err(_) => -1,
        }
    })
}

/// Calculate the since value (absolute epoch) for phase 2 unlock.
/// Parse epoch hex -> calc deposited epochs -> round up to 180-boundary -> encode.
#[no_mangle]
pub extern "C" fn Java_com_nervosnetwork_ckblightclient_LightClientNative_nativeCalculateUnlockEpoch(
    mut env: JNIEnv,
    _class: JClass,
    deposit_epoch_hex: JString,
    withdraw_epoch_hex: JString,
) -> jstring {
    guard_jni(std::ptr::null_mut(), move || {
        let deposit_str = match get_string(&mut env, &deposit_epoch_hex) {
            Some(s) => s,
            None => return ptr::null_mut(),
        };
        let withdraw_str = match get_string(&mut env, &withdraw_epoch_hex) {
            Some(s) => s,
            None => return ptr::null_mut(),
        };

        match bridge_dao::calculate_unlock_epoch(&deposit_str, &withdraw_str) {
            Ok(since) => dao_to_jstring(&mut env, &since),
            Err(_) => ptr::null_mut(),
        }
    })
}
