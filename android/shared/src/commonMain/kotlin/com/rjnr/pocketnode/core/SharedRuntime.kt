package com.rjnr.pocketnode.core

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.Json
import kotlin.coroutines.CoroutineContext

/**
 * The two shared-core values Swift cannot write for itself.
 *
 * Both are Kotlin types from `implementation` dependencies of this module, so
 * neither `Json { ... }` nor `Dispatchers.Default` has a Swift spelling: the
 * Objective-C header exposes `Json` and `CoroutineContext` only as opaque
 * types with no constructor, and SKIE does not generate a builder for either.
 * Every shared class that takes one of them (`SyncEngine`, `SyncCoordinator`,
 * `SingleWalletSyncService`, `createIosPocketNodeCoreDatabase`) would
 * otherwise be unconstructible from `AppContainer`.
 *
 * Android does not use this: Hilt's `AppModule` already provides its own
 * identically configured `Json`, and its shared classes get `Dispatchers.IO`
 * from `SharedModule`. Keeping both in one place is what makes the two
 * platforms provably agree on the JSON configuration.
 */
object SharedRuntime {

    /**
     * The JSON configuration every bridge payload is decoded with.
     *
     * Byte-for-byte the settings `AppModule.provideJson()` uses on Android.
     * `ignoreUnknownKeys` is not optional: a CKB `HeaderView` carries far more
     * fields than [com.rjnr.pocketnode.data.gateway.models.JniHeaderView]
     * declares, and the strict default would fail every tip read.
     */
    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * `Dispatchers.Default`, the context the shared classes' own parameters
     * already default to.
     *
     * Not `Dispatchers.IO`: that is JVM-only in kotlinx-coroutines and invisible
     * from `commonMain`, the same constraint
     * [com.rjnr.pocketnode.data.storage.createPocketNodeCoreDatabase] documents.
     */
    val defaultContext: CoroutineContext = Dispatchers.Default

    /**
     * The same dispatcher, typed as one.
     *
     * [com.rjnr.pocketnode.data.sync.BroadcastWatchdog] takes a
     * `CoroutineDispatcher` rather than a `CoroutineContext`, and
     * `Dispatchers.Default` is as unspellable from Swift as `Json` is, for the
     * same reason: the Objective-C header exposes it as an opaque type with no
     * constructor.
     */
    val defaultDispatcher: CoroutineDispatcher = Dispatchers.Default
}

/**
 * A long-lived [CoroutineScope] for a platform that cannot write one.
 *
 * `CoroutineScope(SupervisorJob() + Dispatchers.Default)` has no Swift
 * spelling for the same reason `Json { }` does not, and
 * [com.rjnr.pocketnode.data.send.SendContext] needs one: the send path's
 * post-broadcast re-register waits five seconds and so has to outlive the send
 * call itself.
 *
 * One per owner, not one per call. The owner ([close]s it when it goes away)
 * is `SendService` on iOS, which lives for the whole launch; a scope minted
 * per send would leave one behind for every transaction.
 */
class SharedScope(context: CoroutineContext = Dispatchers.Default) {

    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + context)

    /** Cancel everything launched in [scope]. */
    fun close() {
        scope.cancel()
    }
}
