//! UniFFI bridge for iOS
//!
//! Thin marshalling layer over [`crate::bridge_core`], mirroring what
//! `jni_bridge` does for Android. Everything here is generated into Swift by
//! `uniffi-bindgen` (proc-macro mode, no UDL); see `build-ios.sh`.

use crate::bridge_core::lifecycle;
use crate::bridge_core::query;
use crate::bridge_core::types::{self, notify_status, STATE_INIT};
use crate::bridge_core::BridgeError;

/// Receives light-client state changes (0 = INIT, 1 = RUNNING, 2 = STOPPED).
#[uniffi::export(callback_interface)]
pub trait StatusListener: Send + Sync {
    fn on_status(&self, status: u8);
}

/// Swift-facing mirror of [`BridgeError`].
#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum LightClientError {
    #[error("light client is not initialized")]
    NotInitialized,
    #[error("light client is already initialized")]
    AlreadyInitialized,
    #[error("config error: {reason}")]
    Config { reason: String },
    #[error("storage error: {reason}")]
    Storage { reason: String },
    #[error("network error: {reason}")]
    Network { reason: String },
    #[error("internal error: {reason}")]
    Internal { reason: String },
}

impl From<BridgeError> for LightClientError {
    fn from(err: BridgeError) -> Self {
        match err {
            BridgeError::NotInitialized => Self::NotInitialized,
            BridgeError::AlreadyInitialized => Self::AlreadyInitialized,
            BridgeError::Config(reason) => Self::Config { reason },
            BridgeError::Storage(reason) => Self::Storage { reason },
            BridgeError::Network(reason) => Self::Network { reason },
            BridgeError::Internal(reason) => Self::Internal { reason },
        }
    }
}

/// Initialize the light client.
///
/// `config_path` is the absolute path of the TOML config. `data_dir`, when
/// non-empty, overrides the store and network paths from that config with
/// `<data_dir>/store.db` and `<data_dir>/network` — iOS containers move between
/// installs, so the paths cannot be baked into the bundled TOML.
///
/// Blocking: this reads the config from disk, opens the store and starts the
/// network service, so it can take seconds. Do not call it on the main thread.
#[uniffi::export]
pub fn init_light_client(
    config_path: String,
    data_dir: String,
    listener: Option<Box<dyn StatusListener>>,
) -> Result<(), LightClientError> {
    let core_listener = listener.map(|listener| {
        let boxed: Box<dyn Fn(u8) + Send + Sync> =
            Box::new(move |status| listener.on_status(status));
        boxed
    });

    lifecycle::init(&config_path, &data_dir, core_listener)?;

    // The listener is only registered once every fallible step succeeded, so
    // the first notification is emitted here rather than inside init.
    notify_status(STATE_INIT);

    Ok(())
}

/// Transition from INIT to RUNNING.
///
/// Blocking: call it off the main thread along with the rest of the lifecycle
/// API. It is cheap today, but it is part of the same blocking surface and is
/// not guaranteed to stay that way.
#[uniffi::export]
pub fn start_light_client() -> Result<(), LightClientError> {
    lifecycle::start().map_err(Into::into)
}

/// Gracefully shut the light client down.
///
/// Blocking: broadcasts exit signals and then waits for every CKB service to
/// exit, which can take a while when peers are connected. Do not call it on
/// the main thread.
#[uniffi::export]
pub fn stop_light_client() -> Result<(), LightClientError> {
    lifecycle::stop().map_err(Into::into)
}

/// Current state (0 = INIT, 1 = RUNNING, 2 = STOPPED).
///
/// Note that 0 is reported both before and after a successful init, since
/// INIT is the resting state of a client that has not been started. Use
/// [`is_initialized`] to tell those two apart.
#[uniffi::export]
pub fn get_status() -> u8 {
    lifecycle::status()
}

/// Whether [`init_light_client`] has completed successfully.
///
/// Disambiguates the two meanings of a `get_status()` of 0: not initialized
/// yet, versus initialized and waiting for [`start_light_client`].
#[uniffi::export]
pub fn is_initialized() -> bool {
    types::is_initialized()
}

/// Tip header as a JSON string.
#[uniffi::export]
pub fn get_tip_header() -> Result<String, LightClientError> {
    query::get_tip_header().map_err(Into::into)
}

/// Local node info as a JSON string.
#[uniffi::export]
pub fn local_node_info() -> Result<String, LightClientError> {
    query::local_node_info().map_err(Into::into)
}

/// Connected peers as a JSON string.
#[uniffi::export]
pub fn get_peers() -> Result<String, LightClientError> {
    query::get_peers().map_err(Into::into)
}

/// Genesis block as a JSON string.
#[uniffi::export]
pub fn get_genesis_block() -> Result<String, LightClientError> {
    query::get_genesis_block().map_err(Into::into)
}

/// Header for a block hash (`0x`-prefixed or bare) as a JSON string.
#[uniffi::export]
pub fn get_header(hash: String) -> Result<String, LightClientError> {
    query::get_header(&hash).map_err(Into::into)
}

/// Header for a block number (decimal, or `0x`-prefixed hex) as a JSON string.
#[uniffi::export]
pub fn get_header_by_number(block_number: String) -> Result<String, LightClientError> {
    query::get_header_by_number(&block_number).map_err(Into::into)
}

/// Fetch status for a header, as a JSON string; queues the fetch when unknown.
#[uniffi::export]
pub fn fetch_header(hash: String) -> Result<String, LightClientError> {
    query::fetch_header(&hash).map_err(Into::into)
}

/// Set the filter scripts to sync (command 0 = all, 1 = partial, 2 = delete).
#[uniffi::export]
pub fn set_scripts(scripts_json: String, command: i32) -> Result<(), LightClientError> {
    query::set_scripts(&scripts_json, command).map_err(Into::into)
}

/// Filter scripts currently synced for, as a JSON string.
#[uniffi::export]
pub fn get_scripts() -> Result<String, LightClientError> {
    query::get_scripts().map_err(Into::into)
}

/// Live cells matching a JSON search key, as a JSON page (`order`: "asc"/"desc").
#[uniffi::export]
pub fn get_cells(
    search_key_json: String,
    order: String,
    limit: i32,
    cursor: String,
) -> Result<String, LightClientError> {
    query::get_cells(&search_key_json, &order, limit, &cursor).map_err(Into::into)
}

/// Transactions matching a JSON search key, as a JSON page (`order`: "asc"/"desc").
#[uniffi::export]
pub fn get_transactions(
    search_key_json: String,
    order: String,
    limit: i32,
    cursor: String,
) -> Result<String, LightClientError> {
    query::get_transactions(&search_key_json, &order, limit, &cursor).map_err(Into::into)
}

/// Total capacity of the cells matching a JSON search key, as a JSON string.
#[uniffi::export]
pub fn get_cells_capacity(search_key_json: String) -> Result<String, LightClientError> {
    query::get_cells_capacity(&search_key_json).map_err(Into::into)
}

/// Verify a JSON transaction and queue it for broadcast; returns its hash as JSON.
#[uniffi::export]
pub fn send_transaction(tx_json: String) -> Result<String, LightClientError> {
    query::send_transaction(&tx_json).map_err(Into::into)
}

/// Transaction and its status for a hash, as a JSON string.
#[uniffi::export]
pub fn get_transaction(hash: String) -> Result<String, LightClientError> {
    query::get_transaction(&hash).map_err(Into::into)
}

/// Fetch status for a transaction, as a JSON string; queues the fetch when unknown.
#[uniffi::export]
pub fn fetch_transaction(hash: String) -> Result<String, LightClientError> {
    query::fetch_transaction(&hash).map_err(Into::into)
}

/// Estimate the cycles a JSON transaction consumes. Not implemented yet: always throws.
#[uniffi::export]
pub fn estimate_cycles(tx_json: String) -> Result<String, LightClientError> {
    query::estimate_cycles(&tx_json).map_err(Into::into)
}
