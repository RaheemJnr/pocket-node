package com.rjnr.pocketnode.core.prefs

import com.rjnr.pocketnode.data.gateway.models.NetworkType

/**
 * Which CKB network the app is pointed at. Global, never namespaced — it is
 * the namespace every other per-network preference is keyed by.
 *
 * Deliberately its own interface: the node lifecycle needs exactly these two
 * members and nothing else, and switching the value is a process-restart
 * event on Android, so the write must be durable before the caller returns.
 */
interface NetworkPreferences {

    fun getSelectedNetwork(): NetworkType

    /** Must flush synchronously: the caller may kill the process right after. */
    fun setSelectedNetwork(network: NetworkType)
}
