use std::sync::Arc;
use std::sync::RwLock;
use std::time::Instant;

use ckb_network::{bytes::Bytes, CKBProtocolHandler, PeerIndex, SupportProtocols};
use ckb_store::ChainStore as _;
use ckb_types::{
    core::{EpochNumberWithFraction, HeaderBuilder},
    packed::{self, Script},
    prelude::*,
    utilities::merkle_mountain_range::VerifiableHeader,
    H256, U256,
};

use crate::storage::{LightClientStorage, SetScriptsCommand};
use crate::storage::{ScriptStatus, ScriptType};
use crate::{
    protocols::{
        light_client::constant::REFRESH_PEERS_TOKEN, BAD_MESSAGE_BAN_TIME, CHECK_POINT_INTERVAL,
        GET_BLOCK_FILTERS_TOKEN, GET_BLOCK_FILTER_HASHES_TOKEN,
    },
    tests::{
        prelude::*,
        utils::{setup, MockChain, MockNetworkContext},
    },
};

// Regression: selecting only the minimum freezes a caught-up wallet until a
// different script has replayed its entire history (849 minutes in the fixture).
#[tokio::test]
async fn live_wallet_is_serviced_while_history_lags() {
    let chain = MockChain::new_with_dummy_pow("fair-live-and-history");
    let live = Script::new_builder()
        .args(Bytes::from_static(b"live").pack())
        .build();
    let historical = Script::new_builder()
        .args(Bytes::from_static(b"history").pack())
        .build();
    chain.client_storage().update_filter_scripts(
        vec![
            ScriptStatus {
                script: historical,
                script_type: ScriptType::Lock,
                block_number: 10,
            },
            ScriptStatus {
                script: live,
                script_type: ScriptType::Lock,
                block_number: 100,
            },
        ],
        SetScriptsCommand::All,
    );
    use crate::storage::scan::{ScanIdentity, ScanState, ScanStorage};
    let storage = chain.client_storage();
    let states = storage
        .get_filter_scripts()
        .into_iter()
        .map(|status| {
            let id = ScanIdentity::registered(storage, &status.script, status.script_type).unwrap();
            ScanState::settled(id, [7; 16], status.block_number, status.block_number)
        })
        .collect();
    storage.install_scan_states([7; 16], states).unwrap();
    let peer = PeerIndex::new(3);
    let peers = chain.create_peers();
    peers.add_peer(peer);
    peers
        .mock_prove_state(
            peer,
            VerifiableHeader::new(
                HeaderBuilder::default()
                    .epoch(EpochNumberWithFraction::new(0, 0, 1000).full_value())
                    .number(101u64)
                    .build(),
                Default::default(),
                None,
                Default::default(),
            ),
        )
        .unwrap();
    let mut filter_data = Vec::new();
    golomb_coded_set::GCSFilterWriter::new(
        &mut filter_data,
        golomb_coded_set::SipHasher24Builder::new(0, 0),
        golomb_coded_set::M,
        golomb_coded_set::P,
    )
    .finish()
    .unwrap();
    let filter_data = Bytes::from(filter_data).pack();
    let mut parent = storage.get_last_check_point().1;
    let hashes: Vec<packed::Byte32> = (0..103)
        .map(|_| {
            parent = ckb_types::utilities::calc_filter_hash(&parent, &filter_data).pack();
            parent.clone()
        })
        .collect();
    peers.mock_latest_block_filter_hashes(peer, 0, hashes[..101].to_vec());
    let nc = MockNetworkContext::new(SupportProtocols::Filter);
    let mut protocol = chain.create_filter_protocol(peers.clone());
    protocol.notify(nc.context(), GET_BLOCK_FILTERS_TOKEN).await;
    let requests: Vec<u64> = nc
        .sent_messages()
        .borrow()
        .iter()
        .filter_map(|(_, _, data)| {
            match packed::BlockFilterMessageReader::from_slice(data)
                .ok()?
                .to_enum()
            {
                packed::BlockFilterMessageUnionReader::GetBlockFilters(req) => {
                    Some(req.start_number().unpack())
                }
                _ => None,
            }
        })
        .collect();
    assert!(
        requests.contains(&101),
        "live wallet must be serviced before historical catch-up; requested {requests:?}"
    );
    protocol.notify(nc.context(), GET_BLOCK_FILTERS_TOKEN).await;
    let requests: Vec<u64> = nc
        .sent_messages()
        .borrow()
        .iter()
        .filter_map(|(_, _, data)| {
            match packed::BlockFilterMessageReader::from_slice(data)
                .ok()?
                .to_enum()
            {
                packed::BlockFilterMessageUnionReader::GetBlockFilters(req) => {
                    Some(req.start_number().unpack())
                }
                _ => None,
            }
        })
        .collect();
    assert_eq!(
        requests,
        vec![101, 11],
        "pending live response must not block historical service"
    );
    assert_eq!(
        chain
            .client_storage()
            .get_filter_scripts()
            .iter()
            .map(|s| s.block_number)
            .min(),
        Some(10),
        "requesting the live range must not promote historical coverage"
    );
    for height in 101u64..=103 {
        if height > 101 {
            peers
                .mock_prove_state(
                    peer,
                    VerifiableHeader::new(
                        HeaderBuilder::default()
                            .epoch(EpochNumberWithFraction::new(0, 0, 1000).full_value())
                            .number(height)
                            .build(),
                        Default::default(),
                        None,
                        Default::default(),
                    ),
                )
                .unwrap();
            peers.mock_latest_block_filter_hashes(peer, 0, hashes[..height as usize].to_vec());
            protocol.notify(nc.context(), GET_BLOCK_FILTERS_TOKEN).await;
        }
        let response = packed::BlockFilters::new_builder()
            .start_number(height)
            .block_hashes(vec![packed::Byte32::default()])
            .filters(vec![filter_data.clone()])
            .build();
        protocol
            .received(
                nc.context(),
                peer,
                packed::BlockFilterMessage::new_builder()
                    .set(response)
                    .build()
                    .as_bytes(),
            )
            .await;
        let cursors = storage.get_filter_scripts();
        assert_eq!(cursors.iter().map(|s| s.block_number).max(), Some(height));
        assert_eq!(cursors.iter().map(|s| s.block_number).min(), Some(10));
    }
    let response = packed::BlockFilters::new_builder()
        .start_number(11u64)
        .block_hashes(vec![packed::Byte32::default(); 10])
        .filters(vec![filter_data; 10])
        .build();
    protocol
        .received(
            nc.context(),
            peer,
            packed::BlockFilterMessage::new_builder()
                .set(response)
                .build()
                .as_bytes(),
        )
        .await;
    assert_eq!(
        storage
            .get_filter_scripts()
            .iter()
            .map(|s| s.block_number)
            .min(),
        Some(20)
    );
    assert_eq!(
        storage
            .get_filter_scripts()
            .iter()
            .map(|s| s.block_number)
            .max(),
        Some(103)
    );
}

#[tokio::test]
async fn shorter_finalized_filter_reply_preserves_cache_and_next_request() {
    check_finalized_filter_reply(10, 3, false, 10, Some(11)).await;
}

#[tokio::test]
async fn conflicting_shorter_finalized_filter_reply_preserves_cache() {
    check_finalized_filter_reply(10, 3, true, 10, None).await;
}

#[tokio::test]
async fn finalized_filter_requests_stop_at_checkpoint() {
    let boundary = CHECK_POINT_INTERVAL as usize;
    check_finalized_filter_reply(
        boundary - 1,
        3,
        false,
        boundary - 1,
        Some(CHECK_POINT_INTERVAL),
    )
    .await;
    check_finalized_filter_reply(boundary, 3, false, boundary, None).await;
    check_finalized_filter_reply(10, boundary + 3, false, boundary, None).await;
}

async fn check_finalized_filter_reply(
    cached_len: usize,
    response_len: usize,
    conflicting: bool,
    expected_len: usize,
    expected_next: Option<u64>,
) {
    let chain = MockChain::new_with_dummy_pow("short-filter-reply");
    let nc = MockNetworkContext::new(SupportProtocols::Filter);
    let peers = chain.create_peers();
    let peer = PeerIndex::new(1);
    peers.add_peer(peer);
    let tip = VerifiableHeader::new(
        HeaderBuilder::default()
            .epoch(EpochNumberWithFraction::new(0, 0, 100).full_value())
            .number(CHECK_POINT_INTERVAL * 2)
            .build(),
        Default::default(),
        None,
        Default::default(),
    );
    peers.mock_prove_state(peer, tip).unwrap();
    let storage = chain.client_storage();
    let (_, checkpoint) = storage.get_last_check_point();
    storage.update_check_points(1, &[packed::Byte32::default()]);
    storage.update_max_check_point_index(1);
    let cached = vec![packed::Byte32::default(); cached_len];
    peers
        .update_cached_block_filter_hashes(cached.clone())
        .await;
    let mut response = vec![packed::Byte32::default(); response_len];
    if conflicting {
        response[1] = packed::Byte32::from_slice(&[1; 32]).unwrap();
    }
    let content = packed::BlockFilterHashes::new_builder()
        .start_number(1u64)
        .parent_block_filter_hash(checkpoint)
        .block_filter_hashes(response)
        .build();
    let message = packed::BlockFilterMessage::new_builder()
        .set(content)
        .build();
    let mut protocol = chain.create_filter_protocol(Arc::clone(&peers));
    protocol
        .received(nc.context(), peer, message.as_bytes())
        .await;
    assert_eq!(
        peers.get_cached_block_filter_hashes().await,
        (0, vec![packed::Byte32::default(); expected_len])
    );
    assert!(nc.not_banned(peer));
    let sent = nc.sent_messages().borrow();
    let requests = sent
        .iter()
        .filter_map(|(_, _, bytes)| {
            match packed::BlockFilterMessageReader::from_slice(bytes)
                .unwrap()
                .to_enum()
            {
                packed::BlockFilterMessageUnionReader::GetBlockFilterHashes(request) => {
                    Some(request.start_number().unpack())
                }
                _ => None,
            }
        })
        .collect::<Vec<u64>>();
    assert_eq!(requests, expected_next.into_iter().collect::<Vec<_>>());
}

#[tokio::test]
async fn test_block_filter_malformed_message() {
    let chain = MockChain::new_with_dummy_pow("test-block-filter");
    let nc = MockNetworkContext::new(SupportProtocols::Filter);

    let peers = chain.create_peers();
    let mut protocol = chain.create_filter_protocol(peers);

    let peer_index = PeerIndex::new(3);
    let data = Bytes::from(vec![2, 3, 4, 5]);
    protocol.received(nc.context(), peer_index, data).await;

    assert_eq!(
        nc.has_banned(peer_index).map(|(duration, _)| duration),
        Some(BAD_MESSAGE_BAN_TIME)
    );
}

#[tokio::test]
async fn test_block_filter_ignore_start_number() {
    let chain = MockChain::new_with_dummy_pow("test-block-filter");
    let nc = MockNetworkContext::new(SupportProtocols::Filter);

    let min_filtered_block_number = 3;
    chain.client_storage().update_filter_scripts(
        vec![ScriptStatus {
            script: Script::default(),
            script_type: ScriptType::Lock,
            block_number: min_filtered_block_number,
        }],
        Default::default(),
    );

    let peer_index = PeerIndex::new(3);
    let peers = {
        let tip_header = VerifiableHeader::new(
            HeaderBuilder::default()
                .epoch(EpochNumberWithFraction::new(0, 0, 100).full_value())
                .number(min_filtered_block_number + 1)
                .build(),
            Default::default(),
            None,
            Default::default(),
        );
        let peers = chain.create_peers();
        peers.add_peer(peer_index);
        peers.mock_prove_state(peer_index, tip_header).unwrap();
        peers
    };
    let mut protocol = chain.create_filter_protocol(peers);
    let content = packed::BlockFilters::new_builder()
        .start_number(min_filtered_block_number - 1)
        .block_hashes(vec![H256(rand::random()).pack(), H256(rand::random()).pack()].pack())
        .filters(vec![Bytes::from("abc").pack(), Bytes::from("def").pack()].pack())
        .build();
    let message = packed::BlockFilterMessage::new_builder()
        .set(content)
        .build();

    protocol
        .received(nc.context(), peer_index, message.as_bytes())
        .await;

    assert!(nc.not_banned(peer_index));
    assert!(nc.sent_messages().borrow().is_empty());
}

#[tokio::test]
async fn test_block_filter_empty_filters() {
    let chain = MockChain::new_with_dummy_pow("test-block-filter");
    let nc = MockNetworkContext::new(SupportProtocols::Filter);

    let min_filtered_block_number = 3;
    chain.client_storage().update_filter_scripts(
        vec![ScriptStatus {
            script: Script::default(),
            script_type: ScriptType::Lock,
            block_number: min_filtered_block_number,
        }],
        Default::default(),
    );

    let peer_index = PeerIndex::new(3);
    let peers = {
        let tip_header = VerifiableHeader::new(
            HeaderBuilder::default()
                .epoch(EpochNumberWithFraction::new(0, 0, 100).full_value())
                .number(min_filtered_block_number + 1)
                .build(),
            Default::default(),
            None,
            Default::default(),
        );
        let peers = chain.create_peers();
        peers.add_peer(peer_index);
        peers.mock_prove_state(peer_index, tip_header).unwrap();
        peers
    };
    let mut protocol = chain.create_filter_protocol(peers);
    let content = packed::BlockFilters::new_builder()
        .start_number(min_filtered_block_number + 1)
        .block_hashes(vec![])
        .filters(vec![])
        .build();
    let message = packed::BlockFilterMessage::new_builder()
        .set(content)
        .build();

    protocol
        .received(nc.context(), peer_index, message.as_bytes())
        .await;

    assert!(nc.not_banned(peer_index));
    assert!(nc.sent_messages().borrow().is_empty());
}

#[tokio::test]
async fn test_block_filter_invalid_filters_count() {
    let chain = MockChain::new_with_dummy_pow("test-block-filter");
    let nc = MockNetworkContext::new(SupportProtocols::Filter);
    let bad_message_allowed_each_hour = 5;

    let min_filtered_block_number = 3;
    chain.client_storage().update_filter_scripts(
        vec![ScriptStatus {
            script: Script::default(),
            script_type: ScriptType::Lock,
            block_number: min_filtered_block_number,
        }],
        Default::default(),
    );

    let peer_index = PeerIndex::new(3);
    let peers = {
        let tip_header = VerifiableHeader::new(
            HeaderBuilder::default()
                .epoch(EpochNumberWithFraction::new(0, 0, 100).full_value())
                .number(min_filtered_block_number + 1)
                .build(),
            Default::default(),
            None,
            Default::default(),
        );
        let peers = chain.create_peers_with_parameters(bad_message_allowed_each_hour);
        peers.add_peer(peer_index);
        peers.mock_prove_state(peer_index, tip_header).unwrap();
        peers
    };
    let mut protocol = chain.create_filter_protocol(peers);
    let content = packed::BlockFilters::new_builder()
        .start_number(min_filtered_block_number + 1)
        .block_hashes(vec![H256(rand::random()).pack(), H256(rand::random()).pack()].pack())
        .filters(vec![])
        .build();
    let message = packed::BlockFilterMessage::new_builder()
        .set(content)
        .build();

    protocol
        .received(nc.context(), peer_index, message.as_bytes())
        .await;
    assert_eq!(
        nc.has_banned(peer_index).map(|(duration, _)| duration),
        Some(BAD_MESSAGE_BAN_TIME)
    );
    assert!(nc.sent_messages().borrow().is_empty());
}

#[tokio::test]
async fn test_block_filter_start_number_greater_then_proved_number() {
    let chain = MockChain::new_with_dummy_pow("test-block-filter");
    let nc = MockNetworkContext::new(SupportProtocols::Filter);

    let min_filtered_block_number = 3;
    let proved_number = min_filtered_block_number;
    let start_number = min_filtered_block_number + 1;
    chain.client_storage().update_filter_scripts(
        vec![ScriptStatus {
            script: Script::default(),
            script_type: ScriptType::Lock,
            block_number: min_filtered_block_number,
        }],
        Default::default(),
    );

    let peer_index = PeerIndex::new(3);
    let peers = {
        let tip_header = VerifiableHeader::new(
            HeaderBuilder::default()
                .epoch(EpochNumberWithFraction::new(0, 0, 100).full_value())
                .number(proved_number)
                .build(),
            Default::default(),
            None,
            Default::default(),
        );
        let peers = chain.create_peers();
        peers.add_peer(peer_index);
        peers.mock_prove_state(peer_index, tip_header).unwrap();
        peers
    };
    let mut protocol = chain.create_filter_protocol(Arc::clone(&peers));
    let content = packed::BlockFilters::new_builder()
        .start_number(start_number)
        .block_hashes(vec![H256(rand::random()).pack(), H256(rand::random()).pack()].pack())
        .filters(vec![Bytes::from("abc").pack(), Bytes::from("def").pack()].pack())
        .build();
    let message = packed::BlockFilterMessage::new_builder()
        .set(content)
        .build();

    peers.mock_latest_block_filter_hashes(
        peer_index,
        0,
        vec![Default::default(); proved_number as usize],
    );
    protocol
        .received(nc.context(), peer_index, message.as_bytes())
        .await;

    assert!(nc.not_banned(peer_index));
    assert!(nc.sent_messages().borrow().is_empty());
}

#[tokio::test(flavor = "multi_thread")]
async fn test_block_filter_ok_with_blocks_not_matched() {
    setup();

    let chain = MockChain::new_with_dummy_pow("test-block-filter").start();
    let nc = MockNetworkContext::new(SupportProtocols::Filter);

    let min_filtered_block_number = 30;
    let proved_number = min_filtered_block_number + 3;
    let start_number = min_filtered_block_number + 1;
    chain.client_storage().update_filter_scripts(
        vec![ScriptStatus {
            script: Script::default(),
            script_type: ScriptType::Lock,
            block_number: min_filtered_block_number,
        }],
        Default::default(),
    );

    chain.mine_to(proved_number);

    let snapshot = chain.shared().snapshot();

    let peer_index = PeerIndex::new(3);
    let peers = {
        let tip_header: VerifiableHeader = snapshot
            .get_verifiable_header_by_number(proved_number)
            .expect("block stored")
            .into();
        let peers = chain.create_peers();
        peers.add_peer(peer_index);
        peers.mock_prove_state(peer_index, tip_header).unwrap();
        peers
    };

    let mut protocol = chain.create_filter_protocol(Arc::clone(&peers));

    let block_hashes = {
        let block_hash_1 = snapshot.get_block_hash(start_number).unwrap();
        let block_hash_2 = snapshot.get_block_hash(start_number + 1).unwrap();
        vec![block_hash_1, block_hash_2]
    };
    let filters = {
        let filter_data_1 = snapshot.get_block_filter_data(start_number).unwrap();
        let filter_data_2 = snapshot.get_block_filter_data(start_number + 1).unwrap();
        vec![filter_data_1, filter_data_2]
    };
    let filter_hashes = {
        let mut filter_hashes = snapshot
            .get_block_filter_hashes_until(proved_number)
            .unwrap();
        filter_hashes.remove(0);
        filter_hashes
    };

    let blocks_count = block_hashes.len();
    let content = packed::BlockFilters::new_builder()
        .start_number(start_number)
        .block_hashes(block_hashes.pack())
        .filters(filters.pack())
        .build();
    let message = packed::BlockFilterMessage::new_builder()
        .set(content)
        .build();

    peers.mock_latest_block_filter_hashes(peer_index, 0, filter_hashes);
    protocol
        .received(nc.context(), peer_index, message.as_bytes())
        .await;

    let filtered_block_number = start_number - 1 + blocks_count as u64;
    assert!(nc.not_banned(peer_index));
    assert_eq!(
        chain.client_storage().get_filter_scripts()[0].block_number,
        filtered_block_number
    );
    let message = {
        let content = packed::GetBlockFilters::new_builder()
            .start_number(filtered_block_number + 1)
            .build();
        packed::BlockFilterMessage::new_builder()
            .set(content)
            .build()
    };
    assert_eq!(
        nc.sent_messages().borrow().clone(),
        vec![(
            SupportProtocols::Filter.protocol_id(),
            peer_index,
            message.as_bytes()
        )]
    );
}

#[tokio::test(flavor = "multi_thread")]
async fn test_block_filter_ok_with_blocks_matched() {
    setup();

    let chain = MockChain::new_with_dummy_pow("test-block-filter").start();
    let nc = MockNetworkContext::new(SupportProtocols::Filter);

    let min_filtered_block_number = 30;
    let start_number = min_filtered_block_number + 1;
    let proved_number = start_number + 5;
    let script = Script::new_builder()
        .code_hash(H256(rand::random()).pack())
        .build();
    chain.client_storage().update_filter_scripts(
        vec![ScriptStatus {
            script: script.clone(),
            script_type: ScriptType::Lock,
            block_number: 0,
        }],
        SetScriptsCommand::All,
    );
    chain
        .client_storage()
        .update_min_filtered_block_number(min_filtered_block_number);

    chain.mine_to(start_number - 3);

    {
        let tx = {
            let tx = chain.get_cellbase_as_input(start_number - 5);
            let output = tx.output(0).unwrap().as_builder().lock(script).build();
            tx.as_advanced_builder().set_outputs(vec![output]).build()
        };
        chain.mine_block(|block| {
            let ids = vec![tx.proposal_short_id()];
            block.as_advanced_builder().proposals(ids).build()
        });
        chain.mine_blocks(1);
        chain.mine_block(|block| block.as_advanced_builder().transaction(tx.clone()).build());
        chain.mine_blocks(1);
    }

    chain.mine_to(proved_number);

    let snapshot = chain.shared().snapshot();

    let tip_header: VerifiableHeader = snapshot
        .get_verifiable_header_by_number(proved_number)
        .expect("block stored")
        .into();
    chain
        .client_storage()
        .update_last_state(&U256::one(), &tip_header.header().data(), &[]);

    let peer_index = PeerIndex::new(3);
    let (peers, prove_state_block_hash) = {
        let prove_state_block_hash = tip_header.header().hash();
        let peers = chain.create_peers();
        peers.add_peer(peer_index);
        peers.mock_prove_state(peer_index, tip_header).unwrap();
        (peers, prove_state_block_hash)
    };

    let filter_data_1 = snapshot.get_block_filter_data(start_number).unwrap();
    let filter_data_2 = snapshot.get_block_filter_data(start_number + 1).unwrap();
    let block_hash_1 = snapshot.get_block_hash(start_number).unwrap();
    let block_hash_2 = snapshot.get_block_hash(start_number + 1).unwrap();
    let filter_hashes = {
        let mut filter_hashes = snapshot
            .get_block_filter_hashes_until(start_number + 3)
            .unwrap();
        filter_hashes.remove(0);
        filter_hashes
    };

    let content = packed::BlockFilters::new_builder()
        .start_number(start_number)
        .block_hashes(vec![block_hash_1.clone(), block_hash_2].pack())
        .filters(vec![filter_data_1, filter_data_2].pack())
        .build();
    let message = packed::BlockFilterMessage::new_builder()
        .set(content)
        .build()
        .as_bytes();

    let mut protocol = chain.create_filter_protocol(Arc::clone(&peers));
    peers.mock_latest_block_filter_hashes(peer_index, 0, filter_hashes);
    protocol.received(nc.context(), peer_index, message).await;
    assert!(nc.not_banned(peer_index));

    let get_blocks_proof_message = {
        let content = packed::GetBlocksProof::new_builder()
            .block_hashes(vec![block_hash_1].pack())
            .last_hash(prove_state_block_hash)
            .build();
        packed::LightClientMessage::new_builder()
            .set(content)
            .build()
            .as_bytes()
    };
    let get_block_filters_message = {
        let blocks_count = 2;
        let new_start_number = start_number - 1 + blocks_count + 1;
        let content = packed::GetBlockFilters::new_builder()
            .start_number(new_start_number)
            .build();
        packed::BlockFilterMessage::new_builder()
            .set(content)
            .build()
            .as_bytes()
    };
    assert_eq!(
        nc.sent_messages().borrow().clone(),
        vec![
            (
                SupportProtocols::LightClient.protocol_id(),
                peer_index,
                get_blocks_proof_message
            ),
            (
                SupportProtocols::Filter.protocol_id(),
                peer_index,
                get_block_filters_message
            )
        ]
    );
}

#[tokio::test]
async fn test_block_filter_notify_ask_filters() {
    let chain = MockChain::new_with_dummy_pow("test-block-filter");
    let nc = MockNetworkContext::new(SupportProtocols::Filter);

    let min_filtered_block_number = 3;
    // for should_ask() return true
    chain.client_storage().update_filter_scripts(
        vec![ScriptStatus {
            script: Script::default(),
            script_type: ScriptType::Lock,
            block_number: min_filtered_block_number,
        }],
        Default::default(),
    );

    let peer_index = PeerIndex::new(3);
    let peers = {
        let tip_header = VerifiableHeader::new(
            HeaderBuilder::default()
                .epoch(EpochNumberWithFraction::new(0, 0, 100).full_value())
                .number(min_filtered_block_number + 1)
                .build(),
            Default::default(),
            None,
            Default::default(),
        );
        let peers = chain.create_peers();
        peers.add_peer(peer_index);
        peers.mock_prove_state(peer_index, tip_header).unwrap();
        peers
    };
    let mut protocol = chain.create_filter_protocol(Arc::clone(&peers));

    peers.mock_latest_block_filter_hashes(
        peer_index,
        0,
        vec![Default::default(); min_filtered_block_number as usize + 1],
    );
    protocol.notify(nc.context(), GET_BLOCK_FILTERS_TOKEN).await;
    let message = {
        let start_number: u64 = min_filtered_block_number + 1;
        let content = packed::GetBlockFilters::new_builder()
            .start_number(start_number)
            .build();
        packed::BlockFilterMessage::new_builder()
            .set(content)
            .build()
    };

    assert_eq!(
        nc.sent_messages().borrow().clone(),
        vec![(
            SupportProtocols::Filter.protocol_id(),
            peer_index,
            message.as_bytes()
        )]
    );
}

#[tokio::test]
async fn test_filter_hash_request_after_checkpoint_quorum_loss() {
    let chain = MockChain::new_with_dummy_pow("test-block-filter");
    let light_client_network_context = MockNetworkContext::new(SupportProtocols::LightClient);
    let filter_network_context = MockNetworkContext::new(SupportProtocols::Filter);

    let storage = chain.client_storage();
    let (initial_check_point_index, initial_check_point) = storage.get_last_check_point();
    assert_eq!(initial_check_point_index, 0);

    let next_check_point = H256(rand::random()).pack();
    let proved_number = CHECK_POINT_INTERVAL * 2;
    let tip_header = VerifiableHeader::new(
        HeaderBuilder::default()
            .epoch(EpochNumberWithFraction::new(0, 0, 100).full_value())
            .number(proved_number)
            .build(),
        Default::default(),
        None,
        Default::default(),
    );
    let peer_indexes = (1..=4).map(PeerIndex::new).collect::<Vec<_>>();
    let peers = chain.create_peers();
    peers.set_max_outbound_peers(8);
    for peer_index in &peer_indexes {
        peers.add_peer(*peer_index);
        peers
            .mock_prove_state(*peer_index, tip_header.clone())
            .unwrap();
        let next_start_number = peers
            .add_check_points(
                *peer_index,
                proved_number,
                0,
                &[initial_check_point.clone(), next_check_point.clone()],
            )
            .unwrap();
        assert!(next_start_number.is_none());
    }

    let mut light_client_protocol = chain.create_light_client_protocol(Arc::clone(&peers));
    light_client_protocol
        .notify(light_client_network_context.context(), REFRESH_PEERS_TOKEN)
        .await;
    assert_eq!(storage.get_last_check_point(), (1, next_check_point));

    peers.remove_peer(peer_indexes[3]).await;
    light_client_protocol
        .notify(light_client_network_context.context(), REFRESH_PEERS_TOKEN)
        .await;

    let mut filter_protocol = chain.create_filter_protocol(peers);
    filter_protocol
        .notify(
            filter_network_context.context(),
            GET_BLOCK_FILTER_HASHES_TOKEN,
        )
        .await;

    let expected_message = packed::BlockFilterMessage::new_builder()
        .set(
            packed::GetBlockFilterHashes::new_builder()
                .start_number(1u64)
                .build(),
        )
        .build()
        .as_bytes();
    let sent_messages = filter_network_context.sent_messages().borrow();
    assert_eq!(sent_messages.len(), 1);
    let (protocol_id, peer_index, message) = &sent_messages[0];
    assert_eq!(*protocol_id, SupportProtocols::Filter.protocol_id());
    assert!(peer_indexes[..3].contains(peer_index));
    assert_eq!(message, &expected_message);
}

#[tokio::test]
async fn test_block_filter_notify_no_proved_peers() {
    let chain = MockChain::new_with_dummy_pow("test-block-filter");
    let nc = MockNetworkContext::new(SupportProtocols::Filter);

    let peer_index = PeerIndex::new(3);
    let peers = {
        let peers = chain.create_peers();
        peers.add_peer(peer_index);
        peers.request_last_state(peer_index).unwrap();
        peers
    };
    let mut protocol = chain.create_filter_protocol(peers);

    protocol.notify(nc.context(), GET_BLOCK_FILTERS_TOKEN).await;

    assert!(nc.sent_messages().borrow().is_empty());
}

#[tokio::test]
async fn test_block_filter_notify_not_reach_ask() {
    let chain = MockChain::new_with_dummy_pow("test-block-filter");
    let nc = MockNetworkContext::new(SupportProtocols::Filter);

    let min_filtered_block_number = 3;
    chain.client_storage().update_filter_scripts(
        vec![ScriptStatus {
            script: Script::default(),
            script_type: ScriptType::Lock,
            block_number: min_filtered_block_number,
        }],
        Default::default(),
    );

    let peer_index = PeerIndex::new(3);
    let peers = {
        let tip_header = VerifiableHeader::new(
            HeaderBuilder::default()
                .epoch(EpochNumberWithFraction::new(0, 0, 100).full_value())
                .number(5u64)
                .build(),
            Default::default(),
            None,
            Default::default(),
        );
        let peers = chain.create_peers();
        peers.add_peer(peer_index);
        peers.mock_prove_state(peer_index, tip_header).unwrap();
        peers
    };
    let mut protocol = chain.create_filter_protocol(peers);
    protocol.last_ask_time = Arc::new(RwLock::new(Some(Instant::now())));

    protocol.notify(nc.context(), GET_BLOCK_FILTERS_TOKEN).await;

    assert!(nc.sent_messages().borrow().is_empty());
}

#[tokio::test]
async fn test_block_filter_notify_proved_number_not_big_enough() {
    let chain = MockChain::new_with_dummy_pow("test-block-filter");
    let nc = MockNetworkContext::new(SupportProtocols::Filter);

    let min_filtered_block_number = 3;
    // for should_ask() return true
    chain.client_storage().update_filter_scripts(
        vec![ScriptStatus {
            script: Script::default(),
            script_type: ScriptType::Lock,
            block_number: min_filtered_block_number,
        }],
        Default::default(),
    );

    let peer_index = PeerIndex::new(3);
    let peers = {
        let tip_header = VerifiableHeader::new(
            HeaderBuilder::default()
                .epoch(EpochNumberWithFraction::new(0, 0, 100).full_value())
                .number(min_filtered_block_number)
                .build(),
            Default::default(),
            None,
            Default::default(),
        );
        let peers = chain.create_peers();
        peers.add_peer(peer_index);
        peers.mock_prove_state(peer_index, tip_header).unwrap();
        peers
    };
    let mut protocol = chain.create_filter_protocol(peers);

    protocol.notify(nc.context(), GET_BLOCK_FILTERS_TOKEN).await;

    assert!(nc.sent_messages().borrow().is_empty());
}

#[tokio::test]
async fn test_block_filter_notify_recover_matched_blocks() {
    let chain = MockChain::new_with_dummy_pow("test-block-filter");
    let nc = MockNetworkContext::new(SupportProtocols::Filter);

    let min_filtered_block_number = 3;
    chain
        .client_storage()
        .update_min_filtered_block_number(min_filtered_block_number);

    let peer_index = PeerIndex::new(3);
    let tip_header = VerifiableHeader::new(
        HeaderBuilder::default()
            .epoch(EpochNumberWithFraction::new(0, 0, 100).full_value())
            .number(min_filtered_block_number + 2)
            .build(),
        Default::default(),
        None,
        Default::default(),
    );
    let tip_hash = tip_header.header().hash();
    chain
        .client_storage()
        .update_last_state(&U256::one(), &tip_header.header().data(), &[]);
    let peers = {
        let peers = chain.create_peers();
        peers.add_peer(peer_index);
        peers.mock_prove_state(peer_index, tip_header).unwrap();
        peers
    };
    let unproved_block_hash = H256(rand::random()).pack();
    let proved_block_hash = H256(rand::random()).pack();
    let matched_blocks = vec![
        (unproved_block_hash.clone(), false),
        (proved_block_hash.clone(), true),
    ];
    chain
        .client_storage()
        .add_matched_blocks(2, 2, matched_blocks);
    let mut protocol = chain.create_filter_protocol(Arc::clone(&peers));

    peers.mock_latest_block_filter_hashes(
        peer_index,
        0,
        vec![Default::default(); min_filtered_block_number as usize + 2],
    );
    protocol.notify(nc.context(), GET_BLOCK_FILTERS_TOKEN).await;

    let get_blocks_proof_message = {
        let content = packed::GetBlocksProof::new_builder()
            .block_hashes(vec![unproved_block_hash].pack())
            .last_hash(tip_hash.clone())
            .build();
        packed::LightClientMessage::new_builder()
            .set(content.clone())
            .build()
            .as_bytes()
    };
    let get_blocks_message = {
        let content = packed::GetBlocks::new_builder()
            .block_hashes(vec![proved_block_hash].pack())
            .build();
        packed::SyncMessage::new_builder()
            .set(content.clone())
            .build()
            .as_bytes()
    };
    let get_block_filters_message = {
        let start_number: u64 = min_filtered_block_number + 1;
        let content = packed::GetBlockFilters::new_builder()
            .start_number(start_number)
            .build();
        packed::BlockFilterMessage::new_builder()
            .set(content)
            .build()
            .as_bytes()
    };
    assert_eq!(
        nc.sent_messages().borrow().clone(),
        vec![
            (
                SupportProtocols::LightClient.protocol_id(),
                peer_index,
                get_blocks_proof_message,
            ),
            (
                SupportProtocols::Sync.protocol_id(),
                peer_index,
                get_blocks_message,
            ),
            (
                SupportProtocols::Filter.protocol_id(),
                peer_index,
                get_block_filters_message,
            ),
        ]
    );
}

#[tokio::test(flavor = "multi_thread")]
async fn test_block_filter_without_enough_hashes() {
    setup();

    let chain = MockChain::new_with_dummy_pow("test-block-filter").start();
    let nc = MockNetworkContext::new(SupportProtocols::Filter);

    let min_filtered_block_number = 30;
    let start_number = min_filtered_block_number + 1;
    let proved_number = start_number + 5;
    let script = Script::new_builder()
        .code_hash(H256(rand::random()).pack())
        .build();
    chain.client_storage().update_filter_scripts(
        vec![ScriptStatus {
            script: script.clone(),
            script_type: ScriptType::Lock,
            block_number: 0,
        }],
        SetScriptsCommand::All,
    );
    chain
        .client_storage()
        .update_min_filtered_block_number(min_filtered_block_number);

    chain.mine_to(start_number - 3);

    {
        let tx = {
            let tx = chain.get_cellbase_as_input(start_number - 5);
            let output = tx.output(0).unwrap().as_builder().lock(script).build();
            tx.as_advanced_builder().set_outputs(vec![output]).build()
        };
        chain.mine_block(|block| {
            let ids = vec![tx.proposal_short_id()];
            block.as_advanced_builder().proposals(ids).build()
        });
        chain.mine_blocks(1);
        chain.mine_block(|block| block.as_advanced_builder().transaction(tx.clone()).build());
        chain.mine_blocks(1);
    }

    chain.mine_to(proved_number);

    let snapshot = chain.shared().snapshot();

    let tip_header: VerifiableHeader = snapshot
        .get_verifiable_header_by_number(proved_number)
        .expect("block stored")
        .into();
    chain
        .client_storage()
        .update_last_state(&U256::one(), &tip_header.header().data(), &[]);

    let peer_index = PeerIndex::new(3);
    let peers = {
        let peers = chain.create_peers();
        peers.add_peer(peer_index);
        peers.mock_prove_state(peer_index, tip_header).unwrap();
        peers.set_max_outbound_peers(3);
        peers
    };

    let filter_data_1 = snapshot.get_block_filter_data(start_number).unwrap();
    let filter_data_2 = snapshot.get_block_filter_data(start_number + 1).unwrap();
    let block_hash_1 = snapshot.get_block_hash(start_number).unwrap();
    let block_hash_2 = snapshot.get_block_hash(start_number + 1).unwrap();
    let filter_hashes = {
        let mut filter_hashes = snapshot
            .get_block_filter_hashes_until(start_number + 3)
            .unwrap();
        filter_hashes.remove(0);
        filter_hashes
    };

    let content = packed::BlockFilters::new_builder()
        .start_number(start_number)
        .block_hashes(vec![block_hash_1.clone(), block_hash_2].pack())
        .filters(vec![filter_data_1, filter_data_2].pack())
        .build();
    let message = packed::BlockFilterMessage::new_builder()
        .set(content)
        .build()
        .as_bytes();

    let mut protocol = chain.create_filter_protocol(Arc::clone(&peers));
    peers.mock_latest_block_filter_hashes(peer_index, 0, filter_hashes);
    protocol.received(nc.context(), peer_index, message).await;
    assert!(nc.not_banned(peer_index));

    assert!(nc.sent_messages().borrow().is_empty());
}

#[test]
fn truncated_gcs_filter_returns_protocol_error_without_advancing_progress() {
    let chain = MockChain::new_with_dummy_pow("truncated-gcs");
    chain.client_storage().update_filter_scripts(
        vec![ScriptStatus {
            script: Script::default(),
            script_type: ScriptType::Lock,
            block_number: 0,
        }],
        Default::default(),
    );
    let protocol = chain.create_filter_protocol(chain.create_peers());
    // A valid Molecule envelope containing a truncated Golomb-coded set.
    let filters = packed::BlockFilters::new_builder()
        .start_number(1u64)
        .block_hashes(vec![packed::Byte32::default()])
        .filters(vec![Bytes::from(1u64.to_le_bytes().to_vec()).pack()])
        .build();
    let error = protocol.check_filters_data(filters, 1).unwrap_err();
    assert_eq!(
        error.code(),
        crate::protocols::StatusCode::MalformedProtocolMessage
    );
    assert_eq!(chain.client_storage().get_min_filtered_block_number(), 0);
    assert!(chain
        .client_storage()
        .get_earliest_matched_blocks()
        .is_none());
}

#[tokio::test]
async fn exhausted_filter_height_does_not_wrap_or_advance() {
    let chain = MockChain::new_with_dummy_pow("filter-height-boundary");
    chain.client_storage().update_filter_scripts(
        vec![ScriptStatus {
            script: Script::default(),
            script_type: ScriptType::Lock,
            block_number: u64::MAX,
        }],
        Default::default(),
    );
    let peer = PeerIndex::new(3);
    let peers = chain.create_peers();
    peers.add_peer(peer);
    peers
        .mock_prove_state(
            peer,
            VerifiableHeader::new(
                HeaderBuilder::default()
                    .epoch(EpochNumberWithFraction::new(0, 0, 100).full_value())
                    .number(u64::MAX)
                    .build(),
                Default::default(),
                None,
                Default::default(),
            ),
        )
        .unwrap();
    let mut protocol = chain.create_filter_protocol(peers);
    let nc = MockNetworkContext::new(SupportProtocols::Filter);
    let content = packed::BlockFilters::new_builder()
        .start_number(0u64)
        .block_hashes(vec![packed::Byte32::default()])
        .filters(vec![Bytes::from_static(&[0]).pack()])
        .build();
    let message = packed::BlockFilterMessage::new_builder()
        .set(content)
        .build();
    protocol
        .received(nc.context(), peer, message.as_bytes())
        .await;
    assert_eq!(
        chain.client_storage().get_min_filtered_block_number(),
        u64::MAX
    );
    assert!(chain
        .client_storage()
        .get_earliest_matched_blocks()
        .is_none());
    assert!(nc.sent_messages().borrow().is_empty());
}

#[tokio::test]
async fn partially_missing_range_discards_suffix_before_reloading_work() {
    let chain = MockChain::new_with_dummy_pow("partial-missing-range");
    let storage = chain.client_storage();
    storage.update_filter_scripts(
        vec![ScriptStatus {
            script: Script::default(),
            script_type: ScriptType::Lock,
            block_number: 0,
        }],
        SetScriptsCommand::All,
    );
    let tip = VerifiableHeader::new(
        HeaderBuilder::default()
            .epoch(EpochNumberWithFraction::new(0, 0, 10000).full_value())
            .number(5000u64)
            .build(),
        Default::default(),
        None,
        Default::default(),
    );
    storage.update_last_state(&U256::one(), &tip.header().data(), &[]);
    storage.update_min_filtered_block_number(5000);
    let peers = chain.create_peers();
    let peer = PeerIndex::new(3);
    peers.add_peer(peer);
    peers.mock_prove_state(peer, tip).unwrap();
    let missing = packed::Byte32::new([1; 32]);
    let present = packed::Byte32::new([2; 32]);
    storage.add_matched_blocks(10, 3, vec![(missing.clone(), false), (present, true)]);
    storage.add_matched_blocks(5000, 1, vec![(packed::Byte32::new([3; 32]), false)]);
    peers.mark_fetching_headers_missing(&[missing]);
    let mut protocol = chain.create_filter_protocol(peers.clone());
    let nc = MockNetworkContext::new(SupportProtocols::Filter);
    protocol.notify(nc.context(), GET_BLOCK_FILTERS_TOKEN).await;
    assert!(
        storage.get_earliest_matched_blocks().is_none(),
        "partial missing range cannot replay only its surviving subset"
    );
    assert!(peers.matched_blocks().read().await.is_empty());
    assert!(storage.get_min_filtered_block_number() < 10);
    assert!(storage
        .get_scripts_hash(11)
        .contains(&Script::default().calc_script_hash()));
}

#[tokio::test]
async fn filter_hash_window_rejects_wrong_exact_checkpoint_endpoint() {
    let chain = MockChain::new_with_dummy_pow("exact-checkpoint-endpoint");
    let storage = chain.client_storage();
    let checkpoint = storage.get_last_check_point().1;
    storage.update_check_points(1, &[packed::Byte32::default()]);
    storage.update_max_check_point_index(1);
    let peer = PeerIndex::new(3);
    let peers = chain.create_peers();
    peers.add_peer(peer);
    peers
        .mock_prove_state(
            peer,
            VerifiableHeader::new(
                HeaderBuilder::default()
                    .epoch(EpochNumberWithFraction::new(0, 0, 100).full_value())
                    .number(CHECK_POINT_INTERVAL * 2)
                    .build(),
                Default::default(),
                None,
                Default::default(),
            ),
        )
        .unwrap();
    let nc = MockNetworkContext::new(SupportProtocols::Filter);
    let mut hashes = vec![packed::Byte32::default(); CHECK_POINT_INTERVAL as usize];
    *hashes.last_mut().unwrap() = packed::Byte32::from_slice(&[1; 32]).unwrap();
    let response = packed::BlockFilterHashes::new_builder()
        .start_number(1u64)
        .parent_block_filter_hash(checkpoint)
        .block_filter_hashes(hashes)
        .build();
    let mut protocol = chain.create_filter_protocol(peers.clone());
    protocol
        .received(
            nc.context(),
            peer,
            packed::BlockFilterMessage::new_builder()
                .set(response)
                .build()
                .as_bytes(),
        )
        .await;
    assert!(
        peers.get_cached_block_filter_hashes().await.1.is_empty(),
        "wrong checkpoint endpoint must not enter cache"
    );
}

#[tokio::test]
async fn scoped_proof_service_rotates_after_busy_peer_releases_request() {
    use crate::storage::scan::{ScanIdentity, ScanRange, ScanState};
    let chain = MockChain::new_with_dummy_pow("scoped-proof-turns");
    let peer = PeerIndex::new(3);
    let peers = chain.create_peers();
    peers.add_peer(peer);
    let header = HeaderBuilder::default()
        .epoch(EpochNumberWithFraction::new(0, 0, 1000).full_value())
        .number(101u64)
        .build();
    peers
        .mock_prove_state(
            peer,
            VerifiableHeader::new(header.clone(), Default::default(), None, Default::default()),
        )
        .unwrap();
    let states: Vec<_> = [(1u8, 100), (2u8, 10)]
        .into_iter()
        .map(|(id, height)| {
            let mut state = ScanState::settled(
                ScanIdentity {
                    suffix: vec![id],
                    registration: vec![id; 24],
                },
                [7; 16],
                0,
                height,
            );
            state.pending = Some(ScanRange {
                id: [id; 16],
                start: height + 1,
                end: height + 1,
                anchor: [9; 32],
                matches: vec![[id; 32]],
            });
            state
        })
        .collect();
    let matched = std::collections::HashMap::from([
        (H256::from([1; 32]), (false, None)),
        (H256::from([2; 32]), (false, None)),
    ]);
    let nc = MockNetworkContext::new(SupportProtocols::Filter);
    for expected in [1u8, 2, 1] {
        crate::utils::scans::service_proofs(
            &states,
            peers.clone(),
            &header.data(),
            &matched,
            nc.context(),
            16,
        )
        .await;
        let messages = nc.sent_messages().borrow();
        let requests: Vec<_> = messages
            .iter()
            .filter_map(|(_, _, data)| packed::LightClientMessageReader::from_slice(data).ok())
            .filter_map(|m| match m.to_enum() {
                packed::LightClientMessageUnionReader::GetBlocksProof(p) => Some(p.to_entity()),
                _ => None,
            })
            .collect();
        assert_eq!(
            requests.last().unwrap().block_hashes().get(0).unwrap(),
            packed::Byte32::from_slice(&[expected; 32]).unwrap()
        );
        let count = requests.len();
        drop(messages);
        crate::utils::scans::service_proofs(
            &states,
            peers.clone(),
            &header.data(),
            &matched,
            nc.context(),
            16,
        )
        .await;
        assert_eq!(
            nc.sent_messages().borrow().len(),
            count,
            "busy peer must not consume next scan turn"
        );
        peers.update_blocks_proof_request(peer, None, true);
    }
}

#[tokio::test]
async fn scoped_delayed_response_retains_window_beyond_cache_capacity() {
    use crate::storage::scan::{ScanIdentity, ScanState, ScanStorage};
    let chain = MockChain::new_with_dummy_pow("pinned-window-response");
    let storage = chain.client_storage();
    let interval = CHECK_POINT_INTERVAL as usize;
    let scripts: Vec<_> = (0..9)
        .map(|n| ScriptStatus {
            script: Script::new_builder().args(vec![n as u8].pack()).build(),
            script_type: ScriptType::Lock,
            block_number: (n * interval) as u64,
        })
        .collect();
    storage.update_filter_scripts(scripts, SetScriptsCommand::All);
    let states = storage
        .get_filter_scripts()
        .into_iter()
        .map(|s| {
            ScanState::settled(
                ScanIdentity::registered(storage, &s.script, s.script_type).unwrap(),
                [7; 16],
                s.block_number,
                s.block_number,
            )
        })
        .collect();
    storage.install_scan_states([7; 16], states).unwrap();
    let mut data = Vec::new();
    golomb_coded_set::GCSFilterWriter::new(
        &mut data,
        golomb_coded_set::SipHasher24Builder::new(0, 0),
        golomb_coded_set::M,
        golomb_coded_set::P,
    )
    .finish()
    .unwrap();
    let data = Bytes::from(data).pack();
    let genesis = storage.get_last_check_point().1;
    let mut parent = genesis.clone();
    let hashes: Vec<packed::Byte32> = (0..9 * interval)
        .map(|_| {
            parent = ckb_types::utilities::calc_filter_hash(&parent, &data).pack();
            parent.clone()
        })
        .collect();
    let cps: Vec<_> = (1..=9).map(|n| hashes[n * interval - 1].clone()).collect();
    storage.update_check_points(1, &cps);
    storage.update_max_check_point_index(9);
    let peer = PeerIndex::new(3);
    let peers = chain.create_peers();
    peers.add_peer(peer);
    peers
        .mock_prove_state(
            peer,
            VerifiableHeader::new(
                HeaderBuilder::default()
                    .epoch(EpochNumberWithFraction::new(0, 0, 1000).full_value())
                    .number((9 * interval + 1) as u64)
                    .build(),
                Default::default(),
                None,
                Default::default(),
            ),
        )
        .unwrap();
    let nc = MockNetworkContext::new(SupportProtocols::Filter);
    let mut protocol = chain.create_filter_protocol(peers);
    for n in (0..9).rev() {
        let parent = if n == 0 {
            genesis.clone()
        } else {
            cps[n - 1].clone()
        };
        assert!(protocol.windows.write().unwrap().insert(
            [7; 16],
            n as u32,
            parent,
            cps[n].clone(),
            hashes[n * interval..(n + 1) * interval].to_vec(),
            interval
        ));
        protocol.notify(nc.context(), GET_BLOCK_FILTERS_TOKEN).await;
    }
    let start = (8 * interval + 1) as u64;
    let response = packed::BlockFilters::new_builder()
        .start_number(start)
        .block_hashes(vec![packed::Byte32::default()])
        .filters(vec![data])
        .build();
    protocol
        .received(
            nc.context(),
            peer,
            packed::BlockFilterMessage::new_builder()
                .set(response)
                .build()
                .as_bytes(),
        )
        .await;
    assert_eq!(
        storage
            .get_filter_scripts()
            .iter()
            .map(|s| s.block_number)
            .max(),
        Some(start),
        "valid delayed response survives unrelated cache churn"
    );
}

#[tokio::test]
async fn scoped_wire_reply_is_rejected_after_registration_or_epoch_changes() {
    use crate::storage::scan::{ScanIdentity, ScanState, ScanStorage};
    for reorg in [false, true] {
        let chain = MockChain::new_with_dummy_pow("stale-scoped-wire");
        let storage = chain.client_storage();
        let script = Script::default();
        let status = ScriptStatus {
            script: script.clone(),
            script_type: ScriptType::Lock,
            block_number: 10,
        };
        storage.update_filter_scripts(
            vec![ScriptStatus {
                script: script.clone(),
                script_type: ScriptType::Lock,
                block_number: 10,
            }],
            SetScriptsCommand::All,
        );
        let id = ScanIdentity::registered(storage, &script, ScriptType::Lock).unwrap();
        storage
            .install_scan_states([7; 16], vec![ScanState::settled(id, [7; 16], 0, 10)])
            .unwrap();
        let peer = PeerIndex::new(3);
        let peers = chain.create_peers();
        peers.add_peer(peer);
        peers
            .mock_prove_state(
                peer,
                VerifiableHeader::new(
                    HeaderBuilder::default()
                        .epoch(EpochNumberWithFraction::new(0, 0, 1000).full_value())
                        .number(11u64)
                        .build(),
                    Default::default(),
                    None,
                    Default::default(),
                ),
            )
            .unwrap();
        let mut data = Vec::new();
        golomb_coded_set::GCSFilterWriter::new(
            &mut data,
            golomb_coded_set::SipHasher24Builder::new(0, 0),
            golomb_coded_set::M,
            golomb_coded_set::P,
        )
        .finish()
        .unwrap();
        let data = Bytes::from(data).pack();
        let mut parent = storage.get_last_check_point().1;
        let hashes: Vec<packed::Byte32> = (0..11)
            .map(|_| {
                parent = ckb_types::utilities::calc_filter_hash(&parent, &data).pack();
                parent.clone()
            })
            .collect();
        peers.mock_latest_block_filter_hashes(peer, 0, hashes);
        let nc = MockNetworkContext::new(SupportProtocols::Filter);
        let mut protocol = chain.create_filter_protocol(peers);
        protocol.notify(nc.context(), GET_BLOCK_FILTERS_TOKEN).await;
        if reorg {
            storage.rollback_to_block(11);
        } else {
            storage.update_filter_scripts(vec![status], SetScriptsCommand::All);
        }
        let response = packed::BlockFilters::new_builder()
            .start_number(11u64)
            .block_hashes(vec![packed::Byte32::default()])
            .filters(vec![data])
            .build();
        protocol
            .received(
                nc.context(),
                peer,
                packed::BlockFilterMessage::new_builder()
                    .set(response)
                    .build()
                    .as_bytes(),
            )
            .await;
        let states = storage.scan_states().unwrap().unwrap();
        assert_eq!(
            states[0].settled, 10,
            "stale wire response cannot settle replacement or reorg epoch"
        );
        assert!(states[0].pending.is_none());
    }
}
