package com.rjnr.pocketnode.data.wallet

import android.content.Context
import androidx.fragment.app.FragmentActivity
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.rjnr.pocketnode.core.log.NoopLogger
import com.rjnr.pocketnode.data.auth.AuthManager
import com.rjnr.pocketnode.data.auth.PinManager
import com.rjnr.pocketnode.data.crypto.*
import com.rjnr.pocketnode.data.database.AppDatabase
import com.rjnr.pocketnode.data.database.entity.WalletEntity
import com.rjnr.pocketnode.data.vault.*
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.coroutines.ContinuationInterceptor
import org.robolectric.Robolectric

/** Real Room, production-cost crypto and coordinator; native UI/hardware are doubles. */
class WalletVaultIntegrationFixture(existingDatabase: AppDatabase? = null, encryptionOverride: KeystoreEncryptionManager? = null) : AutoCloseable {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val db = existingDatabase ?: Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    val activityController = Robolectric.buildActivity(FragmentActivity::class.java).setup()
    val activity = activityController.get()
    val crypto = WalletEnvelopeCrypto()
    val store = WalletVaultStore(db, crypto)
    val encryption = encryptionOverride ?: KeystoreEncryptionManager.createForTest()
    val prefs = context.getSharedPreferences("integration_legacy", Context.MODE_PRIVATE).also { it.edit().clear().commit() }
    val legacy = KeyStoreMigrationHelper(db.keyMaterialDao(), encryption, prefs, NoopLogger)
    val v2 = KeystoreV2MigrationHelper(db.keyMaterialDao(), encryption, prefs, logger = NoopLogger)
    val backup = KeyBackupManager(java.io.File(context.cacheDir, "integration-backups"), NoopLogger)
    val auth = mockk<AuthManager>(relaxed = true)
    val pin = mockk<PinManager>()
    val recovery = RecoveryStateStore(context)
    val preference = WalletUnlockPreference(context)
    val pass = "public disposable wallet passphrase"
    val credentials = ArrayDeque<CharArray?>()
    val choices = ArrayDeque<Boolean?>()
    var cleanupOk = true
    var onChoice: (suspend () -> Unit)? = null
    var onCredential: (suspend () -> Unit)? = null
    val prompt = object : VaultPrompt {
        override suspend fun request(activity: FragmentActivity, title: String, message: String, confirm: Boolean) = run { onCredential?.invoke(); credentials.removeFirstOrNull() }
        override suspend fun requestLegacyPin(activity: FragmentActivity, title: String, message: String) = run { onCredential?.invoke(); credentials.removeFirstOrNull() }
        override suspend fun choose(activity: FragmentActivity, title: String, message: String, positiveLabel: String, negativeLabel: String): Boolean? { onChoice?.invoke(); return if (choices.isEmpty()) false else choices.removeFirst() }
        override suspend fun notify(activity: FragmentActivity, title: String, message: String) {}
    }
    val device = object : VaultDeviceAuthentication {
        override fun unavailableReason() = "no-device-lock"
        override suspend fun wrap(activity: FragmentActivity, envelope: WalletEnvelope, dataKey: ByteArray, mode: VaultDevicePromptMode): DeviceVaultResult<DeviceKeyWrap> = DeviceVaultResult.Unavailable("no-device-lock")
        override suspend fun unwrap(activity: FragmentActivity, envelope: WalletEnvelope, title: String, subtitle: String, mode: VaultDevicePromptMode): DeviceVaultResult<ByteArray> = DeviceVaultResult.Unavailable("missing-key")
    }
    val keyManager = KeyManager(context, MnemonicManager(), NoopLogger).also {
        it.keyStoreMigrationHelper = legacy
        it.testPrefs = context.getSharedPreferences("ckb_wallet_keys", 0)
    }
    val coordinator = WalletVaultCoordinator(db, store, crypto, WalletSecretValidator(MnemonicManager()), prompt, device, object : VaultLegacyCleanup {
        override suspend fun clean(walletId: String, expectedGeneration: Long, verifiedMaterial: KeyMaterial) =
            cleanupOk && WalletLegacyCleanup(context, backup, db, keyManager, legacy).clean(walletId, expectedGeneration, verifiedMaterial)
        override suspend fun cleanWithSource(walletId: String, expectedGeneration: Long, verifiedMaterial: KeyMaterial, proof: BoundLegacySourceProof) =
            cleanupOk && WalletLegacyCleanup(context, backup, db, keyManager, legacy).cleanWithSource(walletId, expectedGeneration, verifiedMaterial, proof)
        override suspend fun cleanWithProof(walletId: String, expectedGeneration: Long, verifiedMaterial: KeyMaterial, proof: BoundLegacyBackupProof) =
            cleanupOk && WalletLegacyCleanup(context, backup, db, keyManager, legacy).cleanWithProof(walletId, expectedGeneration, verifiedMaterial, proof)
    })
    val reader = WalletKeyReader(db.keyMaterialDao(), legacy, encryption, auth, recovery, coordinator, pin, prompt, preference)
    val writer = WalletKeyWriter(coordinator)
    val material = KeyMaterial("00".repeat(31) + "02", walletType = "raw_key", mnemonicBackedUp = false)
    val bundle = WalletKeyBundle(privateKeyHex = material.privateKey, mnemonic = null)
    init {
        every { pin.hasPin() } returns false
        every { auth.isBiometricEnrolled() } returns false
        every { auth.hasDeviceCredential() } returns false
        preference.set(VaultReadPreference.DEFAULT)
    }
    fun phrase(value: String? = pass) { credentials += value?.toCharArray() }
    fun creation(id: String = "a"): WalletRepository.Creation {
        val key = ByteArray(32).also { it[31] = 2 }
        val info = try { WalletDerivation.walletInfo(key) } finally { key.fill(0) }
        return WalletRepository.Creation(WalletEntity(id, "Wallet $id", "raw_key", null, null,
            mainnetAddress = info.mainnetAddress, testnetAddress = info.testnetAddress))
    }
    suspend fun wallet(id: String = "a", type: String = "raw_key", path: String? = null, parent: String? = null): WalletEntity {
        val key = ByteArray(32).also { it[31] = 2 }
        val info = try { WalletDerivation.walletInfo(key) } finally { key.fill(0) }
        return WalletEntity(id, "Wallet $id", type, path, parent, mainnetAddress = info.mainnetAddress, testnetAddress = info.testnetAddress).also { db.walletDao().insert(it) }
    }
    override fun close() { credentials.forEach { it?.fill('\u0000') }; db.close() }
}

@OptIn(ExperimentalCoroutinesApi::class)
fun walletOperationTest(block: suspend CoroutineScope.() -> Unit) = runBlocking {
    Dispatchers.setMain(coroutineContext[ContinuationInterceptor] as CoroutineDispatcher)
    try { block() } finally { Dispatchers.resetMain() }
}
