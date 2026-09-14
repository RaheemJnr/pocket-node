//! Platform-neutral JSON-RPC passthrough.
//!
//! A generic dispatcher over a handful of read-only node methods, returning a
//! JSON-RPC 2.0 response string. Unknown methods and an uninitialized node are
//! reported *inside* that response (as a JSON-RPC `error` object), not as a
//! transport failure — only serializing the response can fail outright.

use super::error::BridgeError;
use super::types::*;
use crate::service::ScriptStatus;
use ckb_jsonrpc_types::{BlockView, HeaderView};
use ckb_network::extract_peer_id;
use ckb_types::prelude::{IntoBlockView, IntoHeaderView};
use log::{error, warn};
use serde_json::json;

/// Serialize a JSON-RPC 2.0 success response.
fn rpc_response<T: serde::Serialize>(result: T) -> Result<String, BridgeError> {
    let response = json!({
        "jsonrpc": "2.0",
        "id": 1,
        "result": result
    });
    serde_json::to_string(&response).map_err(|e| {
        error!("Failed to serialize response: {}", e);
        BridgeError::Internal(e.to_string())
    })
}

/// Serialize a JSON-RPC 2.0 error response.
///
/// Public because the platform bridges need it for failures that happen before
/// `call_rpc` is reached (reading the method name off the platform string type).
pub fn rpc_error_response(code: i64, message: &str) -> Result<String, BridgeError> {
    let response = json!({
        "jsonrpc": "2.0",
        "id": 1,
        "error": {
            "code": code,
            "message": message
        }
    });
    serde_json::to_string(&response).map_err(|e| {
        error!("Failed to serialize error response: {}", e);
        BridgeError::Internal(e.to_string())
    })
}

/// Dispatch a read-only RPC method by name.
///
/// Handles `get_peers`, `get_tip_header`, `get_genesis_block` and
/// `get_scripts`; anything else comes back as a JSON-RPC "unknown method"
/// error response.
pub fn call_rpc(method: &str) -> Result<String, BridgeError> {
    // Get storage
    let swc = match STORAGE_WITH_DATA.get() {
        Some(s) => s,
        None => {
            warn!("Storage not initialized for method: {}", method);
            return rpc_error_response(-32603, "Light client not initialized");
        }
    };

    // Handle different RPC methods
    match method {
        "get_peers" => {
            // Get network controller
            let net_ctrl = match NET_CONTROL.get() {
                Some(nc) => nc,
                None => {
                    return rpc_error_response(-32603, "Network controller not initialized");
                }
            };

            // Get connected peers
            let peers = net_ctrl
                .connected_peers()
                .iter()
                .map(|(peer_index, peer)| {
                    let mut addresses = vec![&peer.connected_addr];
                    addresses.extend(peer.listened_addrs.iter());

                    let node_addresses: Vec<_> = addresses
                        .iter()
                        .map(|addr| {
                            let score = net_ctrl
                                .addr_info(addr)
                                .map(|addr_info| addr_info.score)
                                .unwrap_or(1);
                            let non_negative_score = if score > 0 { score as u64 } else { 0 };
                            json!({
                                "address": addr.to_string(),
                                "score": format!("0x{:x}", non_negative_score)
                            })
                        })
                        .collect();

                    // Get sync state from PEERS
                    let sync_state = PEERS.get().and_then(|peers_mgr| {
                        peers_mgr.get_state(peer_index).map(|state| {
                            json!({
                                "requested_best_known_header": state.get_prove_request().map(|req| {
                                    let header: HeaderView = req.get_last_header().header().to_owned().into();
                                    header
                                }),
                                "proved_best_known_header": state.get_prove_state().map(|req| {
                                    let header: HeaderView = req.get_last_header().header().to_owned().into();
                                    header
                                })
                            })
                        })
                    });

                    json!({
                        "version": peer.identify_info.as_ref()
                            .map(|info| info.client_version.clone())
                            .unwrap_or_else(|| "unknown".to_string()),
                        "node_id": extract_peer_id(&peer.connected_addr)
                            .map(|peer_id| peer_id.to_base58())
                            .unwrap_or_default(),
                        "addresses": node_addresses,
                        "connected_duration": format!("0x{:x}",
                            std::time::Instant::now()
                                .saturating_duration_since(peer.connected_time)
                                .as_millis() as u64
                        ),
                        "sync_state": sync_state,
                        "protocols": peer.protocols.iter().map(|(protocol_id, protocol_version)| {
                            json!({
                                "id": format!("0x{:x}", protocol_id.value() as u64),
                                "version": protocol_version
                            })
                        }).collect::<Vec<_>>()
                    })
                })
                .collect::<Vec<_>>();

            rpc_response(peers)
        }

        "get_tip_header" => {
            let tip_header = swc.storage().get_tip_header();
            let header_view: HeaderView = tip_header.into_view().into();
            rpc_response(header_view)
        }

        "get_genesis_block" => {
            let genesis_block = swc.storage().get_genesis_block();
            let block_view: BlockView = genesis_block.into_view().into();
            rpc_response(block_view)
        }

        "get_scripts" => {
            let scripts = swc.storage().get_filter_scripts();
            let script_statuses: Vec<ScriptStatus> =
                scripts.into_iter().map(|s| s.into()).collect();
            rpc_response(script_statuses)
        }

        _ => {
            let error_msg = format!("Unknown method: {}", method);
            warn!("{}", error_msg);
            rpc_error_response(-32601, &error_msg)
        }
    }
}
