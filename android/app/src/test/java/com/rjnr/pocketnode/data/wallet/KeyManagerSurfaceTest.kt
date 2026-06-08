package com.rjnr.pocketnode.data.wallet

import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Regression guard for #289 (v1.7.2 keystore V2 short-circuit drop).
 *
 * The seven function names below were removed from [KeyManager] when the
 * V2 direct-write path landed. Reintroducing any of them risks reopening
 * the V1-stays-V1 hole — new wallets would silently land on the legacy
 * unrestricted Keystore key instead of going through [WalletKeyWriter] /
 * [com.rjnr.pocketnode.data.migration.KeystoreV2MigrationHelper.writeNewV2Row].
 *
 * If you intentionally need to add a function with one of these names,
 * delete it from [deletedFunctionNames] and add a code comment justifying
 * why the V2 routing is preserved.
 */
class KeyManagerSurfaceTest {

    private val deletedFunctionNames = setOf(
        "generateWallet",
        "importWallet",
        "generateWalletWithMnemonic",
        "importWalletFromMnemonic",
        "savePrivateKey",
        "writeToRoom",
        "storeKeysForWallet",
    )

    @Test
    fun `deleted KeyManager wallet-create functions do not reappear`() {
        // Use plain Java reflection — `kotlin.reflect.full.declaredFunctions` would
        // pull in `kotlin-reflect` which isn't a runtime dep of this app.
        // declaredMethods skips inherited/synthetic methods and includes the
        // Kotlin-mangled name suffixes for suspend functions, but the seven
        // names we guard against are non-suspend in their deleted form, so
        // a plain name check is sufficient.
        val present = KeyManager::class.java.declaredMethods.map { it.name }.toSet()
        val regressions = deletedFunctionNames.intersect(present)
        assertFalse(
            "These KeyManager functions were deleted in v1.7.2 (#289) and must not be " +
                "reintroduced without routing through WalletKeyWriter for V2 persistence: " +
                "$regressions",
            regressions.isNotEmpty()
        )
    }
}
