//! Bounded checkpoint windows, bound to both canonical epoch and checkpoint values.
use ckb_types::packed;
use std::collections::VecDeque;
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
    entries: VecDeque<Window>,
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
        self.entries.push_back(Window {
            epoch,
            index,
            parent,
            end,
            hashes,
        });
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
            .map(|e| std::sync::Arc::new(e.clone()))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use ckb_types::prelude::*;
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
        assert!(cache.get([7; 16], 0, &a, &b).is_some());
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
