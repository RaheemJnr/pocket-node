use ckb_types::packed::Script;

use crate::storage::{self, LightClientStorage};
use crate::tests::utils::new_storage;

#[test]
fn test_forget_update_min_filtred_number() {
    let (storage, _tmp_dir) = new_storage("forget_update_min_filtred_block");
    storage.update_min_filtered_block_number(66);
    storage.update_filter_scripts(
        vec![
            storage::ScriptStatus {
                script: Script::default(),
                script_type: storage::ScriptType::Lock,
                block_number: 33,
            },
            storage::ScriptStatus {
                script: Script::default(),
                script_type: storage::ScriptType::Type,
                block_number: 44,
            },
        ],
        Default::default(),
    );
    assert_eq!(storage.get_min_filtered_block_number(), 33);
}

fn indexed_recovery_fixture(proven_height: u64, rollback_spend: bool) {
    use ckb_types::{core::{BlockBuilder, TransactionBuilder}, packed::{CellInput, CellOutput, OutPoint}, prelude::*};
    use crate::storage::{HeaderWithExtension, Key, StorageBackend, ScriptStatus, ScriptType};
    let (storage, _dir) = new_storage("indexed_recovery");
    let lock = Script::new_builder().args(vec![1u8].pack()).build();
    let type_script = Script::new_builder().args(vec![2u8].pack()).build();
    storage.update_filter_scripts(vec![
        ScriptStatus { script: lock.clone(), script_type: ScriptType::Lock, block_number: 1 },
        ScriptStatus { script: type_script.clone(), script_type: ScriptType::Type, block_number: 1 },
    ], Default::default());
    let tx = TransactionBuilder::default()
        .output(CellOutput::new_builder().capacity(100u64).lock(lock.clone()).type_(Some(type_script.clone()).pack()).build())
        .output_data(vec![0u8; 8].pack()).build();
    let block = BlockBuilder::default().epoch(ckb_types::core::EpochNumberWithFraction::new(0, 0, 100).full_value()).number(10u64)
        .transaction(TransactionBuilder::default().build()).transaction(tx.clone()).build();
    storage.filter_block(block.data());
    let fetched = BlockBuilder::default().epoch(ckb_types::core::EpochNumberWithFraction::new(0, 0, 100).full_value()).number(proven_height).transaction(tx.clone()).build();
    storage.try_add_fetched_txs(&[(&tx.data(), &HeaderWithExtension { header: fetched.data().header(), extension: None })]).unwrap();
    assert!(storage.get_header(&fetched.hash()).is_some());
    let spend = TransactionBuilder::default()
        .input(CellInput::new_builder().previous_output(OutPoint::new(tx.hash(), 0)).build()).build();
    storage.filter_block(BlockBuilder::default().epoch(ckb_types::core::EpochNumberWithFraction::new(0, 0, 100).full_value()).number(30u64).transaction(spend).build().data());
    if rollback_spend {
        storage.update_filter_scripts(vec![
            ScriptStatus { script: lock.clone(), script_type: ScriptType::Lock, block_number: 30 },
            ScriptStatus { script: type_script.clone(), script_type: ScriptType::Type, block_number: 30 },
        ], Default::default());
        storage.rollback_to_block(30);
    }
    for (script, is_type) in [(&lock, false), (&type_script, true)] {
        let key = |height, ordinal| if is_type { Key::CellTypeScript(script, height, ordinal, 0).into_vec() }
            else { Key::CellLockScript(script, height, ordinal, 0).into_vec() };
        assert_eq!(StorageBackend::get(&storage, key(10, 1)).unwrap(),
            if rollback_spend { Some(tx.hash().as_slice().to_vec()) } else { None }, "real indexed cell after spend/rollback");
        assert_eq!(StorageBackend::get(&storage, key(proven_height, u32::MAX)).unwrap(), None, "no synthetic ordinal cell");
    }
    let (height, ordinal, stored) = storage.get_transaction(&tx.hash()).unwrap();
    assert_eq!((height, ordinal), (10, 1));
    assert_eq!(stored.as_slice(), tx.data().as_slice());
}

#[test] fn recovery_same_height_preserves_indexed_spend() { indexed_recovery_fixture(10, false); }
#[test] fn recovery_other_height_preserves_indexed_spend() { indexed_recovery_fixture(20, false); }
#[test] fn recovery_same_height_preserves_indexed_rollback() { indexed_recovery_fixture(10, true); }
#[test] fn recovery_other_height_preserves_indexed_rollback() { indexed_recovery_fixture(20, true); }

// Park a proof batch after it has observed storage, then commit a real indexed block
// before its writes land. This deterministic ordering reproduces the concurrent race.
struct IndexBeforeCommit {
    storage: storage::Storage,
    block: ckb_types::packed::Block,
}
struct IndexBeforeCommitBatch {
    inner: storage::Batch,
    storage: storage::Storage,
    block: ckb_types::packed::Block,
}
impl storage::BatchWriter for IndexBeforeCommitBatch {
    fn put(&mut self, key: &[u8], value: &[u8]) { self.inner.put(key, value); }
    fn compare_and_put(&mut self, key: &[u8], expected: Option<&[u8]>, value: &[u8]) -> crate::error::Result<()> {
        self.inner.compare_and_put(key, expected, value)
    }
    fn delete(&mut self, key: &[u8]) { self.inner.delete(key); }
    fn commit(self) -> crate::error::Result<()> {
        self.storage.filter_block(self.block);
        self.inner.commit()
    }
}
impl storage::StorageBackend for IndexBeforeCommit {
    type Batch = IndexBeforeCommitBatch;
    fn get(&self, key: Vec<u8>) -> crate::error::Result<Option<Vec<u8>>> { self.storage.get(key) }
    fn put(&self, key: Vec<u8>, value: Vec<u8>) -> crate::error::Result<()> { storage::StorageBackend::put(&self.storage, key, value) }
    fn delete(&self, key: &[u8]) -> crate::error::Result<()> { storage::StorageBackend::delete(&self.storage, key) }
    fn batch(&self) -> Self::Batch {
        IndexBeforeCommitBatch { inner: storage::StorageBackend::batch(&self.storage), storage: self.storage.clone(), block: self.block.clone() }
    }
    fn collect_iterator(&self, start: storage::IteratorStart, direction: storage::IteratorDirection,
        take: storage::TakeWhileFn, map: storage::FilterMapFn, limit: usize) -> Vec<storage::KVPair> {
        storage::StorageBackend::collect_iterator(&self.storage, start, direction, take, map, limit)
    }
}
impl LightClientStorage for IndexBeforeCommit {}

fn recovery_races_filter(existing_unknown: bool) {
    use ckb_types::{core::{BlockBuilder, TransactionBuilder}, packed::CellOutput, prelude::*};
    use crate::storage::{HeaderWithExtension, ScriptStatus, ScriptType};
    let (storage, _dir) = new_storage("recovery_races_filter");
    let script = Script::default();
    storage.update_filter_scripts(vec![ScriptStatus { script: script.clone(), script_type: ScriptType::Lock, block_number: 1 }], Default::default());
    let tx = TransactionBuilder::default().output(CellOutput::new_builder().lock(script).build()).output_data(vec![1u8].pack()).build();
    let indexed = BlockBuilder::default().epoch(ckb_types::core::EpochNumberWithFraction::new(0, 0, 100).full_value()).number(10u64).transaction(TransactionBuilder::default().build()).transaction(tx.clone()).build();
    let fetched = BlockBuilder::default().epoch(ckb_types::core::EpochNumberWithFraction::new(0, 0, 100).full_value()).number(20u64).transaction(tx.clone()).build();
    let hwe = HeaderWithExtension { header: fetched.data().header(), extension: None };
    if existing_unknown { storage.add_fetched_tx(&tx.data(), &hwe); }
    let interleaved = IndexBeforeCommit { storage: storage.clone(), block: indexed.data() };
    interleaved.try_add_fetched_txs(&[(&tx.data(), &hwe)]).unwrap();
    let (height, ordinal, _) = storage.get_transaction(&tx.hash()).unwrap();
    assert_eq!((height, ordinal), (10, 1), "indexed commit wins over stale fetched snapshot");
}
#[test] fn recovery_absent_snapshot_cannot_overwrite_concurrent_index() { recovery_races_filter(false); }
#[test] fn recovery_unknown_snapshot_cannot_overwrite_concurrent_index() { recovery_races_filter(true); }

#[test]
fn history_registration_preserves_original_horizon() {
    use crate::storage::{Key, StorageBackend, ScriptStatus, ScriptType};
    use ckb_types::prelude::*;
    let (storage, _dir) = new_storage("history_horizon");
    let script = Script::default();
    storage.update_filter_scripts(vec![ScriptStatus { script: script.clone(), script_type: ScriptType::Lock, block_number: 12 }], Default::default());
    storage.update_block_number(90);
    let mut key = Key::Meta("HISTORY_REGISTRATION_V1").into_vec();
    key.extend_from_slice(script.as_slice()); key.push(0);
    let metadata = StorageBackend::get(&storage, key).unwrap();
    assert!(metadata.is_some(), "registration must retain original horizon independently of scan progress");
    assert_eq!(&metadata.unwrap()[..8], &12u64.to_be_bytes());
}

#[test]
fn history_registration_rebuilds_known_bodies_without_touching_other_scripts() {
    use crate::storage::{Key, StorageBackend, ScriptStatus, ScriptType, CellType, SetScriptsCommand};
    use ckb_types::{core::{BlockBuilder, TransactionBuilder}, packed::CellOutput, prelude::*};
    let (storage, _dir) = new_storage("history_rebuild");
    let script = Script::default();
    let status = |script_type, block_number| ScriptStatus { script: script.clone(), script_type, block_number };
    storage.update_filter_scripts(vec![status(ScriptType::Lock, 1), status(ScriptType::Type, 1)], SetScriptsCommand::All);
    let tx = TransactionBuilder::default().output(CellOutput::new_builder().lock(script.clone()).type_(Some(script.clone()).pack()).build()).output_data(Vec::<u8>::new().pack()).build();
    let block = BlockBuilder::default().epoch(ckb_types::core::EpochNumberWithFraction::new(0,0,100).full_value()).number(10u64).transaction(tx.clone()).build().data();
    storage.filter_block(block.clone());
    storage.update_block_number(20);
    let lock_key = Key::TxLockScript(&script, 10, 0, 0, CellType::Output).into_vec();
    let type_key = Key::TxTypeScript(&script, 10, 0, 0, CellType::Output).into_vec();
    storage.update_filter_scripts(vec![status(ScriptType::Lock, 1)], SetScriptsCommand::Delete);
    storage.rollback_to_block(10);
    storage.update_filter_scripts(vec![status(ScriptType::Lock, 1)], SetScriptsCommand::Partial);
    assert!(StorageBackend::get(&storage, lock_key.clone()).unwrap().is_none(), "unregistered orphan history must be rebuilt on re-registration");
    assert!(storage.get_transaction(&tx.hash()).is_some(), "known body remains available for input resolution");
    storage.filter_block(block.clone());
    assert!(StorageBackend::get(&storage, lock_key.clone()).unwrap().is_some(), "known transaction body must still be reindexed");
    assert!(StorageBackend::get(&storage, type_key.clone()).unwrap().is_some());
    storage.update_filter_scripts(vec![status(ScriptType::Lock, 1)], SetScriptsCommand::Partial);
    assert!(StorageBackend::get(&storage, type_key.clone()).unwrap().is_some(), "other script history survives registration");
    storage.filter_block(block);
    // A rescan may lower progress while later history is still present (for example,
    // a higher-start registration preserves the covered boundary itself).
    storage.update_filter_scripts(vec![status(ScriptType::Lock, 10), status(ScriptType::Type, 10)], SetScriptsCommand::Partial);
    storage.rollback_to_block(10);
    assert!(StorageBackend::get(&storage, lock_key).unwrap().is_none());
    assert!(StorageBackend::get(&storage, type_key).unwrap().is_none());
}

#[test]
fn history_rollback_cleans_indexes_even_when_scan_progress_was_lowered() {
    use crate::storage::{Key, StorageBackend, ScriptStatus, ScriptType, CellType, SetScriptsCommand};
    use ckb_types::{core::{BlockBuilder, TransactionBuilder}, packed::CellOutput, prelude::*};
    let (storage, _dir) = new_storage("history_lowered_progress");
    let script = Script::default();
    storage.update_filter_scripts(vec![ScriptStatus { script: script.clone(), script_type: ScriptType::Lock, block_number: 1 }], SetScriptsCommand::All);
    let tx = TransactionBuilder::default().output(CellOutput::new_builder().lock(script.clone()).build()).output_data(Vec::<u8>::new().pack()).build();
    storage.filter_block(BlockBuilder::default().epoch(ckb_types::core::EpochNumberWithFraction::new(0,0,100).full_value()).number(10u64).transaction(tx).build().data());
    // Simulate persisted lowered progress with later indexes from an earlier scan.
    let key = [Key::Meta("FILTER_SCRIPTS").into_vec(), script.as_slice().to_vec(), vec![0]].concat();
    StorageBackend::put(&storage, key, 1u64.to_be_bytes().to_vec()).unwrap();
    storage.rollback_to_block(10);
    assert!(StorageBackend::get(&storage, Key::TxLockScript(&script, 10, 0, 0, CellType::Output).into_vec()).unwrap().is_none());
    assert_eq!(storage.get_filter_scripts()[0].block_number, 1, "rollback must not advance lowered progress");
}
