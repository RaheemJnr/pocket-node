# Embedded client maintenance

The native library source is pinned to upstream `nervosnetwork/ckb-light-client`
commit `9ce5fc6507ce1379dd8bd4b947426078bb2062e8`. `upstream.json` records the
archive checksum, every upstream file, every vendored file, and the exact list
of omitted, modified, and mobile-only paths. `mobile.patch` reconstructs the
vendored tree from that immutable upstream tree. CI verifies both reconstruction
and checked-in files. The directory is vendored source, not a submodule.

Mobile compatibility patches retain:

* The JNI and UniFFI adapters, lifecycle/panic guards, JSON payloads (including
  per-IO capacity), mobile peer/resource limits, and release overflow checks.
* SQLite's existing `store.db` file and `kv(key BLOB PRIMARY KEY, value BLOB)`
  schema, parent-directory creation, existing cache settings, and legacy mobile
  iterator adapter. The upstream shared storage/service implementation is used.
* rusqlite 0.29, the mobile bridge dependencies, and the library-only Cargo
  workspace. Rust is pinned to upstream's 1.95.0. The Cargo lock is a mobile
  overlay; it also includes compatible advisory fixes beyond the upstream pin.
* Source-based tests using temporary SQLite files. No regression test uses a
  wallet, sends a transaction, or needs a public peer.

Desktop executable, standalone WASM packages, upstream release workflows, and
upstream development-only documentation are omitted explicitly in the manifest.
The shared WASM dependency follows upstream, but WASM is not a supported mobile
release target. Mobile build scripts and this repository's CI remain authoritative.

Deferred backend work from the native integration review:

* The retained, unsupported browser backend groups batch deletes before puts,
  so a put followed by a delete of the same key has the wrong result. Its
  exclusive cursor handling also needs review with the omitted IndexedDB worker.
  Supporting WASM requires ordered, atomic batch operations and browser tests;
  the mobile SQLite backend applies batch operations in call order.
* SQLite pagination uses strict comparisons for exclusive cursors, including
  deleted cursors, but still collects matching rows before applying the result
  limit. Bounded scans need a separate design for snapshots and concurrent writes
  while preserving callbacks that reenter storage.

## Verify and maintain

Download the pinned archive, then run:

```sh
python3 tools/embedded-client/verify.py --archive /path/to/ckb-light-client.tar.gz
```

An already extracted tree can be used with `--upstream-tree`; all its file hashes
are checked. To record an intentional mobile edit against the same pin:

```sh
python3 tools/embedded-client/refresh.py --upstream-tree /path/to/ckb-light-client-9ce5fc6507ce1379dd8bd4b947426078bb2062e8
python3 tools/embedded-client/verify.py --upstream-tree /path/to/ckb-light-client-9ce5fc6507ce1379dd8bd4b947426078bb2062e8
```

Review the source diff and generated overlay together. An upstream version update
requires a new archive digest and an explicit review of every mobile delta;
do not regenerate hashes to conceal unexplained drift.

The pin check detects local drift, not new upstream fixes or advisories. Native
maintainers must review upstream changes and run an unfiltered `cargo audit`
at least monthly and before each native integration release. Record any remaining
exception with the vulnerable operation, mobile reachability and upstream blocker.
Do not upload local diagnostic logs or private regression material in CI.

## Cache compatibility

Store format 2 retains the `kv` layout. The transactional 1-to-2 upgrade removes
chain-derived transaction/cell/header/filter evidence, saves only old script
metadata in an inactive table, and writes a random verification generation.
Unknown metadata is preserved. Both apps read `get_verification_epoch` through
the existing RPC adapter. Before displaying cached chain claims or registering
scripts, they clear every wallet's saved progress and derived caches for that
network, quarantine unverified history, then durably record the generation.
Local pending/failed transaction records and pending broadcast/DAO reconciliation
records survive. Wallet keys, birth settings, sync modes and custom horizons are
outside this operation. Registration recomputes the start using existing settings.

A failed database transaction rolls back. A failed applied-generation write keeps
the UI/registration gate closed and retries cleanup on the next attempt. This is
a two-store handoff, not a cross-database transaction.

Downgrading native integrations is unsupported. The preceding format-1 binary
sets a format-2 store aside under its existing recovery policy. Returning to this
build creates a new generation and repeats the app handoff; a previous applied
marker cannot authorize the recreated store. No set-aside file is automatically
restored as verified chain evidence.
