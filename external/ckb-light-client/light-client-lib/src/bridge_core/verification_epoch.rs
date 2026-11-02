//! Cache trust boundary. Runs before the network or any bridge publishes storage.
//! Only chain-derived rows are invalidated; this database contains no wallet keys.
use super::error::BridgeError;
use crate::storage::{Key, KeyPrefix, Storage};
use rusqlite::{params, Connection, OptionalExtension};
use serde::Serialize;

pub const VERIFICATION_EPOCH: u32 = 1;

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct VerificationEpoch {
    pub version: u32,
    pub generation: String,
}

/// Called inside the store-format 1 -> 2 transaction. Saving script identities
/// separately keeps recovery metadata without activating stale scan progress.
pub(crate) fn upgrade(conn: &Connection) -> rusqlite::Result<()> {
    conn.execute_batch(
        "CREATE TABLE IF NOT EXISTS kv (key BLOB PRIMARY KEY, value BLOB);
         CREATE TABLE pocket_previous_scripts (key BLOB PRIMARY KEY, value BLOB);
         CREATE TABLE pocket_verification (id INTEGER PRIMARY KEY CHECK(id = 1), version INTEGER NOT NULL, generation TEXT NOT NULL);",
    )?;
    let scripts = Key::Meta("FILTER_SCRIPTS").into_vec();
    conn.execute(
        "INSERT INTO pocket_previous_scripts SELECT key, value FROM kv WHERE substr(key, 1, ?1) = ?2",
        params![scripts.len(), &scripts],
    )?;
    conn.execute(
        "DELETE FROM kv WHERE substr(key, 1, ?1) = ?2",
        params![scripts.len(), &scripts],
    )?;
    for prefix in [
        KeyPrefix::TxHash,
        KeyPrefix::CellLockScript,
        KeyPrefix::CellTypeScript,
        KeyPrefix::TxLockScript,
        KeyPrefix::TxTypeScript,
        KeyPrefix::BlockHash,
        KeyPrefix::BlockNumber,
        KeyPrefix::CheckPointIndex,
    ] {
        conn.execute(
            "DELETE FROM kv WHERE substr(key, 1, 1) = ?1",
            params![&[prefix as u8][..]],
        )?;
    }
    for name in [
        "GENESIS_BLOCK",
        "LAST_STATE",
        "LAST_N_HEADERS",
        "MIN_FILTERED_NUMBER",
        "MAX_CHECK_POINT_INDEX",
    ] {
        conn.execute(
            "DELETE FROM kv WHERE key = ?1",
            params![Key::Meta(name).into_vec()],
        )?;
    }
    let matched = Key::Meta("MATCHED_BLOCKS").into_vec();
    conn.execute(
        "DELETE FROM kv WHERE substr(key, 1, ?1) = ?2",
        params![matched.len(), matched],
    )?;
    let generation: String = rand::random::<[u8; 16]>()
        .iter()
        .map(|b| format!("{b:02x}"))
        .collect();
    conn.execute(
        "INSERT INTO pocket_verification VALUES (1, ?1, ?2)",
        params![VERIFICATION_EPOCH, generation],
    )?;
    Ok(())
}

/// Fresh stores need the same durable identity as migrated stores. A missing
/// identity in an already populated store fails closed instead of trusting it.
pub(crate) fn initialize_fresh(storage: &Storage) -> Result<(), BridgeError> {
    let mut conn = storage
        .conn
        .lock()
        .map_err(|_| BridgeError::Storage("store lock poisoned".into()))?;
    let exists: bool = conn
        .query_row(
            "SELECT EXISTS(SELECT 1 FROM sqlite_master WHERE name='pocket_verification')",
            [],
            |r| r.get(0),
        )
        .map_err(db_error)?;
    if !exists {
        let tx = conn.transaction().map_err(db_error)?;
        let count: i64 = tx
            .query_row("SELECT COUNT(*) FROM kv", [], |r| r.get(0))
            .map_err(db_error)?;
        if count != 0 {
            return Err(BridgeError::Storage(
                "populated store is missing its verification identity".into(),
            ));
        }
        upgrade(&tx).map_err(db_error)?;
        tx.commit().map_err(db_error)?;
    }
    drop(conn);
    read(storage).map(|_| ())
}

pub fn read(storage: &Storage) -> Result<VerificationEpoch, BridgeError> {
    let conn = storage
        .conn
        .lock()
        .map_err(|_| BridgeError::Storage("store lock poisoned".into()))?;
    let epoch = conn
        .query_row(
            "SELECT version, generation FROM pocket_verification WHERE id = 1",
            [],
            |row| {
                Ok(VerificationEpoch {
                    version: row.get(0)?,
                    generation: row.get(1)?,
                })
            },
        )
        .optional()
        .map_err(db_error)?
        .ok_or_else(|| BridgeError::Storage("verification identity missing".into()))?;
    if epoch.version != VERIFICATION_EPOCH
        || epoch.generation.len() != 32
        || !epoch.generation.bytes().all(|b| b.is_ascii_hexdigit())
    {
        return Err(BridgeError::Storage(
            "unsupported verification identity".into(),
        ));
    }
    Ok(epoch)
}

fn db_error(error: rusqlite::Error) -> BridgeError {
    BridgeError::Storage(format!("verification epoch: {error}"))
}

#[cfg(test)]
mod tests {
    use super::super::store_version::{ensure_store_version, ensure_store_version_with};
    use super::*;

    #[test]
    fn upgrade_removes_chain_evidence_but_preserves_scripts_and_unknown_metadata() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("store.db");
        let storage = Storage::new(&path);
        let script = [Key::Meta("FILTER_SCRIPTS").into_vec(), vec![1, 2, 3]].concat();
        let unknown = Key::Meta("APP_SETTING").into_vec();
        {
            let conn = storage.conn.lock().unwrap();
            for key in [
                vec![0, 1],
                vec![160, 2],
                Key::Meta("LAST_STATE").into_vec(),
                [Key::Meta("MATCHED_BLOCKS").into_vec(), vec![1]].concat(),
                script.clone(),
                unknown.clone(),
            ] {
                conn.execute(
                    "INSERT INTO kv VALUES (?1, ?2)",
                    params![key, &[4u8; 8][..]],
                )
                .unwrap();
            }
        }
        drop(storage);
        ensure_store_version(&path).unwrap();
        let storage = Storage::new(&path);
        initialize_fresh(&storage).unwrap();
        assert!(storage.get(vec![0, 1]).unwrap().is_none());
        assert!(storage
            .get(Key::Meta("LAST_STATE").into_vec())
            .unwrap()
            .is_none());
        assert!(storage.get(&script).unwrap().is_none());
        assert_eq!(storage.get(unknown).unwrap(), Some(vec![4; 8]));
        let conn = storage.conn.lock().unwrap();
        let archived: Vec<u8> = conn
            .query_row(
                "SELECT value FROM pocket_previous_scripts WHERE key=?1",
                params![script],
                |r| r.get(0),
            )
            .unwrap();
        assert_eq!(archived, vec![4; 8]);
    }

    #[test]
    fn failed_migration_rolls_back_and_retry_is_idempotent() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("store.db");
        let storage = Storage::new(&path);
        {
            let mut conn = storage.conn.lock().unwrap();
            conn.execute("INSERT INTO kv VALUES (X'0001', X'01')", [])
                .unwrap();
            let tx = conn.transaction().unwrap();
            upgrade(&tx).unwrap();
            // Interrupted before commit: drop rolls back cache invalidation and epoch.
        }
        assert_eq!(storage.get(vec![0, 1]).unwrap(), Some(vec![1]));
        drop(storage);
        ensure_store_version(&path).unwrap();
        let storage = Storage::new(&path);
        let first = read(&storage).unwrap();
        drop(storage);
        ensure_store_version(&path).unwrap();
        assert_eq!(first, read(&Storage::new(&path)).unwrap());
    }

    #[test]
    fn downgrade_then_return_changes_generation() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("store.db");
        ensure_store_version(&path).unwrap();
        let storage = Storage::new(&path);
        initialize_fresh(&storage).unwrap();
        let first = read(&storage).unwrap();
        drop(storage);
        // This is the older binary's actual format-version policy.
        ensure_store_version_with(&path, 1, &[]).unwrap();
        ensure_store_version(&path).unwrap();
        let storage = Storage::new(&path);
        initialize_fresh(&storage).unwrap();
        let second = read(&storage).unwrap();
        assert_ne!(first.generation, second.generation);
        assert_eq!(first.version, second.version);
    }
}
