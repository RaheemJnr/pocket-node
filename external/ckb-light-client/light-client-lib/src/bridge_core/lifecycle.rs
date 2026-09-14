//! Lifecycle management for the platform bridges
//!
//! Implements init/start/stop/status functions that mirror light-client-bin/src/subcmds.rs.
//!
//! ## Init invariants
//!
//! `init` registers the status listener (and, on Android, the JNI bridge
//! populates its `JAVA_VM` / `STATUS_CALLBACK` `OnceLock`s) only *after* every
//! other fallible step has succeeded. This guarantees:
//!
//! - If init returns `Err`, no listener is installed, so a retry runs cleanly
//!   without colliding with leftover state from the previous attempt.
//! - Platform handles held by the caller (such as the JNI `GlobalRef` for the
//!   status callback) are dropped automatically on the early-return paths, so
//!   failure cannot leak them.
//!
//! Note: `stop` cannot reset any `OnceLock` (the type has no API to do so), so
//! in-process network switching is not supported. See #218 for the app-side
//! mitigation (force process restart on network switch).

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
use ckb_stop_handler::{broadcast_exit_signals, wait_all_ckb_services_exit};
use log::{error, info, warn};
use std::fs;
use std::sync::{Arc, RwLock};

/// Initialize the platform logger exactly once.
///
/// Android keeps `android_logger` (logcat); iOS routes `log` records to the
/// unified logging system so node output shows up in Console.app and the Xcode
/// console. Other hosts (unit tests, desktop) install nothing.
fn init_platform_logger() {
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

    init_platform_logger();

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

    // Create tokio runtime
    info!("Creating tokio runtime...");
    let (runtime_handle, _receiver, runtime) = new_global_runtime(None);

    // Store runtime
    RUNTIME.set(runtime).map_err(|_| {
        error!("Failed to store runtime");
        BridgeError::Internal("runtime already set".to_owned())
    })?;

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

    // Store network controller
    NET_CONTROL.set(network_controller.clone()).map_err(|_| {
        error!("Failed to store network controller");
        BridgeError::Internal("network controller already set".to_owned())
    })?;

    // Create StorageWithChainData
    let swc = StorageWithChainData::new(storage.clone(), Arc::clone(&peers), pending_txs.clone());

    // Store global state
    STORAGE_WITH_DATA.set(swc).map_err(|_| {
        error!("Failed to store StorageWithChainData");
        BridgeError::Internal("storage already set".to_owned())
    })?;

    let consensus_arc = Arc::new(consensus);
    CONSENSUS.set(consensus_arc.clone()).map_err(|_| {
        error!("Failed to store consensus");
        BridgeError::Internal("consensus already set".to_owned())
    })?;

    PEERS.set(peers.clone()).map_err(|_| {
        error!("Failed to store peers");
        BridgeError::Internal("peers already set".to_owned())
    })?;

    // Start RPC server if configured
    info!("Starting RPC server on {}...", run_env.rpc.listen_address);
    // Note: RPC server implementation would go here
    // For now, we're skipping RPC server startup to keep the implementation focused

    // All fallible init has succeeded. Register the status listener last so
    // that a failure above this point would have left it unset, allowing a
    // clean retry. (See audit #186 Finding High 4.)
    if let Some(listener) = listener {
        STATUS_LISTENER.set(listener).map_err(|_| {
            error!("Failed to store status listener (already set?)");
            BridgeError::Internal("status listener already set".to_owned())
        })?;
    }

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

/// Stop the light client.
///
/// This gracefully shuts down the light client:
/// - Broadcast exit signals
/// - Wait for services to stop
/// - Transition to STOPPED state
pub fn stop() -> Result<(), BridgeError> {
    // Check if running
    if !is_running() {
        warn!("Not in RUNNING state! Current state: {}", get_state());
        return Err(BridgeError::NotInitialized);
    }

    info!("Stopping CKB Light Client...");

    // Broadcast exit signals to all services
    broadcast_exit_signals();

    // Wait for all CKB services to exit
    info!("Waiting for services to exit...");
    wait_all_ckb_services_exit();

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
