//! Bounded checkpoint windows, bound to both canonical epoch and checkpoint values.
use ckb_types::packed;
use std::{collections::VecDeque, sync::Arc};
#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) struct Window {
    pub epoch: [u8; 16],
    pub index: u32,
    pub parent: packed::Byte32,
    pub end: packed::Byte32,
    pub hashes: Vec<packed::Byte32>,
}
#[derive(Default)]
pub(crate) struct FilterWindows {
    entries: VecDeque<Arc<Window>>,
}
impl FilterWindows {
    pub fn insert(
        &mut self,
        epoch: [u8; 16],
        index: u32,
        parent: packed::Byte32,
        end: packed::Byte32,
        hashes: Vec<packed::Byte32>,
        interval: usize,
    ) -> bool {
        if hashes.len() != interval || hashes.last() != Some(&end) {
            return false;
        }
        self.entries
            .retain(|entry| entry.epoch == epoch && entry.index != index);
        self.entries.push_back(Arc::new(Window {
            epoch,
            index,
            parent,
            end,
            hashes,
        }));
        while self.entries.len() > 8 {
            self.entries.pop_front();
        }
        true
    }
    pub fn get(
        &self,
        epoch: [u8; 16],
        index: u32,
        parent: &packed::Byte32,
        end: &packed::Byte32,
    ) -> Option<std::sync::Arc<Window>> {
        self.entries
            .iter()
            .find(|e| e.epoch == epoch && e.index == index && e.parent == *parent && e.end == *end)
            .map(Arc::clone)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use ckb_types::prelude::*;
    #[test]
    fn partial_windows_survive_more_than_32_registered_scopes() {
        let mut downloads = HashDownloads::default();
        let parent = packed::Byte32::new([1; 32]);
        let end = packed::Byte32::new([2; 32]);
        for index in 0..40 {
            downloads.remember(Window {
                epoch: [7; 16],
                index,
                parent: parent.clone(),
                end: end.clone(),
                hashes: vec![packed::Byte32::new([index as u8; 32])],
            });
        }
        assert_eq!(
            downloads.restore([7; 16], 0, &parent, &end, 0),
            vec![packed::Byte32::new([0; 32])]
        );
        downloads.retain([7; 16], &[(0, parent.clone(), end.clone())]);
        assert_eq!(downloads.entries.len(), 1);
        assert!(downloads.restore([7; 16], 0, &end, &parent, 0).is_empty());
        downloads.retain([8; 16], &[(0, parent, end)]);
        assert!(downloads.entries.is_empty());
    }
    #[test]
    fn windows_are_independent_and_stale_anchors_are_rejected() {
        let mut cache = FilterWindows::default();
        let a = packed::Byte32::from_slice(&[1; 32]).unwrap();
        let b = packed::Byte32::from_slice(&[2; 32]).unwrap();
        assert!(!cache.insert([7; 16], 0, a.clone(), b.clone(), vec![a.clone()], 2));
        assert!(!cache.insert(
            [7; 16],
            0,
            a.clone(),
            b.clone(),
            vec![a.clone(), a.clone()],
            2
        ));
        assert!(cache.insert(
            [7; 16],
            0,
            a.clone(),
            b.clone(),
            vec![a.clone(), b.clone()],
            2
        ));
        assert!(cache.insert(
            [7; 16],
            1,
            b.clone(),
            a.clone(),
            vec![b.clone(), a.clone()],
            2
        ));
        let first = cache.get([7; 16], 0, &a, &b).unwrap();
        let second = cache.get([7; 16], 0, &a, &b).unwrap();
        assert!(std::sync::Arc::ptr_eq(&first, &second));
        assert!(cache.get([7; 16], 1, &b, &a).is_some());
        assert!(cache.get([8; 16], 0, &a, &b).is_none());
        assert!(cache.get([7; 16], 0, &a, &a).is_none());
        for index in 2..20 {
            cache.insert(
                [7; 16],
                index,
                a.clone(),
                b.clone(),
                vec![a.clone(), b.clone()],
                2,
            );
        }
        assert!(cache.get([7; 16], 0, &a, &b).is_none());
        assert_eq!(cache.entries.len(), 8);
    }
}

/// Incomplete windows survive a turn switch, with the same epoch/anchor binding
/// as completed windows. Retention is bounded by the currently registered
/// checkpoint windows, so rotating many scopes never discards unfinished work.
#[derive(Default)]
pub(crate) struct HashDownloads {
    entries: VecDeque<Window>,
    progress: Option<(u32, usize, u64)>,
}
impl HashDownloads {
    pub fn remember(&mut self, window: Window) {
        self.entries
            .retain(|w| w.epoch == window.epoch && w.index != window.index);
        self.entries.push_back(window);
    }

    pub fn retain(&mut self, epoch: [u8; 16], needed: &[(u32, packed::Byte32, packed::Byte32)]) {
        self.entries.retain(|window| {
            window.epoch == epoch
                && needed.iter().any(|(index, parent, end)| {
                    window.index == *index && window.parent == *parent && window.end == *end
                })
        });
    }

    pub fn next(&mut self, active: u32, length: usize, missing: &[u32], now: u64) -> Option<u32> {
        let since = match self.progress {
            Some((index, len, since)) if index == active && len == length => since,
            _ => {
                self.progress = Some((active, length, now));
                now
            }
        };
        if missing.contains(&active) && now.saturating_sub(since) < 15_000 {
            return None;
        }
        let next = missing
            .iter()
            .copied()
            .filter(|index| *index > active)
            .min()
            .or_else(|| missing.iter().copied().min())?;
        (next != active).then_some(next)
    }

    pub fn restore(
        &mut self,
        epoch: [u8; 16],
        index: u32,
        parent: &packed::Byte32,
        end: &packed::Byte32,
        now: u64,
    ) -> Vec<packed::Byte32> {
        let hashes = self
            .entries
            .iter()
            .find(|w| w.epoch == epoch && w.index == index && w.parent == *parent && w.end == *end)
            .map(|w| w.hashes.clone())
            .unwrap_or_default();
        self.progress = Some((index, hashes.len(), now));
        hashes
    }
}
