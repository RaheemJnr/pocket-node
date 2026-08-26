use crate::storage::read_cell_count;

/// Exported to Swift through UniFFI.
#[uniffi::export]
pub fn get_cell_count(prefix: String) -> u64 {
    read_cell_count(&prefix)
}

#[uniffi::export]
pub fn start_node() -> bool {
    true
}

/// Exported but never called from Swift, so it should surface as an orphan.
#[uniffi::export]
pub fn unused_export() -> u64 {
    0
}
