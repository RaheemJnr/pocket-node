package com.rjnr.pocketnode

import android.database.sqlite.SQLiteDatabase
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rjnr.pocketnode.core.crypto.Secp256k1Signer
import com.rjnr.pocketnode.data.crypto.KeyMaterial
import com.rjnr.pocketnode.data.vault.WalletEnvelopeCodec
import com.rjnr.pocketnode.data.vault.WalletEnvelopeCrypto
import com.rjnr.pocketnode.data.vault.WalletEnvelopeAuthenticationException
import com.rjnr.pocketnode.data.wallet.WalletDerivation
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Read-only verification of explicitly opted-in disposable emulator fixtures.
 * Does not clear app data, change wallet records, use a network, or broadcast.
 */
@RunWith(AndroidJUnit4::class)
class WalletVaultRecoveryTest {
    @Test
    fun generatedWalletBackupMarkerMatchesCompletedQuizOnly() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Opt in only for the generated disposable quiz wallet", args.getString("generatedQuizFixture") == "true")
        val expectedId = args.getString("generatedWalletId") ?: error("Capture wallet ID before quiz")
        val expectedAddress = args.getString("generatedWalletAddress") ?: error("Capture address before quiz")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        SQLiteDatabase.openDatabase(context.getDatabasePath("pocket_node.db").path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT w.walletId,w.mainnetAddress,k.mnemonicBackedUp,v.cleanupPending FROM wallets w JOIN key_material k ON k.walletId=w.walletId JOIN wallet_vault v ON v.walletId=w.walletId WHERE w.name='GeneratedQuiz'", null).use { row ->
                assertTrue("Committed generated wallet must remain discoverable", row.moveToFirst())
                assertEquals(expectedId, row.getString(0))
                assertEquals(expectedAddress, row.getString(1))
                assertEquals("Backup completion changed without the expected quiz outcome",
                    args.getString("expectedGeneratedBackup") == "true", row.getInt(2) != 0)
                assertEquals(0, row.getInt(3))
                assertFalse(row.moveToNext())
            }
        }
    }

    @Test
    fun publicMnemonicSurvivesCancelledDeviceEnrollmentAndRestart() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Opt in only after creating the disposable public mnemonic wallet",
            args.getString("publicMnemonicFixture") == "abandon-about")
        val pass = (args.getString("fixturePassphrase") ?: error("Explicit fixture passphrase required")).toCharArray()
        val expectedBackup = args.getString("expectedMnemonicBackedUp") == "true"
        val db = SQLiteDatabase.openDatabase(instrumentation.targetContext.getDatabasePath("pocket_node.db").path,
            null, SQLiteDatabase.OPEN_READONLY)
        try {
            db.rawQuery("SELECT v.walletId,v.envelope,w.mainnetAddress,w.testnetAddress,k.mnemonicBackedUp FROM wallet_vault v JOIN wallets w ON w.walletId=v.walletId JOIN key_material k ON k.walletId=v.walletId WHERE w.name='PublicMnemonic'", null).use { row ->
                assertTrue("New protected wallet must remain discoverable", row.moveToFirst())
                val envelope = WalletEnvelopeCodec.decode(row.getBlob(1))
                val plaintext = WalletEnvelopeCrypto().decrypt(envelope, row.getString(0), pass)
                try {
                    val material = Json.decodeFromString<KeyMaterial>(plaintext.toString(Charsets.UTF_8))
                    assertTrue("Expected only standard public mnemonic fixture", material.mnemonic ==
                        List(11) { "abandon" }.plus("about").joinToString(" "))
                    val key = material.privateKey.removePrefix("0x").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                    try {
                        val identity = WalletDerivation.walletInfo(key)
                        assertEquals(identity.mainnetAddress, row.getString(2))
                        assertEquals(identity.testnetAddress, row.getString(3))
                        val message = ByteArray(32) { (it + 1).toByte() }
                        val signature = Secp256k1Signer.signRecoverable(message, key)
                        assertTrue(Secp256k1Signer.verify(signature, message, Secp256k1Signer.publicKey(key)))
                        message[0] = 99
                        assertFalse(Secp256k1Signer.verify(signature, message, Secp256k1Signer.publicKey(key)))
                    } finally { key.fill(0) }
                    assertEquals("Imported mnemonic backup status must remain unchanged", expectedBackup, row.getInt(4) != 0)
                } finally { plaintext.fill(0) }
                assertFalse("Public fixture name must identify one wallet", row.moveToNext())
            }
        } finally { pass.fill('\u0000'); db.close() }
    }

    @Test
    fun recoveredPublicFixtureDecryptsAndSignsLocally() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue("Run only on the disposable Pocket578Control fixture",
            InstrumentationRegistry.getArguments().getString("publicRecoveryFixture") == "scalar2")
        val pass = (InstrumentationRegistry.getArguments().getString("fixturePassphrase")
            ?: error("Explicit public fixture passphrase required")).toCharArray()
        val database = instrumentation.targetContext.getDatabasePath("pocket_node.db")
        val db = SQLiteDatabase.openDatabase(database.path, null, SQLiteDatabase.OPEN_READONLY)
        try {
            db.rawQuery("SELECT v.walletId,v.envelope,w.mainnetAddress,w.testnetAddress,v.cleanupPending,k.mnemonicBackedUp FROM wallet_vault v JOIN wallets w ON w.walletId=v.walletId JOIN key_material k ON k.walletId=v.walletId", null).use { rows ->
                assertTrue("Recovered vault missing", rows.moveToFirst())
                var checked = false
                do {
                    val id = rows.getString(0)
                    val envelope = WalletEnvelopeCodec.decode(rows.getBlob(1))
                    InstrumentationRegistry.getArguments().getString("previousFixturePassphrase")?.toCharArray()?.let { previous ->
                        try {
                            try {
                                WalletEnvelopeCrypto().decrypt(envelope, id, previous).fill(0)
                                fail("Previous passphrase still opens a rotated active envelope")
                            } catch (_: WalletEnvelopeAuthenticationException) { /* expected rejection */ }
                        } finally { previous.fill('\u0000') }
                    }
                    val start = System.nanoTime()
                    val plaintext = WalletEnvelopeCrypto().decrypt(envelope, id, pass)
                    Log.i("WalletRecoveryTest", "production_argon2_decrypt_ms=" + (System.nanoTime() - start) / 1_000_000)
                    try {
                        val material = Json.decodeFromString<KeyMaterial>(plaintext.toString(Charsets.UTF_8))
                        if (material.privateKey.removePrefix("0x") == "00".repeat(31) + "02") {
                            val key = ByteArray(32).also { it[31] = 2 }
                            try {
                                val identity = WalletDerivation.walletInfo(key)
                                assertEquals(identity.mainnetAddress, rows.getString(2))
                                assertEquals(identity.testnetAddress, rows.getString(3))
                                assertEquals("Authenticated legacy cleanup must finish", 0, rows.getInt(4))
                                assertEquals("Another wallet quiz must not mark this raw-key wallet", 0, rows.getInt(5))
                                val message = ByteArray(32) { it.toByte() }
                                val signature = Secp256k1Signer.signRecoverable(message, key)
                                assertTrue(Secp256k1Signer.verify(signature.copyOf(64), message, Secp256k1Signer.publicKey(key)))
                                message[0] = 99
                                assertFalse(Secp256k1Signer.verify(signature.copyOf(64), message, Secp256k1Signer.publicKey(key)))
                                checked = true
                            } finally { key.fill(0) }
                        }
                    } finally { plaintext.fill(0) }
                } while (rows.moveToNext())
                assertTrue("Public scalar-2 fixture was not recovered", checked)
            }
        } finally { pass.fill('\u0000'); db.close() }
    }
}
