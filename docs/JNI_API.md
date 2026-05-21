# Pocket Node JNI API Reference

Reference documentation for the JNI surface between Pocket Node's Kotlin code and the embedded Rust CKB light client. This document targets developers who want to embed the same light client in their own Android apps, or who are contributing to Pocket Node and need to understand the boundary in detail.

End users do not need to read this.

## Table of contents

1. [Architecture overview](#architecture-overview)
2. [Lifecycle](#lifecycle)
3. [Configuration](#configuration)
4. [API reference](#api-reference)
5. [Build instructions](#build-instructions)
6. [Memory model](#memory-model)
7. [Threading](#threading)
8. [Error model](#error-model)
9. [Versioning and ABI stability](#versioning-and-abi-stability)

## Architecture overview

```
Kotlin layer (Pocket Node app)
       │
       │  external fun native* (24 entry points)
       ▼
JNI bridge (Rust, in external/ckb-light-client/light-client-jni/)
       │
       │  Direct in-process Rust calls
       ▼
ckb-light-client runtime (Rust)
       │
       │  TCP to CKB network bootnodes + peers
       ▼
CKB network (mainnet or testnet)
```

The bridge is a single Rust `cdylib` (`libckb_light_client_lib.so`) bundled into the APK at `lib/<abi>/`. Kotlin loads it via `System.loadLibrary("ckb_light_client_lib")` at first use of the `LightClientNative` object.

There is no separate process and no remote indexer. The Rust runtime runs in the same process as the Kotlin app and communicates over JNI for inputs and outputs. The runtime keeps its own Tokio executor and its own peer-discovery loop on background threads owned by the Rust side; Kotlin sees them only through the JNI surface.

### Why on-device

The motivation for embedding the light client rather than calling a remote API is sovereignty: the user does not trust any third party with balance, history, or transaction broadcast. The trade-off is local resource use (CPU, network, storage) and a first-sync delay that a remote API does not have.

## Lifecycle

The four lifecycle functions form a strict state machine.

```
        nativeInit            nativeStart
[NONE] ─────────────► [INIT] ─────────────► [RUNNING]
                                                │
                                                │ nativeStop
                                                ▼
                                            [STOPPED]
```

```kotlin
object LightClientNative {
    external fun nativeInit(configPath: String, statusCallback: StatusCallback): Boolean
    external fun nativeStart(): Boolean
    external fun nativeStop(): Boolean
    external fun nativeGetStatus(): Int  // 0=INIT, 1=RUNNING, 2=STOPPED
}
```

### Init

`nativeInit(configPath, statusCallback)` reads the TOML file at `configPath`, opens the storage directory referenced by the config, constructs the network controller, and registers the `statusCallback`. On success the runtime is fully constructed but not yet accepting connections.

**Idempotency.** Calling `nativeInit` twice on the same process always fails the second call. The Rust side uses `OnceLock` for global runtime state, and there is no exposed `reset` path. This is the most important non-obvious constraint in the API.

**Recoverable failure.** As of the v1.6+ hardening (PR #222 / audit #217), `nativeInit` defers writing to its `OnceLock`s until all subsystems initialize successfully. A failed init leaves the process clean and a subsequent retry can succeed without restarting the JVM.

### Start

`nativeStart` transitions the runtime from INIT to RUNNING. Peer discovery begins, block headers start arriving, and any registered filter scripts begin matching. The status callback fires with `"running"` once the runtime is fully up.

### Stop

`nativeStop` broadcasts an exit signal to all internal services and waits for them to drain. The status callback fires with `"stopped"` on completion.

**Crucial caveat.** `nativeStop` does not free the `OnceLock` global state. After stopping, a fresh `nativeInit` will still fail. To re-initialize, the host process must restart. Pocket Node handles this by calling `Process.killProcess(myPid())` after persisting the new configuration; the user-facing flow is gated by a confirm dialog explaining the restart.

Implementations that need clean re-init within a single process lifetime will need a Rust-side refactor to remove `OnceLock` from the runtime construction (tracked as audit Finding Critical 1 in #186; see `lifecycle.rs` head comment in the Rust source).

### Status callback

`StatusCallback.onStatusChange(status, data)` fires on a Rust-owned thread. Implementations must be thread-safe. Pocket Node forwards these into a `StateFlow` on the main dispatcher.

## Configuration

`nativeInit` takes a path to a TOML file. The format is the CKB light client config schema.

### Minimal configuration

```toml
chain = "testnet"   # or "mainnet"

[store]
path = "data/store"

[network]
path = "data/network"
listen_addresses = ["/ip4/0.0.0.0/tcp/8118"]
bootnodes = [
  "/ip4/18.217.146.65/tcp/8111/p2p/QmT6DFfm18wtbJz3y4aPNn3ac86N4d4p4xtfQRRPf73frC",
  # ... more bootnodes
]

[rpc]
listen_address = "127.0.0.1:9000"
```

### Field reference

| Field | Required | Notes |
|-------|----------|-------|
| `chain` | yes | One of `"mainnet"`, `"testnet"`, or an absolute path to a custom dev chain TOML. |
| `store.path` | yes | Relative to the parent directory of the config file; light client storage lives here. |
| `network.path` | yes | Relative to the parent directory of the config file; peer state lives here. |
| `network.listen_addresses` | yes | Multiaddr list. Use `["/ip4/0.0.0.0/tcp/8118"]` on Android; the port number is unique per app instance and arbitrary. |
| `network.bootnodes` | yes | Multiaddr list. The default mainnet/testnet bootnodes from the official ckb-light-client repo work without modification. |
| `network.public_addresses` | no | Leave empty on mobile; the device's address is rarely public. |
| `rpc.listen_address` | yes | Pocket Node binds to `127.0.0.1:9000`, internal-only. The light client also exposes an HTTP-JSON-RPC server on this socket as an alternative to JNI. |

### Data directory layout

On Android, Pocket Node copies `mainnet.toml` and `testnet.toml` from `assets/` into `context.filesDir` at first run, then passes the absolute path into `nativeInit`. The light client writes to `${filesDir}/data/<network>/store/` and `${filesDir}/data/<network>/network/` so that mainnet and testnet state are fully isolated.

Approximate storage footprint after a full mainnet RECENT-mode sync: 5 MB. FULL_HISTORY on mainnet: 50 to 150 MB depending on how many cells match the registered filter scripts.

## API reference

The JNI surface splits into three groups: lifecycle (4 functions), queries (17 functions), and DAO utilities (3 functions). All return JSON-encoded strings except where noted.

### Query functions

All functions in this section are safe to call from any thread and require the runtime to be in RUNNING state. Calling them in INIT or STOPPED returns null.

#### `nativeGetTipHeader(): String?`

Returns the JSON-encoded `HeaderView` for the chain tip the local light client has caught up to. This is the canonical "are we synced" signal: compare to `nativeGetHeaderByNumber("0x0").number` plus any scan progress reported by your filter logic.

#### `nativeGetGenesisBlock(): String?`

Returns the JSON-encoded `BlockView` for block 0 of the current network. Useful for sanity-checking that the chain ID matches.

#### `nativeGetHeader(hash: String): String?`

Lookup a header by its 0x-prefixed block hash. Returns null if the header has not been fetched yet (the light client only retains headers for matched cells and a sliding tip window).

#### `nativeFetchHeader(hash: String): String?`

Like `nativeGetHeader` but requests the header from peers if not locally available. Returns a `FetchStatus<HeaderView>` JSON value with three possible discriminants: `Fetched`, `Fetching`, `NotFound`. Callers typically retry with backoff while seeing `Fetching`.

#### `nativeGetHeaderByNumber(blockNumber: String): String?`

Looks up a header by block number (decimal or 0x-prefixed hex). Implemented as a two-hop: block number to block hash to header. Only works for blocks the light client has processed (containing a matched transaction or in the tip window).

#### `nativeSetScripts(scriptsJson: String, command: Int): Boolean`

Register the lock or type scripts you want the light client to scan for. `scriptsJson` is a JSON array of `ScriptStatus`:

```json
[
  {
    "script": { "code_hash": "0x9bd7e06f...", "hash_type": "type", "args": "0xaabb..." },
    "script_type": "lock",
    "block_number": "0x4c4b40"
  }
]
```

`block_number` is the start of the scan window (the light client will scan forward from this block looking for matches). `command` is one of:

- `0` (`CMD_SET_SCRIPTS_ALL`): replace the entire registered set with `scriptsJson`.
- `1` (`CMD_SET_SCRIPTS_PARTIAL`): merge `scriptsJson` into the existing registered set, updating any scripts that already exist.
- `2` (`CMD_SET_SCRIPTS_DELETE`): remove the listed scripts from the registered set.

Returns `true` on success. The runtime starts scanning forward from `block_number` asynchronously; progress is observable via the query functions, not via this return value.

#### `nativeGetScripts(): String?`

Returns the currently registered `ScriptStatus` array as JSON. Useful for verifying that `nativeSetScripts` registered what you expected.

#### `nativeGetCells(searchKeyJson, order, limit, cursor): String?`

Paginated query for live cells (unspent transaction outputs) matching a `SearchKey`. `SearchKey` is the standard CKB indexer search key format (lock script, type script, script search mode, filter).

`order` is `"asc"` or `"desc"` (by block number then output index). `limit` is an integer up to 1000. `cursor` is the pagination cursor returned by the previous call, or null for the first page. Returns a JSON `Pagination<Cell>` with `objects` and `last_cursor` fields.

#### `nativeGetTransactions(searchKeyJson, order, limit, cursor): String?`

Same shape as `nativeGetCells` but returns matching transactions instead of cells. Use this to populate transaction history UI.

#### `nativeGetCellsCapacity(searchKeyJson): String?`

Sum the capacity (in shannons) of all live cells matching a `SearchKey`. Returns a JSON `CellsCapacity` with `capacity`, `block_hash`, and `block_number` fields. This is the canonical balance query.

#### `nativeSendTransaction(txJson: String): String?`

Broadcast a fully-signed transaction. `txJson` is the JSON encoding of a `Transaction`. Returns the transaction hash (hex string with 0x prefix) on success, or null on failure (rejection, network error, invalid transaction).

The transaction is broadcast to the light client's peers, who relay it into the mempool. Confirmation status must be polled separately via `nativeGetTransaction`.

#### `nativeGetTransaction(hash: String): String?`

Look up a transaction by hash. Returns a JSON `TransactionWithStatus` with `transaction` and `tx_status` fields. `tx_status.status` is one of `Pending`, `Proposed`, `Committed`, `Unknown`, `Rejected`.

#### `nativeFetchTransaction(hash: String): String?`

Like `nativeGetTransaction` but requests the transaction from peers if not locally cached. Returns a `FetchStatus<TransactionWithStatus>` JSON value.

#### `nativeLocalNodeInfo(): String?`

Returns a JSON `LocalNode` with version, node ID, listen addresses, and connection counts. Used for diagnostic UI (Node Status screen).

#### `nativeGetPeers(): String?`

Returns a JSON array of `RemoteNode` describing all currently connected peers. Pocket Node uses the array length as the peer count shown in the UI.

#### `nativeEstimateCycles(txJson: String): String?`

Pre-flight estimate of the number of cycles a transaction will consume on execution. Returns a JSON `EstimateCycles` with a single `cycles` field. Use this to validate that a transaction will fit within a block's cycle budget before broadcasting.

#### `callRpc(method: String): String?`

Generic JSON-RPC 2.0 endpoint exposing the methods that do not have a dedicated JNI export. Supported methods:

- `get_peers`
- `get_tip_header`
- `get_genesis_block`
- `get_scripts`

Returns the full JSON-RPC 2.0 response string (with `jsonrpc`, `id`, and `result` or `error` fields). For new code, prefer the dedicated `nativeGet*` functions, which return only the result payload.

### DAO utility functions

These do not touch the runtime; they are pure functions exported for convenience because they compose with native CKB DAO field encoding that Kotlin would otherwise need to replicate.

#### `nativeExtractDaoFields(daoHex: String): String?`

Parse a 32-byte DAO header field (hex-encoded) into its four 8-byte components: `C` (total occupied capacity), `AR` (accumulated rate), `S` (total secondary issuance), `U` (total unmade secondary issuance). Returns a JSON object with these four fields as decimal strings.

#### `nativeCalculateMaxWithdraw(depositHex, withdrawHex, depositCapacity, occupiedCapacity): Long`

Returns the maximum withdrawable capacity in shannons for a Nervos DAO deposit cell, given:

- the deposit's block header DAO field (32 bytes hex),
- the withdraw block header DAO field (32 bytes hex),
- the deposit cell's capacity in shannons,
- the deposit cell's occupied capacity in shannons.

The formula follows CKB DAO protocol specification (see CKB RFC 0023).

#### `nativeCalculateUnlockEpoch(depositEpochHex, withdrawEpochHex): String?`

Compute the absolute-epoch since value required for phase-2 DAO withdrawal unlock. Both inputs are hex-encoded epoch values from the respective block headers. Returns a hex string of the since field, or null on invalid input.

### Constants

```kotlin
const val STATUS_INIT = 0
const val STATUS_RUNNING = 1
const val STATUS_STOPPED = 2

const val CMD_SET_SCRIPTS_ALL = 0
const val CMD_SET_SCRIPTS_PARTIAL = 1
const val CMD_SET_SCRIPTS_DELETE = 2
```

## Build instructions

The Rust JNI library builds via Cargo into per-ABI `.so` files that the Gradle build copies into `app/src/main/jniLibs/`.

### Prerequisites

- Rust 1.75 or newer with Android cross-compilation targets:
  ```bash
  rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android i686-linux-android
  ```
- Android NDK (Pocket Node tests against NDK 26 and newer; auto-detected by the Gradle build if installed at `~/Library/Android/sdk/ndk/`).
- `cargo-ndk` for friendlier cross-compilation:
  ```bash
  cargo install cargo-ndk
  ```

### Build script

The repository ships `external/ckb-light-client/build-android-jni.sh` which builds all four ABIs and stages them. The Gradle `preBuild` task depends on `cargoBuild`, which invokes this script, so a normal `./gradlew assembleDebug` builds the JNI library automatically.

For Kotlin-only iteration (no Rust changes), skip the Cargo step:

```bash
./gradlew assembleDebug -x cargoBuild
```

### ABI targets

The four targets shipped in release APKs:

| ABI | Target triple | Devices |
|-----|---------------|---------|
| `arm64-v8a` | `aarch64-linux-android` | All modern phones (2017+) |
| `armeabi-v7a` | `armv7-linux-androideabi` | Older phones, kept for compatibility |
| `x86_64` | `x86_64-linux-android` | Android emulator on Intel/AMD hosts |
| `x86` | `i686-linux-android` | Legacy emulator support |

Pocket Node enables APK splits by ABI in the release build, so a downloading user pulls only the `.so` for their device's architecture.

## Memory model

The JNI boundary uses JSON strings for all complex types, which sidesteps most ownership questions: each call allocates fresh memory on the Rust side, the Rust side hands a `jstring` back, and the JVM owns the resulting Java `String`.

### What lives on each side

| Owner | Owns |
|-------|------|
| Rust runtime | Tokio executor, network controller, storage handles, peer state, registered scripts, header cache |
| JNI bridge | Per-call argument decoding, return-value JSON encoding |
| JVM | Returned `String` results, `StatusCallback` instance |

Nothing from the Rust runtime survives outside the function call as a raw pointer or handle visible to Kotlin. The only Rust state Kotlin can address is the implicit global runtime that the lifecycle functions manage.

### Panic safety

All 24 JNI entry points wrap their bodies in `catch_unwind` (added in PR #221 / audit Finding High 1). A panic in Rust code never unwinds into the JVM; instead the entry point logs the panic, sets the runtime's `RwLock` into a recovered state if needed, and returns null (or false for boolean-returning functions).

This matters because Rust panics across the FFI boundary are undefined behavior in older Rust versions and aborts in newer ones. Without `catch_unwind`, a single panic could SIGABRT the app process.

### String handling

Kotlin passes `String` arguments as `jstring`. The Rust bridge calls `env.get_string()` which copies the UTF-16 contents into a Rust-owned `String` for the duration of the call. The original `jstring` is not retained.

Return values are constructed as Rust `String`s, then handed to `env.new_string()` to produce a `jstring`. The JVM takes ownership of the resulting Java String; Rust does not retain a reference.

## Threading

### Query functions

All 17 query functions are documented as safe to call from any thread, including concurrently. Internally they take read locks on the runtime's `RwLock`-protected state. A single panicked writer can poison the lock; as of PR #221 the bridge recovers the lock state and subsequent calls succeed.

### Lifecycle functions

`nativeInit`, `nativeStart`, and `nativeStop` are NOT safe to call concurrently with each other or with query functions. Pocket Node serializes them through a Kotlin-side mutex; external embedders should do the same.

### Status callback

`StatusCallback.onStatusChange` fires on a Rust-owned thread (the one driving the runtime state machine). Implementations must:

- Be thread-safe (no `@MainThread` reliance).
- Not block (no long synchronous work; bounce to a coroutine if needed).
- Tolerate being called any number of times, including duplicate consecutive statuses.

Pocket Node's implementation forwards the status into a `StateFlow` on `Dispatchers.IO` and surfaces it to the UI via standard flow collection.

### Tokio runtime

The Rust runtime owns its own Tokio executor. Kotlin does not see these threads directly; they are background daemon threads from the JVM's perspective and do not block process shutdown.

## Error model

JNI functions never throw Java exceptions across the boundary in normal operation. The convention is:

| Return type | Failure value |
|-------------|---------------|
| `String?` | `null` |
| `Boolean` | `false` |
| `Long` | `0` or a sentinel; check the call's documentation |
| `Int` | `-1` for status; otherwise documented per call |

The exact failure mode (network error, not-found, invalid input, panic) is logged on the Rust side via `log::error!` and routed to logcat under the `ckb_light_client_lib` tag. Callers receive only the null/false result; the log line is the diagnostic path.

### JNI exceptions

The only path that does raise a Java exception is `UnsatisfiedLinkError`, which fires at class-load time if the `.so` is missing from the APK. Pocket Node's `init` block catches this and logs to stderr so that Robolectric unit tests (which run on the JVM without the native library) can still instantiate the `LightClientNative` object.

Production builds always ship with the library bundled, so `UnsatisfiedLinkError` should never fire on a real device. If it does, the APK is corrupted or the wrong ABI was installed.

### Common null causes

| Function | Common cause of null return |
|----------|----------------------------|
| `nativeGetHeader(hash)` | Header not in local store (only matched-cell headers and tip-window headers are retained) |
| `nativeGetHeaderByNumber(n)` | Block number outside the light client's scanned range |
| `nativeGetTransaction(hash)` | Transaction not yet broadcast, not yet propagated, or already pruned |
| `nativeSendTransaction(tx)` | Transaction was rejected (insufficient capacity, bad signature, mempool full); see logcat |
| `nativeGetCells(...)` | Search key matched zero cells (returns null instead of an empty page; future versions may return an empty `Pagination`) |

## Versioning and ABI stability

The JNI surface is versioned with Pocket Node's app version, not independently. Both the Kotlin signatures and the Rust ABI are subject to change between Pocket Node minor versions (1.6, 1.7, 1.8, ...). Patch versions (1.7.0, 1.7.1) do not change the JNI surface.

### Pinned Rust source

The Rust source for the light client lives at `external/ckb-light-client/` as a vendored copy. The exact commit is pinned per Pocket Node release; the upstream is `https://github.com/nervosnetwork/ckb-light-client` with minor patches for Android-specific build flags.

### Reproducible builds

Cargo.lock is checked in. The Android NDK and Rust toolchain versions are pinned in the CI workflow. Pocket Node's release process produces deterministic `.so` files modulo NDK linker output; users can build from source and compare hashes against published release artifacts.

### Embedding outside Pocket Node

If you want to use the JNI library in your own Android app:

1. Clone the Pocket Node repository.
2. Run `cd external/ckb-light-client && ./build-android-jni.sh` to produce the `.so` files.
3. Copy `lib/<abi>/libckb_light_client_lib.so` into your app's `src/main/jniLibs/<abi>/`.
4. Copy `LightClientNative.kt` (or rewrite the bindings yourself; only the JNI symbol names matter) into your project.
5. Bundle a TOML config in your assets, copy it to `filesDir` at runtime, and pass the absolute path to `nativeInit`.

There is no published Maven artifact at this time. If demand exists, file an issue on the Pocket Node repository.

---

Last updated: 2026-05-21. Updated when the JNI surface changes.
