//! DAO utility JNI functions for Nervos DAO compensation and epoch calculations.
//!
//! Three functions exposing `ckb-dao-utils` for:
//! - Extracting DAO header fields (C, AR, S, U)
//! - Calculating max withdrawable capacity (deposit + compensation)
//! - Calculating unlock epoch (since value for phase 2)

use super::panic_guard::guard_jni;
use jni::objects::{JClass, JString};
use jni::sys::{jlong, jstring};
use jni::JNIEnv;
use log::error;
use std::ptr;

use ckb_types::core::EpochNumberWithFraction;

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

/// Decode a hex string (with optional 0x prefix) into bytes.
/// Uses `as_bytes()` to avoid potential panics from str slicing on non-ASCII boundaries.
fn decode_hex(hex_str: &str) -> Option<Vec<u8>> {
    let stripped = hex_str.strip_prefix("0x").unwrap_or(hex_str);
    let raw = stripped.as_bytes();
    if raw.len() % 2 != 0 {
        return None;
    }
    let mut bytes = Vec::with_capacity(raw.len() / 2);
    for pair in raw.chunks_exact(2) {
        // SAFETY: each byte in a valid hex char is ASCII, so from_utf8 won't fail
        let hex_pair = std::str::from_utf8(pair).ok()?;
        let b = u8::from_str_radix(hex_pair, 16).ok()?;
        bytes.push(b);
    }
    Some(bytes)
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

    let bytes = match decode_hex(&dao_str) {
        Some(b) if b.len() == 32 => b,
        Some(b) => {
            error!("DAO field must be 32 bytes, got {}", b.len());
            return ptr::null_mut();
        }
        None => {
            error!("Failed to decode DAO hex");
            return ptr::null_mut();
        }
    };

    // Slicing into 8-byte chunks of a verified 32-byte vec cannot fail; the
    // pattern match still avoids the unwrap-as-panic-landmine in FFI context.
    let chunks: Option<[[u8; 8]; 4]> = (|| {
        Some([
            bytes[0..8].try_into().ok()?,
            bytes[8..16].try_into().ok()?,
            bytes[16..24].try_into().ok()?,
            bytes[24..32].try_into().ok()?,
        ])
    })();
    let [c_buf, ar_buf, s_buf, u_buf] = match chunks {
        Some(arr) => arr,
        None => {
            error!("DAO field chunk conversion failed");
            return ptr::null_mut();
        }
    };
    let c = u64::from_le_bytes(c_buf);
    let ar = u64::from_le_bytes(ar_buf);
    let s = u64::from_le_bytes(s_buf);
    let u = u64::from_le_bytes(u_buf);

    let json = format!(
        r#"{{"c":"0x{:x}","ar":"0x{:x}","s":"0x{:x}","u":"0x{:x}"}}"#,
        c, ar, s, u
    );

    dao_to_jstring(&mut env, &json)
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

    let parse_ar = |hex_str: &str| -> Option<u64> {
        let bytes = decode_hex(hex_str)?;
        if bytes.len() != 32 { return None; }
        Some(u64::from_le_bytes(bytes[8..16].try_into().ok()?))
    };

    let ar_deposit = match parse_ar(&deposit_dao_str) {
        Some(ar) => ar as u128,
        None => {
            error!("Failed to parse deposit DAO AR");
            return -1;
        }
    };

    let ar_withdraw = match parse_ar(&withdraw_dao_str) {
        Some(ar) => ar as u128,
        None => {
            error!("Failed to parse withdraw DAO AR");
            return -1;
        }
    };

    // Validate inputs
    if deposit_capacity < 0 || occupied_capacity < 0 {
        error!("Negative capacity inputs");
        return -1;
    }
    if ar_deposit == 0 {
        error!("ar_deposit is zero, cannot divide");
        return -1;
    }

    let capacity = deposit_capacity as u128;
    let occupied = occupied_capacity as u128;

    if occupied > capacity {
        error!("occupied_capacity exceeds deposit_capacity");
        return -1;
    }

    // Formula from RFC-0023
    let counted_capacity = capacity - occupied;
    let max_withdraw = counted_capacity.saturating_mul(ar_withdraw) / ar_deposit + occupied;

    if max_withdraw > jlong::MAX as u128 {
        error!("max_withdraw overflows jlong");
        return -1;
    }

    max_withdraw as jlong
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

    let parse_epoch = |s: &str| -> Option<u64> {
        let stripped = s.strip_prefix("0x").unwrap_or(s);
        u64::from_str_radix(stripped, 16).ok()
    };

    let deposit_epoch_raw = match parse_epoch(&deposit_str) {
        Some(e) => e,
        None => {
            error!("Failed to parse deposit epoch hex");
            return ptr::null_mut();
        }
    };
    let withdraw_epoch_raw = match parse_epoch(&withdraw_str) {
        Some(e) => e,
        None => {
            error!("Failed to parse withdraw epoch hex");
            return ptr::null_mut();
        }
    };

    let deposit_epoch = EpochNumberWithFraction::from_full_value(deposit_epoch_raw);
    let withdraw_epoch = EpochNumberWithFraction::from_full_value(withdraw_epoch_raw);

    // Validate epoch fractions: length must be > 0 and index < length
    // (length == 0 only for genesis, which can't be a DAO deposit/withdraw epoch)
    if deposit_epoch.length() == 0 || withdraw_epoch.length() == 0 {
        error!(
            "Invalid epoch length: deposit_epoch length={}, withdraw_epoch length={}",
            deposit_epoch.length(), withdraw_epoch.length()
        );
        return ptr::null_mut();
    }
    if deposit_epoch.index() >= deposit_epoch.length() {
        error!(
            "Invalid deposit epoch: index {} >= length {}",
            deposit_epoch.index(), deposit_epoch.length()
        );
        return ptr::null_mut();
    }
    if withdraw_epoch.index() >= withdraw_epoch.length() {
        error!(
            "Invalid withdraw epoch: index {} >= length {}",
            withdraw_epoch.index(), withdraw_epoch.length()
        );
        return ptr::null_mut();
    }

    let deposit_number = deposit_epoch.number();
    let withdraw_number = withdraw_epoch.number();

    if withdraw_number < deposit_number {
        error!("Withdraw epoch ({}) is earlier than deposit epoch ({})", withdraw_number, deposit_number);
        return ptr::null_mut();
    }

    // Calculate deposited epochs (withdraw fraction > deposit fraction means +1)
    let deposited_epochs = if withdraw_epoch.index() * deposit_epoch.length()
        > deposit_epoch.index() * withdraw_epoch.length()
    {
        withdraw_number - deposit_number + 1
    } else {
        withdraw_number - deposit_number
    };

    // Round up to next 180-epoch boundary
    let lock_epochs = ((deposited_epochs + 179) / 180) * 180;
    let minimal_unlock_epoch = deposit_number + lock_epochs;

    // Encode as absolute epoch since value (0x20 prefix = absolute epoch flag).
    // dao.c's minimal unlock point is (deposit_number + lock_epochs,
    // deposit_index, deposit_length) — the fraction must carry the deposit
    // epoch's index/length, not 0/1, or the on-chain script rejects with
    // ERROR_INCORRECT_SINCE whenever deposit_index > 0 (#315). Matches
    // ckb-sdk-rust's minimal_unlock_point.
    let since_epoch = EpochNumberWithFraction::new(
        minimal_unlock_epoch,
        deposit_epoch.index(),
        deposit_epoch.length(),
    );
    // Absolute epoch flag: 0x2000000000000000
    let since_value = 0x2000_0000_0000_0000u64 | since_epoch.full_value();

    let result = format!("0x{:x}", since_value);
    dao_to_jstring(&mut env, &result)
    })
}
