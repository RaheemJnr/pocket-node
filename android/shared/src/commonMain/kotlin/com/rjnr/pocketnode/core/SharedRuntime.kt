package com.rjnr.pocketnode.core

import kotlinx.coroutines.Dispatchers
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
}
