//! Platform-neutral query APIs
//!
//! Each function returns the exact JSON string the corresponding JNI export
//! returns, so the platform bridges only have to marshal a `String`.
//!
//! Only the three lifecycle-adjacent queries live here so far; the remaining
//! 14 are still implemented in `jni_bridge::query` and move in a follow-up.

use super::error::BridgeError;
use super::types::*;
use crate::service::{LocalNode, RemoteNode};
use ckb_jsonrpc_types::HeaderView;
use ckb_network::extract_peer_id;
use ckb_types::prelude::IntoHeaderView;
use log::{error, warn};

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
