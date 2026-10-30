//! Bounded cell-read admission. Never wait while holding matched or registry locks.
use super::error::BridgeError;
use crate::protocols::light_client::recovery::{ChainSnapshot, RecoveryRegistry};
use tokio::sync::{RwLock, RwLockReadGuard};

fn unavailable() -> BridgeError {
    BridgeError::Storage("cells unavailable".into())
}

pub(super) fn acquire<'a, T>(
    matched: &'a RwLock<T>,
    registry: &RecoveryRegistry,
    mut ready: impl FnMut(&ChainSnapshot) -> Result<(), BridgeError>,
) -> Result<RwLockReadGuard<'a, T>, BridgeError> {
    #[cfg(not(target_arch = "wasm32"))]
    let deadline = std::time::Instant::now() + std::time::Duration::from_millis(500);
    loop {
        if let Ok(guard) = matched.try_read() {
            match registry
                .try_with_chain_snapshot(|_, chain| ready(chain))
                .map_err(|_| unavailable())?
            {
                Some(result) => {
                    result?;
                    return Ok(guard);
                }
                None => {} // Registry contention: drop matched before waiting.
            }
        }
        // Both acquisition guards are gone. Semantic errors never reach this retry.
        #[cfg(target_arch = "wasm32")]
        return Err(unavailable()); // No blocking sleep on the browser thread.
        #[cfg(not(target_arch = "wasm32"))]
        {
            let remaining = deadline.saturating_duration_since(std::time::Instant::now());
            if remaining.is_zero() {
                return Err(unavailable());
            }
            std::thread::sleep(remaining.min(std::time::Duration::from_millis(5)));
            if std::time::Instant::now() >= deadline {
                return Err(unavailable());
            }
        }
    }
}

#[cfg(all(test, not(target_arch = "wasm32")))]
mod tests {
    use super::*;
    use crate::protocols::light_client::recovery::RecoveryIdentity;
    use ckb_types::packed;
    use std::{
        sync::mpsc,
        time::{Duration, Instant},
    };
    fn registry() -> RecoveryRegistry {
        RecoveryRegistry::new(
            RecoveryIdentity {
                genesis_hash: packed::Byte32::new([1; 32]),
                store_generation: "test".into(),
                run_nonce: [1; 16],
            },
            ChainSnapshot {
                anchor: packed::Header::default(),
            },
        )
    }
    #[test]
    fn brief_matched_contention_recovers_and_keeps_query_guard() {
        let matched = RwLock::new(());
        let registry = registry();
        std::thread::scope(|scope| {
            let (entered, wait) = mpsc::channel();
            let matched = &matched;
            scope.spawn(move || {
                let _writer = matched.blocking_write();
                entered.send(()).unwrap();
                std::thread::sleep(Duration::from_millis(30));
            });
            wait.recv().unwrap();
            let guard = acquire(&matched, &registry, |_| Ok(())).unwrap();
            assert!(matched.try_write().is_err());
            drop(guard);
        });
    }
    #[test]
    fn registry_contention_releases_matched_for_writer_before_retry() {
        let matched = RwLock::new(());
        let registry = registry();
        std::thread::scope(|scope| {
            let (entered, wait) = mpsc::channel();
            let registry = &registry;
            let matched = &matched;
            scope.spawn(move || {
                registry
                    .with_chain_snapshot(|_, _| {
                        entered.send(()).unwrap();
                        std::thread::sleep(Duration::from_millis(30));
                        // Would deadlock if the read attempt retained matched while waiting.
                        let _writer = matched.blocking_write();
                    })
                    .unwrap()
            });
            wait.recv().unwrap();
            assert!(acquire(&matched, &registry, |_| Ok(())).is_ok());
        });
    }
    #[test]
    fn persistent_contention_is_bounded_and_semantic_errors_are_not_retried() {
        let matched = RwLock::new(());
        let registry = registry();
        let writer = matched.blocking_write();
        let start = Instant::now();
        assert!(acquire(&matched, &registry, |_| panic!("locked")).is_err());
        assert!(start.elapsed() >= Duration::from_millis(400));
        assert!(start.elapsed() < Duration::from_secs(2));
        drop(writer);
        let mut calls = 0;
        assert!(acquire(&matched, &registry, |_| {
            calls += 1;
            Err(unavailable())
        })
        .is_err());
        assert_eq!(calls, 1);
        registry.revoke_run();
        assert!(acquire(&matched, &registry, |_| panic!("revoked")).is_err());
    }
    #[test]
    fn registry_busy_is_distinct_from_poison_and_unavailable_authority() {
        let matched = RwLock::new(());
        let registry = registry();
        registry
            .with_chain_snapshot(|_, _| {
                assert!(registry
                    .try_with_chain_snapshot(|_, _| ())
                    .unwrap()
                    .is_none());
                let start = Instant::now();
                assert!(acquire(&matched, &registry, |_| panic!("busy")).is_err());
                assert!(start.elapsed() >= Duration::from_millis(400));
                assert!(start.elapsed() < Duration::from_secs(2));
                assert!(matched.try_write().is_ok());
            })
            .unwrap();
        let _ = std::panic::catch_unwind(|| registry.with_chain_snapshot(|_, _| panic!("poison")));
        assert!(registry.try_with_chain_snapshot(|_, _| ()).is_err());
        assert!(acquire(&matched, &registry, |_| panic!("poisoned")).is_err());
    }
}
