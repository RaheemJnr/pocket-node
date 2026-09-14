//! Platform-neutral Nervos DAO helpers.
//!
//! Pure arithmetic over header fields — none of these touch the node, so they
//! work whether or not the light client is running:
//! - Extracting DAO header fields (C, AR, S, U)
//! - Calculating max withdrawable capacity (deposit + compensation)
//! - Calculating unlock epoch (since value for phase 2)

use super::error::BridgeError;
use ckb_types::core::EpochNumberWithFraction;
use log::error;

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

/// Read the AR field (bytes 8..16, little-endian) out of a 32-byte DAO field.
fn parse_ar(hex_str: &str) -> Option<u64> {
    let bytes = decode_hex(hex_str)?;
    if bytes.len() != 32 {
        return None;
    }
    Some(u64::from_le_bytes(bytes[8..16].try_into().ok()?))
}

/// Parse a 32-byte DAO header field into its 4 u64 values (C, AR, S, U).
///
/// Little-endian byte order: bytes 0-8 = C, 8-16 = AR, 16-24 = S, 24-32 = U.
/// Returns JSON: `{"c":"0x...","ar":"0x...","s":"0x...","u":"0x..."}`
pub fn extract_dao_fields(dao_str: &str) -> Result<String, BridgeError> {
    let bytes = match decode_hex(dao_str) {
        Some(b) if b.len() == 32 => b,
        Some(b) => {
            error!("DAO field must be 32 bytes, got {}", b.len());
            return Err(BridgeError::Internal(format!(
                "DAO field must be 32 bytes, got {}",
                b.len()
            )));
        }
        None => {
            error!("Failed to decode DAO hex");
            return Err(BridgeError::Internal("failed to decode DAO hex".to_owned()));
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
            return Err(BridgeError::Internal(
                "DAO field chunk conversion failed".to_owned(),
            ));
        }
    };
    let c = u64::from_le_bytes(c_buf);
    let ar = u64::from_le_bytes(ar_buf);
    let s = u64::from_le_bytes(s_buf);
    let u = u64::from_le_bytes(u_buf);

    Ok(format!(
        r#"{{"c":"0x{:x}","ar":"0x{:x}","s":"0x{:x}","u":"0x{:x}"}}"#,
        c, ar, s, u
    ))
}

/// Calculate max withdrawable capacity (deposit + compensation), in shannons.
///
/// Formula: `(capacity - occupied) * AR_withdraw / AR_deposit + occupied`
pub fn calculate_max_withdraw(
    deposit_dao_str: &str,
    withdraw_dao_str: &str,
    deposit_capacity: i64,
    occupied_capacity: i64,
) -> Result<i64, BridgeError> {
    let ar_deposit = match parse_ar(deposit_dao_str) {
        Some(ar) => ar as u128,
        None => {
            error!("Failed to parse deposit DAO AR");
            return Err(BridgeError::Internal(
                "failed to parse deposit DAO AR".to_owned(),
            ));
        }
    };

    let ar_withdraw = match parse_ar(withdraw_dao_str) {
        Some(ar) => ar as u128,
        None => {
            error!("Failed to parse withdraw DAO AR");
            return Err(BridgeError::Internal(
                "failed to parse withdraw DAO AR".to_owned(),
            ));
        }
    };

    // Validate inputs
    if deposit_capacity < 0 || occupied_capacity < 0 {
        error!("Negative capacity inputs");
        return Err(BridgeError::Internal("negative capacity inputs".to_owned()));
    }
    if ar_deposit == 0 {
        error!("ar_deposit is zero, cannot divide");
        return Err(BridgeError::Internal(
            "ar_deposit is zero, cannot divide".to_owned(),
        ));
    }

    let capacity = deposit_capacity as u128;
    let occupied = occupied_capacity as u128;

    if occupied > capacity {
        error!("occupied_capacity exceeds deposit_capacity");
        return Err(BridgeError::Internal(
            "occupied_capacity exceeds deposit_capacity".to_owned(),
        ));
    }

    // Formula from RFC-0023
    let counted_capacity = capacity - occupied;
    let max_withdraw = counted_capacity.saturating_mul(ar_withdraw) / ar_deposit + occupied;

    if max_withdraw > i64::MAX as u128 {
        error!("max_withdraw overflows jlong");
        return Err(BridgeError::Internal(
            "max_withdraw overflows a 64-bit signed integer".to_owned(),
        ));
    }

    Ok(max_withdraw as i64)
}

/// Calculate the since value (absolute epoch) for a phase-2 DAO unlock.
///
/// Parse epoch hex -> calc deposited epochs -> round up to the 180-epoch
/// boundary -> encode. Returns a `0x`-prefixed hex since value.
pub fn calculate_unlock_epoch(
    deposit_str: &str,
    withdraw_str: &str,
) -> Result<String, BridgeError> {
    let parse_epoch = |s: &str| -> Option<u64> {
        let stripped = s.strip_prefix("0x").unwrap_or(s);
        u64::from_str_radix(stripped, 16).ok()
    };

    let deposit_epoch_raw = match parse_epoch(deposit_str) {
        Some(e) => e,
        None => {
            error!("Failed to parse deposit epoch hex");
            return Err(BridgeError::Internal(
                "failed to parse deposit epoch hex".to_owned(),
            ));
        }
    };
    let withdraw_epoch_raw = match parse_epoch(withdraw_str) {
        Some(e) => e,
        None => {
            error!("Failed to parse withdraw epoch hex");
            return Err(BridgeError::Internal(
                "failed to parse withdraw epoch hex".to_owned(),
            ));
        }
    };

    let deposit_epoch = EpochNumberWithFraction::from_full_value(deposit_epoch_raw);
    let withdraw_epoch = EpochNumberWithFraction::from_full_value(withdraw_epoch_raw);

    // Validate epoch fractions: length must be > 0 and index < length
    // (length == 0 only for genesis, which can't be a DAO deposit/withdraw epoch)
    if deposit_epoch.length() == 0 || withdraw_epoch.length() == 0 {
        error!(
            "Invalid epoch length: deposit_epoch length={}, withdraw_epoch length={}",
            deposit_epoch.length(),
            withdraw_epoch.length()
        );
        return Err(BridgeError::Internal("invalid epoch length".to_owned()));
    }
    if deposit_epoch.index() >= deposit_epoch.length() {
        error!(
            "Invalid deposit epoch: index {} >= length {}",
            deposit_epoch.index(),
            deposit_epoch.length()
        );
        return Err(BridgeError::Internal("invalid deposit epoch".to_owned()));
    }
    if withdraw_epoch.index() >= withdraw_epoch.length() {
        error!(
            "Invalid withdraw epoch: index {} >= length {}",
            withdraw_epoch.index(),
            withdraw_epoch.length()
        );
        return Err(BridgeError::Internal("invalid withdraw epoch".to_owned()));
    }

    let deposit_number = deposit_epoch.number();
    let withdraw_number = withdraw_epoch.number();

    if withdraw_number < deposit_number {
        error!(
            "Withdraw epoch ({}) is earlier than deposit epoch ({})",
            withdraw_number, deposit_number
        );
        return Err(BridgeError::Internal(
            "withdraw epoch is earlier than deposit epoch".to_owned(),
        ));
    }

    // Every arithmetic step below is checked. The values a real chain produces
    // cannot overflow (epoch indices and lengths are 16-bit fields and epoch
    // numbers are 56-bit), but this runs under UniFFI with no panic guard, so
    // a hostile or corrupt header must not be able to abort the process.
    let overflow = || {
        error!("Epoch arithmetic overflowed");
        BridgeError::Internal("epoch arithmetic overflowed".to_owned())
    };

    // Calculate deposited epochs (withdraw fraction > deposit fraction means +1)
    let withdraw_fraction = withdraw_epoch
        .index()
        .checked_mul(deposit_epoch.length())
        .ok_or_else(overflow)?;
    let deposit_fraction = deposit_epoch
        .index()
        .checked_mul(withdraw_epoch.length())
        .ok_or_else(overflow)?;
    // withdraw_number >= deposit_number was checked above, so this cannot wrap.
    let elapsed_epochs = withdraw_number - deposit_number;
    let deposited_epochs = if withdraw_fraction > deposit_fraction {
        elapsed_epochs.checked_add(1).ok_or_else(overflow)?
    } else {
        elapsed_epochs
    };

    // Round up to next 180-epoch boundary
    let lock_epochs = deposited_epochs
        .checked_add(179)
        .and_then(|rounded| (rounded / 180).checked_mul(180))
        .ok_or_else(overflow)?;
    let minimal_unlock_epoch = deposit_number.checked_add(lock_epochs).ok_or_else(overflow)?;

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

    Ok(format!("0x{:x}", since_value))
}
