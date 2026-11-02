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

fn history_rollback_at_replacement_tip(reinclude: bool) {
    use crate::storage::{ScriptStatus, ScriptType, SetScriptsCommand};
    use crate::protocols::light_client::recovery::{RecoveryRegistry, RecoveryIdentity, ChainSnapshot};
    use ckb_types::{core::{BlockBuilder, TransactionBuilder, EpochNumberWithFraction}, packed::CellOutput, prelude::*, U256};
    let (storage, _dir) = new_storage("history_rollback_at_tip");
    let script = Script::default();
    let tx = TransactionBuilder::default()
        .output(CellOutput::new_builder().lock(script.clone()).build())
        .output_data(Vec::<u8>::new().pack()).build();
    let block = |nonce| BlockBuilder::default()
        .epoch(EpochNumberWithFraction::new(0, 0, 100).full_value()).number(20u64).nonce(nonce);
    let original = block(0u128).transaction(tx.clone()).build();
    let replacement = if reinclude { block(1u128).transaction(tx.clone()).build() } else { block(1u128).build() };
    storage.update_filter_scripts(vec![ScriptStatus { script: script.clone(), script_type: ScriptType::Lock, block_number: 1 }], SetScriptsCommand::All);
    storage.filter_block(original.data());
    storage.update_block_number(20);
    storage.update_min_filtered_block_number(20);
    storage.update_last_state(&U256::from(1u64), &original.data().header(), &[]);
    let registry = RecoveryRegistry::new(
        RecoveryIdentity { genesis_hash: ckb_types::packed::Byte32::new([1;32]), store_generation: "fixture".into(), run_nonce: [1;16] },
        ChainSnapshot { anchor: original.data().header() });
    let snapshot = || registry.with_chain_snapshot(|identity, chain|
        crate::bridge_core::history::snapshot(&storage, &script, identity, chain, "TESTNET")).unwrap();
    let hash = format!("{:#x}", tx.hash());
    assert_eq!(snapshot().unwrap()["hashes"], serde_json::json!([hash.clone()]));

    let update = registry.begin_chain_update().unwrap();
    storage.rollback_to_block(20);
    storage.update_last_state(&U256::from(2u64), &replacement.data().header(), &[]);
    registry.finish_chain_update(update, ChainSnapshot { anchor: replacement.data().header() }).unwrap();
    assert_eq!(storage.get_min_filtered_block_number(), 19);
    assert!(snapshot().is_err(), "removed replacement tip is not scanned: no absence authority may reach the cache");

    // The single-block [20,21) filter batch must select this script, even
    // though the existing per-script rollback cursor is 20.
    assert!(storage.get_scripts_hash(21).contains(&script.calc_script_hash()));
    if reinclude {
        storage.add_matched_blocks(20, 1, vec![(replacement.hash(), true)]);
        storage.update_min_filtered_block_number(20);
        assert!(snapshot().is_err(), "filter cursor alone does not prove matched block processing");
    }
    storage.filter_block(replacement.data());
    storage.update_block_number(20);
    storage.update_min_filtered_block_number(20);
    if reinclude { storage.remove_matched_blocks(20); }
    let complete = snapshot().unwrap();
    assert_eq!(complete["tipHeight"], "20");
    assert_eq!(complete["tipHash"], format!("{:#x}", replacement.hash()));
    assert_eq!(complete["hashes"], if reinclude { serde_json::json!([hash]) } else { serde_json::json!([]) },
        "only scanned replacement membership can preserve reinclusion or authorize orphan cleanup");
}

#[test]
fn history_rollback_at_tip_withholds_absence_until_reincluded_transaction_is_indexed() {
    history_rollback_at_replacement_tip(true);
}

#[test]
fn history_rollback_at_tip_allows_absence_only_after_empty_replacement_scan() {
    history_rollback_at_replacement_tip(false);
}

#[test]
fn whole_review_rescan_must_preserve_cell_rollback_undo() {
    use crate::storage::{Key, StorageBackend, ScriptStatus, ScriptType, SetScriptsCommand};
    use ckb_types::{core::{BlockBuilder, TransactionBuilder, EpochNumberWithFraction}, packed::CellOutput, prelude::*};
    let (storage, _dir) = new_storage("whole_review_rescan_cells");
    let script = Script::default();
    let register = || storage.update_filter_scripts(vec![ScriptStatus { script: script.clone(), script_type: ScriptType::Lock, block_number: 1 }], SetScriptsCommand::Partial);
    register();
    let tx = TransactionBuilder::default().output(CellOutput::new_builder().lock(script.clone()).build()).output_data(Vec::<u8>::new().pack()).build();
    storage.filter_block(BlockBuilder::default().epoch(EpochNumberWithFraction::new(0,0,100).full_value()).number(10u64).transaction(tx.clone()).build().data());
    storage.update_block_number(20);
    storage.update_min_filtered_block_number(20);
    let key = Key::CellLockScript(&script, 10, 0, 0).into_vec();
    assert!(StorageBackend::get(&storage, key.clone()).unwrap().is_some());
    register(); // coherent rewind removes the orphan cell and its history together
    storage.rollback_to_block(10);
    assert!(StorageBackend::get(&storage, key).unwrap().is_none(), "orphan output must be removed even if rescan registration happened before reorg");
}

#[test]
fn whole_review_rescan_must_restore_orphan_spent_cell() {
    use crate::storage::{Key, StorageBackend, ScriptStatus, ScriptType, SetScriptsCommand};
    use ckb_types::{core::{BlockBuilder, TransactionBuilder, EpochNumberWithFraction}, packed::{CellInput, CellOutput, OutPoint}, prelude::*};
    for rescan in [false, true] {
        let (storage, _dir) = new_storage("whole_review_spent_cells");
        let script = Script::default();
        let register = || storage.update_filter_scripts(vec![ScriptStatus { script: script.clone(), script_type: ScriptType::Lock, block_number: 1 }], SetScriptsCommand::Partial);
        let block = |n| BlockBuilder::default().epoch(EpochNumberWithFraction::new(0,0,100).full_value()).number(n);
        register();
        let tx = TransactionBuilder::default().output(CellOutput::new_builder().lock(script.clone()).build()).output_data(Vec::<u8>::new().pack()).build();
        storage.filter_block(block(5u64).transaction(tx.clone()).build().data());
        let spend = TransactionBuilder::default().input(CellInput::new_builder().previous_output(OutPoint::new(tx.hash(),0)).build()).build();
        storage.filter_block(block(10u64).transaction(spend).build().data());
        storage.update_block_number(20);storage.update_min_filtered_block_number(20);
        let key = Key::CellLockScript(&script,5,0,0).into_vec();
        assert!(StorageBackend::get(&storage,key.clone()).unwrap().is_none());
        if rescan { register(); }
        storage.rollback_to_block(10);
        if rescan {
            // Registration at 1 rewinds creation at 5 too. Restore it by actual scanning.
            assert!(StorageBackend::get(&storage,key.clone()).unwrap().is_none());
            storage.filter_block(block(5u64).transaction(tx.clone()).build().data());
        }
        assert!(StorageBackend::get(&storage,key).unwrap().is_some(), "canonical creation must be available; rescan={rescan}");
    }
}

#[test]
fn history_registration_rewinds_lock_and_type_cells_in_dependency_order() {
    use crate::storage::{Key, StorageBackend, ScriptStatus, ScriptType, SetScriptsCommand};
    use ckb_types::{core::{BlockBuilder, TransactionBuilder, EpochNumberWithFraction}, packed::{CellInput, CellOutput, OutPoint}, prelude::*};
    for selected in &[ScriptType::Lock, ScriptType::Type] {
        let (storage, _dir) = new_storage("history_cell_prefix");
        let script = Script::default();
        let status = |script_type: &ScriptType, block_number| ScriptStatus { script: script.clone(), script_type: match script_type { ScriptType::Lock => ScriptType::Lock, ScriptType::Type => ScriptType::Type }, block_number };
        let block = |n| BlockBuilder::default().epoch(EpochNumberWithFraction::new(0,0,100).full_value()).number(n);
        let output = || CellOutput::new_builder().lock(script.clone()).type_(Some(script.clone()).pack()).build();
        let cell = |kind: &ScriptType, n| match kind {
            ScriptType::Lock => Key::CellLockScript(&script, n, 0, 0).into_vec(),
            ScriptType::Type => Key::CellTypeScript(&script, n, 0, 0).into_vec(),
        };
        let other = if *selected == ScriptType::Lock { &ScriptType::Type } else { &ScriptType::Lock };
        storage.update_filter_scripts(vec![status(&ScriptType::Lock, 1), status(&ScriptType::Type, 1)], SetScriptsCommand::All);
        let creation = TransactionBuilder::default().output(output()).output_data(Vec::<u8>::new().pack()).build();
        let spend = TransactionBuilder::default().input(CellInput::new_builder().previous_output(OutPoint::new(creation.hash(),0)).build())
            .output(output()).output_data(Vec::<u8>::new().pack()).build();
        let spend_again = TransactionBuilder::default().input(CellInput::new_builder().previous_output(OutPoint::new(spend.hash(),0)).build())
            .output(output()).output_data(Vec::<u8>::new().pack()).build();
        storage.filter_block(block(5u64).transaction(creation.clone()).build().data());
        storage.filter_block(block(10u64).transaction(spend.clone()).build().data());
        storage.filter_block(block(15u64).transaction(spend_again.clone()).build().data());
        storage.update_block_number(20);
        // A registration at the current cursor and the maximum start do not undo cells.
        for height in [20, u64::MAX] {
            storage.update_filter_scripts(vec![status(selected, height)], SetScriptsCommand::Partial);
            assert!(StorageBackend::get(&storage, cell(selected,15)).unwrap().is_some());
        }
        storage.update_filter_scripts(vec![status(selected, 5)], SetScriptsCommand::Partial);
        assert!(StorageBackend::get(&storage, cell(selected,5)).unwrap().is_some(), "exclusive boundary creation must be restored");
        assert!(StorageBackend::get(&storage, cell(selected,10)).unwrap().is_none(), "intermediate output must not survive reverse undo");
        assert!(StorageBackend::get(&storage, cell(selected,15)).unwrap().is_none());
        assert!(StorageBackend::get(&storage, cell(other,5)).unwrap().is_none());
        assert!(StorageBackend::get(&storage, cell(other,15)).unwrap().is_some(), "other script type remains untouched");
        assert!(storage.get_transaction(&spend.hash()).is_some());
        storage.rollback_to_block(10);
        assert!(StorageBackend::get(&storage, cell(selected,5)).unwrap().is_some());
        assert!(StorageBackend::get(&storage, cell(other,5)).unwrap().is_some());
        // Replacement omits old output15 and reincludes spend at11.
        storage.filter_block(block(11u64).transaction(spend.clone()).build().data());
        storage.filter_block(block(15u64).build().data());
        storage.update_block_number(20);
        for kind in &[ScriptType::Lock, ScriptType::Type] {
            assert!(StorageBackend::get(&storage, cell(kind,5)).unwrap().is_none());
            assert!(StorageBackend::get(&storage, cell(kind,10)).unwrap().is_none());
            assert!(StorageBackend::get(&storage, cell(kind,11)).unwrap().is_some());
            assert!(StorageBackend::get(&storage, cell(kind,15)).unwrap().is_none());
        }
    }
}

#[test]
fn history_rewind_cells_unavailable_until_actual_rebuild() {
    use crate::storage::{Key, KeyPrefix, StorageBackend, ScriptStatus, ScriptType, SetScriptsCommand, Storage};
    use crate::protocols::light_client::recovery::ChainSnapshot;
    use crate::bridge_core::history::cells_ready;
    use ckb_types::{core::{BlockBuilder, TransactionBuilder, EpochNumberWithFraction}, packed::{CellInput, CellOutput, OutPoint}, prelude::*, U256};
    for selected in &[ScriptType::Lock, ScriptType::Type] {
    let (storage, dir) = new_storage("history_rewind_availability");
    let script = Script::default().as_builder().args(vec![1u8, 2].pack()).build();
    let status = |n| ScriptStatus { script: script.clone(), script_type: match selected { ScriptType::Lock => ScriptType::Lock, ScriptType::Type => ScriptType::Type }, block_number: n };
    let block = |n| BlockBuilder::default().epoch(EpochNumberWithFraction::new(0,0,100).full_value()).number(n);
    let chain = ChainSnapshot { anchor: block(20u64).build().data().header() };
    storage.update_last_state(&U256::zero(), &chain.anchor, &[]);
    let prefix = [vec![match selected { ScriptType::Lock => KeyPrefix::CellLockScript as u8, ScriptType::Type => KeyPrefix::CellTypeScript as u8 }], crate::storage::extract_raw_data(&script)].concat();
    let type_prefix = [vec![match selected { ScriptType::Lock => KeyPrefix::CellTypeScript as u8, ScriptType::Type => KeyPrefix::CellLockScript as u8 }], crate::storage::extract_raw_data(&script)].concat();
    storage.update_filter_scripts(vec![status(1)], SetScriptsCommand::Partial);
    assert!(cells_ready(&storage, &prefix, &chain).is_ok(), "new registration is not a global sync gate");
    let tx = TransactionBuilder::default().output(CellOutput::new_builder().lock(script.clone()).type_(Some(script.clone()).pack()).build()).output_data(Vec::<u8>::new().pack()).build();
    let spend = TransactionBuilder::default().input(CellInput::new_builder().previous_output(OutPoint::new(tx.hash(),0)).build()).build();
    storage.filter_block(block(5u64).transaction(tx.clone()).build().data());
    storage.filter_block(block(10u64).transaction(spend.clone()).build().data());
    storage.update_block_number(20); storage.update_min_filtered_block_number(20);
    storage.update_filter_scripts(vec![status(20)], SetScriptsCommand::Partial);
    assert!(cells_ready(&storage, &prefix, &chain).is_ok(), "current-cursor registration is unchanged");
    storage.update_filter_scripts(vec![status(5)], SetScriptsCommand::Partial);
    assert!(StorageBackend::get(&storage, match selected { ScriptType::Lock => Key::CellLockScript(&script,5,0,0).into_vec(), ScriptType::Type => Key::CellTypeScript(&script,5,0,0).into_vec() }).unwrap().is_some());
    assert!(cells_ready(&storage, &prefix, &chain).is_err(), "restored spent input must be unavailable to cell/capacity queries");
    // Both public query paths enumerate starts_with(prefix), so a shorter-args
    // query would return this script's restored cells even without its own marker.
    let shorter_prefix = &prefix[..prefix.len() - 1];
    assert!(StorageBackend::get(&storage, [Key::Meta("CELL_REWIND_V1").into_vec(), shorter_prefix.to_vec()].concat()).unwrap().is_none());
    assert!(cells_ready(&storage, shorter_prefix, &chain).is_err(), "prefix query must guard the longer script it returns");
    assert!(cells_ready(&storage, &type_prefix, &chain).is_ok());
    let marker_key = [Key::Meta("CELL_REWIND_V1").into_vec(), prefix.clone()].concat();
    let marker = StorageBackend::get(&storage, marker_key.clone()).unwrap().unwrap();
    StorageBackend::put(&storage, marker_key.clone(), vec![0u8; 4]).unwrap();
    assert!(cells_ready(&storage, &prefix, &chain).is_err(), "malformed marker fails closed");
    StorageBackend::put(&storage, marker_key.clone(), marker).unwrap();
    drop(storage);
    let storage = Storage::new(dir.path().join("store.db"));
    assert!(cells_ready(&storage, &prefix, &chain).is_err(), "guard survives reopen");
    storage.update_filter_scripts(vec![status(20)], SetScriptsCommand::Partial);
    assert_eq!(storage.get_filter_scripts()[0].block_number, 5, "forward jump cannot skip repair");
    storage.update_filter_scripts(vec![status(20)], SetScriptsCommand::Delete);
    storage.update_filter_scripts(vec![status(20)], SetScriptsCommand::Partial);
    assert_eq!(storage.get_filter_scripts()[0].block_number, 5);
    assert!(cells_ready(&storage, &prefix, &chain).is_err());
    // A shorter canonical tip needs only its actual replacement coverage.
    let shorter = ChainSnapshot { anchor: block(8u64).build().data().header() };
    storage.rollback_to_block(8);
    storage.update_last_state(&U256::zero(), &shorter.anchor, &[]);
    assert!(cells_ready(&storage, &prefix, &shorter).is_err());
    storage.filter_block(block(8u64).build().data());
    storage.update_block_number(8);
    assert!(cells_ready(&storage, &prefix, &shorter).is_err(), "global coverage is independent");
    storage.update_min_filtered_block_number(8);
    storage.add_matched_blocks(8,1,vec![(ckb_types::packed::Byte32::default(),false)]);
    assert!(cells_ready(&storage, &prefix, &shorter).is_err());
    storage.remove_matched_blocks(8);
    assert!(cells_ready(&storage, &prefix, &shorter).is_ok());
    assert!(StorageBackend::get(&storage, marker_key).unwrap().is_none(), "completed guard retires durably");
    storage.update_filter_scripts(vec![status(8)], SetScriptsCommand::Partial);
    assert_eq!(storage.get_filter_scripts()[0].block_number, 8);
    // Rebuilding to the original frontier removes the restored spent cell.
    storage.update_last_state(&U256::zero(), &chain.anchor, &[]);
    storage.filter_block(block(10u64).transaction(spend).build().data());
    storage.update_block_number(20); storage.update_min_filtered_block_number(20);
    assert!(cells_ready(&storage, &prefix, &chain).is_ok());
    assert!(StorageBackend::get(&storage, match selected { ScriptType::Lock => Key::CellLockScript(&script,5,0,0).into_vec(), ScriptType::Type => Key::CellTypeScript(&script,5,0,0).into_vec() }).unwrap().is_none());
    }
}

#[test]
fn history_registration_does_not_rewind_scripts_with_longer_argument_prefix() {
    use crate::storage::{Key, StorageBackend, ScriptStatus, ScriptType, SetScriptsCommand, CellType};
    use ckb_types::{core::{BlockBuilder, TransactionBuilder, EpochNumberWithFraction}, packed::{CellInput, CellOutput, OutPoint}, prelude::*};
    let (storage, _dir) = new_storage("history_neighbor_script");
    let script = Script::default();
    let neighbor = script.clone().as_builder().args(vec![1u8].pack()).build();
    let status = |s: Script, n| ScriptStatus { script: s, script_type: ScriptType::Lock, block_number: n };
    storage.update_filter_scripts(vec![status(script.clone(),1),status(neighbor.clone(),1)], SetScriptsCommand::All);
    let tx = TransactionBuilder::default()
        .output(CellOutput::new_builder().lock(script.clone()).build()).output_data(Vec::<u8>::new().pack())
        .output(CellOutput::new_builder().lock(neighbor.clone()).build()).output_data(Vec::<u8>::new().pack()).build();
    storage.filter_block(BlockBuilder::default().epoch(EpochNumberWithFraction::new(0,0,100).full_value()).number(10u64).transaction(tx.clone()).build().data());
    let spend = TransactionBuilder::default().input(CellInput::new_builder().previous_output(OutPoint::new(tx.hash(),1)).build()).build();
    storage.filter_block(BlockBuilder::default().epoch(EpochNumberWithFraction::new(0,0,100).full_value()).number(15u64).transaction(spend).build().data());
    storage.update_block_number(20);storage.update_min_filtered_block_number(20);
    storage.update_filter_scripts(vec![status(script.clone(),1)], SetScriptsCommand::Partial);
    assert!(StorageBackend::get(&storage,Key::CellLockScript(&script,10,0,0).into_vec()).unwrap().is_none());
    assert!(StorageBackend::get(&storage,Key::CellLockScript(&script,10,0,1).into_vec()).unwrap().is_none(), "neighbor undo must not manufacture a cell under the selected script");
    assert!(StorageBackend::get(&storage,Key::CellLockScript(&neighbor,10,0,1).into_vec()).unwrap().is_none());
    assert!(StorageBackend::get(&storage,Key::TxLockScript(&neighbor,10,0,1,CellType::Output).into_vec()).unwrap().is_some());
}
