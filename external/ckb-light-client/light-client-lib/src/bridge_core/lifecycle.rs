//! Lifecycle management for the platform bridges
//!
//! Implements init/start/stop/status functions that mirror light-client-bin/src/subcmds.rs.
//!
//! ## Init invariants
//!
//! `init` publishes *no* global state until every fallible step has succeeded.
//! The runtime, network controller, consensus, peers, storage and the status
//! listener are all held in locals through the fallible section and only
//! `.set()` into their `OnceLock`s at the very end, with nothing fallible
//! between two of those sets. On Android the JNI bridge likewise populates its
//! `JAVA_VM` / `STATUS_CALLBACK` `OnceLock`s only after `init` returned `Ok`.
//! This guarantees:
//!
//! - If init returns `Err`, every `OnceLock` is still empty, so a retry runs
//!   cleanly instead of wedging on a leftover global from the previous attempt.
//! - The tokio runtime is dropped (and therefore shut down) on the failure
//!   paths rather than leaked into a `OnceLock` that can never be cleared.
//! - Platform handles held by the caller (such as the JNI `GlobalRef` for the
//!   status callback) are dropped automatically on the early-return paths, so
//!   failure cannot leak them.
//!
//! ## Stop is one-way
//!
//! `stop` cannot reset any `OnceLock` (the type has no API to do so), so a
//! stopped client cannot be started again and in-process network switching is
//! not supported. `start` reports [`BridgeError::Stopped`] afterwards, and the
//! app relaunches instead — see #218 for the app-side mitigation on network
//! switch, and [`stop`] for why it no longer waits on `ckb-stop-handler` (#487).

use super::error::BridgeError;
use super::types::*;
use crate::protocols::{
    FilterProtocol, LightClientProtocol, Peers, PendingTxs, RelayProtocol, SyncProtocol,
    BAD_MESSAGE_ALLOWED_EACH_HOUR, CHECK_POINT_INTERVAL,
};
use crate::storage::{Storage, StorageWithChainData};
use crate::types::RunEnv;
use crate::utils;
use ckb_async_runtime::new_global_runtime;
use ckb_chain_spec::ChainSpec;
use ckb_network::{
    network::TransportType, CKBProtocol, CKBProtocolHandler, Flags, NetworkService, NetworkState,
    SupportProtocols,
};
use ckb_resource::Resource;
use ckb_stop_handler::broadcast_exit_signals;
use log::{error, info, warn};
use std::fs;
use std::sync::{Arc, RwLock};
use std::time::Duration;

/// Initialize the platform logger exactly once.
///
/// Android keeps `android_logger` (logcat); iOS routes `log` records to the
/// unified logging system so node output shows up in Console.app and the Xcode
/// console. Other hosts (unit tests, desktop) install nothing.
///
/// Idempotent, and safe to call before [`init`]: a platform bridge that logs
/// during its own argument marshalling calls this first so those records are
/// not dropped on the floor.
pub fn init_logging() {
    #[cfg(target_os = "android")]
    android_logger::init_once(
        android_logger::Config::default()
            .with_max_level(log::LevelFilter::Debug)
            .with_tag("ckb-light-client"),
    );

    #[cfg(target_os = "ios")]
    {
        use std::sync::OnceLock;
        static IOS_LOGGER: OnceLock<()> = OnceLock::new();
        IOS_LOGGER.get_or_init(|| {
            let _ = oslog::OsLogger::new("com.rjnr.pocketnode.lightclient")
                .level_filter(log::LevelFilter::Debug)
                .init();
        });
    }
}

/// Initialize the light client.
///
/// This performs all initialization including:
/// - Loading TOML config
/// - Initializing Storage and ChainSpec
/// - Creating protocols (Sync, Relay, LightClient, Filter)
/// - Starting NetworkService
/// - Creating tokio runtime in dedicated thread
///
/// `data_dir`, when non-empty, overrides the store and network paths from the
/// config with `<data_dir>/store.db` and `<data_dir>/network`. Callers that
/// already rewrite those paths into the TOML (the Android app does) pass an
/// empty string and keep the config as-is.
///
/// Note: This starts the network service but doesn't change state to RUNNING
/// yet. Call [`start`] to actually start processing.
pub fn init(
    config_path: &str,
    data_dir: &str,
    listener: Option<Box<dyn Fn(u8) + Send + Sync>>,
) -> Result<(), BridgeError> {
    // Check if already initialized
    if is_initialized() {
        error!("Already initialized!");
        return Err(BridgeError::AlreadyInitialized);
    }

    init_logging();

    info!("Platform logger initialized with Debug level");
    info!("Starting CKB Light Client initialization...");
    info!("Loading config from: {}", config_path);

    // Load and parse TOML config
    let mut run_env: RunEnv = load_config(config_path).map_err(|e| {
        error!("Failed to load config: {}", e);
        BridgeError::Config(e.to_string())
    })?;

    // Sandboxed platforms (iOS) get a fresh container path on every install, so
    // the absolute paths cannot be baked into the bundled TOML.
    if !data_dir.is_empty() {
        run_env.store.path = std::path::Path::new(data_dir).join("store.db");
        run_env.network.path = std::path::Path::new(data_dir).join("network");
    }

    info!("Config loaded successfully");
    info!("Chain: {}", run_env.chain);
    info!("Store path: {:?}", run_env.store.path);
    info!("Network path: {:?}", run_env.network.path);

    // Create network directory if needed
    utils::fs::need_directory(&run_env.network.path).map_err(|e| {
        error!("Failed to create network directory: {}", e);
        BridgeError::Storage(e.to_string())
    })?;

    // Initialize storage
    info!("Initializing storage...");
    let storage = Storage::new(&run_env.store.path);

    // Load chain spec
    info!("Loading chain spec for: {}", run_env.chain);
    let chain_spec = ChainSpec::load_from(&match run_env.chain.as_str() {
        "mainnet" => Resource::bundled("specs/mainnet.toml".to_string()),
        "testnet" => Resource::bundled("specs/testnet.toml".to_string()),
        path => Resource::file_system(path.into()),
    })
    .map_err(|e| {
        error!("Failed to load chain spec: {}", e);
        BridgeError::Config(e.to_string())
    })?;

    let consensus = chain_spec.build_consensus().map_err(|e| {
        error!("Failed to build consensus: {}", e);
        BridgeError::Config(e.to_string())
    })?;

    info!("Initializing genesis block...");
    storage.init_genesis_block(consensus.genesis_block().data());

    info!("Cleaning up invalid matched blocks...");
    storage.cleanup_invalid_matched_blocks();

    // Initialize network components
    info!("Initializing network state...");
    let pending_txs = Arc::new(RwLock::new(PendingTxs::default()));
    let max_outbound_peers = run_env.network.max_outbound_peers;

    let network_state = NetworkState::from_config(run_env.network.clone())
        .map(|ns| {
            Arc::new(ns.required_flags(
                Flags::DISCOVERY
                    | Flags::SYNC
                    | Flags::RELAY
                    | Flags::LIGHT_CLIENT
                    | Flags::BLOCK_FILTER,
            ))
        })
        .map_err(|e| {
            error!("Failed to initialize network state: {}", e);
            BridgeError::Network(e.to_string())
        })?;

    // Create peers manager
    info!("Creating peers manager...");
    let peers = Arc::new(Peers::new(
        max_outbound_peers,
        CHECK_POINT_INTERVAL,
        storage.get_last_check_point(),
        BAD_MESSAGE_ALLOWED_EACH_HOUR,
    ));

    // Initialize protocols
    info!("Initializing protocols...");
    let sync_protocol = SyncProtocol::new(storage.clone(), Arc::clone(&peers));
    let relay_protocol =
        RelayProtocol::new(pending_txs.clone(), Arc::clone(&peers), storage.clone());
    let light_client: Box<dyn CKBProtocolHandler> = Box::new(LightClientProtocol::new(
        storage.clone(),
        Arc::clone(&peers),
        consensus.clone(),
    ));
    let filter_protocol = FilterProtocol::new(storage.clone(), Arc::clone(&peers));

    let protocols = vec![
        CKBProtocol::new_with_support_protocol(
            SupportProtocols::Sync,
            Box::new(sync_protocol),
            Arc::clone(&network_state),
        ),
        CKBProtocol::new_with_support_protocol(
            SupportProtocols::RelayV3,
            Box::new(relay_protocol),
            Arc::clone(&network_state),
        ),
        CKBProtocol::new_with_support_protocol(
            SupportProtocols::LightClient,
            light_client,
            Arc::clone(&network_state),
        ),
        CKBProtocol::new_with_support_protocol(
            SupportProtocols::Filter,
            Box::new(filter_protocol),
            Arc::clone(&network_state),
        ),
    ];

    let required_protocol_ids = vec![
        SupportProtocols::Sync.protocol_id(),
        SupportProtocols::LightClient.protocol_id(),
        SupportProtocols::Filter.protocol_id(),
    ];

    // Create tokio runtime. It is held locally until every fallible step has
    // succeeded: on an early return the local is dropped, which shuts the
    // runtime down instead of leaking it into a global that can never be
    // cleared.
    info!("Creating tokio runtime...");
    let (runtime_handle, _receiver, runtime) = new_global_runtime(None);

    // Start network service
    info!("Starting network service...");
    let network_controller = NetworkService::new(
        Arc::clone(&network_state),
        protocols,
        required_protocol_ids,
        (
            consensus.identify_name(),
            env!("CARGO_PKG_VERSION").to_owned(),
            Flags::DISCOVERY,
        ),
        TransportType::Tcp,
    )
    .start(&runtime_handle)
    .map_err(|e| {
        error!("Failed to start network service: {}", e);
        BridgeError::Network(e.to_string())
    })?;

    // Create StorageWithChainData
    let swc = StorageWithChainData::new(storage.clone(), Arc::clone(&peers), pending_txs.clone());

    // Start RPC server if configured
    info!("Starting RPC server on {}...", run_env.rpc.listen_address);
    // Note: RPC server implementation would go here
    // For now, we're skipping RPC server startup to keep the implementation focused

    // ---- Publish global state ----
    //
    // Everything below is infallible in practice and nothing fallible runs
    // between two `.set()` calls, so init either publishes all of the globals
    // or none of them. That is what makes a retry after a failed init clean:
    // every OnceLock is still empty. (See audit #186 Finding High 4.)
    //
    // A `.set()` can only report an error if another thread initialized
    // concurrently, which the `is_initialized()` guard at the top of this
    // function already rejects for the supported single-caller flow.
    //
    // `STORAGE_WITH_DATA` is published last of the OnceLocks because
    // `is_initialized()` keys off it, and the status listener just before it
    // so a caller can never be notified about a half-published init.
    RUNTIME.set(runtime).map_err(|_| {
        error!("Failed to store runtime");
        BridgeError::Internal("runtime already set".to_owned())
    })?;

    NET_CONTROL.set(network_controller).map_err(|_| {
        error!("Failed to store network controller");
        BridgeError::Internal("network controller already set".to_owned())
    })?;

    CONSENSUS.set(Arc::new(consensus)).map_err(|_| {
        error!("Failed to store consensus");
        BridgeError::Internal("consensus already set".to_owned())
    })?;

    PEERS.set(peers).map_err(|_| {
        error!("Failed to store peers");
        BridgeError::Internal("peers already set".to_owned())
    })?;

    if let Some(listener) = listener {
        STATUS_LISTENER.set(listener).map_err(|_| {
            error!("Failed to store status listener (already set?)");
            BridgeError::Internal("status listener already set".to_owned())
        })?;
    }

    STORAGE_WITH_DATA.set(swc).map_err(|_| {
        error!("Failed to store StorageWithChainData");
        BridgeError::Internal("storage already set".to_owned())
    })?;

    // Set state to INIT
    set_state(STATE_INIT);

    info!("CKB Light Client initialized successfully!");

    // The "initialized" notification is emitted by the platform bridge, which
    // only finishes wiring its own callback handles after `init` returns Ok.
    Ok(())
}

/// Load config from TOML file
fn load_config(path: &str) -> Result<RunEnv, Box<dyn std::error::Error>> {
    let content = fs::read_to_string(path)?;
    let run_env: RunEnv = toml::from_str(&content)?;
    Ok(run_env)
}

/// Start the light client.
///
/// This transitions from INIT to RUNNING state.
/// The network service is already running (started in [`init`]).
pub fn start() -> Result<(), BridgeError> {
    // A stopped client can never be restarted in-process: the runtime, storage
    // and network globals are `OnceLock`s that `stop` cannot clear, so there is
    // nothing to start again. Report that distinctly instead of pretending the
    // client was merely never initialized.
    if is_stopped() {
        error!("Cannot start: the light client was stopped; the app must be relaunched");
        return Err(BridgeError::Stopped);
    }

    // Check if initialized
    if !is_state(STATE_INIT) {
        error!("Not in INIT state! Current state: {}", get_state());
        return Err(BridgeError::NotInitialized);
    }

    info!("Starting CKB Light Client...");

    // Transition to RUNNING
    set_state(STATE_RUNNING);

    info!("CKB Light Client started successfully!");

    // Notify status listener
    notify_status(STATE_RUNNING);

    Ok(())
}

/// How long [`stop`] gives the network service to wind down before returning.
///
/// Cancelling the tokio exit token makes `NetworkService` call
/// `p2p_control.shutdown()`, which closes the live sessions asynchronously on
/// the runtime. Two seconds is long enough for a handful of testnet sessions to
/// drop on a phone, and short enough that the caller (a Swift `Task` awaiting
/// `stopLightClient`) never feels stuck if a session hangs.
const SHUTDOWN_GRACE: Duration = Duration::from_secs(2);

/// Poll interval for the wind-down wait. Peers disappear from the registry as
/// their sessions close, so a short poll usually returns well inside the grace.
const SHUTDOWN_POLL_INTERVAL: Duration = Duration::from_millis(100);

/// Result of the bounded wind-down performed by [`shutdown_sequence`].
#[derive(Debug, PartialEq, Eq)]
pub(crate) enum ShutdownOutcome {
    /// Every peer session closed inside the grace period.
    Quiesced { waited: Duration },
    /// The grace period expired with peers still attached. Harmless: the
    /// process is going away anyway, the sessions die with it.
    GraceExpired { peers_left: usize },
}

/// Broadcast the exit signal, then wait a bounded moment for the network to
/// wind down.
///
/// Split out of [`stop`] so the ordering and the bound are unit-testable
/// without a node: the broadcast has to happen *before* the first poll, and the
/// wait has to terminate whether or not the peers ever go away.
///
/// The dependencies are injected rather than called directly so a test can
/// drive it with a fake peer count and a fake clock.
pub(crate) fn shutdown_sequence(
    broadcast: impl FnOnce(),
    connected_peers: impl Fn() -> usize,
    sleep: impl Fn(Duration),
    grace: Duration,
    interval: Duration,
) -> ShutdownOutcome {
    broadcast();

    let mut waited = Duration::ZERO;
    loop {
        let peers_left = connected_peers();
        if peers_left == 0 {
            return ShutdownOutcome::Quiesced { waited };
        }
        if waited >= grace || interval.is_zero() {
            return ShutdownOutcome::GraceExpired { peers_left };
        }
        sleep(interval);
        waited += interval;
    }
}

/// Number of peers still in the network registry, or 0 when there is no
/// network controller to ask.
fn connected_peer_count() -> usize {
    NET_CONTROL
        .get()
        .map_or(0, |controller| controller.connected_peers().len())
}

/// Stop the light client.
///
/// Cancels the exit token every service and protocol observes, gives the
/// network a bounded moment to close its sessions, then moves to STOPPED and
/// notifies the status listener.
///
/// ## Stop is terminal
///
/// This does *not* tear the node down: `RUNTIME`, `STORAGE_WITH_DATA`,
/// `NET_CONTROL` and friends are `OnceLock`s, and `OnceLock` has no `reset`, so
/// the globals survive. [`start`] therefore refuses to run again and returns
/// [`BridgeError::Stopped`] — the app has to be relaunched, exactly as it does
/// for a network switch (#218). Stop means "shut down until relaunch".
///
/// ## Why not `wait_all_ckb_services_exit`
///
/// It was called here and it deadlocked (#487). `wait_all_ckb_services_exit`
/// calls `new_crossbeam_exit_rx()`, which *registers a fresh receiver*, and
/// then blocks on `recv()`. `broadcast_exit_signals` has already run by then and
/// only ever sends once, to the receivers that existed at broadcast time, so
/// nothing is ever delivered to that new receiver and `recv()` never returns.
/// Upstream ckb gets away with the same two calls because it waits first and
/// broadcasts later from a signal handler. On top of that the bridge registers
/// no thread handles with `ckb_stop_handler::register_thread`, so the join loop
/// on the far side of that `recv()` has nothing to join. Bounded polling of the
/// peer registry gives us the useful half of that wait with a guaranteed
/// return.
pub fn stop() -> Result<(), BridgeError> {
    // A second stop is not an error the caller can act on, but it is worth
    // distinguishing from "never initialized" in the logs and on the UI.
    if is_stopped() {
        warn!("Already stopped");
        return Err(BridgeError::Stopped);
    }

    // Check if running
    if !is_running() {
        warn!("Not in RUNNING state! Current state: {}", get_state());
        return Err(BridgeError::NotInitialized);
    }

    info!("Stopping CKB Light Client...");

    match shutdown_sequence(
        broadcast_exit_signals,
        connected_peer_count,
        std::thread::sleep,
        SHUTDOWN_GRACE,
        SHUTDOWN_POLL_INTERVAL,
    ) {
        ShutdownOutcome::Quiesced { waited } => {
            info!("Network wound down after {} ms", waited.as_millis());
        }
        ShutdownOutcome::GraceExpired { peers_left } => {
            warn!(
                "Network still had {} peer(s) after {} ms; stopping anyway",
                peers_left,
                SHUTDOWN_GRACE.as_millis()
            );
        }
    }

    // Transition to STOPPED
    set_state(STATE_STOPPED);

    info!("CKB Light Client stopped successfully!");

    // Notify status listener
    notify_status(STATE_STOPPED);

    Ok(())
}

/// Get current status
///
/// Returns:
/// - 0 (INIT): Initialized but not started
/// - 1 (RUNNING): Running
/// - 2 (STOPPED): Stopped
pub fn status() -> u8 {
    get_state()
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::cell::RefCell;

    /// `shutdown_sequence` must broadcast first and only then start polling:
    /// that ordering is the whole bug in #487, where the wait ran against a
    /// receiver registered after the broadcast had already fired.
    #[test]
    fn shutdown_sequence_broadcasts_before_polling() {
        let log = RefCell::new(Vec::new());

        let outcome = shutdown_sequence(
            || log.borrow_mut().push("broadcast"),
            || {
                log.borrow_mut().push("poll");
                0
            },
            |_| log.borrow_mut().push("sleep"),
            SHUTDOWN_GRACE,
            SHUTDOWN_POLL_INTERVAL,
        );

        assert_eq!(*log.borrow(), vec!["broadcast", "poll"]);
        assert_eq!(
            outcome,
            ShutdownOutcome::Quiesced {
                waited: Duration::ZERO
            }
        );
    }

    /// The wait ends as soon as the last session is gone, without burning the
    /// rest of the grace period.
    #[test]
    fn shutdown_sequence_returns_once_the_peers_are_gone() {
        let remaining = RefCell::new(2usize);
        let slept = RefCell::new(Vec::new());

        let outcome = shutdown_sequence(
            || {},
            || *remaining.borrow(),
            |interval| {
                slept.borrow_mut().push(interval);
                *remaining.borrow_mut() -= 1;
            },
            Duration::from_secs(2),
            Duration::from_millis(100),
        );

        assert_eq!(
            outcome,
            ShutdownOutcome::Quiesced {
                waited: Duration::from_millis(200)
            }
        );
        assert_eq!(slept.borrow().len(), 2);
    }

    /// A peer that never closes must not hold the caller hostage: the wait is
    /// bounded by the grace period and then gives up.
    #[test]
    fn shutdown_sequence_gives_up_after_the_grace_period() {
        let slept = RefCell::new(0usize);

        let outcome = shutdown_sequence(
            || {},
            || 3,
            |_| *slept.borrow_mut() += 1,
            Duration::from_secs(2),
            Duration::from_millis(100),
        );

        assert_eq!(outcome, ShutdownOutcome::GraceExpired { peers_left: 3 });
        assert_eq!(*slept.borrow(), 20, "2s of grace in 100ms steps");
    }

    /// A zero interval would spin forever against a peer that never leaves.
    #[test]
    fn shutdown_sequence_does_not_spin_on_a_zero_interval() {
        let outcome =
            shutdown_sequence(|| {}, || 1, |_| {}, Duration::from_secs(2), Duration::ZERO);

        assert_eq!(outcome, ShutdownOutcome::GraceExpired { peers_left: 1 });
    }

    /// Nothing in this test binary ever calls `init`, so the process-global
    /// `STATE` is still INIT: `stop` has to reject that, and it has to reject it
    /// straight away rather than blocking (#487).
    #[test]
    fn stop_without_a_started_client_reports_not_initialized() {
        let began = std::time::Instant::now();
        let result = stop();

        assert!(
            matches!(result, Err(BridgeError::NotInitialized)),
            "expected NotInitialized, got {result:?}"
        );
        assert!(
            began.elapsed() < Duration::from_secs(1),
            "stop blocked for {:?}",
            began.elapsed()
        );
    }

    /// End-to-end cover for the deadlock: init a node against a bootnode-less
    /// testnet config, start it, and require `stop` to return.
    ///
    /// `#[ignore]`d because it is not safe to run alongside the rest of this
    /// binary: `init` publishes the process-global `OnceLock`s and moves `STATE`
    /// for good, which would race
    /// `stop_without_a_started_client_reports_not_initialized` above, and
    /// nothing can put those globals back. It also opens a RocksDB store and
    /// binds a TCP listener. Run it on its own — it passes offline in well
    /// under a second, since there is no peer to wait for:
    ///
    /// ```text
    /// cargo test -p ckb-light-client-lib --lib -- --ignored --exact \
    ///     bridge_core::lifecycle::tests::stop_returns_after_init_and_start
    /// ```
    #[test]
    #[ignore = "mutates process-global state shared with the other tests; run it alone"]
    fn stop_returns_after_init_and_start() {
        let dir = tempfile::tempdir().expect("temp dir");
        let config_path = dir.path().join("testnet.toml");
        // No bootnodes and an ephemeral port: the node comes up, finds nobody,
        // and stop has no live session to wind down.
        fs::write(
            &config_path,
            r#"
chain = "testnet"

[store]
path = "store.db"

[network]
path = "network"
listen_addresses = ["/ip4/127.0.0.1/tcp/0"]
bootnodes = []
max_peers = 8
max_outbound_peers = 1
ping_interval_secs = 120
ping_timeout_secs = 1200
connect_outbound_interval_secs = 15
upnp = false
discovery_local_address = false
bootnode_mode = false

[rpc]
listen_address = "127.0.0.1:0"
"#,
        )
        .expect("write config");

        init(
            config_path.to_str().expect("utf-8 path"),
            dir.path().to_str().expect("utf-8 path"),
            None,
        )
        .expect("init");
        start().expect("start");

        let began = std::time::Instant::now();
        stop().expect("stop");
        let elapsed = began.elapsed();

        assert!(elapsed < Duration::from_secs(10), "stop took {elapsed:?}");
        assert!(is_stopped());
        assert!(
            matches!(start(), Err(BridgeError::Stopped)),
            "start after stop must report Stopped"
        );
    }
}
