//! Platform-neutral query APIs
//!
//! Each function returns the exact JSON string the corresponding JNI export
//! returns, so the platform bridges only have to marshal a `String`.

use super::error::BridgeError;
use super::types::*;
use crate::service::{
    Cell, CellType, CellsCapacity, FetchStatus, LocalNode, Pagination, RemoteNode, ScriptStatus,
    ScriptType, SearchKey, SetScriptsCommand, Status, TransactionWithStatus, Tx, TxStatus,
    TxWithCell,
};
use crate::storage::{self, extract_raw_data, Direction, IteratorMode, Key, KeyPrefix};
use crate::verify::verify_tx;
use ckb_jsonrpc_types::{BlockView, HeaderView, JsonBytes, Transaction};
use ckb_network::extract_peer_id;
use ckb_systemtime::unix_time_as_millis;
use ckb_traits::HeaderProvider;
use ckb_types::{
    core, packed,
    prelude::{IntoBlockView, IntoHeaderView, IntoTransactionView, *},
    H256,
};
use log::{debug, error, warn};
use std::str::FromStr;
use std::sync::Arc;

/// Bail out unless the light client is running, matching the JNI `check_running!`
/// behaviour (warn, then report failure to the caller).
fn ensure_running() -> Result<(), BridgeError> {
    if !is_running() {
        warn!("Light client not running, current state: {}", get_state());
        return Err(BridgeError::NotInitialized);
    }
    Ok(())
}

/// Serialize a value to the JSON string the bridges hand back.
fn to_json<T: serde::Serialize>(value: &T) -> Result<String, BridgeError> {
    serde_json::to_string(value).map_err(|e| {
        error!("Failed to serialize to JSON: {}", e);
        BridgeError::Internal(e.to_string())
    })
}

/// Get tip header
pub fn get_tip_header() -> Result<String, BridgeError> {
    ensure_running()?;

    let swc = STORAGE_WITH_DATA.get().ok_or_else(|| {
        error!("Storage not initialized");
        BridgeError::Storage("storage not initialized".to_owned())
    })?;

    let tip_header = swc.storage().get_tip_header();
    let header_view: HeaderView = tip_header.into_view().into();

    to_json(&header_view)
}

/// Get local node info
pub fn local_node_info() -> Result<String, BridgeError> {
    ensure_running()?;

    let net_controller = NET_CONTROL.get().ok_or_else(|| {
        error!("Network controller not initialized");
        BridgeError::Network("network controller not initialized".to_owned())
    })?;

    let _consensus = CONSENSUS.get().ok_or_else(|| {
        error!("Consensus not initialized");
        BridgeError::NotInitialized
    })?;

    let node_id = net_controller.node_id();

    let node_info = LocalNode {
        active: is_running(),
        addresses: vec![], // TODO: get actual addresses
        connections: (net_controller.connected_peers().len() as u64).into(),
        node_id,
        protocols: vec![], // TODO: get actual protocols
        version: env!("CARGO_PKG_VERSION").to_owned(),
    };

    to_json(&node_info)
}

/// Get peers
pub fn get_peers() -> Result<String, BridgeError> {
    ensure_running()?;

    let net_controller = NET_CONTROL.get().ok_or_else(|| {
        error!("Network controller not initialized");
        BridgeError::Network("network controller not initialized".to_owned())
    })?;

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

    to_json(&remote_nodes)
}

/// Genesis block as a JSON `BlockView`.
pub fn get_genesis_block() -> Result<String, BridgeError> {
    ensure_running()?;

    let swc = STORAGE_WITH_DATA.get().ok_or_else(|| {
        error!("Storage not initialized");
        BridgeError::Storage("storage not initialized".to_owned())
    })?;

    let genesis_block = swc.storage().get_genesis_block();

    // Convert packed::Block to BlockView via core::BlockView
    let core_block_view: ckb_types::core::BlockView = genesis_block.into_view();
    let block_view: BlockView = core_block_view.into();

    to_json(&block_view)
}

/// Parse a `0x`-prefixed or bare hash into the packed `Byte32` storage keys use.
fn parse_byte32(context: &str, hash_str: &str) -> Result<packed::Byte32, BridgeError> {
    let hash_hex = hash_str.strip_prefix("0x").unwrap_or(hash_str);
    let h256 = H256::from_str(hash_hex).map_err(|e| {
        error!("{}: invalid hash '{}': {}", context, hash_str, e);
        BridgeError::Internal(format!("invalid hash '{}': {}", hash_str, e))
    })?;

    // H256 is always 32 bytes so the conversion cannot fail in practice;
    // pattern-match anyway to avoid an FFI panic landmine.
    packed::Byte32::from_slice(h256.as_bytes()).map_err(|e| {
        error!("{}: Byte32 conversion failed: {}", context, e);
        BridgeError::Internal(format!("Byte32 conversion failed: {}", e))
    })
}

/// Header for a block hash, as a JSON `HeaderView`.
pub fn get_header(hash_str: &str) -> Result<String, BridgeError> {
    ensure_running()?;

    let swc = STORAGE_WITH_DATA.get().ok_or_else(|| {
        error!("Storage not initialized");
        BridgeError::Storage("storage not initialized".to_owned())
    })?;

    let hash = parse_byte32("get_header", hash_str)?;

    match swc.storage().get_header(&hash) {
        Some(header) => {
            let header_view: HeaderView = header.into();
            to_json(&header_view)
        }
        None => Err(BridgeError::NotFound(format!(
            "header not found for hash '{}'",
            hash_str
        ))),
    }
}

/// Header for a block number, as a JSON `HeaderView`.
///
/// Two-hop lookup (BlockNumber -> BlockHash -> Header), so it only resolves for
/// blocks the light client has actually processed (ones with matched
/// transactions).
pub fn get_header_by_number(number_str: &str) -> Result<String, BridgeError> {
    ensure_running()?;

    // Strip 0x prefix if present and parse as u64
    let num_hex = number_str.strip_prefix("0x").unwrap_or(number_str);
    let radix = if number_str.starts_with("0x") { 16 } else { 10 };
    let block_num: u64 = u64::from_str_radix(num_hex, radix).map_err(|e| {
        warn!(
            "get_header_by_number: invalid block number '{}': {}",
            number_str, e
        );
        BridgeError::Internal(format!("invalid block number '{}': {}", number_str, e))
    })?;

    let swc = STORAGE_WITH_DATA.get().ok_or_else(|| {
        error!("get_header_by_number: storage not initialized");
        BridgeError::Storage("storage not initialized".to_owned())
    })?;

    // Hop 1: BlockNumber -> BlockHash
    let block_hash_bytes = match swc.storage().get(Key::BlockNumber(block_num).into_vec()) {
        Ok(Some(bytes)) => bytes,
        Ok(None) => {
            debug!(
                "get_header_by_number: no block hash for number {}",
                block_num
            );
            return Err(BridgeError::NotFound(format!(
                "no block hash for number {}",
                block_num
            )));
        }
        Err(e) => {
            error!(
                "get_header_by_number: db error for block {}: {}",
                block_num, e
            );
            return Err(BridgeError::Storage(e.to_string()));
        }
    };

    let block_hash = packed::Byte32::from_slice(&block_hash_bytes).map_err(|e| {
        error!(
            "get_header_by_number: malformed block hash for block {}: {}",
            block_num, e
        );
        BridgeError::Storage(format!("malformed block hash for block {}", block_num))
    })?;

    // Hop 2: BlockHash -> Header
    match swc.storage().get_header(&block_hash) {
        Some(header) => {
            let header_view: HeaderView = header.into();
            to_json(&header_view)
        }
        None => {
            warn!(
                "get_header_by_number: block hash found but header missing for number {}",
                block_num
            );
            Err(BridgeError::NotFound(format!(
                "header missing for number {}",
                block_num
            )))
        }
    }
}

/// Fetch a header, as a JSON `FetchStatus<HeaderView>`.
///
/// Returns the header when it is already stored, otherwise reports (and, on
/// the first call, queues) the fetch.
pub fn fetch_header(hash_str: &str) -> Result<String, BridgeError> {
    ensure_running()?;

    let swc = STORAGE_WITH_DATA.get().ok_or_else(|| {
        error!("Storage not initialized");
        BridgeError::Storage("storage not initialized".to_owned())
    })?;

    let peers = PEERS.get().ok_or_else(|| {
        error!("Peers not initialized");
        BridgeError::Network("peers not initialized".to_owned())
    })?;

    let hash = parse_byte32("fetch_header", hash_str)?;

    let fetch_status: FetchStatus<HeaderView> = if let Some(header) = swc.storage().get_header(&hash)
    {
        FetchStatus::Fetched {
            data: header.into(),
        }
    } else if peers.fetching_headers().contains_key(&hash) {
        FetchStatus::Fetching {
            first_sent: 0.into(),
        }
    } else {
        // Add to fetch queue
        let _net_controller = NET_CONTROL.get().ok_or_else(|| {
            error!("Network controller not initialized");
            BridgeError::Network("network controller not initialized".to_owned())
        })?;

        let timestamp = unix_time_as_millis();
        peers.add_fetch_header(hash.clone(), timestamp);

        FetchStatus::Added {
            timestamp: timestamp.into(),
        }
    };

    to_json(&fetch_status)
}

/// Replace, extend or delete the filter scripts the node syncs for.
///
/// `command` is 0 = All (replace), 1 = Partial (merge), 2 = Delete.
pub fn set_scripts(scripts_str: &str, command: i32) -> Result<(), BridgeError> {
    if !is_running() {
        warn!("Light client not running");
        return Err(BridgeError::NotInitialized);
    }

    let scripts_json: Vec<ScriptStatus> = serde_json::from_str(scripts_str).map_err(|e| {
        error!("Failed to parse scripts JSON: {}", e);
        BridgeError::Internal(format!("failed to parse scripts JSON: {}", e))
    })?;

    // Convert service::ScriptStatus to storage::ScriptStatus
    let scripts: Vec<storage::ScriptStatus> = scripts_json
        .into_iter()
        .map(|s| storage::ScriptStatus {
            script: s.script.into(),
            script_type: match s.script_type {
                ScriptType::Lock => storage::ScriptType::Lock,
                ScriptType::Type => storage::ScriptType::Type,
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
            return Err(BridgeError::Internal(format!(
                "invalid set_scripts command: {}",
                command
            )));
        }
    };

    let swc = STORAGE_WITH_DATA.get().ok_or_else(|| {
        error!("Storage not initialized");
        BridgeError::Storage("storage not initialized".to_owned())
    })?;

    swc.storage().update_filter_scripts(scripts, cmd.into());

    // Clear matched blocks when scripts change
    let peers = PEERS.get().ok_or_else(|| {
        error!("Peers not initialized");
        BridgeError::Network("peers not initialized".to_owned())
    })?;

    // Lock matched_blocks and clear them.
    // `blocking_write` panics inside a tokio runtime context, so this must only
    // ever be called from a plain platform thread (the JNI and UniFFI callers).
    let mut matched_blocks = peers.matched_blocks().blocking_write();
    peers.clear_matched_blocks(&mut matched_blocks);

    Ok(())
}

/// Filter scripts currently synced for, as a JSON array of `ScriptStatus`.
pub fn get_scripts() -> Result<String, BridgeError> {
    ensure_running()?;

    let swc = STORAGE_WITH_DATA.get().ok_or_else(|| {
        error!("Storage not initialized");
        BridgeError::Storage("storage not initialized".to_owned())
    })?;

    let scripts = swc.storage().get_filter_scripts();
    // Convert storage::ScriptStatus to service::ScriptStatus for serialization
    let scripts: Vec<ScriptStatus> = scripts
        .into_iter()
        .map(|s| ScriptStatus {
            script: s.script.into(),
            script_type: match s.script_type {
                storage::ScriptType::Lock => ScriptType::Lock,
                storage::ScriptType::Type => ScriptType::Type,
            },
            block_number: s.block_number.into(),
        })
        .collect();

    to_json(&scripts)
}

/// Resolve the iteration direction the JNI/Swift callers pass as `"asc"`/`"desc"`.
fn direction_of(order: &str) -> Direction {
    if order == "asc" {
        Direction::Forward
    } else {
        Direction::Reverse
    }
}

/// Build the storage key prefix a search key scans under.
fn search_prefix(search_key: &SearchKey, lock_prefix: KeyPrefix, type_prefix: KeyPrefix) -> Vec<u8> {
    let mut prefix = match search_key.script_type {
        ScriptType::Lock => vec![lock_prefix as u8],
        ScriptType::Type => vec![type_prefix as u8],
    };
    let script: packed::Script = search_key.script.clone().into();
    prefix.extend_from_slice(extract_raw_data(&script).as_slice());
    prefix
}

/// Starting key and skip count for a paginated scan.
fn from_key_for(prefix: &[u8], direction: Direction, cursor_str: &str) -> (Vec<u8>, usize) {
    if cursor_str.is_empty() {
        if matches!(direction, Direction::Forward) {
            (prefix.to_vec(), 0)
        } else {
            let mut key = prefix.to_vec();
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
            Err(_) => (prefix.to_vec(), 0),
        }
    }
}

/// Parse a `SearchKey` JSON argument.
fn parse_search_key(search_key_str: &str) -> Result<SearchKey, BridgeError> {
    serde_json::from_str(search_key_str).map_err(|e| {
        error!("Failed to parse search_key JSON: {}", e);
        BridgeError::Internal(format!("failed to parse search_key JSON: {}", e))
    })
}

/// Decode the trailing fields of a cell index key: block number, tx index and
/// output index.
///
/// Key layout: `prefix + script_data + block_number(8) + tx_index(4) + output_index(4)`.
/// `None` means the key is too short to carry them — a corrupt index rather
/// than a missing cell. Bounds-checked because this runs under UniFFI too,
/// where there is no `guard_jni` to turn an out-of-range index into a null.
fn decode_cell_key(key: &[u8]) -> Option<(u64, u32, u32)> {
    let len = key.len();
    // checked_sub first: it short-circuits before the other offsets are formed.
    let block_number = u64::from_be_bytes(key.get(len.checked_sub(16)?..len - 8)?.try_into().ok()?);
    let tx_index = u32::from_be_bytes(key.get(len - 8..len - 4)?.try_into().ok()?);
    let output_index = u32::from_be_bytes(key.get(len - 4..)?.try_into().ok()?);
    Some((block_number, tx_index, output_index))
}

/// Decode just the output index off the end of a cell index key.
///
/// `get_cells_capacity` needs nothing else, so it accepts the same keys it
/// always did rather than requiring the full 16-byte tail.
fn decode_cell_output_index(key: &[u8]) -> Option<u32> {
    let len = key.len();
    Some(u32::from_be_bytes(
        key.get(len.checked_sub(4)?..)?.try_into().ok()?,
    ))
}

/// Decode the trailing fields of a transaction index key: block number, tx
/// index, io index and the io-type flag.
///
/// Key layout: `... + block_number(8) + tx_index(4) + io_index(4) + io_type(1)`.
/// `None` means a corrupt index, as for [`decode_cell_key`].
fn decode_tx_key(key: &[u8]) -> Option<(u64, u32, u32, u8)> {
    let len = key.len();
    let block_number = u64::from_be_bytes(key.get(len.checked_sub(17)?..len - 9)?.try_into().ok()?);
    let tx_index = u32::from_be_bytes(key.get(len - 9..len - 5)?.try_into().ok()?);
    let io_index = u32::from_be_bytes(key.get(len - 5..len - 1)?.try_into().ok()?);
    let io_type = *key.last()?;
    Some((block_number, tx_index, io_index, io_type))
}

/// Strip the block number (8) and tx index (4) a stored `Value::Transaction`
/// carries in front of the molecule-encoded transaction.
///
/// `None` means the stored value is shorter than that prefix, i.e. corrupt.
fn stored_tx_body(value: &[u8]) -> Option<&[u8]> {
    value.get(12..)
}

/// Report a too-short index key.
fn corrupt_key(context: &str, len: usize) -> BridgeError {
    error!("{}: index key too short to decode: {} bytes", context, len);
    BridgeError::Storage(format!("corrupt index key ({} bytes)", len))
}

/// Report a too-short stored transaction value.
fn corrupt_tx_value(context: &str, len: usize) -> BridgeError {
    error!(
        "{}: stored transaction value too short: {} bytes",
        context, len
    );
    BridgeError::Storage(format!("corrupt stored transaction value ({} bytes)", len))
}

/// Live cells matching a search key, as a JSON `Pagination<Cell>`.
pub fn get_cells(
    search_key_str: &str,
    order: &str,
    limit: i32,
    cursor_str: &str,
) -> Result<String, BridgeError> {
    ensure_running()?;

    let search_key = parse_search_key(search_key_str)?;
    let direction = direction_of(order);

    let swc = STORAGE_WITH_DATA.get().ok_or_else(|| {
        error!("Storage not initialized");
        BridgeError::Storage("storage not initialized".to_owned())
    })?;

    // Build prefix based on script type (use Cell prefix, not Tx prefix)
    let prefix = search_prefix(
        &search_key,
        KeyPrefix::CellLockScript,
        KeyPrefix::CellTypeScript,
    );

    // Determine from_key based on cursor
    let (from_key, skip) = from_key_for(&prefix, direction, cursor_str);

    let mode = IteratorMode::From(&from_key, direction);
    let items = swc
        .storage()
        .iterator_collect(mode, |(key, _)| key.starts_with(&prefix));

    // A row that cannot be decoded is skipped, exactly as before; only a
    // structurally corrupt key or stored value (which used to panic here)
    // fails the whole query.
    let limit = limit.max(0) as usize;
    let mut last_key = Vec::new();
    let mut cells: Vec<Cell> = Vec::new();

    for (key, value) in items.into_iter().skip(skip) {
        if cells.len() >= limit {
            break;
        }

        let (block_number, tx_index, output_index) =
            decode_cell_key(&key).ok_or_else(|| corrupt_key("get_cells", key.len()))?;

        let Ok(tx_hash) = packed::Byte32::from_slice(&value) else {
            continue;
        };

        // Get the transaction to extract output details
        let Ok(Some(tx_data)) = swc.storage().get(Key::TxHash(&tx_hash).into_vec()) else {
            continue;
        };
        let tx_body = stored_tx_body(&tx_data)
            .ok_or_else(|| corrupt_tx_value("get_cells", tx_data.len()))?;
        let Ok(tx) = packed::Transaction::from_slice(tx_body) else {
            continue;
        };

        let Some(output) = tx.raw().outputs().get(output_index as usize) else {
            continue;
        };
        let output_data = tx.raw().outputs_data().get(output_index as usize);

        last_key = key.to_vec();

        cells.push(Cell {
            output: output.into(),
            output_data: output_data.map(|d| JsonBytes::from_bytes(d.raw_data())),
            out_point: ckb_jsonrpc_types::OutPoint {
                tx_hash: tx_hash.unpack(),
                index: output_index.into(),
            },
            block_number: block_number.into(),
            tx_index: tx_index.into(),
        });
    }

    let result = Pagination {
        objects: cells,
        last_cursor: JsonBytes::from_vec(last_key),
    };

    to_json(&result)
}

/// Transactions matching a search key, as a JSON `Pagination<Tx>`.
pub fn get_transactions(
    search_key_str: &str,
    order: &str,
    limit: i32,
    cursor_str: &str,
) -> Result<String, BridgeError> {
    ensure_running()?;

    let search_key = parse_search_key(search_key_str)?;
    let direction = direction_of(order);

    let swc = STORAGE_WITH_DATA.get().ok_or_else(|| {
        error!("Storage not initialized");
        BridgeError::Storage("storage not initialized".to_owned())
    })?;

    // Build prefix based on script type
    let prefix = search_prefix(&search_key, KeyPrefix::TxLockScript, KeyPrefix::TxTypeScript);

    // Determine from_key
    let (from_key, skip) = from_key_for(&prefix, direction, cursor_str);

    let mode = IteratorMode::From(&from_key, direction);
    let items = swc
        .storage()
        .iterator_collect(mode, |(key, _)| key.starts_with(&prefix));
    // As in `get_cells`: an undecodable row is skipped, a structurally corrupt
    // key or stored value fails the query instead of panicking.
    let limit = limit.max(0) as usize;
    let mut last_key = Vec::new();
    let mut txs: Vec<Tx> = Vec::new();

    for (key, value) in items.into_iter().skip(skip) {
        if txs.len() >= limit {
            break;
        }

        let (block_number, tx_index, io_index, io_type_flag) =
            decode_tx_key(&key).ok_or_else(|| corrupt_key("get_transactions", key.len()))?;
        let io_type = if io_type_flag == 0 {
            CellType::Input
        } else {
            CellType::Output
        };

        let Ok(tx_hash) = packed::Byte32::from_slice(&value) else {
            continue;
        };
        let Ok(Some(tx_data)) = swc.storage().get(Key::TxHash(&tx_hash).into_vec()) else {
            continue;
        };
        let tx_body = stored_tx_body(&tx_data)
            .ok_or_else(|| corrupt_tx_value("get_transactions", tx_data.len()))?;
        let Ok(tx) = packed::Transaction::from_slice(tx_body) else {
            continue;
        };

        // Wrapped in a closure so the `?`-on-Option skip paths below (including
        // the documented prev-tx ones) keep dropping just this row.
        let io_capacity = (|| -> Option<u64> {
            Some(if io_type == CellType::Input {
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
                            "get_transactions: indexed input references prev tx {:#x} \
                             not in storage (unexpected: DB corruption or mid-read reorg, since \
                             filter_block only indexes inputs whose prev tx it stored); \
                             input {}:{} of tx {:#x} dropped",
                            prev_hash, prev_index, io_index, cur_hash
                        );
                        return None;
                    }
                    Err(e) => {
                        error!(
                            "get_transactions: storage error resolving input capacity \
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
                    .map(|output: packed::CellOutput| {
                        Unpack::<core::Capacity>::unpack(&output.capacity()).as_u64()
                    })
                    .unwrap_or_else(|| {
                        warn!(
                            "get_transactions: malformed prev tx {:#x} for input {}:{} \
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
                    .map(|output: packed::CellOutput| {
                        Unpack::<core::Capacity>::unpack(&output.capacity()).as_u64()
                    })
                    .unwrap_or_else(|| {
                        warn!(
                            "get_transactions: output io_index {} out of range for tx {:#x}; \
                             capacity reported as 0",
                            io_index, cur_hash
                        );
                        0
                    })
            })
        })();
        let Some(io_capacity) = io_capacity else {
            continue;
        };

        last_key = key.to_vec();
        txs.push(Tx::Ungrouped(TxWithCell {
            transaction: tx.into_view().into(),
            block_number: block_number.into(),
            tx_index: tx_index.into(),
            io_index: io_index.into(),
            io_type,
            io_capacity: io_capacity.into(),
        }));
    }

    let result = Pagination {
        objects: txs,
        last_cursor: JsonBytes::from_vec(last_key),
    };

    to_json(&result)
}

/// Total capacity of the cells matching a search key, as a JSON `CellsCapacity`.
pub fn get_cells_capacity(search_key_str: &str) -> Result<String, BridgeError> {
    ensure_running()?;

    let search_key = parse_search_key(search_key_str)?;

    let swc = STORAGE_WITH_DATA.get().ok_or_else(|| {
        error!("Storage not initialized");
        BridgeError::Storage("storage not initialized".to_owned())
    })?;

    // Build prefix based on script type
    let prefix = search_prefix(
        &search_key,
        KeyPrefix::CellLockScript,
        KeyPrefix::CellTypeScript,
    );

    // Iterate over cells and sum capacity
    let mode = IteratorMode::From(prefix.as_ref(), Direction::Forward);
    let items = swc
        .storage()
        .iterator_collect(mode, |(key, _)| key.starts_with(&prefix));

    // Same split as `get_cells`: skip an undecodable row, fail on a corrupt
    // key or stored value. `saturating_add` replaces the `sum()` that would
    // have panicked in a debug build on an overflow the chain cannot produce.
    let mut capacity: u64 = 0;
    for (key, value) in items {
        let output_index = decode_cell_output_index(&key)
            .ok_or_else(|| corrupt_key("get_cells_capacity", key.len()))?;

        let Ok(tx_hash) = packed::Byte32::from_slice(&value) else {
            continue;
        };
        let Ok(Some(tx_data)) = swc.storage().get(Key::TxHash(&tx_hash).into_vec()) else {
            continue;
        };
        let tx_body = stored_tx_body(&tx_data)
            .ok_or_else(|| corrupt_tx_value("get_cells_capacity", tx_data.len()))?;
        let Ok(tx) = packed::Transaction::from_slice(tx_body) else {
            continue;
        };
        let Some(output) = tx.raw().outputs().get(output_index as usize) else {
            continue;
        };

        capacity =
            capacity.saturating_add(Unpack::<core::Capacity>::unpack(&output.capacity()).as_u64());
    }

    // Get tip header for block info
    let tip_header = swc.storage().get_tip_header();
    let tip_view = tip_header.into_view();

    let result = CellsCapacity {
        capacity: capacity.into(),
        block_hash: tip_view.hash().unpack(),
        block_number: tip_view.number().into(),
    };

    to_json(&result)
}

/// Verify a transaction and add it to the pending pool.
///
/// Returns the transaction hash as a JSON string on success. Failures carry
/// the real reason (malformed JSON, verification failure, uninitialized
/// globals) so callers can surface it instead of a generic network message.
pub fn send_transaction(tx_str: &str) -> Result<String, BridgeError> {
    ensure_running()?;

    let tx: Transaction = serde_json::from_str(tx_str).map_err(|e| {
        error!("Failed to parse transaction JSON: {}", e);
        BridgeError::Internal(format!("malformed transaction: {}", e))
    })?;

    let swc = STORAGE_WITH_DATA.get().ok_or_else(|| {
        error!("Storage not initialized");
        BridgeError::Storage("light client not ready (storage not initialized)".to_owned())
    })?;

    // Consensus and storage are published together at the tail of
    // `lifecycle::init`, so reaching this with storage present means the client
    // is not actually initialized rather than that sending failed.
    let consensus = CONSENSUS.get().map(Arc::clone).ok_or_else(|| {
        error!("Consensus not initialized");
        BridgeError::NotInitialized
    })?;

    // Convert to packed transaction and view
    let packed_tx: packed::Transaction = tx.into();
    let tx_view = packed_tx.into_view();

    // Verify the transaction
    let last_state = swc.storage().get_last_state().1.into_view();
    let cycles = verify_tx(tx_view.clone(), swc, consensus, &last_state).map_err(|e| {
        // Return the real reason (e.g. Unknown(OutPoint) for an
        // unresolvable input, Dead, capacity, or a script error) so the
        // caller can show it instead of a misleading network message.
        error!("Transaction verification failed: {:?}", e);
        BridgeError::Internal(format!("verification failed: {:?}", e))
    })?;

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
    to_json(&tx_hash)
}

/// Transaction and its status, as a JSON `TransactionWithStatus`.
///
/// Reports `Unknown` (rather than failing) for a hash that is neither stored
/// nor pending.
pub fn get_transaction(hash_str: &str) -> Result<String, BridgeError> {
    ensure_running()?;

    // Strip 0x prefix if present — callers often pass "0xabc..." but
    // H256::from_str expects no prefix
    let byte32 = parse_byte32("get_transaction", hash_str)?;

    let swc = STORAGE_WITH_DATA.get().ok_or_else(|| {
        error!("get_transaction: storage not initialized");
        BridgeError::Storage("storage not initialized".to_owned())
    })?;

    // Recover from poisoning rather than double-panic across the FFI boundary.
    let pending_read = match swc.pending_txs().read() {
        Ok(g) => g,
        Err(poisoned) => {
            error!("get_transaction: pending_txs read lock poisoned; recovering");
            poisoned.into_inner()
        }
    };

    let result = if let Some((transaction, header)) =
        swc.storage().get_transaction_with_header(&byte32)
    {
        debug!("get_transaction: found committed tx {}", hash_str);
        TransactionWithStatus {
            transaction: Some(transaction.into_view().into()),
            cycles: None,
            tx_status: TxStatus {
                block_hash: Some(header.into_view().hash().unpack()),
                status: Status::Committed,
            },
        }
    } else if let Some((transaction, cycles, _)) = pending_read.get(&byte32) {
        debug!("get_transaction: found pending tx {}", hash_str);
        TransactionWithStatus {
            transaction: Some(transaction.into_view().into()),
            cycles: Some(cycles.into()),
            tx_status: TxStatus {
                block_hash: None,
                status: Status::Pending,
            },
        }
    } else {
        warn!(
            "get_transaction: tx not found in storage or pending: {}",
            hash_str
        );
        TransactionWithStatus {
            transaction: None,
            cycles: None,
            tx_status: TxStatus {
                block_hash: None,
                status: Status::Unknown,
            },
        }
    };

    to_json(&result)
}

/// Fetch status for a transaction, as a JSON `FetchStatus<TransactionWithStatus>`.
///
/// Returns the transaction when it is stored or pending, otherwise reports
/// (and, on the first call, queues) the fetch.
pub fn fetch_transaction(hash_str: &str) -> Result<String, BridgeError> {
    ensure_running()?;

    let byte32 = parse_byte32("fetch_transaction", hash_str)?;
    let tx_hash: H256 = byte32.unpack();

    let swc = STORAGE_WITH_DATA.get().ok_or_else(|| {
        error!("fetch_transaction: storage not initialized");
        BridgeError::Storage("storage not initialized".to_owned())
    })?;

    // 1. Check if tx is already in local storage (committed)
    if let Some((transaction, header)) = swc.storage().get_transaction_with_header(&byte32) {
        debug!("fetch_transaction: tx {} already in storage", hash_str);
        let tws = TransactionWithStatus {
            transaction: Some(transaction.into_view().into()),
            cycles: None,
            tx_status: TxStatus {
                block_hash: Some(header.into_view().hash().unpack()),
                status: Status::Committed,
            },
        };
        let fetch_status: FetchStatus<TransactionWithStatus> = FetchStatus::Fetched { data: tws };
        return to_json(&fetch_status);
    }

    // 2. Check if tx is in pending pool. Recover from poisoning to avoid
    // double-panic across the FFI boundary.
    let pending_read = match swc.pending_txs().read() {
        Ok(g) => g,
        Err(poisoned) => {
            error!("fetch_transaction: pending_txs read lock poisoned; recovering");
            poisoned.into_inner()
        }
    };
    if let Some((transaction, cycles, _)) = pending_read.get(&byte32) {
        debug!("fetch_transaction: tx {} is pending", hash_str);
        let tws = TransactionWithStatus {
            transaction: Some(transaction.into_view().into()),
            cycles: Some(cycles.into()),
            tx_status: TxStatus {
                block_hash: None,
                status: Status::Pending,
            },
        };
        let fetch_status: FetchStatus<TransactionWithStatus> = FetchStatus::Fetched { data: tws };
        return to_json(&fetch_status);
    }

    // 3. Check fetch queue status or add to fetch queue
    // Verify network controller is available before queuing fetches
    let _net_controller = NET_CONTROL.get().ok_or_else(|| {
        error!("fetch_transaction: network controller not initialized");
        BridgeError::Network("network controller not initialized".to_owned())
    })?;

    let now = unix_time_as_millis();
    let fetch_status: FetchStatus<TransactionWithStatus> =
        if let Some((added_ts, first_sent, missing)) = swc.get_tx_fetch_info(&tx_hash) {
            if missing {
                // Previously missing — re-add to fetch queue for retry.
                // Return NotFound (not Added) to mirror WASM/RPC behavior (rpc.rs:876-879):
                // the caller learns the tx was not found on the previous attempt.
                // On subsequent polls the status will progress to Added → Fetching → Fetched.
                debug!(
                    "fetch_transaction: tx {} was missing, re-adding to fetch queue",
                    hash_str
                );
                swc.add_fetch_tx(tx_hash, now);
                FetchStatus::NotFound
            } else if first_sent > 0 {
                debug!("fetch_transaction: tx {} is being fetched", hash_str);
                FetchStatus::Fetching {
                    first_sent: first_sent.into(),
                }
            } else {
                debug!("fetch_transaction: tx {} is queued for fetch", hash_str);
                FetchStatus::Added {
                    timestamp: added_ts.into(),
                }
            }
        } else {
            // Not in fetch queue — add it
            debug!("fetch_transaction: adding tx {} to fetch queue", hash_str);
            swc.add_fetch_tx(tx_hash, now);
            FetchStatus::Added {
                timestamp: now.into(),
            }
        };

    to_json(&fetch_status)
}

/// Estimate the cycles a transaction consumes.
///
/// Not implemented yet; always reports failure (the Android bridge has
/// returned null here since the JNI bridge was written).
pub fn estimate_cycles(_tx_str: &str) -> Result<String, BridgeError> {
    warn!("estimate_cycles not yet implemented");
    Err(BridgeError::Internal(
        "estimate_cycles is not implemented".to_owned(),
    ))
}
