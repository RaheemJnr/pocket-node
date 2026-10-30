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
