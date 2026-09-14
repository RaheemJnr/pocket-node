//! Platform-neutral core of the mobile bridges.
//!
//! `jni_bridge` (Android) and `ffi` (iOS/UniFFI) are thin marshalling layers on
//! top of this module: they convert platform string/handle types, call into
//! here, and map [`error::BridgeError`] onto whatever their platform expects.
//!
//! ## Architecture
//!
//! - `types`: global state shared by every platform bridge (OnceLock pattern)
//! - `error`: [`error::BridgeError`], the single error type of the core API
//! - `lifecycle`: init/start/stop/status
//! - `query`: query APIs returning the same JSON strings the JNI bridge returns
//!
//! ## State machine
//!
//! - 0 (INIT): Initialized but not started
//! - 1 (RUNNING): Running
//! - 2 (STOPPED): Stopped

pub mod error;
pub mod lifecycle;
pub mod query;
pub mod types;

pub use error::BridgeError;
