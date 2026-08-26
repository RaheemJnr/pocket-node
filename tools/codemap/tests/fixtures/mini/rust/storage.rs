/// Reads a cell count from the local store.
pub fn read_cell_count(prefix: &str) -> u64 {
    prefix.len() as u64
}

pub struct Store {
    pub path: String,
}

impl Store {
    pub fn open(path: String) -> Store {
        Store { path }
    }
}
