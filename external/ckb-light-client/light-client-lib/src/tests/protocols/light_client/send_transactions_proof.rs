use std::sync::Arc;

use ckb_network::{CKBProtocolHandler, PeerIndex, SupportProtocols};
use ckb_store::ChainStore;
use ckb_types::{
    h256,
    packed::{self},
    prelude::*,
    utilities::{merkle_mountain_range::VerifiableHeader, CBMT},
};

use crate::{
    protocols::{
        light_client::constant::{FETCH_HEADER_TX_TOKEN, REFRESH_PEERS_TOKEN},
        FetchInfo, StatusCode,
    },
    storage::LightClientStorage,
    tests::{
        prelude::*,
        utils::{MockChain, MockNetworkContext},
    },
};

#[tokio::test(flavor = "multi_thread")]
async fn test_send_txs_proof_ok() {
    let chain = MockChain::new_with_dummy_pow("test-send-txs").start();
    let nc = MockNetworkContext::new(SupportProtocols::LightClient);
    let peer_index = PeerIndex::new(3);

    let missing_tx_hashes = vec![h256!("0x1").pack(), h256!("0x2").pack()];

    chain.mine_to(20);
    let tx_hashes: Vec<_> = [13, 15, 17]
        .into_iter()
        .map(|prev_num| {
            let tx = chain.get_cellbase_as_input(prev_num);
            chain.mine_block(|block| {
                let ids = vec![tx.proposal_short_id()];
                block.as_advanced_builder().proposals(ids).build()
            });
            chain.mine_blocks(1);
            chain.mine_block(|block| block.as_advanced_builder().transaction(tx.clone()).build());
            chain.mine_blocks(1);
            tx.hash()
        })
        .collect();

    chain.mine_blocks(4);

    let mut block_numbers = Vec::new();
    let mut filtered_blocks = Vec::new();
    for tx_hash in &tx_hashes {
        let (tx, tx_info) = chain
            .shared()
            .snapshot()
            .get_transaction_with_info(tx_hash)
            .unwrap();
        let block = chain
            .shared()
            .snapshot()
            .get_block(&tx_info.block_hash)
            .unwrap();
        let block_number = block.number();
        let header = block.header();
        let witnesses_root = block.calc_witnesses_root();

        let merkle_proof = CBMT::build_merkle_proof(
            &block
                .transactions()
                .iter()
                .map(|tx| tx.hash())
                .collect::<Vec<_>>(),
            &[tx_info.index as u32],
        )
        .unwrap();
        let filtered_block = packed::FilteredBlock::new_builder()
            .header(header.data())
            .witnesses_root(witnesses_root)
            .transactions(vec![tx.data()].pack())
            .proof(
                packed::MerkleProof::new_builder()
                    .indices(merkle_proof.indices().to_owned().pack())
                    .lemmas(merkle_proof.lemmas().to_owned().pack())
                    .build(),
            )
            .build();
        block_numbers.push(block_number);
        filtered_blocks.push(filtered_block);
    }

    let last_header = chain
        .shared()
        .snapshot()
        .get_verifiable_header_by_number(block_numbers[block_numbers.len() - 1] + 1)
        .unwrap();
    let message = {
        let proof = {
            let last_number = last_header.header().raw().number().unpack();
            chain.build_proof_by_numbers(last_number, &block_numbers)
        };
        let snapshot = chain.shared().snapshot();
        let uncles_hashes = block_numbers
            .iter()
            .map(|n| {
                snapshot
                    .get_block_by_number(*n)
                    .expect("block stored")
                    .calc_uncles_hash()
            })
            .collect::<Vec<_>>();
        let extensions = block_numbers
            .iter()
            .map(|n| {
                packed::BytesOpt::new_builder()
                    .set(
                        snapshot
                            .get_block_by_number(*n)
                            .expect("block stored")
                            .extension(),
                    )
                    .build()
            })
            .collect::<Vec<_>>();
        let items = packed::FilteredBlockVec::new_builder()
            .set(filtered_blocks)
            .build();
        let content = packed::SendTransactionsProofV1::new_builder()
            .last_header(last_header.clone())
            .proof(proof.pack())
            .filtered_blocks(items)
            .missing_tx_hashes(missing_tx_hashes.clone().pack())
            .blocks_uncles_hash(uncles_hashes.pack())
            .blocks_extension(extensions)
            .build();
        packed::LightClientMessage::new_builder()
            .set(content)
            .build()
    };

    let peers = {
        let peers = chain.create_peers();
        let txs_proof_request = packed::GetTransactionsProof::new_builder()
            .last_hash(last_header.header().calc_header_hash())
            .tx_hashes(
                tx_hashes
                    .clone()
                    .into_iter()
                    .chain(missing_tx_hashes.clone().into_iter())
                    .collect::<Vec<_>>()
                    .pack(),
            )
            .build();
        peers.add_peer(peer_index);
        peers
            .mock_prove_state(peer_index, last_header.into())
            .unwrap();
        peers.update_txs_proof_request(peer_index, Some(txs_proof_request));
        for tx_hash in &missing_tx_hashes {
            peers
                .fetching_txs()
                .insert(tx_hash.clone(), FetchInfo::new(1111, 0, false, false));
        }
        peers
    };

    for tx_hash in &tx_hashes {
        peers.add_fetch_tx(tx_hash.clone(), 111)
    }

    let mut protocol = chain.create_light_client_protocol(Arc::clone(&peers));
    protocol
        .received(nc.context(), peer_index, message.as_bytes())
        .await;

    assert!(nc.not_banned(peer_index));
    assert!(nc.sent_messages().borrow().is_empty());
    for tx_hash in tx_hashes {
        assert!(chain
            .client_storage()
            .get_transaction_with_header(&tx_hash)
            .is_some());
    }
    for tx_hash in missing_tx_hashes {
        assert!(peers.fetching_txs().get(&tx_hash).unwrap().missing());
    }
}

#[tokio::test(flavor = "multi_thread")]
async fn test_send_txs_proof_invalid_mmr_proof() {
    let chain = MockChain::new_with_dummy_pow("test-send-txs").start();
    let nc = MockNetworkContext::new(SupportProtocols::LightClient);
    let peer_index = PeerIndex::new(3);

    chain.mine_to(20);
    let tx_hashes: Vec<_> = [13, 15, 17]
        .into_iter()
        .map(|prev_num| {
            let tx = chain.get_cellbase_as_input(prev_num);
            chain.mine_block(|block| {
                let ids = vec![tx.proposal_short_id()];
                block.as_advanced_builder().proposals(ids).build()
            });
            chain.mine_blocks(1);
            chain.mine_block(|block| block.as_advanced_builder().transaction(tx.clone()).build());
            chain.mine_blocks(1);
            tx.hash()
        })
        .collect();

    chain.mine_blocks(4);

    let mut block_numbers = Vec::new();
    let mut filtered_blocks = Vec::new();
    for tx_hash in &tx_hashes {
        let (tx, tx_info) = chain
            .shared()
            .snapshot()
            .get_transaction_with_info(tx_hash)
            .unwrap();
        let block = chain
            .shared()
            .snapshot()
            .get_block(&tx_info.block_hash)
            .unwrap();
        let block_number = block.number();
        let header = block.header();
        let witnesses_root = block.calc_witnesses_root();

        let merkle_proof = CBMT::build_merkle_proof(
            &block
                .transactions()
                .iter()
                .map(|tx| tx.hash())
                .collect::<Vec<_>>(),
            &[tx_info.index as u32],
        )
        .unwrap();
        let filtered_block = packed::FilteredBlock::new_builder()
            .header(header.data())
            .witnesses_root(witnesses_root)
            .transactions(vec![tx.data()].pack())
            .proof(
                packed::MerkleProof::new_builder()
                    .indices(merkle_proof.indices().to_owned().pack())
                    .lemmas(merkle_proof.lemmas().to_owned().pack())
                    .build(),
            )
            .build();
        block_numbers.push(block_number);
        filtered_blocks.push(filtered_block);
    }

    let last_header = chain
        .shared()
        .snapshot()
        .get_verifiable_header_by_number(block_numbers[block_numbers.len() - 1] + 1)
        .unwrap();
    let message = {
        let proof = {
            let last_number = last_header.header().raw().number().unpack();
            // NOTE: this is invalid mmr proof
            chain.build_proof_by_numbers(last_number, &block_numbers[0..block_numbers.len() - 1])
        };
        let snapshot = chain.shared().snapshot();
        let uncles_hashes = block_numbers
            .iter()
            .map(|n| {
                snapshot
                    .get_block_by_number(*n)
                    .expect("block stored")
                    .calc_uncles_hash()
            })
            .collect::<Vec<_>>();
        let extensions = block_numbers
            .iter()
            .map(|n| {
                packed::BytesOpt::new_builder()
                    .set(
                        snapshot
                            .get_block_by_number(*n)
                            .expect("block stored")
                            .extension(),
                    )
                    .build()
            })
            .collect::<Vec<_>>();
        let items = packed::FilteredBlockVec::new_builder()
            .set(filtered_blocks)
            .build();
        let content = packed::SendTransactionsProofV1::new_builder()
            .last_header(last_header.clone())
            .proof(proof.pack())
            .filtered_blocks(items)
            .blocks_uncles_hash(uncles_hashes.pack())
            .blocks_extension(extensions)
            .build();
        packed::LightClientMessage::new_builder()
            .set(content)
            .build()
    };

    let peers = {
        let peers = chain.create_peers();
        let txs_proof_request = packed::GetTransactionsProof::new_builder()
            .last_hash(last_header.header().calc_header_hash())
            .tx_hashes(tx_hashes.clone().pack())
            .build();
        peers.add_peer(peer_index);
        peers
            .mock_prove_state(peer_index, last_header.into())
            .unwrap();
        peers.update_txs_proof_request(peer_index, Some(txs_proof_request));
        peers
    };

    for tx_hash in &tx_hashes {
        peers.add_fetch_tx(tx_hash.clone(), 111);
    }

    let mut protocol = chain.create_light_client_protocol(Arc::clone(&peers));
    protocol
        .received(nc.context(), peer_index, message.as_bytes())
        .await;

    assert!(nc.banned_since(peer_index, StatusCode::InvalidProof));
    assert!(nc.sent_messages().borrow().is_empty());
    for tx_hash in tx_hashes {
        assert!(chain
            .client_storage()
            .get_transaction_with_header(&tx_hash)
            .is_none());
    }
}

#[tokio::test(flavor = "multi_thread")]
async fn test_send_txs_proof_invalid_merkle_proof() {
    let chain = MockChain::new_with_dummy_pow("test-send-txs").start();
    let nc = MockNetworkContext::new(SupportProtocols::LightClient);
    let peer_index = PeerIndex::new(3);

    chain.mine_to(20);
    let tx_hashes: Vec<_> = [13, 15, 17]
        .into_iter()
        .map(|prev_num| {
            let tx = chain.get_cellbase_as_input(prev_num);
            chain.mine_block(|block| {
                let ids = vec![tx.proposal_short_id()];
                block.as_advanced_builder().proposals(ids).build()
            });
            chain.mine_blocks(1);
            chain.mine_block(|block| block.as_advanced_builder().transaction(tx.clone()).build());
            chain.mine_blocks(1);
            tx.hash()
        })
        .collect();

    chain.mine_blocks(4);

    let mut block_numbers = Vec::new();
    let mut filtered_blocks = Vec::new();
    for tx_hash in &tx_hashes {
        let (tx, tx_info) = chain
            .shared()
            .snapshot()
            .get_transaction_with_info(tx_hash)
            .unwrap();
        let block = chain
            .shared()
            .snapshot()
            .get_block(&tx_info.block_hash)
            .unwrap();
        let block_number = block.number();
        let header = block.header();
        let witnesses_root = block.calc_witnesses_root();

        let merkle_proof = CBMT::build_merkle_proof(
            &block
                .transactions()
                .iter()
                .map(|tx| tx.hash())
                .collect::<Vec<_>>(),
            &[tx_info.index as u32],
        )
        .unwrap();
        let filtered_block = packed::FilteredBlock::new_builder()
            .header(header.data())
            .witnesses_root(witnesses_root)
            .transactions(vec![tx.data()].pack())
            .proof(
                packed::MerkleProof::new_builder()
                    .indices(merkle_proof.indices().to_owned().pack())
                    // NOTE: invalid merkle proof
                    .lemmas(
                        merkle_proof
                            .lemmas()
                            .iter()
                            .skip(1)
                            .cloned()
                            .collect::<Vec<_>>()
                            .pack(),
                    )
                    .build(),
            )
            .build();
        block_numbers.push(block_number);
        filtered_blocks.push(filtered_block);
    }

    let last_header = chain
        .shared()
        .snapshot()
        .get_verifiable_header_by_number(block_numbers[block_numbers.len() - 1] + 1)
        .unwrap();
    let message = {
        let proof = {
            let last_number = last_header.header().raw().number().unpack();
            chain.build_proof_by_numbers(last_number, &block_numbers)
        };
        let snapshot = chain.shared().snapshot();
        let uncles_hashes = block_numbers
            .iter()
            .map(|n| {
                snapshot
                    .get_block_by_number(*n)
                    .expect("block stored")
                    .calc_uncles_hash()
            })
            .collect::<Vec<_>>();
        let extensions = block_numbers
            .iter()
            .map(|n| {
                packed::BytesOpt::new_builder()
                    .set(
                        snapshot
                            .get_block_by_number(*n)
                            .expect("block stored")
                            .extension(),
                    )
                    .build()
            })
            .collect::<Vec<_>>();
        let items = packed::FilteredBlockVec::new_builder()
            .set(filtered_blocks)
            .build();
        let content = packed::SendTransactionsProofV1::new_builder()
            .last_header(last_header.clone())
            .proof(proof.pack())
            .filtered_blocks(items)
            .blocks_uncles_hash(uncles_hashes.pack())
            .blocks_extension(extensions)
            .build();
        packed::LightClientMessage::new_builder()
            .set(content)
            .build()
    };

    let peers = {
        let peers = chain.create_peers();
        let txs_proof_request = packed::GetTransactionsProof::new_builder()
            .last_hash(last_header.header().calc_header_hash())
            .tx_hashes(tx_hashes.clone().pack())
            .build();
        peers.add_peer(peer_index);
        peers
            .mock_prove_state(peer_index, last_header.into())
            .unwrap();
        peers.update_txs_proof_request(peer_index, Some(txs_proof_request));
        peers
    };

    for tx_hash in &tx_hashes {
        peers.add_fetch_tx(tx_hash.clone(), 111);
    }

    let mut protocol = chain.create_light_client_protocol(Arc::clone(&peers));
    protocol
        .received(nc.context(), peer_index, message.as_bytes())
        .await;

    assert!(nc.banned_since(peer_index, StatusCode::InvalidProof));
    assert!(nc.sent_messages().borrow().is_empty());
    for tx_hash in tx_hashes {
        assert!(chain
            .client_storage()
            .get_transaction_with_header(&tx_hash)
            .is_none());
    }
}

#[tokio::test(flavor = "multi_thread")]
async fn legacy_txs_proof_for_extension_block_is_rejected() {
    let chain = MockChain::new_with_dummy_pow("test-send-txs-legacy-ext").start();
    let nc = MockNetworkContext::new(SupportProtocols::LightClient);
    let peer_index = PeerIndex::new(3);

    chain.mine_to(20);
    let tx = chain.get_cellbase_as_input(13);
    chain.mine_block(|block| {
        let ids = vec![tx.proposal_short_id()];
        block.as_advanced_builder().proposals(ids).build()
    });
    chain.mine_blocks(1);
    chain.mine_block(|block| block.as_advanced_builder().transaction(tx.clone()).build());
    chain.mine_blocks(1);

    let tx_hash = tx.hash();
    let (tx, tx_info) = chain
        .shared()
        .snapshot()
        .get_transaction_with_info(&tx_hash)
        .unwrap();
    let block = chain
        .shared()
        .snapshot()
        .get_block(&tx_info.block_hash)
        .unwrap();
    let block_number = block.number();
    let witnesses_root = block.calc_witnesses_root();

    // Every block mined by the mock chain carries an extension, so a legacy
    // (v0) message which withholds the V1 fields must be rejected.
    let header = block.header();

    let merkle_proof = CBMT::build_merkle_proof(
        &block
            .transactions()
            .iter()
            .map(|tx| tx.hash())
            .collect::<Vec<_>>(),
        &[tx_info.index as u32],
    )
    .unwrap();
    let filtered_block = packed::FilteredBlock::new_builder()
        .header(header.data())
        .witnesses_root(witnesses_root)
        .transactions(vec![tx.data()].pack())
        .proof(
            packed::MerkleProof::new_builder()
                .indices(merkle_proof.indices().to_owned().pack())
                .lemmas(merkle_proof.lemmas().to_owned().pack())
                .build(),
        )
        .build();

    let last_header = chain
        .shared()
        .snapshot()
        .get_verifiable_header_by_number(block_number + 1)
        .unwrap();
    let message = {
        let proof = {
            let last_number = last_header.header().raw().number().unpack();
            chain.build_proof_by_numbers(last_number, &[block_number])
        };
        let items = packed::FilteredBlockVec::new_builder()
            .set(vec![filtered_block])
            .build();
        let content = packed::SendTransactionsProof::new_builder()
            .last_header(last_header.clone())
            .proof(proof.pack())
            .filtered_blocks(items)
            .build();
        packed::LightClientMessage::new_builder()
            .set(content)
            .build()
    };

    let peers = {
        let peers = chain.create_peers();
        let txs_proof_request = packed::GetTransactionsProof::new_builder()
            .last_hash(last_header.header().calc_header_hash())
            .tx_hashes(vec![tx_hash.clone()].pack())
            .build();
        peers.add_peer(peer_index);
        peers
            .mock_prove_state(peer_index, last_header.into())
            .unwrap();
        peers.update_txs_proof_request(peer_index, Some(txs_proof_request));
        peers
    };

    peers.add_fetch_tx(tx_hash.clone(), 111);

    let mut protocol = chain.create_light_client_protocol(Arc::clone(&peers));
    protocol
        .received(nc.context(), peer_index, message.as_bytes())
        .await;

    assert!(nc.banned_since(peer_index, StatusCode::InvalidProof));
    assert!(nc.sent_messages().borrow().is_empty());
    assert!(chain
        .client_storage()
        .get_transaction_with_header(&tx_hash)
        .is_none());
}

#[tokio::test(flavor = "multi_thread")]
async fn test_send_txs_proof_is_empty() {
    test_empty_txs_proof(false, false).await;
}

#[tokio::test(flavor = "multi_thread")]
async fn rejects_v1_fields_before_processing_changed_last_state() {
    test_empty_txs_proof(true, true).await;
}

async fn test_empty_txs_proof(with_unexpected_extension: bool, last_state_changed: bool) {
    let chain = MockChain::new_with_dummy_pow("test-send-txs").start();
    let nc = MockNetworkContext::new(SupportProtocols::LightClient);
    let peer_index = PeerIndex::new(3);

    chain.mine_to(20);

    let last_header = chain
        .shared()
        .snapshot()
        .get_verifiable_header_by_number(20)
        .unwrap();
    let message = if with_unexpected_extension {
        let unexpected_extension = packed::BytesOpt::new_builder()
            .set(Some(packed::Bytes::default()))
            .build();
        let content = packed::SendTransactionsProofV1::new_builder()
            .last_header(last_header.clone())
            .blocks_extension(vec![unexpected_extension])
            .build();
        packed::LightClientMessage::new_builder()
            .set(content)
            .build()
    } else {
        let content = packed::SendTransactionsProof::new_builder()
            .last_header(last_header.clone())
            .build();
        packed::LightClientMessage::new_builder()
            .set(content)
            .build()
    };

    let peers = {
        let peers = chain.create_peers();
        let txs_proof_request = packed::GetTransactionsProof::new_builder()
            .last_hash(if last_state_changed {
                packed::Byte32::default()
            } else {
                last_header.header().calc_header_hash()
            })
            .build();
        peers.add_peer(peer_index);
        peers
            .mock_prove_state(peer_index, last_header.into())
            .unwrap();
        peers.update_txs_proof_request(peer_index, Some(txs_proof_request));
        peers
    };

    let mut protocol = chain.create_light_client_protocol(Arc::clone(&peers));
    protocol
        .received(nc.context(), peer_index, message.as_bytes())
        .await;

    if with_unexpected_extension {
        assert!(nc.banned_since(peer_index, StatusCode::MalformedProtocolMessage));
    } else {
        assert!(nc.not_banned(peer_index));
    }
    assert!(nc.sent_messages().borrow().is_empty());
}

#[tokio::test(flavor = "multi_thread")]
async fn rejects_malformed_v1_extra_fields() {
    // A V1 proof whose trailing `blocks_extension` field is too short to hold a
    // Molecule Vec header (total_size + item_count). The base message still
    // parses, so the response reaches the handler, but the V1 extra field is
    // structurally invalid. Under the previous `new_unchecked` + `.len()` path
    // this panicked inside `unpack_number`; with the validating reader it must
    // be rejected as a malformed protocol message instead.
    let chain = MockChain::new_with_dummy_pow("test-send-txs").start();
    let nc = MockNetworkContext::new(SupportProtocols::LightClient);
    let peer_index = PeerIndex::new(3);

    chain.mine_to(20);
    let last_header = chain
        .shared()
        .snapshot()
        .get_verifiable_header_by_number(20)
        .unwrap();

    // Build a structurally valid V1 message, then truncate its trailing field.
    let extension = packed::BytesOpt::new_builder()
        .set(Some(packed::Bytes::default()))
        .build();
    let content = packed::SendTransactionsProofV1::new_builder()
        .last_header(last_header.clone())
        .blocks_extension(vec![extension])
        .build();

    let mut bytes = content.as_slice().to_vec();
    {
        // `blocks_extension` is the last field, so it occupies the tail
        // `[extension_offset, total_size)` of the table.
        let reader = packed::SendTransactionsProofV1Reader::new_unchecked(&bytes);
        let extension_len = reader.blocks_extension().as_slice().len();
        let mut header = [0u8; 4];
        header.copy_from_slice(&bytes[0..4]);
        let total_size = u32::from_le_bytes(header) as usize;
        let extension_offset = total_size - extension_len;
        // Keep only 2 bytes of the trailing field: too short for a Vec header,
        // so `from_compatible_slice` rejects it while the base message still
        // parses.
        bytes.truncate(extension_offset + 2);
        let new_total = bytes.len() as u32;
        bytes[0..4].copy_from_slice(&new_total.to_le_bytes());
    }
    let malformed = packed::SendTransactionsProofV1::new_unchecked(bytes.into());
    let message = packed::LightClientMessage::new_builder()
        .set(malformed)
        .build();

    let peers = {
        let peers = chain.create_peers();
        let txs_proof_request = packed::GetTransactionsProof::new_builder()
            .last_hash(last_header.header().calc_header_hash())
            .build();
        peers.add_peer(peer_index);
        peers
            .mock_prove_state(peer_index, last_header.into())
            .unwrap();
        peers.update_txs_proof_request(peer_index, Some(txs_proof_request));
        peers
    };

    let mut protocol = chain.create_light_client_protocol(Arc::clone(&peers));
    protocol
        .received(nc.context(), peer_index, message.as_bytes())
        .await;

    assert!(nc.banned_since(peer_index, StatusCode::MalformedProtocolMessage));
    assert!(nc.sent_messages().borrow().is_empty());
}

#[tokio::test(flavor = "multi_thread")]
async fn test_send_headers_txs_request() {
    let chain = MockChain::new_with_dummy_pow("test-send-headers-txs").start();
    let nc = MockNetworkContext::new(SupportProtocols::LightClient);
    let peer_index = PeerIndex::new(3);

    let peers = {
        let peers = chain.create_peers();
        peers.fetching_headers().insert(
            h256!("0xaa22").pack(),
            FetchInfo::new(111, 3344, false, false),
        );
        peers
            .fetching_headers()
            .insert(h256!("0xaa33").pack(), FetchInfo::new(111, 0, false, false));
        peers.fetching_txs().insert(
            h256!("0xbb22").pack(),
            FetchInfo::new(111, 5566, false, false),
        );
        peers
            .fetching_txs()
            .insert(h256!("0xbb33").pack(), FetchInfo::new(111, 0, false, false));

        let tip_header = VerifiableHeader::new(
            chain.client_storage().get_tip_header().into_view(),
            Default::default(),
            None,
            Default::default(),
        );
        peers.add_peer(peer_index);
        peers.mock_prove_state(peer_index, tip_header).unwrap();
        peers
    };

    let mut protocol = chain.create_light_client_protocol(Arc::clone(&peers));

    assert_eq!(peers.get_headers_to_fetch().len(), 1);
    assert_eq!(peers.get_txs_to_fetch().len(), 1);

    protocol.notify(nc.context(), FETCH_HEADER_TX_TOKEN).await;

    assert!(nc.not_banned(peer_index));
    assert_eq!(nc.sent_messages().borrow().len(), 2);

    assert!(
        peers
            .fetching_headers()
            .get(&h256!("0xaa33").pack())
            .unwrap()
            .first_sent()
            > 0
    );
    assert!(
        peers
            .fetching_txs()
            .get(&h256!("0xbb33").pack())
            .unwrap()
            .first_sent()
            > 0
    );
    let peer = peers.get_peer(&peer_index).unwrap();
    assert!(peer.get_blocks_proof_request().is_some());
    assert!(peer.get_txs_proof_request().is_some());

    assert_eq!(peers.get_headers_to_fetch().len(), 0);
    assert_eq!(peers.get_txs_to_fetch().len(), 0);

    protocol.disconnected(nc.context(), peer_index).await;
    protocol.notify(nc.context(), REFRESH_PEERS_TOKEN).await;

    assert_eq!(peers.get_txs_to_fetch().len(), 1);
    assert_eq!(peers.get_headers_to_fetch().len(), 1);
}

#[tokio::test(flavor = "multi_thread")]
async fn deduplicated_transaction_proof_retains_legitimate_repeated_enqueues() {
    let chain = MockChain::new_with_dummy_pow("duplicate-tx-enqueue").start();
    let nc = MockNetworkContext::new(SupportProtocols::LightClient);
    let peers = chain.create_peers();
    let peer_index = PeerIndex::new(3);
    let a: packed::Byte32 = h256!("0x1").pack();
    let b: packed::Byte32 = h256!("0x2").pack();
    let c: packed::Byte32 = h256!("0x3").pack();
    // Real enqueue API: the map itself coalesces repeated requests. This
    // verifies observable request behavior, not an artificial duplicate Vec.
    for hash in [a.clone(), b.clone(), a.clone(), c.clone(), b.clone()] {
        peers
            .fetching_txs()
            .insert(hash, FetchInfo::new(111, 0, false, false));
    }
    let tip = VerifiableHeader::new(
        chain.client_storage().get_tip_header().into_view(),
        Default::default(),
        None,
        Default::default(),
    );
    peers.add_peer(peer_index);
    peers.mock_prove_state(peer_index, tip).unwrap();
    let mut protocol = chain.create_light_client_protocol(Arc::clone(&peers));
    protocol.notify(nc.context(), FETCH_HEADER_TX_TOKEN).await;
    let messages = nc.sent_messages();
    let messages = messages.borrow();
    assert_eq!(messages.len(), 1);
    let message = packed::LightClientMessage::from_slice(&messages[0].2).unwrap();
    let packed::LightClientMessageUnion::GetTransactionsProof(request) = message.to_enum() else {
        panic!("expected transaction proof request");
    };
    let hashes = request.tx_hashes().into_iter().collect::<Vec<_>>();
    assert_eq!(hashes.len(), 3);
    assert_eq!(
        hashes.into_iter().collect::<std::collections::HashSet<_>>(),
        [a, b, c].into_iter().collect()
    );
    assert!(
        peers.get_txs_to_fetch().is_empty(),
        "all unique requests marked in flight"
    );
}

// This exercises the real scheduler and received-message dispatch; cached data
// must not suppress a fresh wire attempt or satisfy a later registration.
async fn recovery_dispatch_case(case: &str) {
    use crate::protocols::light_client::recovery::{
        ChainSnapshot, RecoveryIdentity, RecoveryStatus,
    };
    use std::time::Instant;
    let chain = MockChain::new_with_dummy_pow("recovery-dispatch").start();
    chain.mine_to(20);
    let block = chain.shared().snapshot().get_block_by_number(5).unwrap();
    let tx = block.transactions()[0].data();
    let last = chain
        .shared()
        .snapshot()
        .get_verifiable_header_by_number(20)
        .unwrap();
    let peers = chain.create_peers();
    let peer = PeerIndex::new(3);
    peers.add_peer(peer);
    peers.mock_prove_state(peer, last.clone().into()).unwrap();
    let header: VerifiableHeader = last.clone().into();
    chain.client_storage().update_last_state(
        &header.total_difficulty(),
        &header.header().data(),
        &[],
    );
    let cached_block = if case == "storage" {
        chain.shared().snapshot().get_block_by_number(4).unwrap()
    } else {
        block.clone()
    };
    if case != "pending" {
        chain.client_storage().add_fetched_tx(
            &tx,
            &crate::storage::HeaderWithExtension {
                header: cached_block.header().data(),
                extension: cached_block.extension(),
            },
        );
    }
    let pending = Arc::new(crate::types::RwLock::new(
        crate::protocols::PendingTxs::default(),
    ));
    if case == "pending" {
        pending.write().unwrap().push(tx.clone().into_view(), 0);
    }
    let swc = crate::storage::StorageWithChainData::new(
        chain.client_storage().clone(),
        peers.clone(),
        pending.clone(),
    );
    if matches!(case, "generic" | "generic_missing" | "generic_during_send") {
        peers.add_fetch_tx(tx.calc_tx_hash(), 111);
    }
    peers
        .recovery()
        .initialize(
            RecoveryIdentity {
                genesis_hash: chain.consensus().genesis_block().hash(),
                store_generation: "isolated-test-store".into(),
                run_nonce: [7; 16],
            },
            ChainSnapshot {
                anchor: header.header().data(),
            },
        )
        .unwrap();
    let a = swc
        .recovery()
        .request(tx.calc_tx_hash(), Instant::now())
        .unwrap();
    let b = peers
        .recovery()
        .request(tx.calc_tx_hash(), Instant::now())
        .unwrap();
    let mut protocol = chain.create_light_client_protocol(Arc::clone(&peers));
    let nc = MockNetworkContext::new(SupportProtocols::LightClient);
    if case == "generic_during_send" {
        let peers = peers.clone();
        let hash = tx.calc_tx_hash();
        nc.on_next_send(move || peers.add_fetch_tx(hash, 112));
    }
    protocol.notify(nc.context(), FETCH_HEADER_TX_TOKEN).await;
    assert!(
        peers
            .get_peer(&peer)
            .unwrap()
            .get_txs_proof_request()
            .is_some(),
        "cached tx must issue proof request"
    );
    assert_eq!(
        peers.recovery().poll(a, Instant::now()).unwrap(),
        RecoveryStatus::Fetching
    );
    assert_eq!(
        peers.recovery().poll(b, Instant::now()).unwrap(),
        RecoveryStatus::Fetching
    );
    let later = peers
        .recovery()
        .request(tx.calc_tx_hash(), Instant::now())
        .unwrap();
    if matches!(case, "generic" | "generic_missing") {
        peers.add_fetch_tx(tx.calc_tx_hash(), 112);
    }
    let merkle = CBMT::build_merkle_proof(
        &block
            .transactions()
            .iter()
            .map(|t| t.hash())
            .collect::<Vec<_>>(),
        &[0],
    )
    .unwrap();
    let filtered = packed::FilteredBlock::new_builder()
        .header(block.header().data())
        .witnesses_root(block.calc_witnesses_root())
        .transactions(vec![tx.clone()].pack())
        .proof(
            packed::MerkleProof::new_builder()
                .indices(merkle.indices().to_vec().pack())
                .lemmas(merkle.lemmas().to_vec().pack())
                .build(),
        )
        .build();
    let proof = if case == "invalid_mmr" {
        packed::HeaderDigestVec::default()
    } else {
        chain.build_proof_by_numbers(20, &[5])
    };
    let filtered = if case == "duplicate" {
        vec![filtered.clone(), filtered]
    } else if matches!(case, "missing" | "generic_missing") {
        vec![]
    } else {
        vec![filtered]
    };
    let mut builder = packed::SendTransactionsProofV1::new_builder()
        .last_header(last)
        .proof(proof.pack())
        .filtered_blocks(
            packed::FilteredBlockVec::new_builder()
                .set(filtered.clone())
                .build(),
        )
        .blocks_uncles_hash(vec![block.calc_uncles_hash(); filtered.len()].pack())
        .blocks_extension(vec![
            packed::BytesOpt::new_builder()
                .set(block.extension())
                .build();
            filtered.len()
        ]);
    if matches!(case, "missing" | "generic_missing") {
        builder = builder
            .proof(Vec::<packed::HeaderDigest>::new().pack())
            .missing_tx_hashes(vec![tx.calc_tx_hash()].pack());
    }
    if case == "partition" {
        builder = builder.missing_tx_hashes(vec![tx.calc_tx_hash()].pack());
    }
    if case == "wrong_anchor" {
        builder = builder.last_header(
            chain
                .shared()
                .snapshot()
                .get_verifiable_header_by_number(19)
                .unwrap(),
        );
    }
    let content = builder.build();
    if case == "storage" {
        chain.client_storage().conn.lock().unwrap().execute_batch("CREATE TRIGGER recovery_fail BEFORE INSERT ON kv WHEN hex(substr(NEW.key, 1, 1)) = '00' BEGIN SELECT RAISE(ABORT, 'injected recovery failure'); END;").unwrap();
    }
    if case == "timeout" {
        peers.mark_fetching_txs_timeout(peer);
    }
    if case == "disconnect" {
        protocol.disconnected(nc.context(), peer).await;
        peers.add_peer(peer);
    }
    if case == "tip" {
        let update = peers.recovery().begin_chain_update().unwrap();
        peers
            .recovery()
            .finish_chain_update(
                update,
                ChainSnapshot {
                    anchor: chain
                        .shared()
                        .snapshot()
                        .get_header_by_number(19)
                        .unwrap()
                        .data(),
                },
            )
            .unwrap();
    }

    let message = packed::LightClientMessage::new_builder()
        .set(content)
        .build();
    protocol
        .received(nc.context(), peer, message.as_bytes())
        .await;
    if matches!(
        case,
        "success" | "pending" | "generic" | "generic_during_send"
    ) {
        assert!(nc.not_banned(peer));
        assert_eq!(
            peers.recovery().poll(later, Instant::now()).unwrap(),
            RecoveryStatus::Queued
        );
        let mut receipts = Vec::new();
        for id in [a, b] {
            let receipt = match peers.recovery().poll(id, Instant::now()).unwrap() {
                RecoveryStatus::Verified(r) => r,
                other => panic!("expected verified receipt: {other:?}"),
            };
            assert_eq!(receipt.transaction.as_slice(), tx.raw().as_slice());
            assert_eq!(receipt.header.as_slice(), block.header().data().as_slice());
            assert_eq!(receipt.transaction_index, None);
            assert!(peers
                .recovery()
                .validate(id, receipt.id, Instant::now())
                .is_ok());
            receipts.push((id, receipt));
        }
        let update = peers.recovery().begin_chain_update().unwrap();
        peers
            .recovery()
            .finish_chain_update(
                update,
                ChainSnapshot {
                    anchor: header.header().data(),
                },
            )
            .unwrap();
        for (id, receipt) in receipts {
            assert!(peers
                .recovery()
                .validate(id, receipt.id, Instant::now())
                .is_err());
        }
        if case == "pending" {
            assert!(pending.read().unwrap().get(&tx.calc_tx_hash()).is_some());
        }
        if case == "generic" {
            assert!(
                peers.get_tx_fetch_info(&tx.calc_tx_hash()).is_some(),
                "later generic waiter must survive"
            );
        }
        assert_eq!(
            chain
                .client_storage()
                .get_transaction(&tx.calc_tx_hash())
                .unwrap()
                .1,
            u32::MAX
        );
    } else {
        assert!(
            !matches!(
                peers.recovery().poll(a, Instant::now()),
                Ok(RecoveryStatus::Verified(_))
            ),
            "{case} minted a receipt"
        );
        assert_ne!(
            peers.recovery().poll(a, Instant::now()).unwrap(),
            RecoveryStatus::Fetching
        );
    }
    if case == "generic_during_send" {
        assert_eq!(
            peers
                .fetching_txs()
                .get(&tx.calc_tx_hash())
                .unwrap()
                .first_sent(),
            0,
            "an enqueue during send must remain eligible for its own request"
        );
        assert!(peers.get_txs_to_fetch().contains(&tx.calc_tx_hash()));
    }
    if case == "storage" {
        assert_eq!(
            chain
                .client_storage()
                .get_transaction_with_header(&tx.calc_tx_hash())
                .unwrap()
                .1
                .calc_header_hash(),
            cached_block.hash()
        );
        assert!(
            chain.client_storage().get_header(&block.hash()).is_none(),
            "earlier header write must roll back with tx write"
        );
    }
    if case == "generic_missing" {
        assert!(
            !peers
                .fetching_txs()
                .get(&tx.calc_tx_hash())
                .unwrap()
                .missing(),
            "old missing response must not overwrite new generic waiter"
        );
    }
    if case == "invalid_mmr" {
        assert!(
            peers.get_peer(&peer).unwrap().txs_quarantined,
            "failed recovery attempt must drain before slot reuse"
        );
    }
    if !matches!(
        case,
        "success" | "pending" | "generic" | "generic_during_send" | "tip"
    ) {
        assert_eq!(
            peers.recovery().poll(later, Instant::now()).unwrap(),
            RecoveryStatus::Queued
        );
    }
}

#[tokio::test(flavor = "multi_thread")]
async fn recovery_cached_transaction_dispatches_fresh_attempt() {
    recovery_dispatch_case("success").await;
}
#[tokio::test(flavor = "multi_thread")]
async fn recovery_rejected_proofs_never_publish() {
    for case in ["invalid_mmr", "duplicate", "partition", "wrong_anchor"] {
        recovery_dispatch_case(case).await;
    }
}
#[tokio::test(flavor = "multi_thread")]
async fn recovery_missing_partition_is_unavailable() {
    recovery_dispatch_case("missing").await;
}
#[tokio::test(flavor = "multi_thread")]
async fn recovery_storage_failure_never_publishes() {
    recovery_dispatch_case("storage").await;
}
#[tokio::test(flavor = "multi_thread")]
async fn recovery_timeout_and_old_connection_never_publish() {
    for case in ["timeout", "disconnect"] {
        recovery_dispatch_case(case).await;
    }
}
#[tokio::test(flavor = "multi_thread")]
async fn recovery_tip_change_during_flight_never_publishes() {
    recovery_dispatch_case("tip").await;
}

#[tokio::test(flavor = "multi_thread")]
async fn recovery_pending_transaction_cannot_satisfy_membership() {
    recovery_dispatch_case("pending").await;
}
#[tokio::test(flavor = "multi_thread")]
async fn recovery_does_not_remove_later_generic_waiter() {
    recovery_dispatch_case("generic").await;
}

fn recovery_proof_message(
    chain: &impl RunningChainExt,
    tip: u64,
    number: u64,
    tx_hash: &packed::Byte32,
) -> packed::LightClientMessage {
    let snapshot = chain.shared().snapshot();
    let block = snapshot.get_block_by_number(number).unwrap();
    let transactions = block.transactions();
    let index = transactions
        .iter()
        .position(|tx| &tx.hash() == tx_hash)
        .unwrap();
    let tx = transactions[index].data();
    let proof = CBMT::build_merkle_proof(
        &transactions.iter().map(|t| t.hash()).collect::<Vec<_>>(),
        &[index as u32],
    )
    .unwrap();
    let filtered = packed::FilteredBlock::new_builder()
        .header(block.header().data())
        .witnesses_root(block.calc_witnesses_root())
        .transactions(vec![tx].pack())
        .proof(
            packed::MerkleProof::new_builder()
                .indices(proof.indices().to_vec().pack())
                .lemmas(proof.lemmas().to_vec().pack())
                .build(),
        )
        .build();
    let content = packed::SendTransactionsProofV1::new_builder()
        .last_header(snapshot.get_verifiable_header_by_number(tip).unwrap())
        .proof(chain.build_proof_by_numbers(tip, &[number]))
        .filtered_blocks(
            packed::FilteredBlockVec::new_builder()
                .set(vec![filtered])
                .build(),
        )
        .blocks_uncles_hash(vec![block.calc_uncles_hash()].pack())
        .blocks_extension(vec![packed::BytesOpt::new_builder()
            .set(block.extension())
            .build()])
        .build();
    packed::LightClientMessage::new_builder()
        .set(content)
        .build()
}

#[tokio::test(flavor = "multi_thread")]
async fn recovery_old_generic_inflight_proof_cannot_authorize_new_registration() {
    recovery_old_generic_case(false).await;
}

#[tokio::test(flavor = "multi_thread")]
async fn recovery_old_generic_timeout_requires_disconnect_before_slot_reuse() {
    recovery_old_generic_case(true).await;
}

async fn recovery_old_generic_case(timeout: bool) {
    use crate::protocols::light_client::recovery::{
        ChainSnapshot, RecoveryIdentity, RecoveryStatus,
    };
    use std::time::Instant;
    let chain = MockChain::new_with_dummy_pow("recovery-old-flight").start();
    chain.mine_to(20);
    let block = chain.shared().snapshot().get_block_by_number(5).unwrap();
    let hash = block.transactions()[0].hash();
    let last: VerifiableHeader = chain
        .shared()
        .snapshot()
        .get_verifiable_header_by_number(20)
        .unwrap()
        .into();
    chain
        .client_storage()
        .update_last_state(&last.total_difficulty(), &last.header().data(), &[]);
    let peers = chain.create_peers();
    let peer = PeerIndex::new(3);
    peers.add_peer(peer);
    peers.mock_prove_state(peer, last.clone()).unwrap();
    peers
        .recovery()
        .initialize(
            RecoveryIdentity {
                genesis_hash: chain.consensus().genesis_block().hash(),
                store_generation: "isolated-test-store".into(),
                run_nonce: [6; 16],
            },
            ChainSnapshot {
                anchor: last.header().data(),
            },
        )
        .unwrap();
    let mut protocol = chain.create_light_client_protocol(peers.clone());
    let nc = MockNetworkContext::new(SupportProtocols::LightClient);
    peers.add_fetch_tx(hash.clone(), 111);
    protocol.notify(nc.context(), FETCH_HEADER_TX_TOKEN).await;
    let original_connection = peers.get_peer(&peer).unwrap().connection;
    let sent_before_timeout = nc.sent_messages().borrow().len();
    if timeout {
        // The production refresh path uses this for generic and recovery requests alike.
        peers.mark_fetching_txs_timeout(peer);
        assert!(peers.get_peer(&peer).unwrap().txs_quarantined);
    }
    let id = peers
        .recovery()
        .request(hash.clone(), Instant::now())
        .unwrap();
    if timeout {
        protocol.notify(nc.context(), FETCH_HEADER_TX_TOKEN).await;
        assert_eq!(nc.sent_messages().borrow().len(), sent_before_timeout);
    }
    let message = recovery_proof_message(&chain, 20, 5, &hash);
    protocol
        .received(nc.context(), peer, message.as_bytes())
        .await;
    assert_eq!(
        peers.recovery().poll(id, Instant::now()).unwrap(),
        RecoveryStatus::Queued
    );
    if timeout {
        // A late generic response cannot release the quarantined slot either.
        protocol.notify(nc.context(), FETCH_HEADER_TX_TOKEN).await;
        assert_eq!(nc.sent_messages().borrow().len(), sent_before_timeout);
        assert!(peers.get_peer(&peer).unwrap().txs_quarantined);
        assert_eq!(
            peers.recovery().poll(id, Instant::now()).unwrap(),
            RecoveryStatus::Queued
        );
        protocol.disconnected(nc.context(), peer).await;
        assert!(peers.get_peer(&peer).is_none());
        peers.add_peer(peer);
        peers.mock_prove_state(peer, last.clone()).unwrap();
        assert_ne!(peers.get_peer(&peer).unwrap().connection, original_connection);
    }
    protocol.notify(nc.context(), FETCH_HEADER_TX_TOKEN).await;
    assert_eq!(
        peers.recovery().poll(id, Instant::now()).unwrap(),
        RecoveryStatus::Fetching
    );
    protocol
        .received(nc.context(), peer, message.as_bytes())
        .await;
    assert!(matches!(
        peers.recovery().poll(id, Instant::now()).unwrap(),
        RecoveryStatus::Verified(_)
    ));
}

#[tokio::test(flavor = "multi_thread")]
async fn recovery_changed_tip_drains_generic_request() {
    recovery_changed_tip_case(false).await;
}

#[tokio::test(flavor = "multi_thread")]
async fn recovery_changed_tip_drains_recovery_request() {
    recovery_changed_tip_case(true).await;
}

async fn recovery_changed_tip_case(with_recovery: bool) {
    use crate::protocols::light_client::recovery::{
        ChainSnapshot, RecoveryIdentity, RecoveryStatus,
    };
    use std::time::Instant;
    let chain = MockChain::new_with_dummy_pow("recovery-old-flight").start();
    chain.mine_to(20);
    let block = chain.shared().snapshot().get_block_by_number(5).unwrap();
    let hash = block.transactions()[0].hash();
    let last: VerifiableHeader = chain
        .shared()
        .snapshot()
        .get_verifiable_header_by_number(20)
        .unwrap()
        .into();
    chain
        .client_storage()
        .update_last_state(&last.total_difficulty(), &last.header().data(), &[]);
    let peers = chain.create_peers();
    let peer = PeerIndex::new(3);
    peers.add_peer(peer);
    peers.mock_prove_state(peer, last.clone()).unwrap();
    peers
        .recovery()
        .initialize(
            RecoveryIdentity {
                genesis_hash: chain.consensus().genesis_block().hash(),
                store_generation: "isolated-test-store".into(),
                run_nonce: [6; 16],
            },
            ChainSnapshot {
                anchor: last.header().data(),
            },
        )
        .unwrap();
    let mut protocol = chain.create_light_client_protocol(peers.clone());
    let nc = MockNetworkContext::new(SupportProtocols::LightClient);
    peers.add_fetch_tx(hash.clone(), 111);
    let old_id = with_recovery.then(|| {
        peers
            .recovery()
            .request(hash.clone(), Instant::now())
            .unwrap()
    });
    protocol.notify(nc.context(), FETCH_HEADER_TX_TOKEN).await;
    assert!(!peers.get_txs_to_fetch().contains(&hash));
    let connection = peers.get_peer(&peer).unwrap().connection;
    chain.mine_to(21);
    let changed_tip = chain
        .shared()
        .snapshot()
        .get_verifiable_header_by_number(21)
        .unwrap();
    let empty = packed::SendTransactionsProofV1::new_builder()
        .last_header(changed_tip)
        .build();
    let changed = packed::LightClientMessage::new_builder().set(empty).build();
    protocol
        .received(nc.context(), peer, changed.as_bytes())
        .await;
    assert!(
        nc.not_banned(peer),
        "valid changed-tip response must be accepted"
    );
    assert!(
        nc.disconnect_requested(peer),
        "quarantine must promptly request drain"
    );
    assert!(peers.get_peer(&peer).unwrap().txs_quarantined);
    assert!(peers
        .get_peer(&peer)
        .unwrap()
        .get_txs_proof_request()
        .is_some());
    assert!(
        peers.get_txs_to_fetch().contains(&hash),
        "generic waiter must remain retryable"
    );
    if let Some(id) = old_id {
        assert_eq!(
            peers.recovery().poll(id, Instant::now()).unwrap(),
            RecoveryStatus::Unavailable
        );
    }
    let fresh_id = peers
        .recovery()
        .request(hash.clone(), Instant::now())
        .unwrap();
    let old_response = recovery_proof_message(&chain, 20, 5, &hash);
    protocol
        .received(nc.context(), peer, old_response.as_bytes())
        .await;
    assert_eq!(
        peers.recovery().poll(fresh_id, Instant::now()).unwrap(),
        RecoveryStatus::Queued
    );
    assert!(peers.get_txs_to_fetch().contains(&hash));
    let sent = nc.sent_messages().borrow().len();
    protocol.notify(nc.context(), FETCH_HEADER_TX_TOKEN).await;
    assert_eq!(nc.sent_messages().borrow().len(), sent);
    protocol.disconnected(nc.context(), peer).await;
    assert!(peers.get_peer(&peer).is_none());
    peers.add_peer(peer);
    // The peer hint did not advance our proven local tip. The replacement proves
    // that current local anchor before it is eligible for a fresh attempt.
    peers.mock_prove_state(peer, last).unwrap();
    assert_ne!(peers.get_peer(&peer).unwrap().connection, connection);
    protocol.notify(nc.context(), FETCH_HEADER_TX_TOKEN).await;
    assert_eq!(
        peers.recovery().poll(fresh_id, Instant::now()).unwrap(),
        RecoveryStatus::Fetching
    );
    protocol
        .received(nc.context(), peer, old_response.as_bytes())
        .await;
    assert!(matches!(
        peers.recovery().poll(fresh_id, Instant::now()).unwrap(),
        RecoveryStatus::Verified(_)
    ));
    assert!(peers.get_tx_fetch_info(&hash).is_none());
}

#[tokio::test(flavor = "multi_thread")]
async fn recovery_real_fork_reinclusion_and_commit_invalidation() {
    use crate::protocols::light_client::recovery::{
        ChainSnapshot, RecoveryIdentity, RecoveryStatus,
    };
    use crate::protocols::{LastState, ProveRequest, ProveState};
    use std::{collections::HashSet, time::Instant};
    let chain = MockChain::new_with_dummy_pow("recovery-real-fork").start();
    chain.mine_to(20);
    let tx = chain.get_cellbase_as_input(13);
    chain.mine_block(|block| {
        block
            .as_advanced_builder()
            .proposals(vec![tx.proposal_short_id()])
            .build()
    });
    chain.mine_blocks(1);
    chain.mine_block(|block| block.as_advanced_builder().transaction(tx.clone()).build());
    chain.mine_to(25);
    let old = chain.shared().snapshot().get_block_by_number(23).unwrap();
    let last: VerifiableHeader = chain
        .shared()
        .snapshot()
        .get_verifiable_header_by_number(25)
        .unwrap()
        .into();
    let old_headers: Vec<_> = (20..25)
        .map(|n| chain.shared().snapshot().get_header_by_number(n).unwrap())
        .collect();
    chain.client_storage().update_last_state(
        &last.total_difficulty(),
        &last.header().data(),
        &old_headers,
    );
    chain.client_storage().add_fetched_tx(
        &tx.data(),
        &crate::storage::HeaderWithExtension {
            header: old.header().data(),
            extension: old.extension(),
        },
    );
    let peers = chain.create_peers();
    let peer = PeerIndex::new(3);
    peers.add_peer(peer);
    peers.mock_prove_state(peer, last.clone()).unwrap();
    peers
        .recovery()
        .initialize(
            RecoveryIdentity {
                genesis_hash: chain.consensus().genesis_block().hash(),
                store_generation: "isolated-fork-store".into(),
                run_nonce: [5; 16],
            },
            ChainSnapshot {
                anchor: last.header().data(),
            },
        )
        .unwrap();
    let mut protocol = chain.create_light_client_protocol(peers.clone());
    let nc = MockNetworkContext::new(SupportProtocols::LightClient);
    let id = peers.recovery().request(tx.hash(), Instant::now()).unwrap();
    protocol.notify(nc.context(), FETCH_HEADER_TX_TOKEN).await;
    let old_message = recovery_proof_message(&chain, 25, 23, &tx.hash());
    protocol
        .received(nc.context(), peer, old_message.as_bytes())
        .await;
    let old_receipt = match peers.recovery().poll(id, Instant::now()).unwrap() {
        RecoveryStatus::Verified(r) => r,
        other => panic!("{other:?}"),
    };
    let inflight = peers.recovery().request(tx.hash(), Instant::now()).unwrap();
    protocol.notify(nc.context(), FETCH_HEADER_TX_TOKEN).await;
    let queued = peers.recovery().request(tx.hash(), Instant::now()).unwrap();

    // Build a genuine competing branch that re-includes the same raw tx at 25.
    chain.rollback_to(22, HashSet::new());
    for _ in 0..2 {
        chain.mine_block(|block| {
            let cellbase = block.transactions().get(0).unwrap().into_view();
            block
                .as_advanced_builder()
                .set_transactions(vec![cellbase])
                .build()
        });
    }
    chain.mine_block(|block| {
        let cellbase = block.transactions().get(0).unwrap().into_view();
        block
            .as_advanced_builder()
            .set_transactions(vec![cellbase, tx.clone()])
            .build()
    });
    chain.mine_to(28);
    let fresh = chain.shared().snapshot().get_block_by_number(25).unwrap();
    assert_ne!(fresh.hash(), old.hash());
    let new_last: VerifiableHeader = chain
        .shared()
        .snapshot()
        .get_verifiable_header_by_number(28)
        .unwrap()
        .into();
    let request = ProveRequest::new(LastState::new(new_last.clone()), Default::default());
    peers.mock_prove_request(peer, request.clone()).unwrap();
    let reorg = (20..23)
        .map(|n| chain.shared().snapshot().get_header_by_number(n).unwrap())
        .collect();
    let headers = (23..28)
        .map(|n| chain.shared().snapshot().get_header_by_number(n).unwrap())
        .collect();
    assert!(protocol
        .commit_prove_state(peer, ProveState::new_from_request(request, reorg, headers))
        .await
        .unwrap());
    for request in [id, inflight, queued] {
        assert_eq!(
            peers.recovery().poll(request, Instant::now()).unwrap(),
            RecoveryStatus::Unavailable
        );
    }
    assert!(peers
        .recovery()
        .validate(id, old_receipt.id, Instant::now())
        .is_err());
    // Production rollback intentionally retained the stale association.
    assert_eq!(
        chain
            .client_storage()
            .get_transaction_with_header(&tx.hash())
            .unwrap()
            .1
            .calc_header_hash(),
        old.hash()
    );
    protocol
        .received(nc.context(), peer, old_message.as_bytes())
        .await;
    assert!(peers
        .recovery()
        .validate(id, old_receipt.id, Instant::now())
        .is_err());
    let recovered = peers.recovery().request(tx.hash(), Instant::now()).unwrap();
    protocol.notify(nc.context(), FETCH_HEADER_TX_TOKEN).await;
    protocol
        .received(
            nc.context(),
            peer,
            recovery_proof_message(&chain, 28, 25, &tx.hash()).as_bytes(),
        )
        .await;
    let receipt = match peers.recovery().poll(recovered, Instant::now()).unwrap() {
        RecoveryStatus::Verified(r) => r,
        other => panic!("{other:?}"),
    };
    assert_eq!(receipt.transaction.as_slice(), tx.data().raw().as_slice());
    assert_eq!(receipt.header.calc_header_hash(), fresh.hash());
    assert_eq!(receipt.transaction_index, None);
    assert_eq!(
        chain
            .client_storage()
            .get_transaction_with_header(&tx.hash())
            .unwrap()
            .1
            .calc_header_hash(),
        fresh.hash()
    );
    assert!(peers
        .recovery()
        .validate(recovered, receipt.id, Instant::now())
        .is_ok());
}

#[tokio::test]
async fn recovery_no_eligible_peer_expires_without_cache_fallback() {
    use crate::protocols::light_client::recovery::{
        ChainSnapshot, RecoveryIdentity, RecoveryStatus,
    };
    use std::time::{Duration, Instant};
    let chain = MockChain::new_with_dummy_pow("recovery-no-peer");
    let peers = chain.create_peers();
    peers
        .recovery()
        .initialize(
            RecoveryIdentity {
                genesis_hash: chain.consensus().genesis_block().hash(),
                store_generation: "isolated-no-peer".into(),
                run_nonce: [4; 16],
            },
            ChainSnapshot {
                anchor: chain.client_storage().get_tip_header(),
            },
        )
        .unwrap();
    let now = Instant::now();
    let id = peers.recovery().request(Default::default(), now).unwrap();
    let mut protocol = chain.create_light_client_protocol(peers.clone());
    let nc = MockNetworkContext::new(SupportProtocols::LightClient);
    protocol.notify(nc.context(), FETCH_HEADER_TX_TOKEN).await;
    assert!(nc.sent_messages().borrow().is_empty());
    assert_eq!(
        peers.recovery().poll(id, now).unwrap(),
        RecoveryStatus::Queued
    );
    assert_eq!(
        peers
            .recovery()
            .poll(id, now + Duration::from_secs(30))
            .unwrap(),
        RecoveryStatus::Expired
    );
}

#[tokio::test(flavor = "multi_thread")]
async fn recovery_missing_response_preserves_later_generic_waiter() {
    recovery_dispatch_case("generic_missing").await;
}

#[tokio::test(flavor = "multi_thread")]
async fn recovery_generic_enqueue_during_send_keeps_retry_eligibility() {
    recovery_dispatch_case("generic_during_send").await;
}
