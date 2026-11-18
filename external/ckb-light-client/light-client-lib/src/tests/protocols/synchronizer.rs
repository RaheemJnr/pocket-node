use std::sync::Arc;

use ckb_network::{CKBProtocolHandler, PeerIndex, SupportProtocols};
use ckb_types::{
    core::{BlockBuilder, BlockView},
    packed::{self, Script},
    prelude::*,
};

use crate::{
    storage::{LightClientStorage, ScriptStatus, ScriptType},
    tests::{
        prelude::*,
        utils::{MockChain, MockNetworkContext},
    },
};

#[tokio::test]
async fn test_sync_add_block() {
    let chain = MockChain::new_with_dummy_pow("test-sync");
    let nc = MockNetworkContext::new(SupportProtocols::Sync);

    let scripts = vec![ScriptStatus {
        script: Script::default(),
        script_type: ScriptType::Lock,
        block_number: 1,
    }];
    chain
        .client_storage()
        .update_filter_scripts(scripts, Default::default());

    let min_filtered_block_number = chain
        .client_storage()
        .get_filter_scripts()
        .into_iter()
        .map(|ss| ss.block_number)
        .min()
        .unwrap_or_default();
    let start_number = min_filtered_block_number + 1;
    let blocks_count = 1;
    // Match the production filter -> durable debt -> body-indexing sequence.
    // A genesis body cannot settle a range starting at block2.
    let block_view = BlockBuilder::default()
        .epoch(ckb_types::core::EpochNumberWithFraction::new(0, 0, 100).full_value())
        .number(start_number)
        .build();
    chain
        .client_storage()
        .update_min_filtered_block_number(start_number + blocks_count - 1);
    let proved_block_hash = block_view.hash();
    chain.client_storage().add_matched_blocks(
        start_number,
        blocks_count,
        vec![(proved_block_hash.clone(), true)],
    );
    let peer_index = PeerIndex::new(3);
    let peers = {
        let peers = chain.create_peers();
        peers.add_peer(peer_index);
        {
            let mut matched_blocks = peers.matched_blocks().write().await;
            peers.add_matched_blocks(&mut matched_blocks, vec![(proved_block_hash, true)]);
        }
        peers
    };

    let message = {
        let content = packed::SendBlock::new_builder()
            .block(block_view.data())
            .build();
        packed::SyncMessage::new_builder()
            .set(content)
            .build()
            .as_bytes()
    };

    let mut protocol = chain.create_sync_protocol(Arc::clone(&peers));
    protocol.received(nc.context(), peer_index, message).await;

    assert!(peers.matched_blocks().read().await.is_empty());
    assert!(chain
        .client_storage()
        .get_earliest_matched_blocks()
        .is_none());
    assert!(nc.not_banned(peer_index));
    let storage_filtered_block_number = chain
        .client_storage()
        .get_filter_scripts()
        .into_iter()
        .map(|ss| ss.block_number)
        .min()
        .unwrap_or_default();
    let filtered_block_number = start_number - 1 + blocks_count;
    assert_eq!(storage_filtered_block_number, filtered_block_number);
    assert!(nc.sent_messages().borrow().is_empty());
}

async fn assert_rejects_block_body(block_view: BlockView, received_block: packed::Block) {
    let chain = MockChain::new_with_dummy_pow("test-sync");
    let network_context = MockNetworkContext::new(SupportProtocols::Sync);

    let scripts = vec![ScriptStatus {
        script: Script::default(),
        script_type: ScriptType::Lock,
        block_number: 1,
    }];
    chain
        .client_storage()
        .update_filter_scripts(scripts, Default::default());

    let proved_block_hash = block_view.hash();
    chain
        .client_storage()
        .add_matched_blocks(2, 1, vec![(proved_block_hash.clone(), true)]);

    let peer_index = PeerIndex::new(3);
    let peers = {
        let peers = chain.create_peers();
        peers.add_peer(peer_index);
        {
            let mut matched_blocks = peers.matched_blocks().write().await;
            peers.add_matched_blocks(&mut matched_blocks, vec![(proved_block_hash.clone(), true)]);
        }
        peers
    };

    let message = packed::SyncMessage::new_builder()
        .set(
            packed::SendBlock::new_builder()
                .block(received_block)
                .build(),
        )
        .build()
        .as_bytes();

    let mut protocol = chain.create_sync_protocol(Arc::clone(&peers));
    protocol
        .received(network_context.context(), peer_index, message)
        .await;

    assert!(network_context.has_banned(peer_index).is_some());
    assert!(peers
        .matched_blocks()
        .read()
        .await
        .get(&proved_block_hash.unpack())
        .is_some_and(|(_, block)| block.is_none()));
    assert!(chain
        .client_storage()
        .get_earliest_matched_blocks()
        .is_some());
}

#[tokio::test]
async fn rejects_block_with_transactions_not_committed_by_header() {
    let block_view = BlockBuilder::default().build();
    let received_block = block_view
        .data()
        .as_builder()
        .transactions(vec![packed::Transaction::default()].pack())
        .build();

    assert_rejects_block_body(block_view, received_block).await;
}

#[tokio::test]
async fn rejects_block_with_proposals_not_committed_by_header() {
    let block_view = BlockBuilder::default().build();
    let received_block = block_view
        .data()
        .as_builder()
        .proposals(vec![packed::ProposalShortId::default()].pack())
        .build();

    assert_rejects_block_body(block_view, received_block).await;
}

#[tokio::test]
async fn rejects_block_with_extension_not_committed_by_header() {
    let extension = packed::Bytes::new_builder().push(1u8).build();
    let block_view = BlockBuilder::default().build();
    let received_block = block_view
        .as_advanced_builder()
        .extension(Some(extension))
        .build_unchecked()
        .data();

    assert_rejects_block_body(block_view, received_block).await;
}

#[tokio::test]
async fn rejects_block_with_uncles_not_committed_by_header() {
    let block_view = BlockBuilder::default().build();
    let received_uncle = packed::UncleBlock::new_builder().build();
    let received_block = block_view
        .data()
        .as_builder()
        .uncles(vec![received_uncle].pack())
        .build();

    assert_rejects_block_body(block_view, received_block).await;
}

#[tokio::test]
async fn rejects_block_with_uncle_proposals_not_committed_by_uncle_header() {
    let uncle = packed::UncleBlock::new_builder().build().into_view();
    let block_view = BlockBuilder::default().uncle(uncle.clone()).build();
    let received_uncle = uncle
        .data()
        .as_builder()
        .proposals(vec![packed::ProposalShortId::default()].pack())
        .build();
    let received_block = block_view
        .data()
        .as_builder()
        .uncles(vec![received_uncle].pack())
        .build();

    assert_rejects_block_body(block_view, received_block).await;
}

#[tokio::test]
async fn scoped_live_body_settles_while_historical_proof_is_missing() {
    use crate::storage::scan::{ScanIdentity, ScanState, ScanStorage};
    use crate::storage::{Key, StorageBackend};
    use ckb_types::core::{EpochNumberWithFraction, TransactionBuilder};
    let chain = MockChain::new_with_dummy_pow("scoped-body-fairness");
    let storage = chain.client_storage();
    let live = Script::default();
    let old = Script::new_builder().args(vec![1u8].pack()).build();
    storage.update_filter_scripts(
        vec![
            ScriptStatus {
                script: live.clone(),
                script_type: ScriptType::Lock,
                block_number: 100,
            },
            ScriptStatus {
                script: old.clone(),
                script_type: ScriptType::Lock,
                block_number: 10,
            },
        ],
        Default::default(),
    );
    let live_id = ScanIdentity::registered(storage, &live, ScriptType::Lock).unwrap();
    let old_id = ScanIdentity::registered(storage, &old, ScriptType::Lock).unwrap();
    storage
        .install_scan_states(
            [7; 16],
            vec![
                ScanState::settled(live_id.clone(), [7; 16], 100, 100),
                ScanState::settled(old_id.clone(), [7; 16], 10, 10),
            ],
        )
        .unwrap();
    let tx = TransactionBuilder::default()
        .output(
            packed::CellOutput::new_builder()
                .capacity(100u64)
                .lock(live.clone())
                .build(),
        )
        .output_data(Vec::<u8>::new().pack())
        .build();
    let block = BlockBuilder::default()
        .number(101u64)
        .epoch(EpochNumberWithFraction::new(0, 0, 1000).full_value())
        .transaction(tx.clone())
        .build();
    storage
        .begin_scan_range(&old_id, [7; 16], 11, 20, [9; 32], vec![[1; 32]])
        .unwrap();
    storage
        .begin_scan_range(
            &live_id,
            [7; 16],
            101,
            101,
            [9; 32],
            vec![block.hash().as_slice().try_into().unwrap()],
        )
        .unwrap();
    let peer = PeerIndex::new(3);
    let peers = chain.create_peers();
    peers.add_peer(peer);
    {
        let mut pending = peers.matched_blocks().write().await;
        peers.add_matched_blocks(
            &mut pending,
            vec![
                (block.hash(), true),
                (packed::Byte32::from_slice(&[1; 32]).unwrap(), false),
            ],
        );
    }
    let nc = MockNetworkContext::new(SupportProtocols::Sync);
    let mut protocol = chain.create_sync_protocol(peers.clone());
    let message = packed::SyncMessage::new_builder()
        .set(packed::SendBlock::new_builder().block(block.data()).build())
        .build();
    protocol
        .received(nc.context(), peer, message.as_bytes())
        .await;
    assert_eq!(storage.scan_state(&live_id).unwrap().unwrap().settled, 101);
    assert!(storage
        .scan_state(&live_id)
        .unwrap()
        .unwrap()
        .pending
        .is_none());
    assert_eq!(storage.scan_state(&old_id).unwrap().unwrap().settled, 10);
    assert!(storage
        .scan_state(&old_id)
        .unwrap()
        .unwrap()
        .pending
        .is_some());
    assert_eq!(
        StorageBackend::get(storage, Key::CellLockScript(&live, 101, 0, 0).into_vec()).unwrap(),
        Some(tx.hash().as_slice().to_vec())
    );
    assert_eq!(peers.matched_blocks().read().await.len(), 1);
}
