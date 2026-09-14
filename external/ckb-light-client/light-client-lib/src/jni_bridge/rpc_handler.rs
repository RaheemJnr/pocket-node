//! RPC handler for JNI bridge
//!
//! Provides a direct JNI method for RPC calls instead of an HTTP server. The
//! dispatch itself lives in [`crate::bridge_core::rpc`]; this is marshalling
//! only.

use super::panic_guard::guard_jni;
use crate::bridge_core::rpc as bridge_rpc;
use jni::objects::{JClass, JString};
use jni::sys::jstring;
use jni::JNIEnv;
use log::error;
use std::ptr;

/// Helper to create a JString from a JSON-RPC response produced by `bridge_core`
fn rpc_to_jstring(env: &mut JNIEnv, json: &str) -> jstring {
    match env.new_string(json) {
        Ok(s) => s.into_raw(),
        Err(e) => {
            error!("Failed to create JString: {}", e);
            ptr::null_mut()
        }
    }
}

/// JNI: Call RPC method
///
/// This provides a generic RPC interface that handles common methods:
/// - get_peers
/// - get_tip_header
/// - get_genesis_block
/// - get_scripts
///
/// Returns JSON-RPC 2.0 formatted response as string
#[no_mangle]
pub extern "C" fn Java_com_nervosnetwork_ckblightclient_LightClientNative_callRpc(
    mut env: JNIEnv,
    _class: JClass,
    method_jstr: JString,
) -> jstring {
    guard_jni(std::ptr::null_mut(), move || {
        // Get method name
        let method: String = match env.get_string(&method_jstr) {
            Ok(s) => s.into(),
            Err(e) => {
                error!("Failed to get method name: {}", e);
                return match bridge_rpc::rpc_error_response(-32700, "Failed to parse method name") {
                    Ok(json) => rpc_to_jstring(&mut env, &json),
                    Err(_) => ptr::null_mut(),
                };
            }
        };

        match bridge_rpc::call_rpc(&method) {
            Ok(json) => rpc_to_jstring(&mut env, &json),
            Err(_) => ptr::null_mut(),
        }
    })
}
