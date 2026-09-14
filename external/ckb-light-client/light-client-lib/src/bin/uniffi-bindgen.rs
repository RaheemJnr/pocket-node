//! `uniffi-bindgen` CLI for this crate.
//!
//! Used by `build-ios.sh` to generate the Swift bindings from the compiled
//! library: `cargo run --features uniffi-bridge --bin uniffi-bindgen -- generate ...`

fn main() {
    uniffi::uniffi_bindgen_main()
}
