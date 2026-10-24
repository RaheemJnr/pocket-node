package com.rjnr.pocketnode.data.vault

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.rjnr.pocketnode.data.database.AppDatabase
import com.rjnr.pocketnode.data.database.entity.KeyMaterialEntity
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE)
class WalletVaultStoreTest {
    private lateinit var db: AppDatabase
    private lateinit var store: WalletVaultStore
    private val crypto = WalletEnvelopeCrypto()
    private val pass = "  sixteen words with Unicode ไทย  ".toCharArray()
    private val nextPass = "another long exact passphrase".toCharArray()
    private val material = byteArrayOf(3, 5, 8, 13)
    private fun metadata(id: String) = KeyMaterialEntity(id, byteArrayOf(9, 8), byteArrayOf(7),
        byteArrayOf(4, 2), "mnemonic", true, 123, 2)
    private fun envelope(id: String, phrase: CharArray = pass) = crypto.create(id, 0, material, phrase)
    private suspend fun add(id: String) = store.activate(metadata(id), null, envelope(id), pass)
    private suspend fun rejects(block: suspend () -> Unit) {
        try { block(); fail("Operation unexpectedly succeeded") } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
        }
    }
    @Before fun setup() {
        ApplicationProvider.getApplicationContext<Context>().deleteDatabase("vault-store.db")
        db = openDatabase()
        store = WalletVaultStore(db, crypto)
    }
    private fun openDatabase() = Room.databaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java, "vault-store.db")
        .allowMainThreadQueries().build()
    @After fun teardown() { db.close(); ApplicationProvider.getApplicationContext<Context>().deleteDatabase("vault-store.db"); pass.fill('\u0000'); nextPass.fill('\u0000') }

    @Test fun `activation archives exact source and persists verified marker and pending cleanup`() = runBlocking {
        val legacy = metadata("a")
        db.keyMaterialDao().upsert(legacy)
        store.activate(legacy, legacy.iv, envelope("a"), pass)
        assertArrayEquals(material, crypto.decrypt(store.get("a")!!, "a", pass))
        val marker = db.keyMaterialDao().getByWalletId("a")!!
        assertEquals(3, marker.kdfVersion)
        assertEquals(0, marker.iv.size)
        assertEquals(0, marker.encryptedPrivateKey.size)
        assertNull(marker.encryptedMnemonic)
        assertTrue(marker.mnemonicBackedUp)
        assertEquals(legacy, db.walletVaultDao().legacy("a")!!.source())
        assertTrue(store.pendingLegacyCleanup().contains("a"))
        rejects { store.markLegacyCleanupComplete("a", 1) }
        assertEquals(legacy, db.walletVaultDao().legacy("a")!!.source())
        store.markLegacyCleanupComplete("a", 0)
        assertFalse(store.pendingLegacyCleanup().contains("a"))
        assertNull(db.walletVaultDao().legacy("a"))
    }
    @Test fun `wrong verification passphrase preserves legacy and does not create profile`() = runBlocking {
        val legacy = metadata("a")
        db.keyMaterialDao().upsert(legacy)
        rejects { store.activate(legacy, legacy.iv, envelope("a"), nextPass) }
        assertEquals(legacy, db.keyMaterialDao().getByWalletId("a"))
        assertNull(store.get("a")); assertFalse(store.hasProfile())
        assertNull(db.walletVaultDao().legacy("a"))
    }
    @Test fun `one installation passphrase enforced for new wallets and exact whitespace`() = runBlocking {
        add("a")
        assertTrue(store.verifyPassphrase(pass))
        assertFalse(store.verifyPassphrase(pass.concatToString().trim().toCharArray()))
        rejects { store.activate(metadata("b"), null, envelope("b", nextPass), nextPass) }
        assertNull(store.get("b")); assertNull(db.keyMaterialDao().getByWalletId("b"))
        add("b"); assertEquals(2, store.listActive().size)
    }
    @Test fun `stale legacy IV or an existing vault rejects concurrent activation`() = runBlocking {
        val legacy = metadata("a")
        db.keyMaterialDao().upsert(legacy)
        rejects { store.activate(legacy, byteArrayOf(1), envelope("a"), pass) }
        rejects { store.activate(legacy, null, envelope("a"), pass) }
        store.activate(legacy, legacy.iv, envelope("a"), pass)
        rejects { store.activate(legacy, legacy.iv, envelope("a"), pass) }
        assertEquals(0L, store.get("a")!!.generation)
    }
    @Test fun `concurrent activations across store instances cannot overwrite the winner`() = runBlocking {
        val candidate = envelope("a")
        val other = WalletVaultStore(db, crypto)
        val results = coroutineScope {
            listOf(store, other).map { s -> async { runCatching { s.activate(metadata("a"), null, candidate, pass) }.isSuccess } }.map { it.await() }
        }
        assertEquals(1, results.count { it })
        assertArrayEquals(material, crypto.decrypt(store.get("a")!!, "a", pass))
    }
    @Test fun `persisted corruption during activation rolls back profile archive and metadata`() = runBlocking {
        val legacy = metadata("a")
        db.keyMaterialDao().upsert(legacy)
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER corrupt_vault AFTER INSERT ON wallet_vault BEGIN UPDATE wallet_vault SET envelope = X'00' WHERE walletId = NEW.walletId; END")
        rejects { store.activate(legacy, legacy.iv, envelope("a"), pass) }
        assertEquals(legacy, db.keyMaterialDao().getByWalletId("a"))
        assertFalse(store.hasProfile()); assertNull(store.get("a"))
        assertNull(db.walletVaultDao().legacy("a"))
    }
    @Test fun `rotation changes every generation and profile together`() = runBlocking {
        add("a"); add("b")
        store.rotate(pass, nextPass)
        for (id in listOf("a", "b")) {
            val row = store.get(id)!!
            assertEquals(1L, row.generation)
            assertArrayEquals(material, crypto.decrypt(row, id, nextPass))
            rejects { crypto.decrypt(row, id, pass) }
        }
        assertTrue(store.verifyPassphrase(nextPass)); assertFalse(store.verifyPassphrase(pass))
    }
    @Test fun `write failure halfway through rotation preserves all old bytes and profile`() = runBlocking {
        add("a"); add("b")
        val before = store.snapshot()
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_rotation BEFORE UPDATE ON wallet_vault WHEN NEW.walletId = 'b' BEGIN SELECT RAISE(ABORT, 'rejected'); END")
        rejects { store.rotate(pass, nextPass) }
        db.close()
        db = openDatabase()
        store = WalletVaultStore(db, crypto)
        for (row in before.envelopes) assertArrayEquals(WalletEnvelopeCodec.encode(row), WalletEnvelopeCodec.encode(store.get(row.walletId)!!))
        assertTrue(store.verifyPassphrase(pass)); assertFalse(store.verifyPassphrase(nextPass))
    }
    @Test fun `unsupported or oversized persisted vault fails closed including rotation`() = runBlocking {
        add("a"); add("b")
        val oldA = WalletEnvelopeCodec.encode(store.get("a")!!)
        db.openHelper.writableDatabase.execSQL("UPDATE wallet_vault SET envelope = CAST(REPLACE(CAST(envelope AS TEXT), '\"version\":1', '\"version\":99') AS BLOB) WHERE walletId = 'b'")
        rejects { store.get("b") }; rejects { store.listActive() }; rejects { store.rotate(pass, nextPass) }
        assertArrayEquals(oldA, WalletEnvelopeCodec.encode(store.get("a")!!))
        db.openHelper.writableDatabase.execSQL("UPDATE wallet_vault SET envelope = zeroblob(524289) WHERE walletId = 'b'")
        rejects { store.get("b") }
    }
    @Test fun `reserved profile identity cannot be installed as wallet`() = runBlocking {
        rejects { store.activate(metadata(WalletVaultStore.PROFILE_ID), null, envelope(WalletVaultStore.PROFILE_ID), pass) }
        assertFalse(store.hasProfile())
    }
    @Test fun `device wrap update rejects stale generation and preserves payload`() = runBlocking {
        add("a")
        val wrap = DeviceKeyWrap("alias", ByteArray(48) { 8 }, ByteArray(12) { 9 })
        rejects { store.updateDeviceWrap("a", 1, wrap) }
        store.updateDeviceWrap("a", 0, wrap)
        wrap.wrappedKey.fill(0)
        assertEquals(8.toByte(), store.get("a")!!.deviceWrap!!.wrappedKey[0])
        assertArrayEquals(material, crypto.decrypt(store.get("a")!!, "a", pass))
        store.rotate(pass, nextPass)
        assertNull(store.get("a")!!.deviceWrap)
    }
    @Test fun `authenticated partial reset preserves unrecovered wallet and rejects stale snapshots`() = runBlocking {
        add("a"); add("b")
        val before = store.snapshot()
        val replacement = crypto.create("a", 1, material, nextPass)
        store.replaceAfterAuthenticatedReset(before, listOf(replacement), nextPass)
        assertArrayEquals(material, crypto.decrypt(store.get("a")!!, "a", nextPass))
        assertArrayEquals(material, crypto.decrypt(store.get("b")!!, "b", pass))
        assertTrue(store.verifyPassphrase(nextPass))
        assertEquals(setOf("b"), store.snapshot().unrecoveredWalletIds)
        rejects { store.replaceAfterAuthenticatedReset(before, listOf(replacement), nextPass) }
        rejects { store.rotate(nextPass, pass) }
        assertEquals(1L, store.get("a")!!.generation)
        assertEquals(0L, store.get("b")!!.generation)
    }
    @Test fun `activation and rotation snapshot caller buffers before dispatch`() = runTest {
        val queued = WalletVaultStore(db, crypto, StandardTestDispatcher(testScheduler))
        val suppliedPass = pass.copyOf()
        val suppliedEnvelope = envelope("a")
        val suppliedMetadata = metadata("a")
        val activation = async(start = CoroutineStart.UNDISPATCHED) {
            queued.activate(suppliedMetadata, null, suppliedEnvelope, suppliedPass)
        }
        suppliedPass.fill('x')
        suppliedEnvelope.ciphertext.fill(0)
        suppliedEnvelope.passphraseWrap.wrappedKey.fill(0)
        suppliedMetadata.iv.fill(0)
        activation.await()
        assertArrayEquals(material, crypto.decrypt(queued.get("a")!!, "a", pass))
        val old = pass.copyOf()
        val next = nextPass.copyOf()
        val rotation = async(start = CoroutineStart.UNDISPATCHED) { queued.rotate(old, next) }
        old.fill('x'); next.fill('y')
        rotation.await()
        assertTrue(queued.verifyPassphrase(nextPass))
        assertArrayEquals(material, crypto.decrypt(queued.get("a")!!, "a", nextPass))
    }
    @Test fun `reset rejects empty duplicate unknown and unverifiable replacements without changing profile`() = runBlocking {
        add("a")
        val before = store.snapshot()
        rejects { store.replaceAfterAuthenticatedReset(before, emptyList(), nextPass) }
        val replacement = crypto.create("a", 1, material, nextPass)
        rejects { store.replaceAfterAuthenticatedReset(before, listOf(replacement, replacement), nextPass) }
        rejects { store.replaceAfterAuthenticatedReset(before, listOf(crypto.create("unknown", 1, material, nextPass)), nextPass) }
        rejects { store.replaceAfterAuthenticatedReset(before, listOf(replacement), pass) }
        assertTrue(store.verifyPassphrase(pass))
        assertEquals(0L, store.get("a")!!.generation)
        // Snapshot getters are defensive, so this mutation cannot invalidate the retained CAS token.
        before.envelopes.single().ciphertext.fill(0)
        before.profile!!.ciphertext.fill(0)
        store.replaceAfterAuthenticatedReset(before, listOf(replacement), nextPass)
        assertTrue(store.verifyPassphrase(nextPass))
    }
    @Test fun `reserved profile identity in historical wallet metadata blocks profile enrollment`() = runBlocking {
        db.keyMaterialDao().upsert(metadata(WalletVaultStore.PROFILE_ID))
        rejects { add("a") }
        assertFalse(store.hasProfile())
        assertNull(store.get("a"))
    }

    @Test fun `wallet removal deletes only its secret rows and retains installation profile`() = runBlocking {
        val legacy = metadata("a")
        db.keyMaterialDao().upsert(legacy)
        store.activate(legacy, legacy.iv, envelope("a"), pass)
        add("b")
        store.removeWallet("a")
        assertNull(store.get("a"))
        assertNull(db.keyMaterialDao().getByWalletId("a"))
        assertNull(db.walletVaultDao().legacy("a"))
        assertArrayEquals(material, crypto.decrypt(store.get("b")!!, "b", pass))
        store.removeWallet("b")
        assertTrue(store.hasProfile()); assertTrue(store.verifyPassphrase(pass))
        add("c")
        assertEquals(0L, store.get("c")!!.generation)
    }

    @Test fun `recovering older profile wallet clears only its unresolved binding`() = runBlocking {
        add("a"); add("b"); add("c")
        store.replaceAfterAuthenticatedReset(store.snapshot(), listOf(crypto.create("a", 1, material, nextPass)), nextPass)
        assertEquals(setOf("b", "c"), store.snapshot().unrecoveredWalletIds)
        val bReplacement = crypto.create("b", 1, material, nextPass)
        rejects { store.replaceAuthenticatedWallet("b", 1, bReplacement, nextPass) }
        rejects { store.replaceAuthenticatedWallet("b", 0, bReplacement, pass) }
        store.replaceAuthenticatedWallet("b", 0, bReplacement, nextPass)
        assertEquals(setOf("c"), store.snapshot().unrecoveredWalletIds)
        assertArrayEquals(material, crypto.decrypt(store.get("b")!!, "b", nextPass))
        assertArrayEquals(material, crypto.decrypt(store.get("a")!!, "a", nextPass))
        assertArrayEquals(material, crypto.decrypt(store.get("c")!!, "c", pass))
    }

    @Test fun `activation preserves source flags changed since caller read the metadata`() = runBlocking {
        val stale = metadata("a").copy(mnemonicBackedUp = false, updatedAt = 1)
        val current = metadata("a")
        db.keyMaterialDao().upsert(current)
        store.activate(stale, stale.iv, envelope("a"), pass)
        val marker = db.keyMaterialDao().getByWalletId("a")!!
        assertTrue(marker.mnemonicBackedUp)
        assertEquals(123L, marker.updatedAt)
        assertEquals(current, db.walletVaultDao().legacy("a")!!.source())
    }

    @Test fun `cancellation while staging rotation leaves the entire prior generation usable`() = runBlocking {
        add("a"); add("b")
        val before = store.snapshot()
        var operation: Job? = null
        val cancellingRandom = object : java.security.SecureRandom() {
            override fun nextBytes(bytes: ByteArray) {
                super.nextBytes(bytes)
                operation?.cancel()
            }
        }
        val cancelling = WalletVaultStore(db, WalletEnvelopeCrypto(cancellingRandom))
        operation = launch(start = CoroutineStart.LAZY) { cancelling.rotate(pass, nextPass) }
        operation.start(); operation.join()
        assertTrue(operation.isCancelled)
        for (row in before.envelopes) assertArrayEquals(WalletEnvelopeCodec.encode(row), WalletEnvelopeCodec.encode(store.get(row.walletId)!!))
        assertTrue(store.verifyPassphrase(pass))
    }
    @Test fun `archive deletion failure leaves cleanup pending and source retained`() = runBlocking {
        val legacy = metadata("a")
        db.keyMaterialDao().upsert(legacy)
        store.activate(legacy, legacy.iv, envelope("a"), pass)
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_cleanup BEFORE DELETE ON wallet_vault_legacy BEGIN SELECT RAISE(ABORT, 'rejected'); END")
        rejects { store.markLegacyCleanupComplete("a", 0) }
        assertTrue(store.pendingLegacyCleanup().contains("a"))
        assertEquals(legacy, db.walletVaultDao().legacy("a")!!.source())
        assertArrayEquals(material, crypto.decrypt(store.get("a")!!, "a", pass))
    }

}
