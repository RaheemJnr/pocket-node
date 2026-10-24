package com.rjnr.pocketnode

import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rjnr.pocketnode.core.log.NoopLogger
import com.rjnr.pocketnode.data.wallet.KeyManager
import com.rjnr.pocketnode.data.wallet.MnemonicManager
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore

/** Opt-in, public fixture only; never opens wallet preference files or shared master aliases. */
// Intentionally exercises the retired ESP format; modern APIs cannot read these captures.
@Suppress("DEPRECATION")
@RunWith(AndroidJUnit4::class)
class CapturedLegacyPreferencesTest {
    @Test fun capturedEspIsReadOnlyAndCannotGenerateMissingKeys() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("capturedEspProof") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val suffix = java.util.UUID.randomUUID().toString()
        val name = "pocket578_public_esp_fixture_$suffix"
        val alias = "pocket578_public_esp_master_$suffix"
        val missingAlias = "pocket578_public_absent_master_$suffix"
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val keyManager = KeyManager(context, MnemonicManager(), NoopLogger)
        try {
            val master = MasterKey.Builder(context, alias).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            val prefs = EncryptedSharedPreferences.create(context, name, master,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
            assertTrue(prefs.edit().putString("private_key", "11".repeat(32))
                .putString("mnemonic_words", "public disposable fixture words").commit())
            val raw = context.getSharedPreferences(name, 0).all.mapValues { it.value!! }
            val file = java.io.File(context.filesDir.parentFile, "shared_prefs/$name.xml")
            val before = file.readBytes()
            val aliases = keys.aliases().toList().toSet()
            val recovered = keyManager.readCapturedPreference(name, raw, masterAlias = alias)
            assertEquals("11".repeat(32), recovered?.privateKeyHex)
            assertEquals("public disposable fixture words", recovered?.mnemonic)
            // The captured filename is authenticated, even though no backing file is opened.
            assertTrue(runCatching { keyManager.readCapturedPreference(name + "_wrong", raw, masterAlias = alias) }.isFailure)
            assertTrue(runCatching { keyManager.readCapturedPreference(name, raw, masterAlias = missingAlias) }.isFailure)
            assertTrue(runCatching { keyManager.readCapturedPreference(name,
                raw - "__androidx_security_crypto_encrypted_prefs_key_keyset__", masterAlias = alias) }.isFailure)
            assertFalse(keys.containsAlias(missingAlias))
            assertEquals(aliases, keys.aliases().toList().toSet())
            assertArrayEquals(before, file.readBytes())
            assertEquals(raw, context.getSharedPreferences(name, 0).all)
        } finally {
            context.deleteSharedPreferences(name)
            // Only this test's unique alias is eligible for cleanup.
            if (keys.containsAlias(alias)) keys.deleteEntry(alias)
        }
    }
}
