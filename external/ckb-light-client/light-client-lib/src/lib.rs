#![allow(clippy::mutable_key_type)]

#[cfg(test)]
#[macro_use]
mod tests;

pub mod error;
pub mod protocols;
pub mod service;
pub mod storage;
pub mod types;
pub mod utils;
pub mod verify;

// Platform-neutral core shared by the mobile bridges (Android JNI, iOS UniFFI)
#[cfg(not(target_arch = "wasm32"))]
pub mod bridge_core;

// JNI bridge for Android
#[cfg(all(feature = "jni-bridge", target_os = "android"))]
pub mod jni_bridge;
