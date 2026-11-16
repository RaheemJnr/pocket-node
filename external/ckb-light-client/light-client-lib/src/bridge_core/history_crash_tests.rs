//! Real SQLite commit-boundary interruption fixtures for matched indexing debt.
use super::*;
use crate::storage::{BatchWriter, FilterMapFn, IteratorDirection, IteratorStart, KVPair,
    LightClientStorage, ScriptStatus, ScriptType, SetScriptsCommand, StorageBackend, TakeWhileFn};
use ckb_types::core::{BlockBuilder, TransactionBuilder};
use std::sync::{Arc, atomic::{AtomicUsize, Ordering}};

struct CrashStorage {
    inner: Storage,
    commits: Arc<AtomicUsize>,
    stop: usize,
    after: bool,
}
struct CrashBatch {
    inner: <Storage as StorageBackend>::Batch,
    commits: Arc<AtomicUsize>,
    stop: usize,
    after: bool,
}
impl BatchWriter for CrashBatch {
    fn put(&mut self, key: &[u8], value: &[u8]) { self.inner.put(key, value); }
    fn delete(&mut self, key: &[u8]) { self.inner.delete(key); }
    fn compare_and_put(&mut self, key: &[u8], expected: Option<&[u8]>, value: &[u8]) -> crate::error::Result<()> {
        self.inner.compare_and_put(key, expected, value)
    }
    fn commit(self) -> crate::error::Result<()> {
        let at = self.commits.fetch_add(1, Ordering::SeqCst) + 1;
        assert!(at != self.stop || self.after, "interrupted before commit {at}");
        self.inner.commit()?;
        assert!(at != self.stop || !self.after, "interrupted after commit {at}");
        Ok(())
    }
}
impl StorageBackend for CrashStorage {
    type Batch = CrashBatch;
    fn get(&self, key: Vec<u8>) -> crate::error::Result<Option<Vec<u8>>> { StorageBackend::get(&self.inner, key) }
    fn put(&self, key: Vec<u8>, value: Vec<u8>) -> crate::error::Result<()> { StorageBackend::put(&self.inner, key, value) }
    fn delete(&self, key: &[u8]) -> crate::error::Result<()> { StorageBackend::delete(&self.inner, key) }
    fn batch(&self) -> Self::Batch { CrashBatch { inner: StorageBackend::batch(&self.inner), commits: self.commits.clone(), stop: self.stop, after: self.after } }
    fn collect_iterator(&self, start: IteratorStart, direction: IteratorDirection, take: TakeWhileFn, map: FilterMapFn, limit: usize) -> Vec<KVPair> {
        StorageBackend::collect_iterator(&self.inner, start, direction, take, map, limit)
    }
}
impl LightClientStorage for CrashStorage {}

struct Fixture {
    script: packed::Script,
    blocks: Vec<packed::Block>,
    funding: packed::Transaction,
    live: packed::Transaction,
    identity: RecoveryIdentity,
    chain: ChainSnapshot,
}
fn setup(storage: &Storage) -> Fixture {
    storage.init_genesis_block(BlockBuilder::default().build().data());
    let script = packed::Script::default();
    storage.update_filter_scripts(vec![ScriptStatus { script: script.clone(), script_type: ScriptType::Lock, block_number: 0 }], SetScriptsCommand::All);
    let output = |capacity| packed::CellOutput::new_builder().capacity(capacity).lock(script.clone()).build();
    let funding = TransactionBuilder::default().output(output(100u64)).output_data(Vec::<u8>::new().pack()).build().data();
    let spent = TransactionBuilder::default().input(packed::CellInput::new_builder()
        .previous_output(packed::OutPoint::new_builder().tx_hash(funding.calc_tx_hash()).index(0u32).build()).build()).build().data();
    let live = TransactionBuilder::default().output(output(101u64)).output_data(Vec::<u8>::new().pack()).build().data();
    let blocks: Vec<_> = [funding.clone(), spent, live.clone()].into_iter().enumerate().map(|(index, tx)| {
        BlockBuilder::default().epoch(ckb_types::core::EpochNumberWithFraction::new(0, 0, 100).full_value())
            .number(10u64 + index as u64).transaction(tx.into_view()).build().data()
    }).collect();
    let chain = ChainSnapshot { anchor: blocks[2].header() };
    storage.update_last_state(&ckb_types::U256::zero(), &chain.anchor, &[]);
    storage.add_matched_blocks(10, 3, blocks.iter().map(|b| (b.calc_header_hash(), true)).collect());
    storage.update_min_filtered_block_number(12);
    Fixture { script, blocks, funding, live, identity: RecoveryIdentity {
        genesis_hash: packed::Byte32::default(), store_generation: "crash-fixture".into(), run_nonce: [1; 16],
    }, chain }
}
fn cell(storage: &Storage, script: &packed::Script, number: u64) -> Option<Vec<u8>> {
    StorageBackend::get(storage, Key::CellLockScript(script, number, 0, 0).into_vec()).unwrap()
}
fn assert_final_and_undo(storage: &Storage, f: &Fixture) {
    let proof = snapshot(storage, &f.script, &f.identity, &f.chain, "TESTNET").unwrap();
    assert_eq!(proof["hashes"].as_array().unwrap().len(), 3);
    assert_eq!(proof["startHeight"], "0");
    assert_eq!(proof["durableCoverageVersion"], 1);
    assert!(cell(storage, &f.script, 10).is_none(), "spent funding must not resurrect on replay");
    assert_eq!(cell(storage, &f.script, 12).unwrap(), f.live.calc_tx_hash().as_slice());
    storage.rollback_to_block(11);
    assert_eq!(cell(storage, &f.script, 10).unwrap(), f.funding.calc_tx_hash().as_slice());
    assert!(cell(storage, &f.script, 12).is_none());
    storage.rollback_to_block(10);
    assert!(cell(storage, &f.script, 10).is_none());
    assert!(cell(storage, &f.script, 12).is_none());
    assert!(snapshot(storage, &f.script, &f.identity, &f.chain, "TESTNET").is_err());
}

#[test]
fn range_reopens_before_and_after_each_index_and_final_ack_commit() {
    // Commits 1..3 index funding, spend and new live output; commit 4 atomically
    // advances progress and deletes the debt. Each case closes every handle.
    for stop in 1..=4 {
        for after in [false, true] {
            let (storage, dir) = crate::tests::utils::new_storage("matched-range-crash");
            let f = setup(&storage);
            let crashing = CrashStorage { inner: storage, commits: Arc::new(AtomicUsize::new(0)), stop, after };
            let mut blocks = f.blocks.clone(); blocks.reverse();
            assert!(std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
                crashing.index_matched_range(10, 3, blocks);
            })).is_err());
            drop(crashing);
            let storage = Storage::new(dir.path().join("store.db"));
            if stop == 4 && after {
                assert!(storage.get_earliest_matched_blocks().is_none());
            } else {
                assert!(storage.get_earliest_matched_blocks().is_some(), "debt lost at commit {stop}, after={after}");
                assert!(snapshot(&storage, &f.script, &f.identity, &f.chain, "TESTNET").is_err());
                let prefix = [vec![KeyPrefix::CellLockScript as u8], extract_raw_data(&f.script)].concat();
                assert!(cells_ready(&storage, &prefix, &f.chain).is_err(), "partial live cells cannot authorize spending");
                storage.index_matched_range(10, 3, f.blocks.clone());
            }
            assert_final_and_undo(&storage, &f);
        }
    }
}

#[test]
fn discarded_ranges_reopen_with_coherent_rewind_before_and_after_commit() {
    for after in [false, true] {
        let (storage, dir) = crate::tests::utils::new_storage("discard-range-crash");
        let f = setup(&storage);
        storage.filter_block(f.blocks[0].clone());
        storage.filter_block(f.blocks[1].clone());
        let crashing = CrashStorage { inner: storage, commits: Arc::new(AtomicUsize::new(0)), stop: 1, after };
        assert!(std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| crashing.remove_matched_blocks(10))).is_err());
        drop(crashing);
        let storage = Storage::new(dir.path().join("store.db"));
        assert!(snapshot(&storage, &f.script, &f.identity, &f.chain, "TESTNET").is_err());
        if after {
            assert!(storage.get_earliest_matched_blocks().is_none());
            assert!(storage.get_min_filtered_block_number() < 10);
            storage.update_block_number(storage.get_min_filtered_block_number());
            assert!(snapshot(&storage, &f.script, &f.identity, &f.chain, "TESTNET").is_err());
            storage.add_matched_blocks(10, 3, f.blocks.iter().map(|b| (b.calc_header_hash(), true)).collect());
            storage.update_min_filtered_block_number(12);
        } else { assert!(storage.get_earliest_matched_blocks().is_some()); }
        storage.index_matched_range(10, 3, f.blocks.clone());
        assert_final_and_undo(&storage, &f);
    }
}

#[test]
fn discarding_before_index_keeps_old_live_cells_unavailable_until_spend_is_rebuilt() {
    let (storage, dir) = crate::tests::utils::new_storage("discard-before-index");
    let mut f = setup(&storage);
    let prior = TransactionBuilder::default().output(packed::CellOutput::new_builder()
        .capacity(99u64).lock(f.script.clone()).build()).output_data(Vec::<u8>::new().pack()).build();
    let prefix_block = BlockBuilder::default().epoch(ckb_types::core::EpochNumberWithFraction::new(0, 0, 100).full_value())
        .number(5u64).transaction(prior.clone()).build().data();
    storage.filter_block(prefix_block);
    storage.update_block_number(5);
    let spend = TransactionBuilder::default().input(packed::CellInput::new_builder()
        .previous_output(packed::OutPoint::new_builder().tx_hash(prior.hash()).index(0u32).build()).build()).build();
    f.blocks[0] = BlockBuilder::default().epoch(ckb_types::core::EpochNumberWithFraction::new(0, 0, 100).full_value())
        .number(10u64).transaction(spend).build().data();
    storage.add_matched_blocks(10, 3, f.blocks.iter().map(|b| (b.calc_header_hash(), true)).collect());
    storage.remove_matched_blocks(10);
    drop(storage);
    let storage = Storage::new(dir.path().join("store.db"));
    let prefix = [vec![KeyPrefix::CellLockScript as u8], extract_raw_data(&f.script)].concat();
    assert!(cell(&storage, &f.script, 5).is_some());
    assert!(cells_ready(&storage, &prefix, &f.chain).is_err(), "a discarded spend obligation must still fence prior live cells");
    storage.add_matched_blocks(10, 3, f.blocks.iter().map(|b| (b.calc_header_hash(), true)).collect());
    storage.update_min_filtered_block_number(12);
    storage.index_matched_range(10, 3, f.blocks);
    assert!(cell(&storage, &f.script, 5).is_none());
    assert!(cell(&storage, &f.script, 12).is_some());
    assert!(cells_ready(&storage, &prefix, &f.chain).is_ok());
    storage.rollback_to_block(10);
    assert_eq!(cell(&storage, &f.script, 5).unwrap(), prior.hash().as_slice());
    assert!(cell(&storage, &f.script, 12).is_none());
}

#[test]
fn startup_cleanup_discard_rewinds_missing_and_out_of_range_debt_after_reopen() {
    for missing in [true, false] {
        let (storage, dir) = crate::tests::utils::new_storage("cleanup-index-debt");
        let mut f = setup(&storage);
        if missing {
            let tip = BlockBuilder::default().epoch(ckb_types::core::EpochNumberWithFraction::new(0, 0, 3000).full_value())
                .number(2000u64).build().data().header();
            storage.update_last_state(&ckb_types::U256::zero(), &tip, &[]);
            f.chain.anchor = tip;
        } else {
            storage.add_fetched_header(&crate::storage::HeaderWithExtension { header: f.blocks[2].header(), extension: None });
            storage.add_matched_blocks(10, 1, vec![(f.blocks[2].calc_header_hash(), true)]);
        }
        storage.cleanup_invalid_matched_blocks();
        drop(storage);
        let storage = Storage::new(dir.path().join("store.db"));
        assert!(storage.get_earliest_matched_blocks().is_none());
        assert!(storage.get_min_filtered_block_number() < 10);
        storage.update_block_number(storage.get_min_filtered_block_number());
        assert!(snapshot(&storage, &f.script, &f.identity, &f.chain, "TESTNET").is_err());
        let prefix = [vec![KeyPrefix::CellLockScript as u8], extract_raw_data(&f.script)].concat();
        assert!(cells_ready(&storage, &prefix, &f.chain).is_err());
    }
}
