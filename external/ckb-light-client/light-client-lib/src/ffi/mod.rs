//! UniFFI bridge for iOS
//!
//! Thin marshalling layer over [`crate::bridge_core`], mirroring what
//! `jni_bridge` does for Android. Everything here is generated into Swift by
//! `uniffi-bindgen` (proc-macro mode, no UDL); see `build-ios.sh`.

use crate::bridge_core::lifecycle;
use crate::bridge_core::query;
use crate::bridge_core::types::{notify_status, STATE_INIT};
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
#[uniffi::export]
pub fn start_light_client() -> Result<(), LightClientError> {
    lifecycle::start().map_err(Into::into)
}

/// Gracefully shut the light client down.
#[uniffi::export]
pub fn stop_light_client() -> Result<(), LightClientError> {
    lifecycle::stop().map_err(Into::into)
}

/// Current state (0 = INIT, 1 = RUNNING, 2 = STOPPED).
#[uniffi::export]
pub fn get_status() -> u8 {
    lifecycle::status()
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
