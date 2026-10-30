//! Ephemeral v1 authority for locally registered, independently verified membership.
//!
//! Lock order: owned peer/request snapshots -> registry -> synchronous storage batch.
//! Never acquire a peer guard from this module, or retain the mutex across an await.
//! Chain writers close authority before mutation and reopen with an update token.
use ckb_types::{packed, prelude::*};
#[cfg(not(target_arch = "wasm32"))]
pub use std::time::Instant;
use std::{
    collections::{HashMap, HashSet},
    sync::{Mutex, MutexGuard},
    time::Duration,
};
#[cfg(target_arch = "wasm32")]
pub use web_time::Instant;

pub const RECOVERY_SCHEMA_VERSION: u32 = 1;
const MAX_REQUESTS: usize = 128;
const REQUEST_LIFETIME: Duration = Duration::from_secs(30);

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct RecoveryIdentity {
    pub genesis_hash: packed::Byte32,
    pub store_generation: String,
    pub run_nonce: [u8; 16],
}
#[derive(Clone, Debug)]
pub struct ChainSnapshot {
    pub anchor: packed::Header,
}
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash, serde::Serialize, serde::Deserialize)]
/// Opaque operation identity. Bridges encode it as a token, not app-owned fields.
pub struct RequestId {
    run: [u8; 16],
    sequence: u64,
}
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash, serde::Serialize, serde::Deserialize)]
pub struct AttemptId {
    run: [u8; 16],
    sequence: u64,
}
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash, serde::Serialize, serde::Deserialize)]
pub struct ReceiptId {
    request: RequestId,
    attempt: AttemptId,
}
#[derive(Clone, Debug)]
pub struct RecoveryReceipt {
    pub schema_version: u32,
    pub id: ReceiptId,
    pub identity: RecoveryIdentity,
    pub request_id: RequestId,
    pub attempt_id: AttemptId,
    pub chain: ChainSnapshot,
    pub revision: u64,
    /// Membership authenticates raw fields, not arbitrary supplied witness bytes.
    pub transaction: packed::RawTransaction,
    pub header: packed::Header,
    /// Unknown for SendTransactionsProof: its CBMT indices are heap positions.
    pub transaction_index: Option<u32>,
}
impl PartialEq for ChainSnapshot {
    fn eq(&self, other: &Self) -> bool {
        self.anchor.as_slice() == other.anchor.as_slice()
    }
}
impl Eq for ChainSnapshot {}
impl PartialEq for RecoveryReceipt {
    fn eq(&self, other: &Self) -> bool {
        self.schema_version == other.schema_version
            && self.id == other.id
            && self.identity == other.identity
            && self.request_id == other.request_id
            && self.attempt_id == other.attempt_id
            && self.chain == other.chain
            && self.revision == other.revision
            && self.transaction.as_slice() == other.transaction.as_slice()
            && self.header.as_slice() == other.header.as_slice()
            && self.transaction_index == other.transaction_index
    }
}
impl Eq for RecoveryReceipt {}

#[derive(Clone, Debug)]
pub(crate) struct RecoveryAttempt {
    pub(crate) id: AttemptId,
    pub(crate) session: [u8; 16],
    snapshot: ChainSnapshot,
    revision: u64,
    recipients: Vec<RequestId>,
}
#[derive(Clone, Debug)]
pub(crate) struct ProvenTransaction {
    pub transaction: packed::Transaction,
    pub header: packed::Header,
}
#[derive(Clone, Copy, Debug)]
pub(crate) struct ChainUpdate {
    revision: u64,
}
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum RecoveryError {
    Unavailable,
    Capacity,
    UnknownRequest,
    Expired,
    SequenceExhausted,
    Storage,
    InvalidReceipt,
}
/// V1 status. Only Verified carries authority, and it must be revalidated at use.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum RecoveryStatus {
    Queued,
    Fetching,
    Verified(RecoveryReceipt),
    Unavailable,
    Expired,
    Cancelled,
}
struct Entry {
    hash: packed::Byte32,
    deadline: Instant,
    attempt: Option<AttemptId>,
    status: RecoveryStatus,
}
#[derive(Default)]
struct State {
    identity: Option<RecoveryIdentity>,
    snapshot: Option<ChainSnapshot>,
    running: bool,
    updating: bool,
    revision: u64,
    sequence: u64,
    entries: HashMap<RequestId, Entry>,
}
impl State {
    fn available(&self) -> bool {
        self.running && !self.updating && self.snapshot.is_some()
    }
    fn next(&mut self) -> Result<u64, RecoveryError> {
        match self.sequence.checked_add(1) {
            Some(next) => {
                self.sequence = next;
                Ok(next)
            }
            None => {
                self.running = false;
                self.entries.clear();
                Err(RecoveryError::SequenceExhausted)
            }
        }
    }
    fn expire(&mut self, now: Instant) {
        for entry in self.entries.values_mut() {
            if now >= entry.deadline {
                entry.status = RecoveryStatus::Expired;
            }
        }
    }
}
#[derive(Default)]
pub struct RecoveryRegistry {
    state: Mutex<State>,
}
impl RecoveryRegistry {
    fn lock(&self) -> Result<MutexGuard<'_, State>, RecoveryError> {
        self.state.lock().map_err(|_| RecoveryError::Unavailable)
    }
    pub(crate) fn initialize(
        &self,
        identity: RecoveryIdentity,
        snapshot: ChainSnapshot,
    ) -> Result<(), RecoveryError> {
        let mut state = self.lock()?;
        if state.identity.is_some() || identity.store_generation.is_empty() {
            return Err(RecoveryError::Unavailable);
        }
        state.identity = Some(identity);
        state.snapshot = Some(snapshot);
        state.running = true;
        Ok(())
    }
    /// Freeze canonical chain identity while a bounded synchronous storage read runs.
    /// Lock order is registry then storage, identical to recovery persistence.
    pub(crate) fn with_chain_snapshot<T>(&self, read: impl FnOnce(&RecoveryIdentity, &ChainSnapshot) -> T) -> Result<T, RecoveryError> {
        self.try_with_chain_snapshot(read)?.ok_or(RecoveryError::Unavailable)
    }

    /// None means contention only; unavailable authority and poisoned state fail immediately.
    pub(crate) fn try_with_chain_snapshot<T>(&self, read: impl FnOnce(&RecoveryIdentity, &ChainSnapshot) -> T) -> Result<Option<T>, RecoveryError> {
        let state = match self.state.try_lock() {
            Ok(state) => state,
            Err(std::sync::TryLockError::WouldBlock) => return Ok(None),
            Err(std::sync::TryLockError::Poisoned(_)) => return Err(RecoveryError::Unavailable),
        };
        if !state.available() { return Err(RecoveryError::Unavailable); }
        Ok(Some(read(state.identity.as_ref().ok_or(RecoveryError::Unavailable)?,
            state.snapshot.as_ref().ok_or(RecoveryError::Unavailable)?)))
    }

    /// A binding snapshot for bridge registration, never authorization by itself.
    pub fn identity(&self) -> Result<RecoveryIdentity, RecoveryError> {
        let state = self.lock()?;
        if !state.available() {
            return Err(RecoveryError::Unavailable);
        }
        state.identity.clone().ok_or(RecoveryError::Unavailable)
    }
    pub fn new(identity: RecoveryIdentity, snapshot: ChainSnapshot) -> Self {
        let registry = Self::default();
        // A rejected identity leaves the registry unavailable.
        let _ = registry.initialize(identity, snapshot);
        registry
    }
    pub fn request(
        &self,
        tx_hash: packed::Byte32,
        now: Instant,
    ) -> Result<RequestId, RecoveryError> {
        let mut state = self.lock()?;
        if !state.available() {
            return Err(RecoveryError::Unavailable);
        }
        let effective_now = now.max(Instant::now());
        state
            .entries
            .retain(|_, entry| effective_now < entry.deadline);
        if state.entries.len() >= MAX_REQUESTS {
            return Err(RecoveryError::Capacity);
        }
        let sequence = state.next()?;
        let id = RequestId {
            run: state
                .identity
                .as_ref()
                .ok_or(RecoveryError::Unavailable)?
                .run_nonce,
            sequence,
        };
        let deadline = now
            .checked_add(REQUEST_LIFETIME)
            .ok_or(RecoveryError::Unavailable)?;
        state.entries.insert(
            id,
            Entry {
                hash: tx_hash,
                deadline,
                attempt: None,
                status: RecoveryStatus::Queued,
            },
        );
        Ok(id)
    }
    pub fn poll(&self, id: RequestId, now: Instant) -> Result<RecoveryStatus, RecoveryError> {
        let mut state = self.lock()?;
        state.expire(now.max(Instant::now()));
        let entry = state
            .entries
            .get(&id)
            .ok_or(RecoveryError::UnknownRequest)?;
        Ok(entry.status.clone())
    }
    /// Point-in-time validation. Call again at each later authorization boundary.
    pub fn validate(
        &self,
        id: RequestId,
        receipt: ReceiptId,
        now: Instant,
    ) -> Result<RecoveryReceipt, RecoveryError> {
        let mut state = self.lock()?;
        state.expire(now.max(Instant::now()));
        if !state.available() {
            return Err(RecoveryError::Unavailable);
        }
        let entry = state
            .entries
            .get(&id)
            .ok_or(RecoveryError::UnknownRequest)?;
        match &entry.status {
            RecoveryStatus::Verified(r)
                if r.id == receipt
                    && r.request_id == id
                    && Some(&r.identity) == state.identity.as_ref()
                    && Some(&r.chain) == state.snapshot.as_ref()
                    && r.revision == state.revision
                    && Some(r.attempt_id) == entry.attempt
                    && r.transaction.calc_tx_hash() == entry.hash =>
            {
                Ok(r.clone())
            }
            RecoveryStatus::Expired => Err(RecoveryError::Expired),
            _ => Err(RecoveryError::InvalidReceipt),
        }
    }
    pub fn cancel(&self, id: RequestId) -> Result<(), RecoveryError> {
        let mut state = self.lock()?;
        let entry = state
            .entries
            .get_mut(&id)
            .ok_or(RecoveryError::UnknownRequest)?;
        entry.status = RecoveryStatus::Cancelled;
        Ok(())
    }
    pub fn revoke_run(&self) {
        if let Ok(mut state) = self.lock() {
            state.running = false;
            state.entries.clear();
        }
    }
    pub(crate) fn begin_chain_update(&self) -> Result<ChainUpdate, RecoveryError> {
        let mut state = self.lock()?;
        if !state.available() {
            return Err(RecoveryError::Unavailable);
        }
        state.updating = true;
        state.revision = match state.revision.checked_add(1) {
            Some(revision) => revision,
            None => {
                state.running = false;
                state.entries.clear();
                return Err(RecoveryError::SequenceExhausted);
            }
        };
        for entry in state.entries.values_mut() {
            if !matches!(
                entry.status,
                RecoveryStatus::Cancelled | RecoveryStatus::Expired
            ) {
                entry.status = RecoveryStatus::Unavailable;
            }
        }
        Ok(ChainUpdate {
            revision: state.revision,
        })
    }
    pub(crate) fn finish_chain_update(
        &self,
        update: ChainUpdate,
        snapshot: ChainSnapshot,
    ) -> Result<(), RecoveryError> {
        let mut state = self.lock()?;
        if !state.running || !state.updating || state.revision != update.revision {
            return Err(RecoveryError::Unavailable);
        }
        state.snapshot = Some(snapshot);
        state.updating = false;
        Ok(())
    }
    pub(crate) fn queued_hashes(&self, now: Instant) -> Vec<packed::Byte32> {
        let Ok(mut state) = self.lock() else {
            return vec![];
        };
        state.expire(now.max(Instant::now()));
        if !state.available() {
            return vec![];
        }
        state
            .entries
            .values()
            .filter(|e| e.status == RecoveryStatus::Queued)
            .map(|e| e.hash.clone())
            .collect::<HashSet<_>>()
            .into_iter()
            .collect()
    }
    pub(crate) fn freeze(
        &self,
        hashes: &[packed::Byte32],
        session: [u8; 16],
        anchor: &packed::Header,
        now: Instant,
    ) -> Result<Option<RecoveryAttempt>, RecoveryError> {
        let mut state = self.lock()?;
        state.expire(now.max(Instant::now()));
        if !state.available()
            || state.snapshot.as_ref().map(|s| s.anchor.as_slice()) != Some(anchor.as_slice())
        {
            return Ok(None);
        }
        let recipients: Vec<_> = state
            .entries
            .iter()
            .filter(|(_, e)| e.status == RecoveryStatus::Queued && hashes.contains(&e.hash))
            .map(|(id, _)| *id)
            .collect();
        if recipients.is_empty() {
            return Ok(None);
        }
        let sequence = state.next()?;
        let id = AttemptId {
            run: state
                .identity
                .as_ref()
                .ok_or(RecoveryError::Unavailable)?
                .run_nonce,
            sequence,
        };
        for recipient in &recipients {
            let e = state.entries.get_mut(recipient).unwrap();
            e.attempt = Some(id);
            e.status = RecoveryStatus::Fetching;
        }
        Ok(Some(RecoveryAttempt {
            id,
            session,
            snapshot: state.snapshot.clone().ok_or(RecoveryError::Unavailable)?,
            revision: state.revision,
            recipients,
        }))
    }
    pub(crate) fn fail_attempt(&self, attempt: &RecoveryAttempt) {
        if let Ok(mut state) = self.lock() {
            for id in &attempt.recipients {
                if let Some(e) = state.entries.get_mut(id) {
                    if e.attempt == Some(attempt.id) && e.status == RecoveryStatus::Fetching {
                        e.status = RecoveryStatus::Unavailable;
                    }
                }
            }
        }
    }
    /// The caller has fully verified membership. The callback persists exactly once,
    /// under the revision lock, before any success is visible. `active` controls
    /// recovery-only writes; generic fetches may still persist when it is false.
    pub(crate) fn publish(
        &self,
        attempt: &RecoveryAttempt,
        proof: &[ProvenTransaction],
        now: Instant,
        persist: impl FnOnce(bool) -> Result<(), RecoveryError>,
    ) -> Result<bool, RecoveryError> {
        let mut state = self.lock()?;
        state.expire(now.max(Instant::now()));
        let active = state.available()
            && state.revision == attempt.revision
            && state.snapshot.as_ref() == Some(&attempt.snapshot)
            && state.identity.as_ref().map(|i| i.run_nonce) == Some(attempt.id.run)
            && attempt.recipients.iter().any(|id| {
                state.entries.get(id).is_some_and(|e| {
                    e.attempt == Some(attempt.id) && e.status == RecoveryStatus::Fetching
                })
            });
        if let Err(error) = persist(active) {
            for id in &attempt.recipients {
                if let Some(e) = state.entries.get_mut(id) {
                    if e.attempt == Some(attempt.id) && e.status == RecoveryStatus::Fetching {
                        e.status = RecoveryStatus::Unavailable;
                    }
                }
            }
            return Err(error);
        }
        // A synchronous storage stall cannot extend the registration deadline.
        state.expire(now.max(Instant::now()));
        let active = active
            && attempt.recipients.iter().any(|id| {
                state.entries.get(id).is_some_and(|e| {
                    e.attempt == Some(attempt.id) && e.status == RecoveryStatus::Fetching
                })
            });
        if !active {
            return Ok(false);
        }
        let identity = state.identity.clone().ok_or(RecoveryError::Unavailable)?;
        for id in &attempt.recipients {
            if let Some(entry) = state.entries.get_mut(id) {
                if entry.attempt != Some(attempt.id) || entry.status != RecoveryStatus::Fetching {
                    continue;
                }
                entry.status = match proof
                    .iter()
                    .find(|p| p.transaction.calc_tx_hash() == entry.hash)
                {
                    Some(p) => RecoveryStatus::Verified(RecoveryReceipt {
                        schema_version: RECOVERY_SCHEMA_VERSION,
                        id: ReceiptId {
                            request: *id,
                            attempt: attempt.id,
                        },
                        identity: identity.clone(),
                        request_id: *id,
                        attempt_id: attempt.id,
                        chain: attempt.snapshot.clone(),
                        revision: attempt.revision,
                        transaction: p.transaction.raw(),
                        header: p.header.clone(),
                        transaction_index: None,
                    }),
                    None => RecoveryStatus::Unavailable,
                };
            }
        }
        Ok(true)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::time::Duration;
    fn registry() -> RecoveryRegistry {
        RecoveryRegistry::new(
            RecoveryIdentity {
                genesis_hash: Default::default(),
                store_generation: "synthetic-store".into(),
                run_nonce: [1; 16],
            },
            ChainSnapshot {
                anchor: Default::default(),
            },
        )
    }
    #[test]
    fn recovery_new_calls_never_inherit_authority() {
        let r = registry();
        let now = Instant::now();
        let a = r.request(Default::default(), now).unwrap();
        let b = r.request(Default::default(), now).unwrap();
        assert_ne!(a, b);
        assert_eq!(r.poll(a, now).unwrap(), RecoveryStatus::Queued);
        assert_eq!(r.poll(b, now).unwrap(), RecoveryStatus::Queued);
    }
    #[test]
    fn recovery_bound_expiry_and_eviction() {
        let r = registry();
        let now = Instant::now();
        let first = r.request(Default::default(), now).unwrap();
        for _ in 1..128 {
            r.request(Default::default(), now).unwrap();
        }
        assert_eq!(
            r.request(Default::default(), now),
            Err(RecoveryError::Capacity)
        );
        assert_eq!(
            r.poll(first, now + Duration::from_secs(30)).unwrap(),
            RecoveryStatus::Expired
        );
        assert!(r
            .request(Default::default(), now + Duration::from_secs(30))
            .is_ok());
    }
    #[test]
    fn recovery_cancel_and_stop_are_terminal() {
        let r = registry();
        let now = Instant::now();
        let id = r.request(Default::default(), now).unwrap();
        r.cancel(id).unwrap();
        assert_eq!(r.poll(id, now).unwrap(), RecoveryStatus::Cancelled);
        r.revoke_run();
        assert_eq!(
            r.request(Default::default(), now),
            Err(RecoveryError::Unavailable)
        );
        assert!(!matches!(
            r.poll(id, now),
            Ok(RecoveryStatus::Queued | RecoveryStatus::Fetching)
        ));
    }
    fn dispatched(
        r: &RecoveryRegistry,
        now: Instant,
    ) -> (RequestId, RecoveryAttempt, ProvenTransaction) {
        let tx = packed::Transaction::default();
        let id = r.request(tx.calc_tx_hash(), now).unwrap();
        let attempt = r
            .freeze(
                &[tx.calc_tx_hash()],
                [2; 16],
                &packed::Header::default(),
                now,
            )
            .unwrap()
            .unwrap();
        (
            id,
            attempt,
            ProvenTransaction {
                transaction: tx,
                header: packed::Header::default(),
            },
        )
    }
    #[test]
    fn recovery_only_frozen_recipients_receive_receipts() {
        let r = registry();
        let now = Instant::now();
        let (id, attempt, proof) = dispatched(&r, now);
        let later = r.request(proof.transaction.calc_tx_hash(), now).unwrap();
        assert!(r
            .publish(&attempt, &[proof.clone()], now, |active| {
                assert!(active);
                Ok(())
            })
            .unwrap());
        let receipt = match r.poll(id, now).unwrap() {
            RecoveryStatus::Verified(receipt) => receipt,
            other => panic!("{other:?}"),
        };
        assert_eq!(receipt.schema_version, 1);
        assert_eq!(
            receipt.transaction.as_slice(),
            proof.transaction.raw().as_slice()
        );
        assert_eq!(receipt.header.as_slice(), proof.header.as_slice());
        assert_eq!(receipt.transaction_index, None);
        assert_eq!(r.validate(id, receipt.id, now).unwrap(), receipt);
        assert!(r.validate(later, receipt.id, now).is_err());
        assert_eq!(r.poll(later, now).unwrap(), RecoveryStatus::Queued);
    }
    #[test]
    fn recovery_storage_failure_cannot_publish() {
        let r = registry();
        let now = Instant::now();
        let (id, attempt, proof) = dispatched(&r, now);
        assert_eq!(
            r.publish(&attempt, &[proof], now, |_| Err(RecoveryError::Storage)),
            Err(RecoveryError::Storage)
        );
        assert_eq!(r.poll(id, now).unwrap(), RecoveryStatus::Unavailable);
    }
    #[test]
    fn recovery_tip_change_invalidates_queued_inflight_and_verified() {
        for publish in [false, true] {
            let r = registry();
            let now = Instant::now();
            let (id, attempt, proof) = dispatched(&r, now);
            let queued = r.request(Default::default(), now).unwrap();
            let receipt = if publish {
                r.publish(&attempt, &[proof.clone()], now, |_| Ok(()))
                    .unwrap();
                match r.poll(id, now).unwrap() {
                    RecoveryStatus::Verified(r) => Some(r),
                    _ => panic!("missing receipt"),
                }
            } else {
                None
            };
            let update = r.begin_chain_update().unwrap();
            assert_eq!(
                r.request(Default::default(), now),
                Err(RecoveryError::Unavailable)
            );
            assert_eq!(r.poll(id, now).unwrap(), RecoveryStatus::Unavailable);
            assert_eq!(r.poll(queued, now).unwrap(), RecoveryStatus::Unavailable);
            r.finish_chain_update(
                update,
                ChainSnapshot {
                    anchor: Default::default(),
                },
            )
            .unwrap();
            assert!(!r
                .publish(&attempt, &[proof], now, |active| {
                    assert!(!active);
                    Ok(())
                })
                .unwrap());
            if let Some(receipt) = receipt {
                assert!(r.validate(id, receipt.id, now).is_err());
            }
        }
    }
    #[test]
    fn recovery_foreign_run_and_identity_fail_closed() {
        let r = registry();
        let now = Instant::now();
        let (id, attempt, proof) = dispatched(&r, now);
        r.publish(&attempt, &[proof], now, |_| Ok(())).unwrap();
        let receipt = match r.poll(id, now).unwrap() {
            RecoveryStatus::Verified(r) => r,
            _ => panic!("missing receipt"),
        };
        let foreign = RecoveryRegistry::new(
            RecoveryIdentity {
                run_nonce: [3; 16],
                ..receipt.identity.clone()
            },
            receipt.chain.clone(),
        );
        assert!(foreign.validate(id, receipt.id, now).is_err());
        assert!(r
            .validate(id, receipt.id, now + Duration::from_secs(30))
            .is_err());
        r.revoke_run();
        assert!(r.validate(id, receipt.id, now).is_err());
    }

    #[test]
    fn recovery_expiry_during_storage_does_not_publish_authority() {
        let r = registry();
        let now = Instant::now();
        let (id, attempt, proof) = dispatched(&r, now);
        r.state
            .lock()
            .unwrap()
            .entries
            .get_mut(&id)
            .unwrap()
            .deadline = now + Duration::from_millis(5);
        let published = r
            .publish(&attempt, &[proof], now, |_| {
                std::thread::sleep(Duration::from_millis(15));
                Ok(())
            })
            .unwrap();
        assert!(!published, "storage delay must not extend receipt lifetime");
        assert_eq!(r.poll(id, now).unwrap(), RecoveryStatus::Expired);
    }
    #[test]
    fn recovery_sequences_fail_closed_before_wraparound() {
        for revision in [false, true] {
            let r = registry();
            let now = Instant::now();
            let (id, attempt, proof) = dispatched(&r, now);
            r.publish(&attempt, &[proof], now, |_| Ok(())).unwrap();
            let receipt = match r.poll(id, now).unwrap() {
                RecoveryStatus::Verified(r) => r,
                _ => panic!(),
            };
            if revision {
                r.state.lock().unwrap().revision = u64::MAX;
                assert!(matches!(
                    r.begin_chain_update(),
                    Err(RecoveryError::SequenceExhausted)
                ));
            } else {
                r.state.lock().unwrap().sequence = u64::MAX;
                assert_eq!(
                    r.request(Default::default(), now),
                    Err(RecoveryError::SequenceExhausted)
                );
            }
            assert!(r.validate(id, receipt.id, now).is_err());
            assert_eq!(
                r.request(Default::default(), now),
                Err(RecoveryError::Unavailable)
            );
        }
    }
    #[test]
    fn recovery_poison_and_wrong_identity_fail_closed() {
        let r = registry();
        let now = Instant::now();
        let (id, attempt, proof) = dispatched(&r, now);
        r.publish(&attempt, &[proof], now, |_| Ok(())).unwrap();
        let receipt = match r.poll(id, now).unwrap() {
            RecoveryStatus::Verified(r) => r,
            _ => panic!(),
        };
        for identity in [
            RecoveryIdentity {
                store_generation: "wrong-store".into(),
                ..receipt.identity.clone()
            },
            RecoveryIdentity {
                run_nonce: [9; 16],
                ..receipt.identity.clone()
            },
            RecoveryIdentity {
                genesis_hash: ckb_types::h256!("0x1").pack(),
                ..receipt.identity.clone()
            },
        ] {
            r.state.lock().unwrap().identity = Some(identity);
            assert!(r.validate(id, receipt.id, now).is_err());
        }
        let _ = std::panic::catch_unwind(|| {
            let _guard = r.state.lock().unwrap();
            panic!("injected poison");
        });
        assert_eq!(
            r.request(Default::default(), now),
            Err(RecoveryError::Unavailable)
        );
        assert!(r.poll(id, now).is_err());
        assert!(r.queued_hashes(now).is_empty());
    }
    #[test]
    fn recovery_chain_update_waits_for_storage_then_revokes_publication() {
        use std::sync::{mpsc, Arc};
        let r = Arc::new(registry());
        let now = Instant::now();
        let (id, attempt, proof) = dispatched(&r, now);
        let (start, receive) = mpsc::channel();
        let other = r.clone();
        let writer = std::thread::spawn(move || {
            receive.recv().unwrap();
            other.begin_chain_update().unwrap()
        });
        r.publish(&attempt, &[proof], now, |_| {
            start.send(()).unwrap();
            Ok(())
        })
        .unwrap();
        let update = writer.join().unwrap();
        assert_eq!(
            r.poll(id, Instant::now()).unwrap(),
            RecoveryStatus::Unavailable
        );
        r.finish_chain_update(
            update,
            ChainSnapshot {
                anchor: Default::default(),
            },
        )
        .unwrap();
        assert_eq!(
            r.poll(id, Instant::now()).unwrap(),
            RecoveryStatus::Unavailable
        );
    }
}
