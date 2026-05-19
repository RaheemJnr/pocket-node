//! Panic safety wrapper for JNI exports.
//!
//! Rust panics that unwind across an `extern "C"` boundary are undefined
//! behavior since Rust 1.71. Every JNI export wraps its body in `guard_jni`
//! to convert any panic into the function's null/false/error sentinel.

use std::panic::{catch_unwind, AssertUnwindSafe};

/// Wrap an FFI body so panics return `default` instead of unwinding into the JVM.
pub fn guard_jni<F, T>(default: T, f: F) -> T
where
    F: FnOnce() -> T,
{
    match catch_unwind(AssertUnwindSafe(f)) {
        Ok(value) => value,
        Err(panic_info) => {
            let msg = panic_info
                .downcast_ref::<&str>()
                .map(|s| (*s).to_string())
                .or_else(|| panic_info.downcast_ref::<String>().cloned())
                .unwrap_or_else(|| "unknown panic payload".to_string());
            log::error!("JNI export panicked: {}", msg);
            default
        }
    }
}
