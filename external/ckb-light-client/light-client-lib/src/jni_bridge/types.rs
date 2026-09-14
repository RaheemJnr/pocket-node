//! Global state management for JNI bridge
//!
//! The platform-neutral globals (state machine, storage, network controller,
//! consensus, peers, runtime) live in [`crate::bridge_core::types`] and are
//! re-exported here so the JNI modules keep using `super::types::*`.
//!
//! Only handles that are meaningless off the JVM are declared here.

pub use crate::bridge_core::types::*;

use jni::objects::GlobalRef;
use jni::JavaVM;
use std::sync::OnceLock;

/// Global JavaVM for callbacks
pub static JAVA_VM: OnceLock<JavaVM> = OnceLock::new();

/// Global status callback
pub static STATUS_CALLBACK: OnceLock<GlobalRef> = OnceLock::new();
