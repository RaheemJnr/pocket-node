//! Platform-neutral query APIs
//!
//! Each function returns the exact JSON string the corresponding JNI export
//! returns, so the platform bridges only have to marshal a `String`.

use super::error::BridgeError;
use super::types::*;
use crate::service::{FetchStatus, LocalNode, RemoteNode, ScriptStatus, ScriptType, SetScriptsCommand};
use crate::storage::{self, Key};
use ckb_jsonrpc_types::{BlockView, HeaderView};
use ckb_network::extract_peer_id;
use ckb_systemtime::unix_time_as_millis;
use ckb_traits::HeaderProvider;
use ckb_types::{
    packed,
    prelude::{IntoBlockView, IntoHeaderView, *},
    H256,
};
use log::{debug, error, warn};
use std::str::FromStr;

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
        None => Err(BridgeError::Internal(format!(
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
            return Err(BridgeError::Internal(format!(
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
            Err(BridgeError::Internal(format!(
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

    // Lock matched_blocks and clear them
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
