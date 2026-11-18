//! Bounded fair selection of independently settled script scans.
use crate::storage::scan::{ScanIdentity, ScanState};
use ckb_network::PeerIndex;
use std::collections::VecDeque;

const REQUEST_TIMEOUT_MS: u64 = 15_000;
const MAX_OUTSTANDING: usize = 32;

#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) struct ScanRequest {
    pub identity: ScanIdentity,
    pub epoch: [u8; 16],
    pub start: u64,
    pub window: Option<std::sync::Arc<super::windows::Window>>,
}
struct InFlight {
    request: ScanRequest,
    peer: PeerIndex,
    sent_ms: u64,
}
#[derive(Default)]
pub(crate) struct ScanScheduler {
    queue: VecDeque<ScanIdentity>,
    in_flight: Vec<InFlight>,
}
impl ScanScheduler {
    /// No storage/network waits occur here. Reconcile membership each selection;
    /// replacements and reorgs invalidate local outstanding request bindings.
    pub fn select(&mut self, states: &[ScanState], tip: u64, now_ms: u64) -> Option<ScanRequest> {
        self.queue
            .retain(|id| states.iter().any(|s| s.identity == *id));
        self.in_flight.retain(|f| {
            now_ms.saturating_sub(f.sent_ms) < REQUEST_TIMEOUT_MS
                && states.iter().any(|s| Self::current(&f.request, s))
        });
        let mut new: Vec<_> = states
            .iter()
            .filter(|s| !self.queue.contains(&s.identity))
            .collect();
        new.sort_by(|a, b| {
            b.settled
                .cmp(&a.settled)
                .then_with(|| a.identity.suffix.cmp(&b.identity.suffix))
        });
        self.queue
            .extend(new.into_iter().map(|s| s.identity.clone()));
        if self.in_flight.len() >= MAX_OUTSTANDING {
            return None;
        }
        for _ in 0..self.queue.len() {
            let id = self.queue.pop_front()?;
            self.queue.push_back(id.clone());
            let Some(state) = states.iter().find(|s| s.identity == id) else {
                continue;
            };
            if state.pending.is_some()
                || state.settled >= tip
                || self.in_flight.iter().any(|f| f.request.identity == id)
            {
                continue;
            }
            let Some(start) = state.settled.checked_add(1) else {
                continue;
            };
            return Some(ScanRequest {
                identity: id,
                epoch: state.epoch,
                start,
                window: None,
            });
        }
        None
    }
    fn current(request: &ScanRequest, state: &ScanState) -> bool {
        request.identity == state.identity
            && request.epoch == state.epoch
            && state.pending.is_none()
            && state.settled.checked_add(1) == Some(request.start)
    }
    pub fn sent(&mut self, request: &ScanRequest, peer: PeerIndex, now_ms: u64) {
        self.completed(request);
        self.in_flight.push(InFlight {
            request: request.clone(),
            peer,
            sent_ms: now_ms,
        });
    }
    pub fn accepts(
        &self,
        request: &ScanRequest,
        peer: PeerIndex,
        start: u64,
        states: &[ScanState],
    ) -> bool {
        request.start == start
            && self
                .in_flight
                .iter()
                .any(|f| f.request == *request && f.peer == peer)
            && states.iter().any(|s| Self::current(request, s))
    }
    pub fn binding(
        &self,
        peer: PeerIndex,
        start: u64,
        states: &[ScanState],
    ) -> Option<ScanRequest> {
        self.in_flight
            .iter()
            .find(|f| f.peer == peer && self.accepts(&f.request, peer, start, states))
            .map(|f| f.request.clone())
    }
    pub fn completed(&mut self, request: &ScanRequest) {
        self.in_flight.retain(|f| f.request != *request);
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::storage::scan::{ScanIdentity, ScanState};
    fn state(id: u8, height: u64) -> ScanState {
        ScanState::settled(
            ScanIdentity {
                suffix: vec![id],
                registration: vec![id; 24],
            },
            [7; 16],
            0,
            height,
        )
    }
    #[test]
    fn newest_first_then_round_robin_despite_timeout() {
        let mut scheduler = ScanScheduler::default();
        let states = vec![state(1, 10), state(2, 100)];
        let live = scheduler.select(&states, 101, 0).unwrap();
        assert_eq!(live.identity, states[1].identity);
        scheduler.sent(&live, PeerIndex::new(3), 0);
        let history = scheduler.select(&states, 101, 1).unwrap();
        assert_eq!(history.identity, states[0].identity);
        scheduler.sent(&history, PeerIndex::new(3), 1);
        assert!(scheduler.select(&states, 101, 2).is_none());
        assert_eq!(
            scheduler.select(&states, 101, 15000).unwrap().identity,
            live.identity
        );
    }
    #[test]
    fn pending_history_cannot_block_new_live_tip_and_stale_reply_is_rejected() {
        let mut scheduler = ScanScheduler::default();
        let mut states = vec![state(1, 10), state(2, 100)];
        states[0].pending = Some(crate::storage::scan::ScanRange {
            id: [1; 16],
            start: 11,
            end: 20,
            anchor: [9; 32],
            matches: vec![[1; 32]],
        });
        let request = scheduler.select(&states, 101, 0).unwrap();
        scheduler.sent(&request, PeerIndex::new(3), 0);
        assert!(!scheduler.accepts(&request, PeerIndex::new(4), 101, &states));
        assert!(!scheduler.accepts(&request, PeerIndex::new(3), 102, &states));
        assert!(scheduler.accepts(&request, PeerIndex::new(3), 101, &states));
        states[1].identity.registration = vec![5; 24];
        assert!(!scheduler.accepts(&request, PeerIndex::new(3), 101, &states));
        states[1].identity = request.identity.clone();
        states[1].epoch = [8; 16];
        assert!(!scheduler.accepts(&request, PeerIndex::new(3), 101, &states));
        scheduler.completed(&request);
        states[1].settled = 101;
        assert_eq!(scheduler.select(&states, 102, 1).unwrap().start, 102);
    }
}
