use env_logger::{Builder, Target};
use log::LevelFilter;

mod chain;
mod network_context;

pub(crate) use chain::MockChain;
pub(crate) use network_context::MockNetworkContext;

use crate::storage::Storage;

pub(crate) fn setup() {
    let _ = Builder::new()
        .filter_module("ckb_stop_handler", LevelFilter::Off)
        .filter_module("ckb_light_client", LevelFilter::Trace)
        .target(Target::Stdout)
        .is_test(true)
        .try_init();
    println!();
}

pub(crate) fn new_storage(prefix: &str) -> Storage {
    // `Storage::new` opens its argument as the sqlite database *file*, mirroring
    // `lifecycle::run` which joins "store.db" onto the configured data directory.
    // Passing the bare tempdir here made every test open the directory itself as
    // the db file (SqliteFailure(CannotOpen, 14)). `into_path()` also leaks the
    // TempDir instead of deleting it when this function returns, which would
    // otherwise remove the directory out from under the open sqlite connection
    // (e.g. before WAL/SHM files get created on first write).
    let tmp_dir = tempfile::Builder::new().prefix(prefix).tempdir().unwrap();
    let db_path = tmp_dir.into_path().join("store.db");
    Storage::new(db_path)
}
