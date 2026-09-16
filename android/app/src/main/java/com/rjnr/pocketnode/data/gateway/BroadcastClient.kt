package com.rjnr.pocketnode.data.gateway

import javax.inject.Inject
import javax.inject.Singleton

/** Indirection over the light-client bridge so tests can fake it. */
fun interface BroadcastClient {
    /** Returns the bridge-returned tx hash JSON string, or null on failure. */
    suspend fun sendRaw(txJson: String): String?
}

@Singleton
class LightClientBroadcastClient @Inject constructor(
    private val lightClient: LightClientApi,
) : BroadcastClient {
    override suspend fun sendRaw(txJson: String): String? =
        lightClient.sendTransaction(txJson)
}
