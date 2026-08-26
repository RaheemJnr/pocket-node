use crate::storage::read_cell_count;

#[no_mangle]
pub extern "C" fn Java_com_example_mini_LightClientNative_nativeGetCells(
    prefix: *const i8,
) -> u64 {
    read_cell_count("x")
}

#[no_mangle]
pub extern "C" fn Java_com_example_mini_LightClientNative_nativeOrphanFn() -> u64 {
    0
}
