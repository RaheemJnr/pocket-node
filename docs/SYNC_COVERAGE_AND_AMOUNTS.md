# Sync coverage and amount accuracy

## TL;DR

Balances and activity amounts are correct **for wallets synced from at or before
their first funding block**. The only way an amount can be wrong is a wallet
synced from a checkpoint *after* some of its funds arrived, which leaves
"pre-window" cells the light client can never see. The fix is coverage, not
computation: choose a **Custom** sync height at or before the wallet's first
transaction. This matters mainly for **imported** wallets, whose funding history
is unknown to us.

This document exists so we stop re-diagnosing this as a read-path or
computation bug. It is neither. It is an inherent property of a light client
that syncs from a checkpoint.

## How the light client indexes a wallet's history

The embedded CKB light client (`external/ckb-light-client`) processes each
matched block in `Storage::filter_block` (`storage/db/native.rs`). For each
transaction it:

- **Outputs:** for every output whose lock/type matches a registered script,
  writes a `CellLockScript`/`CellTypeScript` cell entry and a `TxLockScript`
  Output history entry, and stores the full transaction under `TxHash`.
- **Inputs:** for every input, it looks up the **previous** transaction that
  created the spent cell:

  ```rust
  if let Some((.., previous_tx)) = self.get_transaction(&previous_tx_hash) {
      let previous_output = previous_tx.raw().outputs().get(prev_index);
      if scripts.contains(&(previous_output.lock(), Lock)) {
          // delete the cell entry, write a TxLockScript Input history entry
      }
  }
  ```

  The input's lock is not carried in the transaction (a CKB input is just an
  `OutPoint`), so the client can only decide the input is *ours* by resolving
  the previous output from **its own storage**.

Two consequences follow, and they are the whole story:

1. **An input is indexed only if its previous transaction is already stored.**
2. **Stored transactions are never pruned.** The only deletion is
   `rollback_to_block`, used for reorgs.

## Why "wrong amounts" happen, and why only under checkpoint sync

If a cell was funded **before** the sync start block, its creating transaction
is never stored. When that cell is later spent in an in-window block,
`filter_block`'s `get_transaction(prev_hash)` misses, so the input is **never
matched and never indexed**. The pre-window cell is invisible everywhere:
balance, cells, and activity.

The visible symptom appears on a *mixed* transaction, one that spends a
pre-window cell **and** has an in-window output back to the wallet (change, or a
received output). Only the output is indexed, so:

- `netShannonsByTx` (Kotlin, `TransactionWalk.kt`) sees `+output` with no
  matching input, so the net looks positive.
- `GatewayRepository` classifies direction by net sign
  (`netChangeShannons > 0 -> "in"`), so a **send can render as a receive**, with
  an understated amount.

The spent cell's lock and capacity are genuinely absent from local data, so **no
read-path computation can recover them.** In particular:

- The JNI `get_transactions` handler (`jni_bridge/query.rs`) computes each
  input's `io_capacity` by looking up the previous transaction. Because of
  consequence (1) + (2) above, that lookup **always resolves for a legitimately
  indexed input** — the previous tx is in storage permanently. The `Ok(None)`
  branch there is a defensive guard for DB corruption or a mid-read reorg, not
  the source of wrong amounts. It logs and drops the row.

## The remedy: coverage

For a wallet synced from at/before its first funding block, no cell is ever
pre-window: every input's previous tx is stored, every capacity resolves, and
every balance and amount is correct by construction.

- **New wallets** start at the current tip, so they have no pre-window history
  and are always correct.
- **Imported wallets** have unknown funding history. If funds may have arrived
  long ago (e.g. a mining wallet, or a seed previously used in Neuron), pick a
  **Custom** sync height at or before the first transaction. Full history
  (genesis) is always safe; a known earlier height is faster.

This is already the guidance given to users ("sync from your first height") and
is available in the app via the Custom sync height option. We rely on that plus
the diagnostic logging in `query.rs` rather than attempting to reconstruct
unknowable pre-window data locally, which would require fetching historical
transactions from a full node and cuts against the light, self-custodial design.

## What we deliberately did NOT build

- **On-demand pre-window fetch** from a peer/full node to value missing inputs:
  most complete, but heaviest and most against the no-remote-server design.
- **A history probe** that asks a full node whether an address has pre-sync
  activity: reveals the address to a server, same objection.
- **Storing capacity in the cell index** so inputs could be valued without the
  previous tx: does not help, because the pre-window input is never indexed in
  the first place (consequence 1), so there is no row to attach capacity to.
