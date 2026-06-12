# Local devnet harness (manual, opt-in)

Validation recipe used for #332/#342 (2026-06-12). Points a DEBUG build's
TESTNET network at a local CKB dev node — never commit the override into
`android/app/src/debug/assets/`, or every debug build's testnet breaks.

## Steps
1. Run a local node + miner (needs `LightClient` + `Filter` in ckb.toml
   `support_protocols`): `./ckb run` and `./ckb miner`.
2. Create `android/app/src/debug/assets/testnet.toml` (gitignored — keep the
   real file local only). Start from the production testnet asset and change:

   ```toml
   chain = "/data/data/com.rjnr.pocketnode/files/dev.toml"

   [network]
   max_outbound_peers = 1   # light client needs ceil(max_outbound/2) proved peers

   bootnodes = [
     "/ip4/10.0.2.2/tcp/<P2P_PORT>/p2p/<NODE_ID from local_node_info RPC>"
   ]
   ```
3. Push a **Dummy-PoW** copy of the node's chain spec into the app
   (light client verifies header PoW against its spec; mixed-era dev chains
   fail Eaglesong verification with InvalidNonce(432)):
   `sed 's/func = "Eaglesong"/func = "Dummy"/' specs/dev.toml > /tmp/dev-lc.toml`
   `adb push /tmp/dev-lc.toml /data/local/tmp/dev.toml`
   `adb shell "run-as com.rjnr.pocketnode cp /data/local/tmp/dev.toml files/dev.toml"`
4. In the app, switch network to Testnet (Settings → Current Network).
5. Mine continuously — the light client rejects peers whose tip is >24h old
   (PeerIsInIBD).
6. Fund via `ckb-cli wallet transfer --privkey-path <genesis dev key>`.

Known limit: DAO deposits from the app fail on devnet — the app's hardcoded
testnet secp dep_group tx hash doesn't exist in a dev genesis. Not an app bug.
