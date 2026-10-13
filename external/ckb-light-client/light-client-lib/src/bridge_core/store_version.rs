//! Format version of the light client's on-device store.
//!
//! The store is a SQLite key-value file (`<data_dir>/store.db`, one per
//! network). Its layout version lives in the SQLite header as
//! `PRAGMA user_version`. Stores written before this module existed carry 0,
//! which is the same layout as [`STORE_FORMAT_VERSION`] 1.
//!
//! [`ensure_store_version`] runs in [`super::lifecycle::init`] before the
//! storage layer opens the file, so both platform bridges go through it and
//! neither the JNI nor the UniFFI surface changes:
//!
//! | On disk                         | Action                                     |
//! |---------------------------------|--------------------------------------------|
//! | no file                         | create an empty store stamped 1            |
//! | user_version 0 (pre-versioning) | stamp 1, no data change                    |
//! | user_version 1                  | nothing                                    |
//! | 0 < v < 1 + registered steps    | run each step in order, stamping after it  |
//! | step missing or failing         | set aside, fresh store stamped 1           |
//! | v > 1 (store from a newer build)| set aside, fresh store stamped 1           |
//! | not a SQLite file / corrupt     | set aside, fresh store stamped 1           |
//! | a directory (old RocksDB store) | set aside, fresh store stamped 1           |
//!
//! "Set aside" renames `store.db` (and its `-wal` / `-shm` siblings, if any)
//! to `store.db.incompatible-<version>-<unix seconds>`; nothing is deleted.
//! The light client then syncs again from its configured start point.
//!
//! The version is read and written on a short-lived connection that is closed
//! before [`crate::storage::Storage::new`] opens the file, so the two never
//! hold the file at the same time.

use super::error::BridgeError;
use log::{info, warn};
use rusqlite::{Connection, ErrorCode};
use std::fs;
use std::io::Read;
use std::path::{Path, PathBuf};
use std::time::{SystemTime, UNIX_EPOCH};

/// Layout version of the store this build reads and writes.
pub const STORE_FORMAT_VERSION: u32 = 1;

/// One upgrade step: migrates a store at version `from` to `from + 1`.
///
/// `run` executes inside a transaction that also stamps `from + 1`, so a step
/// either completes and is recorded or leaves the store unchanged.
#[derive(Clone, Copy)]
pub struct UpgradeStep {
    pub from: u32,
    pub run: fn(&Connection) -> rusqlite::Result<()>,
}

/// Registered upgrade steps, in any order. Adding a layout change later means
/// bumping [`STORE_FORMAT_VERSION`] and adding one entry here.
const UPGRADE_STEPS: &[UpgradeStep] = &[];

/// What [`ensure_store_version`] did. Logged by `init`; not surfaced to the
/// platform bridges.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum StoreVersionAction {
    /// There was no store; an empty one stamped with the current version was
    /// created for the storage layer to fill.
    Created,
    /// A pre-versioning store (user_version 0) was stamped as version 1.
    Stamped,
    /// The store already had the current version.
    Current,
    /// Upgrade steps ran from `from` to `to`.
    Upgraded { from: u32, to: u32 },
    /// The store could not be used by this build. It was renamed to
    /// `renamed_to` (a file name, in the same directory) and an empty store
    /// stamped with the current version took its place. `found` is the
    /// version on disk, or `None` if the file was not a readable SQLite store.
    SetAside {
        found: Option<u32>,
        renamed_to: String,
    },
}

/// Bring the store at `path` to [`STORE_FORMAT_VERSION`], or set it aside if
/// this build cannot read it. Must be called before the storage layer opens
/// the file.
pub fn ensure_store_version(path: &Path) -> Result<StoreVersionAction, BridgeError> {
    ensure_store_version_with(path, STORE_FORMAT_VERSION, UPGRADE_STEPS)
}

/// [`ensure_store_version`] with the target version and upgrade table as
/// parameters, so tests can exercise the upgrade path while production has no
/// steps.
pub(crate) fn ensure_store_version_with(
    path: &Path,
    target: u32,
    steps: &[UpgradeStep],
) -> Result<StoreVersionAction, BridgeError> {
    if !path.exists() {
        create_stamped(path, target)?;
        return Ok(StoreVersionAction::Created);
    }

    let found = match read_version(path)? {
        Some(v) => v,
        None => return set_aside(path, None, target),
    };

    if found == target {
        return Ok(StoreVersionAction::Current);
    }
    if found == 0 {
        stamp(path, target)?;
        return Ok(StoreVersionAction::Stamped);
    }
    if found > target {
        return set_aside(path, Some(found), target);
    }

    match upgrade(path, found, target, steps)? {
        Ok(()) => Ok(StoreVersionAction::Upgraded {
            from: found,
            to: target,
        }),
        Err(reached) => set_aside(path, Some(reached), target),
    }
}

/// `Ok(None)` when the file is not a usable SQLite database (wrong magic, or
/// SQLite reports it as corrupt). Any other failure (busy, permissions, I/O)
/// is an error, so a good store is never set aside because of a transient
/// problem.
fn read_version(path: &Path) -> Result<Option<u32>, BridgeError> {
    // A directory (for example a RocksDB store from an old build) is not
    // something SQLite can open.
    if path.is_dir() {
        return Ok(None);
    }
    // Check the magic before SQLite sees the file, so it never touches the
    // -wal / -shm siblings of something that is not a database.
    let mut magic = [0u8; 16];
    let mut file = fs::File::open(path).map_err(storage_err)?;
    let len = file.metadata().map_err(storage_err)?.len();
    if len > 0 {
        if len < magic.len() as u64 {
            return Ok(None);
        }
        file.read_exact(&mut magic).map_err(storage_err)?;
        if &magic != b"SQLite format 3\0" {
            return Ok(None);
        }
    }
    drop(file);

    let conn = Connection::open(path).map_err(sqlite_err)?;
    match conn.pragma_query_value(None, "user_version", |row| row.get::<_, i64>(0)) {
        // user_version is a signed 32-bit header field; a negative value can
        // only come from a foreign writer and reads as a huge unsigned version.
        Ok(v) => Ok(Some(v as u32)),
        Err(e) if is_unreadable(&e) => Ok(None),
        Err(e) => Err(sqlite_err(e)),
    }
}

fn is_unreadable(e: &rusqlite::Error) -> bool {
    matches!(
        e.sqlite_error_code(),
        Some(ErrorCode::NotADatabase) | Some(ErrorCode::DatabaseCorrupt)
    )
}

fn create_stamped(path: &Path, version: u32) -> Result<(), BridgeError> {
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent).map_err(storage_err)?;
    }
    stamp(path, version)
}

/// Writes `user_version` and closes the connection. In WAL mode the write goes
/// through the WAL with the connection's default `synchronous = FULL`, and the
/// close checkpoints it into the main file.
fn stamp(path: &Path, version: u32) -> Result<(), BridgeError> {
    let conn = Connection::open(path).map_err(sqlite_err)?;
    conn.pragma_update(None, "user_version", version)
        .map_err(sqlite_err)?;
    conn.close().map_err(|(_, e)| sqlite_err(e))
}

/// Runs the steps from `from` up to `target`. The outer `Result` is an I/O
/// level failure to open the store; the inner `Err` carries the version the
/// store reached when a step was missing or failed.
fn upgrade(
    path: &Path,
    from: u32,
    target: u32,
    steps: &[UpgradeStep],
) -> Result<Result<(), u32>, BridgeError> {
    let mut conn = Connection::open(path).map_err(sqlite_err)?;
    for v in from..target {
        let Some(step) = steps.iter().find(|s| s.from == v) else {
            warn!("No upgrade step for light client store version {}", v);
            return Ok(Err(v));
        };
        let result = conn.transaction().and_then(|tx| {
            (step.run)(&tx)?;
            tx.pragma_update(None, "user_version", v + 1)?;
            tx.commit()
        });
        if let Err(e) = result {
            warn!(
                "Light client store upgrade {} -> {} failed: {}",
                v,
                v + 1,
                e
            );
            return Ok(Err(v));
        }
        info!("Light client store upgraded {} -> {}", v, v + 1);
    }
    conn.close().map_err(|(_, e)| sqlite_err(e))?;
    Ok(Ok(()))
}

/// Renames the store and its -wal / -shm siblings out of the way, then creates
/// an empty store stamped `target`. Never deletes anything.
fn set_aside(
    path: &Path,
    found: Option<u32>,
    target: u32,
) -> Result<StoreVersionAction, BridgeError> {
    let file_name = path
        .file_name()
        .and_then(|n| n.to_str())
        .ok_or_else(|| BridgeError::Storage("store path has no file name".to_owned()))?;
    let tag = found.map_or_else(|| "unreadable".to_owned(), |v| v.to_string());
    let ts = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0);

    // Pick a name nothing (main file or sibling) already uses.
    let mut base = format!("{}.incompatible-{}-{}", file_name, tag, ts);
    let mut n = 1;
    while ["", "-wal", "-shm"]
        .iter()
        .any(|s| sibling(path, &format!("{}{}", base, s)).exists())
    {
        base = format!("{}.incompatible-{}-{}-{}", file_name, tag, ts, n);
        n += 1;
    }

    // Siblings first: if the main rename then fails, a leftover main file with
    // no WAL is still a consistent (if older) database.
    for suffix in ["-wal", "-shm"] {
        let from = sibling(path, &format!("{}{}", file_name, suffix));
        if from.exists() {
            fs::rename(&from, sibling(path, &format!("{}{}", base, suffix)))
                .map_err(storage_err)?;
        }
    }
    fs::rename(path, sibling(path, &base)).map_err(storage_err)?;

    warn!(
        "Light client store (format version {}) cannot be read by this build (format version {}); \
         moved aside as {} and starting a fresh store",
        tag, target, base
    );

    create_stamped(path, target)?;
    Ok(StoreVersionAction::SetAside {
        found,
        renamed_to: base,
    })
}

fn sibling(path: &Path, name: &str) -> PathBuf {
    path.with_file_name(name)
}

fn storage_err(e: std::io::Error) -> BridgeError {
    BridgeError::Storage(format!("store version check: {}", e))
}

fn sqlite_err(e: rusqlite::Error) -> BridgeError {
    BridgeError::Storage(format!("store version check: {}", e))
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::storage::Storage;
    use tempfile::TempDir;

    fn user_version(path: &Path) -> u32 {
        let conn = Connection::open(path).unwrap();
        conn.pragma_query_value(None, "user_version", |r| r.get::<_, u32>(0))
            .unwrap()
    }

    /// Writes rows through the real storage layer (WAL mode, `kv` table) and
    /// closes it, leaving a store exactly as today's builds leave it.
    fn write_store(path: &Path, rows: &[(&[u8], &[u8])]) {
        let storage = Storage::new(path);
        {
            let conn = storage.conn.lock().unwrap();
            for (k, v) in rows {
                conn.execute(
                    "INSERT OR REPLACE INTO kv (key, value) VALUES (?1, ?2)",
                    rusqlite::params![k, v],
                )
                .unwrap();
            }
        }
        drop(storage);
    }

    fn set_version(path: &Path, v: u32) {
        let conn = Connection::open(path).unwrap();
        conn.pragma_update(None, "user_version", v).unwrap();
    }

    fn rows(path: &Path) -> Vec<(Vec<u8>, Vec<u8>)> {
        let conn = Connection::open(path).unwrap();
        let mut stmt = conn
            .prepare("SELECT key, value FROM kv ORDER BY key")
            .unwrap();
        let out = stmt
            .query_map([], |r| Ok((r.get(0)?, r.get(1)?)))
            .unwrap()
            .map(|r| r.unwrap())
            .collect();
        out
    }

    fn row_count(path: &Path) -> i64 {
        let conn = Connection::open(path).unwrap();
        conn.query_row("SELECT count(*) FROM kv", [], |r| r.get(0))
            .unwrap()
    }

    fn entries(dir: &Path) -> Vec<String> {
        let mut v: Vec<String> = fs::read_dir(dir)
            .unwrap()
            .map(|e| e.unwrap().file_name().into_string().unwrap())
            .collect();
        v.sort();
        v
    }

    const ROWS: &[(&[u8], &[u8])] = &[(b"GENESIS_BLOCK", b"g"), (b"LAST_STATE", b"s")];

    #[test]
    fn missing_store_is_created_with_current_version() {
        let dir = TempDir::new().unwrap();
        let path = dir.path().join("nested").join("store.db");

        assert_eq!(
            ensure_store_version(&path).unwrap(),
            StoreVersionAction::Created
        );
        // The storage layer then opens it as usual, and the stamp survives.
        let storage = Storage::new(&path);
        drop(storage);
        assert_eq!(user_version(&path), STORE_FORMAT_VERSION);
        assert_eq!(row_count(&path), 0);
    }

    #[test]
    fn pre_versioning_store_is_stamped_and_rows_kept() {
        let dir = TempDir::new().unwrap();
        let path = dir.path().join("store.db");
        write_store(&path, ROWS);
        assert_eq!(user_version(&path), 0);
        let before = rows(&path);

        assert_eq!(
            ensure_store_version(&path).unwrap(),
            StoreVersionAction::Stamped
        );
        assert_eq!(user_version(&path), 1);
        assert_eq!(rows(&path), before);

        // Still stamped after the storage layer reopens it in WAL mode.
        write_store(&path, &[(b"MORE", b"m")]);
        assert_eq!(user_version(&path), 1);
        assert_eq!(row_count(&path), 3);
        assert_eq!(entries(dir.path()), vec!["store.db"]);
    }

    #[test]
    fn stamp_is_durable_while_wal_is_uncheckpointed() {
        // A stamp made while another connection keeps the WAL alive must be
        // visible to the next reader.
        let dir = TempDir::new().unwrap();
        let path = dir.path().join("store.db");
        write_store(&path, ROWS);
        let holder = Storage::new(&path);
        stamp(&path, 1).unwrap();
        assert_eq!(user_version(&path), 1);
        drop(holder);
        assert_eq!(user_version(&path), 1);
    }

    #[test]
    fn current_store_is_untouched() {
        let dir = TempDir::new().unwrap();
        let path = dir.path().join("store.db");
        write_store(&path, ROWS);
        set_version(&path, 1);
        let before = rows(&path);

        assert_eq!(
            ensure_store_version(&path).unwrap(),
            StoreVersionAction::Current
        );
        assert_eq!(user_version(&path), 1);
        assert_eq!(rows(&path), before);
        assert_eq!(entries(dir.path()), vec!["store.db"]);
    }

    #[test]
    fn newer_store_is_set_aside_with_bytes_preserved() {
        let dir = TempDir::new().unwrap();
        let path = dir.path().join("store.db");
        write_store(&path, ROWS);
        set_version(&path, 2);
        let original = fs::read(&path).unwrap();

        let action = ensure_store_version(&path).unwrap();
        let StoreVersionAction::SetAside { found, renamed_to } = action else {
            panic!("expected SetAside, got {:?}", action);
        };
        assert_eq!(found, Some(2));
        assert!(renamed_to.starts_with("store.db.incompatible-2-"));

        let aside = dir.path().join(&renamed_to);
        assert_eq!(fs::read(&aside).unwrap(), original);
        assert_eq!(user_version(&aside), 2);
        assert_eq!(row_count(&aside), 2);

        // A fresh, empty, current store took its place.
        assert_eq!(user_version(&path), 1);
        write_store(&path, &[]);
        assert_eq!(row_count(&path), 0);
    }

    #[test]
    fn newer_store_with_live_wal_is_set_aside_with_its_rows() {
        // Simulate a process killed mid-session: copy store.db, -wal and -shm
        // while the newer build still has the store open.
        let src = TempDir::new().unwrap();
        let src_path = src.path().join("store.db");
        write_store(&src_path, &[]);
        let live = Storage::new(&src_path);
        {
            let conn = live.conn.lock().unwrap();
            conn.pragma_update(None, "user_version", 2).unwrap();
            for (k, v) in ROWS {
                conn.execute(
                    "INSERT INTO kv (key, value) VALUES (?1, ?2)",
                    rusqlite::params![k, v],
                )
                .unwrap();
            }
        }
        let dir = TempDir::new().unwrap();
        let path = dir.path().join("store.db");
        for name in ["store.db", "store.db-wal", "store.db-shm"] {
            fs::copy(src.path().join(name), dir.path().join(name)).unwrap();
        }
        drop(live);

        let action = ensure_store_version(&path).unwrap();
        let StoreVersionAction::SetAside { found, renamed_to } = action else {
            panic!("expected SetAside, got {:?}", action);
        };
        assert_eq!(found, Some(2));
        let aside = dir.path().join(&renamed_to);
        assert_eq!(user_version(&aside), 2);
        assert_eq!(row_count(&aside), 2);
        assert!(!dir.path().join("store.db-wal").exists());
        assert!(!dir.path().join("store.db-shm").exists());
        assert_eq!(user_version(&path), 1);
    }

    #[test]
    fn unreadable_store_is_set_aside_with_siblings() {
        let dir = TempDir::new().unwrap();
        let path = dir.path().join("store.db");
        let junk: &[u8] = b"this is not a sqlite database, just some bytes";
        fs::write(&path, junk).unwrap();
        fs::write(dir.path().join("store.db-wal"), b"wal bytes").unwrap();
        fs::write(dir.path().join("store.db-shm"), b"shm bytes").unwrap();

        let action = ensure_store_version(&path).unwrap();
        let StoreVersionAction::SetAside { found, renamed_to } = action else {
            panic!("expected SetAside, got {:?}", action);
        };
        assert_eq!(found, None);
        assert!(renamed_to.starts_with("store.db.incompatible-unreadable-"));

        assert_eq!(fs::read(dir.path().join(&renamed_to)).unwrap(), junk);
        assert_eq!(
            fs::read(dir.path().join(format!("{}-wal", renamed_to))).unwrap(),
            b"wal bytes"
        );
        assert_eq!(
            fs::read(dir.path().join(format!("{}-shm", renamed_to))).unwrap(),
            b"shm bytes"
        );
        assert!(!dir.path().join("store.db-wal").exists());
        assert!(!dir.path().join("store.db-shm").exists());
        assert_eq!(user_version(&path), 1);
    }

    #[test]
    fn corrupt_store_with_valid_magic_is_set_aside() {
        let dir = TempDir::new().unwrap();
        let path = dir.path().join("store.db");
        let mut bytes = b"SQLite format 3\0".to_vec();
        bytes.extend(std::iter::repeat_n(0xAB, 4096));
        fs::write(&path, &bytes).unwrap();

        let action = ensure_store_version(&path).unwrap();
        let StoreVersionAction::SetAside { found, renamed_to } = action else {
            panic!("expected SetAside, got {:?}", action);
        };
        assert_eq!(found, None);
        assert_eq!(fs::read(dir.path().join(&renamed_to)).unwrap(), bytes);
        assert_eq!(user_version(&path), 1);
    }

    #[test]
    fn directory_in_place_of_store_is_set_aside() {
        let dir = TempDir::new().unwrap();
        let path = dir.path().join("store.db");
        fs::create_dir(&path).unwrap();
        fs::write(path.join("CURRENT"), b"MANIFEST-000001").unwrap();

        let action = ensure_store_version(&path).unwrap();
        let StoreVersionAction::SetAside { found, renamed_to } = action else {
            panic!("expected SetAside, got {:?}", action);
        };
        assert_eq!(found, None);
        assert_eq!(
            fs::read(dir.path().join(renamed_to).join("CURRENT")).unwrap(),
            b"MANIFEST-000001"
        );
        assert_eq!(user_version(&path), 1);
    }

    #[test]
    fn set_aside_never_reuses_an_existing_name() {
        let dir = TempDir::new().unwrap();
        let path = dir.path().join("store.db");
        fs::write(&path, b"first junk store file").unwrap();
        let StoreVersionAction::SetAside { renamed_to: a, .. } =
            ensure_store_version(&path).unwrap()
        else {
            panic!()
        };
        fs::write(&path, b"second junk store file").unwrap();
        let StoreVersionAction::SetAside { renamed_to: b, .. } =
            ensure_store_version(&path).unwrap()
        else {
            panic!()
        };
        assert_ne!(a, b);
        assert_eq!(
            fs::read(dir.path().join(&a)).unwrap(),
            b"first junk store file"
        );
        assert_eq!(
            fs::read(dir.path().join(&b)).unwrap(),
            b"second junk store file"
        );
    }

    fn add_marker_table(conn: &Connection) -> rusqlite::Result<()> {
        conn.execute_batch("CREATE TABLE upgraded_marker (x INTEGER)")
    }

    fn failing_step(conn: &Connection) -> rusqlite::Result<()> {
        conn.execute_batch("CREATE TABLE half_done (x INTEGER)")?;
        conn.execute_batch("THIS IS NOT SQL")
    }

    fn has_table(path: &Path, name: &str) -> bool {
        let conn = Connection::open(path).unwrap();
        conn.query_row(
            "SELECT count(*) FROM sqlite_master WHERE type = 'table' AND name = ?1",
            [name],
            |r| r.get::<_, i64>(0),
        )
        .unwrap()
            > 0
    }

    #[test]
    fn production_has_no_upgrade_steps() {
        assert!(UPGRADE_STEPS.is_empty());
    }

    #[test]
    fn registered_upgrade_step_runs_and_stamps() {
        let dir = TempDir::new().unwrap();
        let path = dir.path().join("store.db");
        write_store(&path, ROWS);
        set_version(&path, 1);
        let before = rows(&path);
        let steps = [UpgradeStep {
            from: 1,
            run: add_marker_table,
        }];

        assert_eq!(
            ensure_store_version_with(&path, 2, &steps).unwrap(),
            StoreVersionAction::Upgraded { from: 1, to: 2 }
        );
        assert_eq!(user_version(&path), 2);
        assert!(has_table(&path, "upgraded_marker"));
        assert_eq!(rows(&path), before);
        assert_eq!(entries(dir.path()), vec!["store.db"]);
    }

    #[test]
    fn missing_upgrade_step_sets_store_aside() {
        let dir = TempDir::new().unwrap();
        let path = dir.path().join("store.db");
        write_store(&path, ROWS);
        set_version(&path, 1);

        let action = ensure_store_version_with(&path, 2, &[]).unwrap();
        let StoreVersionAction::SetAside { found, renamed_to } = action else {
            panic!("expected SetAside, got {:?}", action);
        };
        assert_eq!(found, Some(1));
        assert_eq!(row_count(&dir.path().join(renamed_to)), 2);
        assert_eq!(user_version(&path), 2);
    }

    #[test]
    fn failing_upgrade_step_rolls_back_and_sets_store_aside() {
        let dir = TempDir::new().unwrap();
        let path = dir.path().join("store.db");
        write_store(&path, ROWS);
        set_version(&path, 1);
        let steps = [UpgradeStep {
            from: 1,
            run: failing_step,
        }];

        let action = ensure_store_version_with(&path, 2, &steps).unwrap();
        let StoreVersionAction::SetAside { found, renamed_to } = action else {
            panic!("expected SetAside, got {:?}", action);
        };
        assert_eq!(found, Some(1));
        let aside = dir.path().join(renamed_to);
        assert_eq!(user_version(&aside), 1);
        assert!(!has_table(&aside, "half_done"));
        assert_eq!(row_count(&aside), 2);
    }

    #[test]
    fn idempotent_across_repeated_inits() {
        let dir = TempDir::new().unwrap();
        let path = dir.path().join("store.db");
        assert_eq!(
            ensure_store_version(&path).unwrap(),
            StoreVersionAction::Created
        );
        write_store(&path, ROWS);
        assert_eq!(
            ensure_store_version(&path).unwrap(),
            StoreVersionAction::Current
        );
        assert_eq!(row_count(&path), 2);
    }
}
