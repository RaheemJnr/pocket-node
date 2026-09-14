//! Lifecycle JNI exports
//!
//! Thin marshalling layer over [`crate::bridge_core::lifecycle`]: convert the
//! JNI argument types, call the core, map `Result` onto `JNI_TRUE`/`JNI_FALSE`.
//! All of the real work (config loading, storage, protocols, network service)
//! lives in the core so the iOS/UniFFI bridge shares it.
//!
//! ## Init invariants
//!
//! `nativeInit` populates the `JAVA_VM` and `STATUS_CALLBACK` `OnceLock`s
//! only *after* every other fallible step has succeeded. This guarantees:
//!
//! - If init returns `JNI_FALSE`, neither callback nor JavaVM is populated,
//!   so a retry runs cleanly without colliding with leftover state from the
//!   previous attempt.
//! - The `GlobalRef` held for the status callback is dropped automatically
//!   on the early-return paths, so failure cannot leak JVM references.
//!
//! Note: `nativeStop` cannot reset any `OnceLock` (the type has no API to
//! do so), so in-process network switching is not supported. See #218 for
//! the app-side mitigation (force process restart on network switch).

use super::callbacks::invoke_status_callback;
use super::panic_guard::guard_jni;
use super::types::*;
use crate::bridge_core::lifecycle;
use jni::objects::{JClass, JObject, JString};
use jni::sys::{jboolean, jint, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;
use log::error;

/// Map a `STATE_*` value onto the status name the Kotlin callback expects.
fn status_name(status: u8) -> &'static str {
    match status {
        STATE_RUNNING => "running",
        STATE_STOPPED => "stopped",
        _ => "initialized",
    }
}

/// JNI: Initialize the light client
///
/// See [`crate::bridge_core::lifecycle::init`] for what initialization covers.
///
/// Note: This starts the network service but doesn't change state to RUNNING yet.
/// Call nativeStart() to actually start processing.
#[no_mangle]
pub extern "C" fn Java_com_nervosnetwork_ckblightclient_LightClientNative_nativeInit(
    mut env: JNIEnv,
    _class: JClass,
    config_path_jstr: JString,
    status_callback: JObject,
) -> jboolean {
    guard_jni(JNI_FALSE, move || {
        // Check if already initialized
        if is_initialized() {
            error!("Already initialized!");
            return JNI_FALSE;
        }

        // Acquire VM and status callback handles up front, but hold them in local
        // variables until the rest of init succeeds. The audit finding "partial-
        // init unrecoverable" (#186 High 4) was caused by setting these OnceLocks
        // early: a failure later in init would leave them populated, blocking
        // every retry attempt. By deferring the .set() calls until all fallible
        // work has succeeded, a transient init failure drops the local variables
        // (releasing the GlobalRef) and retry starts cleanly.
        let vm = match env.get_java_vm() {
            Ok(vm) => vm,
            Err(e) => {
                eprintln!("Failed to get JavaVM: {}", e);
                return JNI_FALSE;
            }
        };

        let status_callback_ref = match env.new_global_ref(status_callback) {
            Ok(r) => r,
            Err(e) => {
                eprintln!("Failed to create status callback GlobalRef: {}", e);
                return JNI_FALSE;
            }
        };

        // Get config path
        let config_path: String = match env.get_string(&config_path_jstr) {
            Ok(s) => s.into(),
            Err(e) => {
                error!("Failed to get config path: {}", e);
                return JNI_FALSE;
            }
        };

        // The Android app rewrites the store/network paths into the TOML before
        // calling in, so no data-dir override is needed here.
        let listener: Box<dyn Fn(u8) + Send + Sync> = Box::new(|status| {
            let _ = invoke_status_callback(status_name(status), "");
        });

        if let Err(e) = lifecycle::init(&config_path, "", Some(listener)) {
            error!("Failed to initialize light client: {}", e);
            return JNI_FALSE;
        }

        // All fallible init has succeeded. Populate the JavaVM and status callback
        // OnceLocks last so that a failure above this point would have left both
        // unset, allowing retry. (See audit #186 Finding High 4.)
        if JAVA_VM.set(vm).is_err() {
            error!("Failed to store JavaVM (already set?)");
            return JNI_FALSE;
        }
        if STATUS_CALLBACK.set(status_callback_ref).is_err() {
            error!("Failed to store status callback (already set?)");
            return JNI_FALSE;
        }

        // Notify status callback
        let _ = invoke_status_callback("initialized", "");

        JNI_TRUE
    })
}

/// JNI: Start the light client
///
/// This transitions from INIT to RUNNING state.
/// The network service is already running (started in init).
#[no_mangle]
pub extern "C" fn Java_com_nervosnetwork_ckblightclient_LightClientNative_nativeStart(
    _env: JNIEnv,
    _class: JClass,
) -> jboolean {
    guard_jni(JNI_FALSE, || match lifecycle::start() {
        Ok(()) => JNI_TRUE,
        Err(_) => JNI_FALSE,
    })
}

/// JNI: Stop the light client
///
/// This gracefully shuts down the light client:
/// - Broadcast exit signals
/// - Wait for services to stop
/// - Transition to STOPPED state
#[no_mangle]
pub extern "C" fn Java_com_nervosnetwork_ckblightclient_LightClientNative_nativeStop(
    _env: JNIEnv,
    _class: JClass,
) -> jboolean {
    guard_jni(JNI_FALSE, || match lifecycle::stop() {
        Ok(()) => JNI_TRUE,
        Err(_) => JNI_FALSE,
    })
}

/// JNI: Get current status
///
/// Returns:
/// - 0 (INIT): Initialized but not started
/// - 1 (RUNNING): Running
/// - 2 (STOPPED): Stopped
#[no_mangle]
pub extern "C" fn Java_com_nervosnetwork_ckblightclient_LightClientNative_nativeGetStatus(
    _env: JNIEnv,
    _class: JClass,
) -> jint {
    guard_jni(STATE_STOPPED as jint, || lifecycle::status() as jint)
}
