//! Error type of the platform-neutral bridge core.

use thiserror::Error;

/// Every failure mode the bridge core can report to a platform bridge.
///
/// The JNI bridge collapses these onto `JNI_FALSE` / `null` (its historical
/// contract); the UniFFI bridge maps them onto `LightClientError` so Swift
/// callers get the reason.
#[derive(Debug, Error)]
pub enum BridgeError {
    /// The light client has not been initialized, or is not running yet.
    #[error("light client is not initialized")]
    NotInitialized,
    /// `init` was called a second time. Globals live in `OnceLock`s and cannot
    /// be reset, so the process must be restarted first.
    #[error("light client is already initialized")]
    AlreadyInitialized,
    /// The thing asked for is legitimately absent, as opposed to the lookup
    /// having failed. A light client only stores the blocks it matched, so an
    /// unknown header is an ordinary answer rather than an error condition.
    #[error("not found: {0}")]
    NotFound(String),
    /// The TOML config could not be read or parsed.
    #[error("config error: {0}")]
    Config(String),
    /// Storage or chain-spec initialization failed.
    #[error("storage error: {0}")]
    Storage(String),
    /// Network state or the network service failed to start.
    #[error("network error: {0}")]
    Network(String),
    /// Anything else (serialization, global-state races).
    #[error("internal error: {0}")]
    Internal(String),
}
