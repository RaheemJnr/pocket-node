# DAO protocol fixture handoff

`dao-protocol-fixture.json` is literal output from
`tests::protocols::light_client::send_transactions_proof::recovery_dao_protocol_bridge_fixture`
in the vendored native crate. Do not edit its receipt, identity, token, header or
raw transaction fields. Regenerate using:

```sh
CARGO_TARGET_DIR=/private/tmp/pocket-dao-recovery-task1-target \
CARGO_PROFILE_DEV_DEBUG=0 CARGO_PROFILE_TEST_DEBUG=0 CARGO_INCREMENTAL=0 \
POCKET_DAO_PROTOCOL_FIXTURE_PATH="$PWD/android/app/src/test/resources/dao-protocol-fixture.json" \
cargo +1.95.0 test --manifest-path external/ckb-light-client/Cargo.toml \
  -p ckb-light-client-lib --locked -j3 --features uniffi-bridge \
  --lib recovery_dao_protocol_bridge_fixture
```

This is a **fixture handoff**, not a live JNI integration. The native test makes
a real competing branch, retains the old transaction/header association through
production rollback, re-includes the raw transaction from height 23 at height 25,
and sends the generated wire proof through the real protocol handler. Request,
poll, validation, expiry and restart failure envelopes use the production bridge
adapter via a test-only seam. No successful receipt is fabricated. Expiry is
observed by the registry at a future monotonic sample; restart uses a new registry
with a different run identity. The Android test replays these bytes through the
shared typed client and actual Room coordinator; it does not rerun cryptography.

The chain uses **dummy PoW**. For the two blocks containing the synthetic
DAO-shaped transaction, the producer uses `Switch::DISABLE_ALL`: epoch, uncles,
two-phase commit, DAO header, reward, non-contextual, script and extension
full-node verification are bypassed. Other blocks use the ordinary test miner.
The original `recovery_real_fork_reinclusion_and_commit_invalidation` fixture
continues to use full producer checks. Neither test weakens the light-client
handler: request membership, configured PoW, extension commitments, header MMR
and transaction Merkle proof checks still run before receipt publication.
This fixture proves raw membership and the bridge/Room association contract;
it does not establish full-node DAO script validity, witness authorization,
unspentness, production PoW security, or device ABI behavior.

The fixture is intentionally nondeterministic in mined timestamps/block hashes;
Android derives its synthetic immutable row identity from the emitted data.
Regeneration changes the fixture bytes and must accompany a native test pass.
