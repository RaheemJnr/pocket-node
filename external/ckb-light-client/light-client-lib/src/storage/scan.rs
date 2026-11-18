//! Durable, script-scoped scan work. Callers hold the registration/chain write
//! guard across reads and commits. A received range is not settled progress.
//! Cryptographic filter/proof verification precedes these storage operations;
//! matched blocks must be scoped and indexed before `finish_scan_range`.
use super::{
    BatchWriter, IteratorDirection, IteratorStart, Key, LightClientStorage, ScriptType,
    StorageBackend,
};
use crate::error::{Error, Result};
use ckb_types::{
    packed::{Block, Script},
    prelude::*,
};
use serde::{Deserialize, Serialize};
use std::collections::HashSet;

pub(crate) const SCAN_STATE: &str = "SCAN_STATE_V2";
pub(crate) const SCAN_EPOCH: &str = "SCAN_EPOCH_V2";

fn invalid() -> Error {
    Error::runtime("invalid or stale scoped scan work")
}
fn key(name: &str, suffix: &[u8]) -> Vec<u8> {
    [Key::Meta(name).into_vec(), suffix.to_vec()].concat()
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub(crate) struct ScanIdentity {
    pub suffix: Vec<u8>,
    pub registration: Vec<u8>,
}

impl ScanIdentity {
    pub fn registered<S: StorageBackend + ?Sized>(
        store: &S,
        script: &Script,
        kind: ScriptType,
    ) -> Result<Self> {
        let suffix = [
            script.as_slice(),
            &[match kind {
                ScriptType::Lock => 0,
                ScriptType::Type => 1,
            }],
        ]
        .concat();
        let registration = store
            .get(key("HISTORY_REGISTRATION_V1", &suffix))?
            .ok_or_else(invalid)?;
        let identity = Self {
            suffix,
            registration,
        };
        identity.validate(store)?;
        Ok(identity)
    }

    fn validate<S: StorageBackend + ?Sized>(&self, store: &S) -> Result<()> {
        if self.registration.len() != 24
            || self.suffix.len() < 2
            || !matches!(self.suffix.last(), Some(0 | 1))
            || Script::from_slice(&self.suffix[..self.suffix.len() - 1]).is_err()
            || store
                .get(key("HISTORY_REGISTRATION_V1", &self.suffix))?
                .as_ref()
                != Some(&self.registration)
            || store.get(key("FILTER_SCRIPTS", &self.suffix))?.is_none()
        {
            return Err(invalid());
        }
        Ok(())
    }
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub(crate) struct ScanRange {
    pub id: [u8; 16],
    pub start: u64,
    pub end: u64,
    pub anchor: [u8; 32],
    pub matches: Vec<[u8; 32]>,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub(crate) struct ScanState {
    pub identity: ScanIdentity,
    pub epoch: [u8; 16],
    pub floor: u64,
    pub settled: u64,
    pub pending: Option<ScanRange>,
}

impl ScanState {
    pub fn settled(identity: ScanIdentity, epoch: [u8; 16], floor: u64, settled: u64) -> Self {
        Self {
            identity,
            epoch,
            floor,
            settled,
            pending: None,
        }
    }

    pub(crate) fn validate(&self) -> Result<()> {
        if self.floor > self.settled {
            return Err(invalid());
        }
        if let Some(range) = &self.pending {
            if self.settled.checked_add(1) != Some(range.start)
                || range.end < range.start
                || range.matches.is_empty()
                || range.matches.iter().collect::<HashSet<_>>().len() != range.matches.len()
            {
                return Err(invalid());
            }
        }
        Ok(())
    }
}

pub(crate) trait ScanStorage: LightClientStorage {
    /// Startup migration gate. All rewinds, registrations, scoped cursors and
    /// legacy-debt invalidation commit together. Genesis repair is separately
    /// fenced and idempotent if the process stops between its two commits.
    fn prepare_scoped_scans(&self) -> Result<()> {
        if self.get(Key::Meta(SCAN_EPOCH).into_vec())?.is_some() {
            if self.get_earliest_matched_blocks().is_some() {
                return Err(invalid());
            }
            self.finish_scoped_genesis()?;
            self.scan_states()?;
            return Ok(());
        }
        let epoch = rand::random();
        let mut batch = self.batch();
        let mut minimum = None::<u64>;
        for mut status in self.get_filter_scripts() {
            let coverage = self.history_segment_coverage(&status);
            let (floor, through) = coverage.unwrap_or((0, 0));
            status.block_number = through;
            if let Some(first) = through.checked_add(1) {
                self.rollback_script_to_block(&status, first, &mut batch);
            }
            let suffix = [
                status.script.as_slice(),
                &[match status.script_type {
                    ScriptType::Lock => 0,
                    ScriptType::Type => 1,
                }],
            ]
            .concat();
            let nonce: [u8; 16] = rand::random();
            let registration = [floor.to_be_bytes().to_vec(), nonce.to_vec()].concat();
            let state = ScanState::settled(
                ScanIdentity {
                    suffix: suffix.clone(),
                    registration: registration.clone(),
                },
                epoch,
                floor,
                through,
            );
            batch.put(&key("FILTER_SCRIPTS", &suffix), &through.to_be_bytes());
            batch.put(&key("HISTORY_REGISTRATION_V1", &suffix), &registration);
            batch.put(&key("HISTORY_SEGMENT_V1", &suffix), &registration);
            batch.put(
                &key(SCAN_STATE, &suffix),
                &serde_json::to_vec(&state).map_err(Error::runtime)?,
            );
            if coverage.is_none() {
                batch.put(&key("HISTORY_GENESIS_PENDING_V1", &suffix), &nonce);
            }
            minimum = Some(minimum.map_or(through, |n| n.min(through)));
        }
        let prefix = Key::Meta(super::MATCHED_FILTER_BLOCKS_KEY).into_vec();
        for entry in self.collect_iterator(
            IteratorStart::From(prefix.clone()),
            IteratorDirection::Forward,
            Box::new(move |k| k.starts_with(&prefix)),
            Box::new(|_, v| Some(v.to_vec())),
            usize::MAX,
        ) {
            batch.delete(&entry.key);
        }
        if let Some(minimum) = minimum {
            batch.put(
                &Key::Meta(super::MIN_FILTERED_BLOCK_NUMBER).into_vec(),
                &minimum.to_le_bytes(),
            );
        }
        batch.put(&Key::Meta(SCAN_EPOCH).into_vec(), &epoch);
        batch.delete(&Key::Meta("SCAN_MIGRATION_REQUIRED_V2").into_vec());
        batch.commit()?;
        self.finish_scoped_genesis()?;
        self.scan_states()?;
        Ok(())
    }

    fn finish_scoped_genesis(&self) -> Result<()> {
        let mut scripts = HashSet::new();
        let mut markers = Vec::new();
        for status in self.get_filter_scripts() {
            let suffix = [
                status.script.as_slice(),
                &[match status.script_type {
                    ScriptType::Lock => 0,
                    ScriptType::Type => 1,
                }],
            ]
            .concat();
            let marker = key("HISTORY_GENESIS_PENDING_V1", &suffix);
            if let Some(value) = self.get(marker.clone())? {
                let raw = self.get(key(SCAN_STATE, &suffix))?.ok_or_else(invalid)?;
                let state: ScanState = serde_json::from_slice(&raw).map_err(Error::runtime)?;
                state.validate()?;
                state.identity.validate(self)?;
                if state.identity.suffix != suffix
                    || state.floor != 0
                    || state.settled != 0
                    || value.as_slice() != &state.identity.registration[8..]
                    || self.get(Key::Meta(SCAN_EPOCH).into_vec())?.as_deref()
                        != Some(state.epoch.as_slice())
                {
                    return Err(invalid());
                }
                scripts.insert((status.script, status.script_type));
                markers.push(marker);
            }
        }
        if !markers.is_empty() {
            self.filter_block_for_scripts(self.get_genesis_block(), &scripts)?;
            let mut batch = self.batch();
            for marker in markers {
                batch.delete(&marker);
            }
            batch.commit()?;
        }
        Ok(())
    }

    fn stage_scoped_rollback(&self, batch: &mut Self::Batch, removed_from: u64) -> Result<bool> {
        let Some(states) = self.scan_states()? else {
            return Ok(false);
        };
        let epoch = rand::random();
        let through = removed_from.saturating_sub(1);
        for mut state in states {
            state.epoch = epoch;
            state.pending = None;
            state.settled = state.settled.min(through);
            if state.floor > state.settled {
                state.floor = 0;
                state.settled = 0;
                let script =
                    Script::from_slice(&state.identity.suffix[..state.identity.suffix.len() - 1])
                        .map_err(|_| invalid())?;
                let kind = match state.identity.suffix.last() {
                    Some(0) => ScriptType::Lock,
                    Some(1) => ScriptType::Type,
                    _ => return Err(invalid()),
                };
                self.rollback_script_to_block(
                    &super::ScriptStatus {
                        script,
                        script_type: kind,
                        block_number: 0,
                    },
                    1,
                    batch,
                );
                let nonce: [u8; 16] = rand::random();
                state.identity.registration =
                    [0u64.to_be_bytes().to_vec(), nonce.to_vec()].concat();
                batch.put(
                    &key("HISTORY_REGISTRATION_V1", &state.identity.suffix),
                    &state.identity.registration,
                );
                batch.put(
                    &key("HISTORY_SEGMENT_V1", &state.identity.suffix),
                    &state.identity.registration,
                );
                batch.put(
                    &key("HISTORY_GENESIS_PENDING_V1", &state.identity.suffix),
                    &nonce,
                );
            }
            batch.put(
                &key("FILTER_SCRIPTS", &state.identity.suffix),
                &state.settled.to_be_bytes(),
            );
            batch.put(
                &key(SCAN_STATE, &state.identity.suffix),
                &serde_json::to_vec(&state).map_err(Error::runtime)?,
            );
        }
        batch.put(&Key::Meta(SCAN_EPOCH).into_vec(), &epoch);
        Ok(true)
    }

    fn stage_scoped_registration(
        &self,
        batch: &mut Self::Batch,
        suffix: &[u8],
        registration: &[u8],
        through: u64,
    ) -> Result<()> {
        let Some(epoch) = self.get(Key::Meta(SCAN_EPOCH).into_vec())? else {
            return Ok(());
        };
        let epoch = epoch.as_slice().try_into().map_err(|_| invalid())?;
        let floor = u64::from_be_bytes(
            registration
                .get(..8)
                .ok_or_else(invalid)?
                .try_into()
                .map_err(|_| invalid())?,
        );
        let state = ScanState::settled(
            ScanIdentity {
                suffix: suffix.to_vec(),
                registration: registration.to_vec(),
            },
            epoch,
            floor,
            through,
        );
        state.validate()?;
        batch.put(
            &key(SCAN_STATE, suffix),
            &serde_json::to_vec(&state).map_err(Error::runtime)?,
        );
        Ok(())
    }

    fn scan_states(&self) -> Result<Option<Vec<ScanState>>> {
        if self.get(Key::Meta(SCAN_EPOCH).into_vec())?.is_none() {
            return Ok(None);
        }
        self.get_filter_scripts()
            .into_iter()
            .map(|s| {
                let id = ScanIdentity::registered(self, &s.script, s.script_type)?;
                self.scan_state(&id)?.ok_or_else(invalid)
            })
            .collect::<Result<Vec<_>>>()
            .map(Some)
    }

    /// Migration/registration entry point. The caller proves floors before
    /// installing them; an existing cursor alone is NOT proof of historical floor.
    /// Installing a whole generation and its cursors is one transaction.
    #[cfg(test)]
    fn install_scan_states(&self, epoch: [u8; 16], states: Vec<ScanState>) -> Result<()> {
        let mut seen = HashSet::new();
        let mut batch = self.batch();
        for state in states {
            state.validate()?;
            state.identity.validate(self)?;
            if state.epoch != epoch || !seen.insert(state.identity.suffix.clone()) {
                return Err(invalid());
            }
            let cursor = self
                .get(key("FILTER_SCRIPTS", &state.identity.suffix))?
                .ok_or_else(invalid)?;
            let cursor = u64::from_be_bytes(cursor.as_slice().try_into().map_err(|_| invalid())?);
            if state.settled > cursor {
                return Err(invalid());
            }
            batch.put(
                &key(SCAN_STATE, &state.identity.suffix),
                &serde_json::to_vec(&state).map_err(Error::runtime)?,
            );
            batch.put(
                &key("FILTER_SCRIPTS", &state.identity.suffix),
                &state.settled.to_be_bytes(),
            );
        }
        batch.put(&Key::Meta(SCAN_EPOCH).into_vec(), &epoch);
        batch.commit()
    }

    fn scan_state(&self, identity: &ScanIdentity) -> Result<Option<ScanState>> {
        identity.validate(self)?;
        if self
            .get(key("HISTORY_GENESIS_PENDING_V1", &identity.suffix))?
            .is_some()
        {
            return Err(invalid());
        }
        let Some(raw) = self.get(key(SCAN_STATE, &identity.suffix))? else {
            return Ok(None);
        };
        let state: ScanState = serde_json::from_slice(&raw).map_err(Error::runtime)?;
        state.validate()?;
        if state.identity != *identity
            || self.get(Key::Meta(SCAN_EPOCH).into_vec())?.as_deref()
                != Some(state.epoch.as_slice())
        {
            return Err(invalid());
        }
        let cursor = self
            .get(key("FILTER_SCRIPTS", &identity.suffix))?
            .ok_or_else(invalid)?;
        if cursor != state.settled.to_be_bytes() {
            return Err(invalid());
        }
        Ok(Some(state))
    }

    /// Record already verified contiguous filters. Empty ranges settle in the
    /// same commit; matched ranges remain durable debt without moving the cursor.
    fn begin_scan_range(
        &self,
        identity: &ScanIdentity,
        epoch: [u8; 16],
        start: u64,
        end: u64,
        anchor: [u8; 32],
        matches: Vec<[u8; 32]>,
    ) -> Result<ScanRange> {
        let mut state = self.scan_state(identity)?.ok_or_else(invalid)?;
        if state.epoch != epoch
            || state.settled.checked_add(1) != Some(start)
            || end < start
            || matches.iter().collect::<HashSet<_>>().len() != matches.len()
        {
            return Err(invalid());
        }
        if let Some(pending) = &state.pending {
            return if (pending.start, pending.end, pending.anchor, &pending.matches)
                == (start, end, anchor, &matches)
            {
                Ok(pending.clone())
            } else {
                Err(invalid())
            };
        }
        let range = ScanRange {
            id: rand::random(),
            start,
            end,
            anchor,
            matches,
        };
        if range.matches.is_empty() {
            state.settled = end;
        } else {
            state.pending = Some(range.clone());
        }
        let mut batch = self.batch();
        batch.put(
            &key(SCAN_STATE, &identity.suffix),
            &serde_json::to_vec(&state).map_err(Error::runtime)?,
        );
        batch.put(
            &key("FILTER_SCRIPTS", &identity.suffix),
            &state.settled.to_be_bytes(),
        );
        batch.commit()?;
        Ok(range)
    }

    /// A missing/uncle match invalidates only this unsettled range. Undo any
    /// interrupted indexing atomically with clearing debt; settled coverage is
    /// unchanged and a fresh range gets a new acknowledgement identity.
    fn invalidate_scan_range(
        &self,
        identity: &ScanIdentity,
        epoch: [u8; 16],
        range_id: [u8; 16],
    ) -> Result<()> {
        let mut state = self.scan_state(identity)?.ok_or_else(invalid)?;
        let range = state.pending.as_ref().ok_or_else(invalid)?;
        if state.epoch != epoch || range.id != range_id {
            return Err(invalid());
        }
        let script = Script::from_slice(&identity.suffix[..identity.suffix.len() - 1])
            .map_err(|_| invalid())?;
        let script_type = match identity.suffix.last() {
            Some(0) => ScriptType::Lock,
            Some(1) => ScriptType::Type,
            _ => return Err(invalid()),
        };
        let mut batch = self.batch();
        self.rollback_script_to_block(
            &super::ScriptStatus {
                script,
                script_type,
                block_number: state.settled,
            },
            range.start,
            &mut batch,
        );
        state.pending = None;
        batch.put(
            &key(SCAN_STATE, &identity.suffix),
            &serde_json::to_vec(&state).map_err(Error::runtime)?,
        );
        batch.put(
            &key("FILTER_SCRIPTS", &identity.suffix),
            &state.settled.to_be_bytes(),
        );
        batch.commit()
    }

    /// Acknowledge only the exact range whose proved bodies have been indexed.
    /// A different script's debt, a subset, or duplicate bodies cannot settle it.
    fn finish_scan_range(
        &self,
        identity: &ScanIdentity,
        epoch: [u8; 16],
        range_id: [u8; 16],
        indexed_hashes: &[[u8; 32]],
    ) -> Result<()> {
        let mut state = self.scan_state(identity)?.ok_or_else(invalid)?;
        let pending = state.pending.as_ref().ok_or_else(invalid)?;
        let actual = indexed_hashes.iter().collect::<HashSet<_>>();
        if state.epoch != epoch
            || pending.id != range_id
            || actual.len() != indexed_hashes.len()
            || actual != pending.matches.iter().collect::<HashSet<_>>()
        {
            return Err(invalid());
        }
        state.settled = pending.end;
        state.pending = None;
        let mut batch = self.batch();
        batch.put(
            &key(SCAN_STATE, &identity.suffix),
            &serde_json::to_vec(&state).map_err(Error::runtime)?,
        );
        batch.put(
            &key("FILTER_SCRIPTS", &identity.suffix),
            &state.settled.to_be_bytes(),
        );
        batch.commit()
    }

    /// Bodies must already have passed canonical membership and body validation.
    /// Keep debt durable during every index commit; replay after interruption is
    /// idempotent, and only the final commit exposes the settled script cursor.
    fn index_scan_range(
        &self,
        identity: &ScanIdentity,
        epoch: [u8; 16],
        range_id: [u8; 16],
        mut blocks: Vec<Block>,
    ) -> Result<()> {
        let state = self.scan_state(identity)?.ok_or_else(invalid)?;
        let range = state.pending.ok_or_else(invalid)?;
        let hashes: Vec<[u8; 32]> = blocks
            .iter()
            .map(|b| {
                b.calc_header_hash()
                    .as_slice()
                    .try_into()
                    .expect("hash length")
            })
            .collect();
        if state.epoch != epoch
            || range.id != range_id
            || hashes.iter().collect::<HashSet<_>>().len() != blocks.len()
            || hashes.iter().collect::<HashSet<_>>() != range.matches.iter().collect::<HashSet<_>>()
            || blocks.iter().any(|block| {
                let n: u64 = block.header().raw().number().unpack();
                n < range.start || n > range.end
            })
        {
            return Err(invalid());
        }
        let kind = match identity.suffix.last() {
            Some(0) => ScriptType::Lock,
            Some(1) => ScriptType::Type,
            _ => return Err(invalid()),
        };
        let script = Script::from_slice(&identity.suffix[..identity.suffix.len() - 1])
            .map_err(|_| invalid())?;
        let scripts = HashSet::from([(script, kind)]);
        blocks.sort_by_key(|block| Unpack::<u64>::unpack(&block.header().raw().number()));
        for block in blocks {
            self.filter_block_for_scripts(block, &scripts)?;
        }
        self.finish_scan_range(identity, epoch, range_id, &hashes)
    }
}

impl<S: LightClientStorage + ?Sized> ScanStorage for S {}
