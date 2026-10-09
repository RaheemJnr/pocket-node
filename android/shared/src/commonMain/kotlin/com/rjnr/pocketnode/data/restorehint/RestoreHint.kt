package com.rjnr.pocketnode.data.restorehint

import kotlinx.serialization.Serializable

/**
 * The restore hint file (#559): a small, user-initiated file exported from
 * the old phone that lets a phrase (or private key) restore on a new phone
 * pick a safe custom start height instead of choosing a sync mode blind.
 *
 * On disk it is the [RestoreHintEnvelope] JSON:
 *
 * ```
 * {"format":"pocket-node-restore-hint","v":1,"payload":"<base64>","mac":"<hex>"}
 * ```
 *
 * `payload` is the base64 of the canonical UTF-8 JSON of a
 * [RestoreHintPayload]; `mac` is HMAC-SHA256 over exactly those bytes under a
 * key derived from the wallet's secret (see [RestoreHintCodec]).
 *
 * The payload is deliberately not encrypted: it carries no keys, addresses,
 * script args, amounts or transaction hashes, only block heights, derivation
 * indices and sync-mode names. The MAC is what matters: it proves the file was
 * made by someone holding the same recovery phrase (or private key) and that
 * nobody edited it since.
 */
@Serializable
data class RestoreHintEnvelope(
    val format: String,
    val v: Int,
    val payload: String,
    val mac: String,
)

/**
 * What the old phone knew about where each account's history starts.
 *
 * Field order is part of the format: the canonical encoding is the
 * declaration order below, and the known-answer test pins it.
 */
@Serializable
data class RestoreHintPayload(
    val v: Int = RestoreHintFormat.VERSION,
    /** [com.rjnr.pocketnode.data.gateway.models.NetworkType] name: MAINNET or TESTNET. */
    val network: String,
    val createdAtMs: Long,
    /** The source's light-client tip when the file was made. Informational anchor. */
    val tipHeight: Long,
    val tipHash: String,
    /** [RestoreHintKind.MNEMONIC] or [RestoreHintKind.RAW_KEY]. */
    val kind: String,
    val accounts: List<RestoreHintAccount>,
    val discovery: RestoreHintDiscovery = RestoreHintDiscovery(),
)

/**
 * One account of the exported wallet.
 *
 * @property index the BIP44 account index (m/44'/309'/index'/0/0); 0 for the
 *   main account and for a raw-key wallet.
 * @property coverageStart the source wallet's light-client start block for
 *   this account (`sync_progress.lightStartBlockNumber`).
 * @property firstActivity the lowest block number among this account's cached
 *   transactions, or null when it has none.
 * @property syncMode the source's [com.rjnr.pocketnode.data.gateway.models.SyncMode]
 *   name, informational only.
 */
@Serializable
data class RestoreHintAccount(
    val index: Int,
    val coverageStart: Long,
    val firstActivity: Long? = null,
    val syncMode: String,
)

/**
 * Sub-account discovery results from the source (#82 account axis).
 *
 * @property found account indices the discovery saw on-chain activity for.
 * @property highestScanned the highest account index the source scanned.
 */
@Serializable
data class RestoreHintDiscovery(
    val found: List<Int> = emptyList(),
    val highestScanned: Int = 0,
)

object RestoreHintKind {
    const val MNEMONIC = "mnemonic"
    const val RAW_KEY = "rawKey"
}

object RestoreHintFormat {
    const val FORMAT = "pocket-node-restore-hint"
    const val VERSION = 1

    /** SAF suggested file name, e.g. `pocket-node-restore-hint-testnet.json`. */
    fun fileName(network: String): String = "$FORMAT-${network.lowercase()}.json"
}

/** Why a restore hint file was rejected. Nothing from a rejected file is used. */
enum class RestoreHintError {
    /** Not a restore hint, unparseable, or internally inconsistent. */
    MALFORMED,

    /** A format version this build does not understand. */
    UNSUPPORTED_VERSION,

    /** Authentic for this secret, but made on the other network. */
    WRONG_NETWORK,

    /** The MAC does not verify: another wallet's file, or an edited one. */
    BAD_MAC,
}
