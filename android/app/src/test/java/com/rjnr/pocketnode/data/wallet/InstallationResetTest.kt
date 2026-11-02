package com.rjnr.pocketnode.data.wallet

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.rjnr.pocketnode.core.log.NoopLogger
import com.rjnr.pocketnode.data.auth.AuthManager
import com.rjnr.pocketnode.data.auth.PinManager
import com.rjnr.pocketnode.data.crypto.*
import com.rjnr.pocketnode.data.database.AppDatabase
import com.rjnr.pocketnode.data.database.dao.WalletDao
import com.rjnr.pocketnode.data.database.entity.KeyMaterialEntity
import com.rjnr.pocketnode.data.gateway.LocalWalletReset
import com.rjnr.pocketnode.data.migration.WalletMigrationHelper
import com.rjnr.pocketnode.data.vault.WalletEnvelopeCrypto
import com.rjnr.pocketnode.data.vault.WalletVaultStore
import io.mockk.mockk
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class InstallationResetTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private class Installation(val context: Context) : AutoCloseable {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        val boundary = WalletStorageBoundary(context)
        val mnemonic = MnemonicManager()
        val preferences = WalletPreferences(context, NoopLogger)
        val legacyPrefs = context.getSharedPreferences("ckb_wallet_keys", 0)
        val legacy = KeyStoreMigrationHelper(db.keyMaterialDao(), KeystoreEncryptionManager.createForTest(),
            context.getSharedPreferences("key_migration", 0), NoopLogger, database = db)
        val backup = KeyBackupManager(File(context.filesDir, "key_backups"), NoopLogger, boundary).apply {
            argon2Iterations = 1; argon2MemoryKb = 8; argon2Parallelism = 1
        }
        val auth = AuthManager(context)
        val keys = KeyManager(context, mnemonic, NoopLogger).also {
            it.testPrefs = legacyPrefs; it.keyStoreMigrationHelper = legacy; it.keyBackupManager = backup; it.authManager = auth
        }
        val repo = WalletRepository(db.walletDao(), keys, preferences, preferences, mnemonic, db,
            db.transactionDao(), db.balanceCacheDao(), db.daoCellDao(), db.pendingDaoWithdrawDao(),
            db.pendingDaoUnlockDao(), db.keyMaterialDao(), db.subAccountCandidateDao(),
            SubAccountDiscovery(mnemonic, keys), preferences, NoopLogger)
        val pin = PinManager(context, com.rjnr.pocketnode.data.crypto.Blake2b(), NoopLogger).apply {
            testPrefs = context.getSharedPreferences("reset_test_pin", 0)
            argon2Iterations = 1; argon2MemoryKb = 8; argon2Parallelism = 1
        }
        val reset = LocalWalletReset(repo, keys, db.keyMaterialDao(), backup, mockk(relaxed = true),
            mockk(relaxed = true), pin, RecoveryStateStore(context))
        fun migrator(dao: WalletDao = db.walletDao()) = WalletMigrationHelper(dao, keys, preferences, db,
            db.syncProgressDao(), legacy, NoopLogger)
        override fun close() { db.close() }
    }

    // A new application identity over the same persisted files models a fresh process without
    // exposing production test-only reset hooks. Room fixtures are empty after verified deletion.
    private fun restartedContext() = object : ContextWrapper(context) {
        override fun getApplicationContext(): Context = this
    }

    @Test fun `reset clears queued preference writes and sessions and fresh migration stays empty`() = runBlocking {
        Installation(context).use { app ->
            app.legacyPrefs.edit().putString("private_key", "00".repeat(31) + "02").apply()
            app.keys.testPrefs = null // exercise production raw-cache invalidation, not the existing test seam
            app.keys.setSessionPin("123456".toCharArray()); app.auth.setSessionPin("123456".toCharArray())
            app.pin.setPin("123456")
            var now = 1_000_000L
            app.pin.timeProvider = { now }
            repeat(10) {
                now += app.pin.getLockoutRemainingMs() + 1
                assertFalse(app.pin.verifyPin("000000"))
            }
            assertTrue(app.pin.isPermanentlyLocked())
            app.reset.removeAllWalletsAndPin()
            assertEquals(5, app.pin.getRemainingAttempts())
            assertFalse(app.pin.isLockedOut()); assertFalse(app.pin.isPermanentlyLocked())
            assertFalse(app.pin.testPrefs!!.contains("failed_attempts"))
            assertFalse(app.pin.testPrefs!!.contains("lockout_until"))
            assertFalse(app.legacyPrefs.contains("private_key"))
            assertFalse(File(context.applicationInfo.dataDir, "shared_prefs/ckb_wallet_keys.xml").exists())
            assertNull(app.keys.sessionPin); assertFalse(app.auth.hasSessionPin())
            assertFalse(app.pin.hasPin())
            assertTrue(app.boundary.isClosed())
            assertEquals(LocalWalletReset.Startup.RESTART, app.reset.prepareStartup())
        }
        Installation(restartedContext()).use { fresh ->
            assertEquals(LocalWalletReset.Startup.READY, fresh.reset.prepareStartup())
            assertFalse(fresh.pin.hasPin()); assertFalse(fresh.pin.isPermanentlyLocked())
            assertEquals(5, fresh.pin.getRemainingAttempts())
            fresh.migrator().migrateIfNeeded()
            fresh.keys.migrateEspToRoomIfNeeded(fresh.db)
            assertFalse(fresh.keys.hasWallet()); assertEquals(0, fresh.db.walletDao().count())
            assertEquals(0, fresh.db.keyMaterialDao().count())
        }
    }

    @Test fun `failed filesystem deletion retains PIN and durable retry suppresses migration`() = runBlocking {
        val stuck = File(context.filesDir, "key_backups/orphan.enc.tmp")
        Installation(context).use { app ->
            stuck.mkdirs(); File(stuck, "child").writeText("disposable")
            app.legacyPrefs.edit().putString("private_key", "00".repeat(31) + "02").commit()
            app.pin.setPin("123456")
            app.pin.timeProvider = { 1_000_000L }
            repeat(5) { app.pin.verifyPin("000000") }
            assertTrue(app.pin.isLockedOut())
            assertTrue(runCatching { app.reset.removeAllWalletsAndPin() }.isFailure)
            assertTrue(app.pin.hasPin()); assertTrue(app.pin.isLockedOut())
            assertEquals(30_000L, app.pin.getLockoutRemainingMs())
            assertEquals(WalletStorageBoundary.Status.PENDING, app.boundary.status())
        }
        Installation(restartedContext()).use { fresh ->
            fresh.pin.timeProvider = { 1_000_000L }
            fresh.preferences.setLastSeenVersionCode(19)
            com.rjnr.pocketnode.recordStartupVersion(fresh.preferences, 20)
            assertTrue(fresh.pin.isLockedOut())
            assertEquals(30_000L, fresh.pin.getLockoutRemainingMs())
            assertEquals(0, fresh.pin.getRemainingAttempts())
            // Reintroduce a fixture source to prove that the pending marker, not just deletion,
            // suppresses reads and migration after process death.
            fresh.legacyPrefs.edit().putString("private_key", "00".repeat(31) + "02").commit()
            fresh.migrator().migrateIfNeeded()
            assertFalse(fresh.keys.hasWallet()); assertEquals(0, fresh.db.walletDao().count())
            assertEquals(LocalWalletReset.Startup.RETRY, fresh.reset.prepareStartup())
            assertTrue(fresh.pin.hasPin())
            stuck.deleteRecursively()
            assertEquals(LocalWalletReset.Startup.RESTART, fresh.reset.prepareStartup())
            assertFalse(fresh.pin.hasPin()); assertFalse(fresh.legacyPrefs.contains("private_key"))
        }
    }

    @Test fun `corrupt marker requires explicit retry without reading or deleting source`() = runBlocking {
        val marker = File(context.noBackupFilesDir, "wallet_reset")
        Installation(context).use { it.pin.setPin("123456") }
        marker.writeText("invalid")
        Installation(restartedContext()).use { app ->
            app.legacyPrefs.edit().putString("private_key", "disposable").commit()
            assertEquals(LocalWalletReset.Startup.RETRY, app.reset.prepareStartup())
            app.migrator().migrateIfNeeded()
            assertTrue(app.legacyPrefs.contains("private_key")); assertTrue(app.pin.hasPin())
            app.reset.removeAllWalletsAndPin()
            assertFalse(app.legacyPrefs.contains("private_key")); assertFalse(app.pin.hasPin())
        }
    }

    @Test fun `late wallet activation metadata writes and legacy reads are fenced after reset`() = runBlocking {
        Installation(context).use { app ->
            val crypto = WalletEnvelopeCrypto()
            val store = WalletVaultStore(app.db, crypto)
            val creation = WalletRepository.Creation(com.rjnr.pocketnode.data.database.entity.WalletEntity("late", "Late", "raw_key", null, null))
            val pass = "disposable wallet passphrase".toCharArray()
            val envelope = crypto.create("late", 0, byteArrayOf(1, 2), pass)
            app.reset.removeAllWalletsAndPin()
            assertTrue(runCatching {
                store.activate(KeyMaterialEntity("late", byteArrayOf(), null, byteArrayOf(), "raw_key", false, 0),
                    null, envelope, pass, creation)
            }.isFailure)
            assertTrue(runCatching { app.backup.readBackup("late", "123456".toCharArray()) }.isFailure)
            assertTrue(runCatching { app.keys.setMnemonicBackedUpForWallet("late", true) }.isFailure)
            assertEquals(0, app.db.walletDao().count()); assertEquals(0, app.db.keyMaterialDao().count())
            assertFalse(app.backup.hasAnyBackups())
        }
    }

    @Test fun `reset waits for migration transaction and removes its committed keys`() = runBlocking {
        Installation(context).use { app ->
            app.legacyPrefs.edit().putString("private_key", "00".repeat(31) + "02").commit()
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val paused = object : WalletDao by app.db.walletDao() {
                override suspend fun count(): Int { entered.complete(Unit); release.await(); return app.db.walletDao().count() }
            }
            val migration = async(Dispatchers.IO) { app.migrator(paused).migrateIfNeeded() }
            withTimeout(5000) { entered.await() }
            val resetStarted = CompletableDeferred<Unit>()
            val reset = async(Dispatchers.IO) { resetStarted.complete(Unit); app.repo.factoryReset() }
            resetStarted.await()
            assertFalse(reset.isCompleted)
            assertEquals(WalletStorageBoundary.Status.NONE, app.boundary.status())
            release.complete(Unit)
            withTimeout(5000) { migration.await(); reset.await() }
            app.migrator().migrateIfNeeded()
            assertEquals(0, app.db.walletDao().count()); assertEquals(0, app.db.keyMaterialDao().count())
            assertFalse(app.legacyPrefs.contains("private_key"))
        }
    }

    @Test fun `legacy helper cannot persist material after reset`() = runBlocking {
        Installation(context).use { app ->
            app.repo.factoryReset()
            assertTrue(runCatching {
                app.legacy.migrateWallet("late", "00".repeat(31) + "02", null, "raw_key", false)
            }.isFailure)
            assertEquals(0, app.db.keyMaterialDao().count())
        }
    }

    @Test fun `V2 compatibility writer cannot persist material after reset`() = runBlocking {
        Installation(context).use { app ->
            val encryption = KeystoreEncryptionManager.createForTest()
            val writer = KeystoreV2MigrationHelper(app.db.keyMaterialDao(), encryption,
                context.getSharedPreferences("compatibility_writer", 0), logger = NoopLogger, database = app.db)
            val cipher = encryption.newEncryptCipherV2()
            app.repo.factoryReset()
            assertTrue(writer.writeNewV2Row("late", WalletKeyBundle("00".repeat(31) + "02", null),
                cipher, "raw_key", false).isFailure)
            assertEquals(0, app.db.keyMaterialDao().count())
        }
    }

    @Test fun `reset discovers orphan Room keys vaults archives and every file copy`() = runBlocking {
        Installation(context).use { app ->
            val files = listOf("ckb_wallet_keys.xml", "ckb_wallet_keys.xml.bak", "ckb_wallet_keys_default.xml",
                "ckb_wallet_keys_default.xml.bak", "ckb_wallet_keys_orphan.xml", "ckb_wallet_keys_orphan.xml.bak")
                .map { File(context.applicationInfo.dataDir, "shared_prefs/$it") } +
                listOf("orphan.enc", "orphan.enc.tmp").map { File(context.filesDir, "key_backups/$it") }
            files.forEach { it.parentFile!!.mkdirs(); it.writeText("unreadable disposable ciphertext") }
            app.keys.testPrefs = null
            app.db.keyMaterialDao().upsert(KeyMaterialEntity("orphan", byteArrayOf(1), null, byteArrayOf(2), "raw_key", false, 0))
            app.db.walletVaultDao().insert(com.rjnr.pocketnode.data.database.entity.WalletVaultEntity("orphan", 0, 0, byteArrayOf(1), true))
            app.db.walletVaultDao().archive(com.rjnr.pocketnode.data.database.entity.WalletVaultLegacyEntity("orphan", byteArrayOf(1), null, byteArrayOf(2), "raw_key", false, 0, 1))
            app.db.walletVaultDao().insertProfile(com.rjnr.pocketnode.data.database.entity.WalletPassphraseProfileEntity(envelope = byteArrayOf(1)))
            assertEquals(0, app.db.walletDao().count())
            app.repo.factoryReset()
            files.forEach { assertFalse(it.exists()) }
            assertEquals(0, app.db.keyMaterialDao().count()); assertTrue(app.db.walletVaultDao().all().isEmpty())
            assertNull(app.db.walletVaultDao().legacy("orphan")); assertNull(app.db.walletVaultDao().profile())
        }
    }

    @Test fun `failure to persist reset intent leaves wallet source and PIN intact`() = runBlocking {
        val marker = File(context.noBackupFilesDir, "wallet_reset")
        Installation(context).use { it.pin.setPin("123456") }
        marker.mkdirs(); File(marker, "child").writeText("disposable")
        Installation(restartedContext()).use { app ->
            app.legacyPrefs.edit().putString("private_key", "disposable").commit()
            assertTrue(runCatching { app.reset.removeAllWalletsAndPin() }.isFailure)
            assertTrue(app.legacyPrefs.contains("private_key")); assertTrue(app.pin.hasPin())
            assertTrue(app.boundary.isClosed())
        }
    }

    @Test fun `completed marker cannot reopen storage when verification finds a remaining copy`() = runBlocking {
        Installation(context).use { it.reset.removeAllWalletsAndPin() }
        File(context.filesDir, "key_backups/orphan.enc.tmp").writeText("disposable")
        Installation(restartedContext()).use { app ->
            assertEquals(LocalWalletReset.Startup.RETRY, app.reset.prepareStartup())
            assertTrue(app.boundary.isClosed())
        }
    }

    @Test fun `late authentication cannot restore session PIN after reset`() = runBlocking {
        Installation(context).use { app ->
            app.repo.factoryReset()
            runCatching { app.auth.setSessionPin("123456".toCharArray()) }
            runCatching { app.keys.setSessionPin("123456".toCharArray()) }
            assertFalse(app.auth.hasSessionPin()); assertNull(app.keys.sessionPin)
        }
    }

    @Test fun `ordinary deletion targets default or scoped namespace without deleting unrelated material`() = runBlocking {
        Installation(context).use { app ->
            val scoped = context.getSharedPreferences("ckb_wallet_keys_other", 0)
            app.legacyPrefs.edit().putString("private_key", "default fixture").commit()
            scoped.edit().putString("private_key", "other fixture").commit()
            app.keys.deleteWalletKeys("other")
            assertTrue(app.legacyPrefs.contains("private_key")); assertFalse(scoped.contains("private_key"))
            scoped.edit().putString("private_key", "other fixture").commit()
            app.keys.deleteWalletKeys("default")
            assertFalse(app.legacyPrefs.contains("private_key")); assertTrue(scoped.contains("private_key"))
        }
    }
}
