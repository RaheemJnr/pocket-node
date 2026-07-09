use ckb_jsonrpc_types::JsonBytes;

// Regression guard for the get_cells/get_transactions cursor round-trip bug.
// The JNI emits `last_cursor` as a JsonBytes -> a bare hex string "0x..".
// The app sends that raw string back, and the Rust side must recover the
// original key bytes from it.

fn parse_cursor_broken(cursor_str: &str) -> Option<Vec<u8>> {
    // What the code did: serde_json on the RAW hex (needs a quoted JSON string).
    serde_json::from_str::<JsonBytes>(cursor_str).ok().map(|c| c.as_bytes().to_vec())
}

fn parse_cursor_fixed(cursor_str: &str) -> Option<Vec<u8>> {
    // The fix: the cursor IS a JsonBytes value; wrap it as the JSON string it is.
    serde_json::from_str::<JsonBytes>(&format!("\"{}\"", cursor_str)).ok().map(|c| c.as_bytes().to_vec())
}

#[test]
fn cursor_roundtrips_after_fix() {
    let key = vec![0x2a_u8, 0x3f, 0x00, 0xab, 0xff, 0x10];
    // How the cursor reaches the app then comes back (JsonBytes -> "0x..").
    let emitted = JsonBytes::from_vec(key.clone());
    let cursor_str = serde_json::to_string(&emitted).unwrap();     // == "\"0x2a3f00abff10\""
    let cursor_str = cursor_str.trim_matches('"').to_string();     // app strips JSON quotes -> "0x2a3f00abff10"

    // BROKEN: raw hex fails to parse -> None -> code fell back to `prefix` -> page-2 empty -> caps at 100.
    assert_eq!(parse_cursor_broken(&cursor_str), None, "raw-hex parse must fail (the bug)");

    // FIXED: recovers the exact original key bytes -> pagination advances.
    assert_eq!(parse_cursor_fixed(&cursor_str), Some(key), "fixed parse recovers the key");
}
