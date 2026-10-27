// SQLite storage backend for CKB Light Client
// This implementation provides the same interface as RocksDB but uses SQLite as the underlying storage

use super::super::backend::{BatchWriter, FilterMapFn, StorageBackend, TakeWhileFn};
use super::super::storage_trait::LightClientStorage;
use super::super::{Byte32, Key};
use super::iterator::{IteratorDirection, IteratorStart, KVPair};
use crate::error::Result;
use ckb_types::{
    core::{
        cell::{CellMeta, CellProvider, CellStatus},
        TransactionInfo,
    },
    packed::{CellOutput, Header, OutPoint},
    prelude::*,
};
use rusqlite::{Connection, OpenFlags};
use std::{
    path::Path,
    sync::{Arc, Mutex},
};

#[derive(Clone)]
pub struct Storage {
    pub(crate) conn: Arc<Mutex<Connection>>,
}

#[derive(Debug, Clone, Copy)]
pub enum Direction {
    Forward,
    Reverse,
}

#[derive(Debug)]
pub enum IteratorMode<'a> {
    Start,
    End,
    From(&'a [u8], Direction),
}

impl Storage {
    pub fn iterator_collect<F>(&self, mode: IteratorMode, mut predicate: F) -> Vec<(Vec<u8>, Vec<u8>)>
    where F: FnMut(&(Vec<u8>, Vec<u8>)) -> bool
    {
        let conn = self.conn.lock().unwrap();
        let (sql, params_vec) = match mode {
             IteratorMode::Start => ("SELECT key, value FROM kv ORDER BY key ASC".to_string(), vec![]),
             IteratorMode::End => ("SELECT key, value FROM kv ORDER BY key DESC".to_string(), vec![]),
             IteratorMode::From(start_key, Direction::Forward) => {
                 ("SELECT key, value FROM kv WHERE key >= ?1 ORDER BY key ASC".to_string(), vec![start_key.to_vec()])
             },
             IteratorMode::From(start_key, Direction::Reverse) => {
                 ("SELECT key, value FROM kv WHERE key <= ?1 ORDER BY key DESC".to_string(), vec![start_key.to_vec()])
             }
        };

        let mut stmt = conn.prepare(&sql).expect("prepare iterator");
        let params_refs: Vec<&dyn rusqlite::ToSql> = params_vec.iter().map(|p| p as &dyn rusqlite::ToSql).collect();

        let iter = stmt.query_map(&*params_refs, |row| {
            Ok((row.get::<_, Vec<u8>>(0)?, row.get::<_, Vec<u8>>(1)?))
        }).expect("query map");

        let mut results = Vec::new();
        for item in iter {
            if let Ok(kv) = item {
                if !predicate(&kv) {
                    break;
                }
                results.push(kv);
            }
        }
        results
    }


    pub fn new<P: AsRef<Path>>(path: P) -> Self {
        let db_path = path.as_ref();
        if let Some(parent) = db_path.parent() {
            std::fs::create_dir_all(parent).expect("Failed to create sqlite parent directory");
        }

        let conn = Connection::open_with_flags(
            &db_path,
            OpenFlags::SQLITE_OPEN_READ_WRITE
                | OpenFlags::SQLITE_OPEN_CREATE
                | OpenFlags::SQLITE_OPEN_NO_MUTEX,
        )
        .unwrap_or_else(|e| panic!("Failed to open sqlite database: {:?}", e));

        // Initialize the schema
        Self::init_schema(&conn).expect("Failed to initialize sqlite schema");

        Self {
            conn: Arc::new(Mutex::new(conn)),
        }
    }

    fn init_schema(conn: &Connection) -> Result<()> {
        conn.execute_batch(
            r#"
            CREATE TABLE IF NOT EXISTS kv (
                key BLOB PRIMARY KEY,
                value BLOB
            );

            PRAGMA journal_mode = WAL;
            PRAGMA synchronous = NORMAL;
            PRAGMA cache_size = 10000;
            PRAGMA encoding = 'UTF-8';
            "#,
        )
        .map_err(|e| crate::error::Error::runtime(format!("Failed to initialize schema: {}", e)))
    }

    fn batch(&self) -> Batch {
        Batch {
            conn: Arc::clone(&self.conn),
            operations: Vec::new(),
        }
    }

    pub fn get<K: AsRef<[u8]>>(&self, key: K) -> Result<Option<Vec<u8>>> {
        let conn = self.conn.lock().unwrap();
        let mut stmt = conn
            .prepare_cached("SELECT value FROM kv WHERE key = ?")
            .map_err(|e| crate::error::Error::runtime(format!("Failed to prepare get: {}", e)))?;

        let result = stmt.query_row(rusqlite::params![key.as_ref()], |row| {
            row.get::<_, Vec<u8>>(0)
        });

        match result {
            Ok(value) => Ok(Some(value)),
            Err(rusqlite::Error::QueryReturnedNoRows) => Ok(None),
            Err(e) => Err(crate::error::Error::runtime(format!(
                "Failed to get: {}",
                e
            ))),
        }
    }
}

pub struct Batch {
    conn: Arc<Mutex<Connection>>,
    operations: Vec<BatchOp>,
}

enum BatchOp {
    Put(Vec<u8>, Vec<u8>),
    Delete(Vec<u8>),
}

// Implement BatchWriter trait for Batch
impl BatchWriter for Batch {
    fn put(&mut self, key: &[u8], value: &[u8]) {
        self.operations
            .push(BatchOp::Put(key.to_vec(), value.to_vec()));
    }

    fn delete(&mut self, key: &[u8]) {
        self.operations.push(BatchOp::Delete(key.to_vec()));
    }

    fn commit(self) -> Result<()> {
        let conn = self.conn.lock().map_err(|_| {
            crate::error::Error::runtime("storage batch lock poisoned")
        })?;
        let tx = conn.unchecked_transaction().map_err(|e| {
            crate::error::Error::runtime(format!("Failed to begin transaction: {}", e))
        })?;

        for op in self.operations {
            match op {
                BatchOp::Put(key, value) => {
                    tx.execute(
                        "INSERT OR REPLACE INTO kv (key, value) VALUES (?, ?)",
                        rusqlite::params![&key, &value],
                    )
                    .map_err(|e| {
                        crate::error::Error::runtime(format!("Failed to put in batch: {}", e))
                    })?;
                }
                BatchOp::Delete(key) => {
                    tx.execute(
                        "DELETE FROM kv WHERE key = ?",
                        rusqlite::params![&key],
                    )
                    .map_err(|e| {
                        crate::error::Error::runtime(format!("Failed to delete in batch: {}", e))
                    })?;
                }
            }
        }

        tx.commit()
            .map_err(|e| crate::error::Error::runtime(format!("Failed to commit batch: {}", e)))?;

        Ok(())
    }
}

// Implementation of StorageBackend trait for SQLite
impl StorageBackend for Storage {
    type Batch = Batch;

    fn get(&self, key: Vec<u8>) -> Result<Option<Vec<u8>>> {
        Storage::get(self, key)
    }

    fn put(&self, key: Vec<u8>, value: Vec<u8>) -> Result<()> {
        let conn = self.conn.lock().unwrap();
        conn.execute(
            "INSERT OR REPLACE INTO kv (key, value) VALUES (?, ?)",
            rusqlite::params![key.as_slice(), value.as_slice()],
        )
        .map_err(|e| crate::error::Error::runtime(format!("Failed to put: {}", e)))?;
        Ok(())
    }

    fn delete(&self, key: &[u8]) -> Result<()> {
        let conn = self.conn.lock().unwrap();
        conn.execute("DELETE FROM kv WHERE key = ?", rusqlite::params![key])
            .map_err(|e| crate::error::Error::runtime(format!("Failed to delete: {}", e)))?;
        Ok(())
    }

    fn batch(&self) -> Self::Batch {
        Storage::batch(self)
    }

    fn collect_iterator(
        &self,
        start: IteratorStart,
        direction: IteratorDirection,
        take_while_fn: TakeWhileFn,
        filter_map_fn: FilterMapFn,
        limit: usize,
    ) -> Vec<KVPair> {
        // First, collect all matching rows while holding the lock.
        // This is necessary because filter_map_fn may call storage.get() which needs the lock,
        // and std::sync::Mutex is not reentrant. By collecting rows first and releasing the lock,
        // we avoid deadlock when filter_map_fn tries to acquire the lock again.
        let rows: Vec<(Vec<u8>, Vec<u8>)> = {
            let conn = self.conn.lock().unwrap();

            let (order_clause, comparison) = match (direction, &start) {
                (IteratorDirection::Forward, IteratorStart::From(_)) => ("ASC", ">="),
                (IteratorDirection::Reverse, IteratorStart::From(_)) => ("DESC", "<="),
                (IteratorDirection::Forward, IteratorStart::After(_)) => ("ASC", ">"),
                (IteratorDirection::Reverse, IteratorStart::After(_)) => ("DESC", "<"),
            };

            let query = format!(
                "SELECT key, value FROM kv WHERE key {} ? ORDER BY key {}",
                comparison, order_clause
            );

            let mut stmt = conn.prepare(&query).expect("prepare query");

            let from_key = start.key();
            let query_rows = stmt
                .query_map(rusqlite::params![from_key], |row| {
                    Ok((row.get::<_, Vec<u8>>(0)?, row.get::<_, Vec<u8>>(1)?))
                })
                .expect("query rows");

            // Collect rows that pass the take_while condition.
            // We need to eagerly collect because the lock must be released before filter_map_fn runs.
            query_rows
                .filter_map(|r| r.ok())
                .take_while(|(key, _)| take_while_fn(key))
                .collect()
        };
        // Lock is released here

        // Now process rows with filter_map_fn (which may call storage.get())
        rows.into_iter()
            .filter_map(|(key, value)| {
                filter_map_fn(&key, &value).map(|transformed_value| KVPair {
                    key,
                    value: transformed_value,
                })
            })
            .take(limit)
            .collect()
    }
}

// Implementation of LightClientStorage trait for SQLite
// All methods use default implementations from the trait
impl LightClientStorage for Storage {}

impl CellProvider for Storage {
    // assume all cells are live and load data eagerly
    fn cell(&self, out_point: &OutPoint, _eager_load: bool) -> CellStatus {
        if let Some((block_number, tx_index, tx)) = self.get_transaction(&out_point.tx_hash()) {
            let block_hash = Byte32::from_slice(
                &StorageBackend::get(self, Key::BlockNumber(block_number).into_vec())
                    .expect("db get should be ok")
                    .expect("stored block number / hash mapping"),
            )
            .expect("stored block hash should be OK");

            let header = Header::from_slice(
                &StorageBackend::get(self, Key::BlockHash(&block_hash).into_vec())
                    .expect("db get should be ok")
                    .expect("stored block hash / header mapping")[..Header::TOTAL_SIZE],
            )
            .expect("stored header should be OK")
            .into_view();

            let output_index = out_point.index().unpack();
            let tx = tx.into_view();
            if let Some(cell_output) = tx.outputs().get(output_index) {
                let output_data = tx
                    .outputs_data()
                    .get(output_index)
                    .expect("output_data's index should be same as output")
                    .raw_data();
                let output_data_data_hash = CellOutput::calc_data_hash(&output_data);
                let cell_meta = CellMeta {
                    out_point: out_point.clone(),
                    cell_output,
                    transaction_info: Some(TransactionInfo {
                        block_hash,
                        block_epoch: header.epoch(),
                        block_number,
                        index: tx_index as usize,
                    }),
                    data_bytes: output_data.len() as u64,
                    mem_cell_data: Some(output_data),
                    mem_cell_data_hash: Some(output_data_data_hash),
                };
                return CellStatus::Live(cell_meta);
            }
        }
        CellStatus::Unknown
    }
}

#[cfg(test)]
mod mobile_compatibility_tests {
    use super::*;

    #[test]
    fn opens_original_kv_file_and_preserves_rows_and_atomic_batches() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("store.db");
        {
            let old = Connection::open(&path).unwrap();
            old.execute_batch("CREATE TABLE kv (key BLOB PRIMARY KEY, value BLOB); INSERT INTO kv VALUES (X'0102', X'0304');").unwrap();
        }
        let storage = Storage::new(&path);
        assert_eq!(storage.get([1, 2]).unwrap(), Some(vec![3, 4]));
        let mut batch = StorageBackend::batch(&storage);
        batch.put(&[5], &[6]);
        batch.delete(&[1, 2]);
        batch.commit().unwrap();
        drop(storage);
        let reopened = Storage::new(&path);
        assert_eq!(reopened.get([5]).unwrap(), Some(vec![6]));
        assert_eq!(reopened.get([1, 2]).unwrap(), None);
        let conn = reopened.conn.lock().unwrap();
        let accidental_table: bool = conn.query_row("SELECT EXISTS(SELECT 1 FROM sqlite_master WHERE name='kv_store')", [], |r| r.get(0)).unwrap();
        assert!(!accidental_table);
        assert!(!path.join("db.sqlite").exists());
    }

    #[test]
    fn creates_parent_directory_and_retains_mobile_iterator_order() {
        let dir = tempfile::tempdir().unwrap();
        let storage = Storage::new(dir.path().join("nested/store.db"));
        for i in 1..4 { StorageBackend::put(&storage, vec![i], vec![i]).unwrap(); }
        let asc = storage.iterator_collect(IteratorMode::From(&[2], Direction::Forward), |_| true);
        let desc = storage.iterator_collect(IteratorMode::From(&[2], Direction::Reverse), |_| true);
        assert_eq!(asc.iter().map(|x| x.0[0]).collect::<Vec<_>>(), vec![2, 3]);
        assert_eq!(desc.iter().map(|x| x.0[0]).collect::<Vec<_>>(), vec![2, 1]);
    }
}

#[cfg(test)]
mod cursor_regressions {
    use super::*;

    #[test]
    fn exclusive_cursor_preserves_first_survivor_when_cursor_is_absent() {
        let directory = tempfile::tempdir().unwrap();
        let storage = Storage::new(directory.path().join("cursor.db"));
        for key in [1, 3, 5] {
            StorageBackend::put(&storage, vec![key], vec![key]).unwrap();
        }
        let cases = [
            (
                IteratorStart::From(vec![3]),
                IteratorDirection::Forward,
                vec![3, 5],
            ),
            (
                IteratorStart::From(vec![3]),
                IteratorDirection::Reverse,
                vec![3, 1],
            ),
            (
                IteratorStart::After(vec![3]),
                IteratorDirection::Forward,
                vec![5],
            ),
            (
                IteratorStart::After(vec![3]),
                IteratorDirection::Reverse,
                vec![1],
            ),
            (
                IteratorStart::After(vec![2]),
                IteratorDirection::Forward,
                vec![3, 5],
            ),
            (
                IteratorStart::After(vec![4]),
                IteratorDirection::Reverse,
                vec![3, 1],
            ),
        ];
        for (start, direction, expected) in cases {
            // The mapper reenters storage, as service queries do.
            let reader = storage.clone();
            let actual = storage.collect_iterator(
                start,
                direction,
                Box::new(|_| true),
                Box::new(move |key, _| reader.get(key).unwrap()),
                10,
            );
            assert_eq!(
                actual
                    .into_iter()
                    .map(|pair| pair.key[0])
                    .collect::<Vec<_>>(),
                expected
            );
        }
    }
}
