//! Dedicated v1 fresh-membership operations; never routed through read-only RPC.
//! Receipts authenticate raw transaction membership, not witnesses or unspentness.
use super::{types::*, BridgeError};
use crate::protocols::light_client::recovery::*;
use ckb_jsonrpc_types::{HeaderView, Transaction};
use ckb_types::{packed, prelude::*, H256};
use serde::{de::DeserializeOwned, Serialize};
use serde_json::{json, Value};
use std::str::FromStr;

const MAX_TOKEN_BYTES: usize = 2048;
fn hex(bytes: &[u8]) -> String {
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}
fn token<T: Serialize>(kind: &str, id: &T) -> String {
    // The ID types contain only fixed-size integers/arrays; serialization is infallible.
    format!(
        "dr1.{kind}.{}",
        hex(&serde_json::to_vec(id).expect("fixed recovery ID"))
    )
}
fn decode<T: DeserializeOwned + Serialize>(kind: &str, input: &str) -> Result<T, &'static str> {
    let prefix = format!("dr1.{kind}.");
    let data = input.strip_prefix(&prefix).ok_or("invalid_token")?;
    if input.len() > MAX_TOKEN_BYTES || data.is_empty() || data.len() % 2 != 0 {
        return Err("invalid_token");
    }
    let bytes = data
        .as_bytes()
        .chunks_exact(2)
        .map(|pair| {
            let nibble = |c| match c {
                b'0'..=b'9' => Some(c - b'0'),
                b'a'..=b'f' => Some(c - b'a' + 10),
                _ => None,
            };
            Ok(nibble(pair[0]).ok_or("invalid_token")? * 16
                + nibble(pair[1]).ok_or("invalid_token")?)
        })
        .collect::<Result<Vec<u8>, &str>>()?;
    let id: T = serde_json::from_slice(&bytes).map_err(|_| "invalid_token")?;
    if token(kind, &id) != input {
        return Err("invalid_token");
    }
    Ok(id)
}
fn error_code(error: RecoveryError) -> &'static str {
    match error {
        RecoveryError::Unavailable => "unavailable",
        RecoveryError::Capacity => "capacity",
        RecoveryError::UnknownRequest => "unknown_request",
        RecoveryError::Expired => "expired",
        RecoveryError::SequenceExhausted => "sequence_exhausted",
        RecoveryError::Storage => "storage",
        RecoveryError::InvalidReceipt => "invalid_receipt",
    }
}
fn identity(value: &RecoveryIdentity) -> Value {
    json!({"genesis_hash":format!("{:#x}",value.genesis_hash), "store_generation":value.store_generation,
        "run_nonce":format!("0x{}",hex(&value.run_nonce))})
}
fn receipt(value: &RecoveryReceipt) -> Value {
    let tx = packed::Transaction::new_builder()
        .raw(value.transaction.clone())
        .build();
    let mut raw = serde_json::to_value(Transaction::from(tx)).expect("raw transaction JSON");
    raw.as_object_mut()
        .expect("transaction object")
        .remove("witnesses");
    json!({"schema_version":value.schema_version,"proof_kind":"raw_transaction_membership",
        "receipt_id":token("r",&value.id),"request_id":token("q",&value.request_id),
        "attempt_id":token("a",&value.attempt_id),"identity":identity(&value.identity),
        "revision":value.revision.to_string(),"chain_anchor":HeaderView::from(value.chain.anchor.clone().into_view()),
        "transaction_hash":format!("{:#x}",value.transaction.calc_tx_hash()),
        "raw_transaction":raw,"header":HeaderView::from(value.header.clone().into_view()),
        "transaction_index":value.transaction_index})
}
struct Adapter<'a> {
    state: u8,
    registry: &'a RecoveryRegistry,
}
impl Adapter<'_> {
    fn result(&self, operation: &str, first: &str, second: &str) -> Result<Value, &'static str> {
        let now = Instant::now();
        match operation {
            "request" => {
                let hash = H256::from_str(first.strip_prefix("0x").unwrap_or(first))
                    .map_err(|_| "invalid_hash")?;
                let identity_snapshot = self.registry.identity().map_err(error_code)?;
                let id = self
                    .registry
                    .request(hash.pack(), now)
                    .map_err(error_code)?;
                Ok(
                    json!({"request_id":token("q",&id),"transaction_hash":format!("{hash:#x}"),"identity":identity(&identity_snapshot)}),
                )
            }
            "poll" => {
                let id = decode::<RequestId>("q", first)?;
                let (status, proof) = match self.registry.poll(id, now).map_err(error_code)? {
                    RecoveryStatus::Queued => ("queued", None),
                    RecoveryStatus::Fetching => ("fetching", None),
                    RecoveryStatus::Verified(r) => ("verified", Some(receipt(&r))),
                    RecoveryStatus::Unavailable => ("unavailable", None),
                    RecoveryStatus::Expired => ("expired", None),
                    RecoveryStatus::Cancelled => ("cancelled", None),
                };
                Ok(json!({"request_id":first,"status":status,"receipt":proof}))
            }
            "validate" => {
                let id = decode::<RequestId>("q", first)?;
                let rid = decode::<ReceiptId>("r", second)?;
                let proof = self.registry.validate(id, rid, now).map_err(error_code)?;
                Ok(json!({"request_id":first,"valid":true,"receipt":receipt(&proof)}))
            }
            "cancel" => {
                let id = decode::<RequestId>("q", first)?;
                self.registry.cancel(id).map_err(error_code)?;
                Ok(json!({"request_id":first,"status":"cancelled"}))
            }
            _ => Err("unknown_operation"),
        }
    }
    fn call(&self, operation: &str, first: &str, second: &str) -> Result<String, BridgeError> {
        ensure_running(self.state)?;
        let mut envelope = json!({"schema_version":RECOVERY_SCHEMA_VERSION,"operation":operation});
        match self.result(operation, first, second) {
            Ok(result) => envelope["result"] = result,
            Err(code) => envelope["error"] = json!({"code":code}),
        }
        serde_json::to_string(&envelope).map_err(|e| BridgeError::Internal(e.to_string()))
    }
}
fn ensure_running(state: u8) -> Result<(), BridgeError> {
    match state {
        STATE_RUNNING => Ok(()),
        STATE_STOPPED => Err(BridgeError::Stopped),
        _ => Err(BridgeError::NotInitialized),
    }
}
fn call(operation: &str, first: &str, second: &str) -> Result<String, BridgeError> {
    ensure_running(get_state())?;
    let storage = STORAGE_WITH_DATA.get().ok_or(BridgeError::NotInitialized)?;
    Adapter {
        state: get_state(),
        registry: storage.recovery(),
    }
    .call(operation, first, second)
}
/// Register a fresh request even when this transaction is cached or pending.
pub fn request_dao_recovery(tx_hash: &str) -> Result<String, BridgeError> {
    call("request", tx_hash, "")
}
pub fn poll_dao_recovery(request_id: &str) -> Result<String, BridgeError> {
    call("poll", request_id, "")
}
/// Point-in-time validation; callers must repeat it at each authorization boundary.
pub fn validate_dao_recovery(request_id: &str, receipt_id: &str) -> Result<String, BridgeError> {
    call("validate", request_id, receipt_id)
}
pub fn cancel_dao_recovery(request_id: &str) -> Result<String, BridgeError> {
    call("cancel", request_id, "")
}

/// Test seam for real protocol fixtures; uses the production envelope adapter.
#[cfg(test)]
pub(crate) fn protocol_fixture_call(
    registry: &RecoveryRegistry, operation: &str, first: &str, second: &str,
) -> Value {
    serde_json::from_str(&Adapter { state: STATE_RUNNING, registry }
        .call(operation, first, second).unwrap()).unwrap()
}

#[cfg(test)]
pub(crate) fn protocol_fixture_request_id(envelope: &Value) -> RequestId {
    decode("q", envelope["result"]["request_id"].as_str().unwrap()).unwrap()
}

#[cfg(test)]
mod tests {
    use super::*;
    use ckb_types::packed;
    use serde_json::{json, Value};
    fn make_registry() -> RecoveryRegistry {
        RecoveryRegistry::new(
            RecoveryIdentity {
                genesis_hash: packed::Byte32::default(),
                store_generation: "synthetic-store".into(),
                run_nonce: [7; 16],
            },
            ChainSnapshot {
                anchor: packed::Header::default(),
            },
        )
    }
    fn value(adapter: &Adapter, op: &str, first: &str, second: &str) -> Value {
        serde_json::from_str(&adapter.call(op, first, second).unwrap()).unwrap()
    }
    fn transaction() -> packed::Transaction {
        packed::Transaction::new_builder()
            .raw(
                packed::RawTransaction::new_builder()
                    .cell_deps(vec![packed::CellDep::default()])
                    .header_deps(vec![packed::Byte32::default()])
                    .inputs(vec![packed::CellInput::default()])
                    .outputs(vec![packed::CellOutput::new_builder()
                        .capacity(100_000_000u64)
                        .build()])
                    .outputs_data(vec![ckb_types::bytes::Bytes::from(vec![0u8; 8]).pack()])
                    .build(),
            )
            .build()
    }
    fn hash() -> String {
        format!("{:#x}", transaction().calc_tx_hash())
    }
    #[test]
    fn dao_recovery_bridge_request_poll_cancel() {
        let registry = make_registry();
        let a = Adapter {
            state: STATE_RUNNING,
            registry: &registry,
        };
        let request = value(&a, "request", &hash(), "");
        assert_eq!(request["schema_version"], 1);
        assert_eq!(request["operation"], "request");
        assert_eq!(
            request["result"]["identity"]["store_generation"],
            "synthetic-store"
        );
        assert_eq!(request["result"]["transaction_hash"], hash());
        let id = request["result"]["request_id"].as_str().unwrap();
        assert_eq!(value(&a, "poll", id, "")["result"]["status"], "queued");
        assert_eq!(value(&a, "cancel", id, "")["result"]["status"], "cancelled");
        assert_eq!(value(&a, "poll", id, "")["result"]["status"], "cancelled");
    }
    #[test]
    fn dao_recovery_bridge_verified_snapshot_roundtrip() {
        let registry = make_registry();
        let a = Adapter {
            state: STATE_RUNNING,
            registry: &registry,
        };
        let request = value(&a, "request", &hash(), "");
        let id = request["result"]["request_id"].as_str().unwrap();
        let tx = transaction();
        let header = packed::Header::default();
        let attempt = registry
            .freeze(&[tx.calc_tx_hash()], [8; 16], &header, Instant::now())
            .unwrap()
            .unwrap();
        registry
            .publish(
                &attempt,
                &[ProvenTransaction {
                    transaction: tx,
                    header,
                }],
                Instant::now(),
                |_| Ok(()),
            )
            .unwrap();
        let poll = value(&a, "poll", id, "");
        let receipt = &poll["result"]["receipt"];
        assert_eq!(poll["result"]["status"], "verified");
        assert_eq!(receipt["transaction_hash"], hash());
        assert_eq!(receipt["transaction_index"], Value::Null);
        assert!(receipt["raw_transaction"].get("witnesses").is_none());
        assert!(receipt["header"]["hash"]
            .as_str()
            .unwrap()
            .starts_with("0x"));
        let rid = receipt["receipt_id"].as_str().unwrap();
        let validated = value(&a, "validate", id, rid);
        assert_eq!(validated["result"]["valid"], true);
        assert_eq!(validated["result"]["receipt"], *receipt);
        if let Ok(path) = std::env::var("POCKET_DAO_FIXTURE_PATH") {
            std::fs::write(
                path,
                serde_json::to_string_pretty(
                    &json!({"request":request,"poll":poll,"validate":validated}),
                )
                .unwrap(),
            )
            .unwrap();
        }
        value(&a, "cancel", id, "");
        assert_eq!(
            value(&a, "validate", id, rid)["error"]["code"],
            "invalid_receipt"
        );
    }
    #[test]
    fn dao_recovery_bridge_bad_inputs_fail_closed() {
        let registry = make_registry();
        let a = Adapter {
            state: STATE_RUNNING,
            registry: &registry,
        };
        assert_eq!(
            value(&a, "request", "bad", "")["error"]["code"],
            "invalid_hash"
        );
        for token in ["", "null", "dr1.q.00", "dr2.q.00", "dr1.r.00", "dr1.q.zz"] {
            assert_eq!(
                value(&a, "poll", token, "")["error"]["code"],
                "invalid_token"
            );
        }
        let foreign = make_registry();
        let other = Adapter {
            state: STATE_RUNNING,
            registry: &foreign,
        };
        let id = value(&a, "request", &hash(), "")["result"]["request_id"]
            .as_str()
            .unwrap()
            .to_owned();
        assert_eq!(
            value(&other, "poll", &id, "")["error"]["code"],
            "unknown_request"
        );
        assert!(value(&a, "unknown", "", "").get("result").is_none());
        let _ = json!({"schema_version": 1});
    }
    #[test]
    fn dao_recovery_bridge_not_running_and_revoked() {
        let r = make_registry();
        for state in [STATE_INIT, STATE_STOPPED] {
            let a = Adapter {
                state,
                registry: &r,
            };
            for op in ["request", "poll", "validate", "cancel"] {
                assert!(a.call(op, &hash(), "bad").is_err());
            }
        }
        let a = Adapter {
            state: STATE_RUNNING,
            registry: &r,
        };
        r.revoke_run();
        assert_eq!(
            value(&a, "request", &hash(), "")["error"]["code"],
            "unavailable"
        );
    }
    #[test]
    fn dao_recovery_bridge_tokens_are_bounded_canonical_and_typed() {
        let registry = make_registry();
        let id = registry
            .request(Default::default(), Instant::now())
            .unwrap();
        let encoded = token("q", &id);
        assert_eq!(decode::<RequestId>("q", &encoded).unwrap(), id);
        for invalid in [
            format!("{encoded}00"),
            encoded.replace("dr1.q.", "dr1.r."),
            "a".repeat(2049),
        ] {
            assert!(decode::<RequestId>("q", &invalid).is_err());
        }
        let mut value = serde_json::to_value(id).unwrap();
        value["unknown"] = json!(1);
        let noncanonical = format!("dr1.q.{}", hex(&serde_json::to_vec(&value).unwrap()));
        assert!(decode::<RequestId>("q", &noncanonical).is_err());
    }
    #[test]
    fn dao_recovery_bridge_identity_busy_and_rpc_are_non_authorizing() {
        let registry = make_registry();
        assert!(registry.identity().is_ok());
        let update = registry.begin_chain_update().unwrap();
        assert_eq!(registry.identity(), Err(RecoveryError::Unavailable));
        registry
            .finish_chain_update(
                update,
                ChainSnapshot {
                    anchor: Default::default(),
                },
            )
            .unwrap();
        assert!(registry.identity().is_ok());
        for method in [
            "request_dao_recovery",
            "poll_dao_recovery",
            "validate_dao_recovery",
            "cancel_dao_recovery",
        ] {
            let answer: Value =
                serde_json::from_str(&super::super::rpc::call_rpc(method).unwrap()).unwrap();
            assert!(answer.get("error").is_some());
            assert!(answer.get("result").is_none());
        }
        registry.revoke_run();
        assert_eq!(registry.identity(), Err(RecoveryError::Unavailable));
    }
}
