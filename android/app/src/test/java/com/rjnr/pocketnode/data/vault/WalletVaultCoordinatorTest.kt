package com.rjnr.pocketnode.data.vault

import android.content.Context
import androidx.fragment.app.FragmentActivity
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.rjnr.pocketnode.data.crypto.KeyMaterial
import com.rjnr.pocketnode.data.database.AppDatabase
import com.rjnr.pocketnode.data.database.entity.WalletEntity
import com.rjnr.pocketnode.data.database.entity.KeyMaterialEntity
import com.rjnr.pocketnode.data.wallet.MnemonicManager
import com.rjnr.pocketnode.data.wallet.WalletDerivation
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.coroutines.ContinuationInterceptor
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE)
class WalletVaultCoordinatorTest {
    private lateinit var db: AppDatabase
    private lateinit var store: WalletVaultStore
    private lateinit var coordinator: WalletVaultCoordinator
    private val crypto = WalletEnvelopeCrypto()
    private val validator = WalletSecretValidator(MnemonicManager())
    private val activityController = Robolectric.buildActivity(FragmentActivity::class.java).setup()
    private val activity = activityController.get()
    private val pass = "  exact long wallet passphrase  "
    private val next = "a different long wallet phrase"
    private val key = ByteArray(32).also { it[31] = 1 }
    private val material = KeyMaterial("00".repeat(31) + "01", walletType = "raw_key", mnemonicBackedUp = false)
    private val credentials = ArrayDeque<CharArray?>()
    private val issued = mutableListOf<CharArray>()
    private var cleanupOk = true
    private var cleanupThrows = false
    private val deviceKeys = mutableMapOf<String, ByteArray>()
    private var unwrapResult: DeviceVaultResult<ByteArray>? = null
    private var wrapCount = 0
    private var chooseWrap = false
    private var wrapCancelled = false
    private var onWrap: suspend () -> Unit = {}
    private var onUnwrap: suspend () -> Unit = {}
    private var activeWrapKey: ByteArray? = null
    private var deviceModes = setOf(VaultDevicePromptMode.BIOMETRIC_ONLY)
    private var lastDeviceMode: VaultDevicePromptMode? = null
    private val prompt = object : VaultPrompt {
        override suspend fun request(activity: FragmentActivity, title: String, message: String, confirm: Boolean): CharArray? =
            credentials.removeFirstOrNull()?.also { issued += it }
        override suspend fun choose(activity: FragmentActivity, title: String, message: String, positiveLabel: String, negativeLabel: String): Boolean? = if (positiveLabel == "Continue") true else chooseWrap
        override suspend fun notify(activity: FragmentActivity, title: String, message: String) {}
        override suspend fun requestLegacyPin(activity: FragmentActivity, title: String, message: String): CharArray? = error("App PIN must never be used")
    }
    private val device = object : VaultDeviceAuthentication {
        override fun unavailableReason(): String? = if (chooseWrap) null else "no-device-lock"
        override fun availablePromptModes() = deviceModes
        override suspend fun wrap(activity: FragmentActivity, envelope: WalletEnvelope, dataKey: ByteArray, mode: VaultDevicePromptMode): DeviceVaultResult<DeviceKeyWrap> {
            wrapCount++
            activeWrapKey = dataKey
            lastDeviceMode = mode
            assertTrue(issued.all { chars -> chars.all { it == '\u0000' } })
            onWrap()
            if (wrapCancelled) return DeviceVaultResult.Cancelled
            deviceKeys[envelope.walletId] = dataKey.copyOf()
            return DeviceVaultResult.Success(DeviceKeyWrap("test-" + envelope.walletId, ByteArray(48), ByteArray(12)))
        }
        override suspend fun unwrap(activity: FragmentActivity, envelope: WalletEnvelope, title: String, subtitle: String, mode: VaultDevicePromptMode): DeviceVaultResult<ByteArray> {
            lastDeviceMode = mode
            onUnwrap()
            return unwrapResult ?: deviceKeys[envelope.walletId]?.let { DeviceVaultResult.Success(it.copyOf()) } ?: DeviceVaultResult.Unavailable("missing-key")
        }
    }
    private fun operationTest(block: suspend CoroutineScope.() -> Unit) = runBlocking {
        Dispatchers.setMain(coroutineContext[ContinuationInterceptor] as CoroutineDispatcher)
        try { block() } finally { Dispatchers.resetMain() }
    }
    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java).allowMainThreadQueries().build()
        store = WalletVaultStore(db, crypto)
        coordinator = WalletVaultCoordinator(db, store, crypto, validator, prompt, device, VaultLegacyCleanup { _, _, _ -> if (cleanupThrows) error("simulated cleanup IO failure"); cleanupOk })
    }
    @After fun close() { db.close(); issued.forEach { it.fill('\u0000') }; deviceKeys.values.forEach { it.fill(0) } }
    private fun phrase(value: String? = pass) { credentials += value?.toCharArray() }
    private suspend fun wallet(id: String = "a"): WalletEntity {
        val info = WalletDerivation.walletInfo(key)
        return WalletEntity(id, "Wallet", "raw_key", null, null, mainnetAddress = info.mainnetAddress, testnetAddress = info.testnetAddress).also { db.walletDao().insert(it) }
    }
    private suspend fun active(id: String = "a", deviceWrap: Boolean = false) {
        wallet(id)
        val bytes = Json.encodeToString(material).toByteArray()
        val envelope = crypto.create(id, 0, bytes, pass.toCharArray())
        store.activate(KeyMaterialEntity(id, byteArrayOf(), null, byteArrayOf(), "raw_key", false, 0), null, envelope, pass.toCharArray())
        store.markLegacyCleanupComplete(id, 0)
        if (deviceWrap) {
            deviceKeys[id] = crypto.unwrapDataKey(envelope, id, pass.toCharArray())
            store.updateDeviceWrap(id, 0, DeviceKeyWrap("test-$id", ByteArray(48), ByteArray(12)))
        }
        bytes.fill(0)
    }
    @Test fun `no lock creation persists before metadata and verifies passphrase only reads`() = operationTest {
        phrase()
        assertTrue(coordinator.persistNew(activity, "a", material) is VaultAccessResult.Success)
        assertNotNull(store.get("a")); assertNull(db.walletDao().getById("a"))
        assertEquals(0, wrapCount)
        wallet(); phrase()
        val result = coordinator.read(activity, "a", "Read", "Authenticate") as VaultAccessResult.Success
        assertEquals(material.privateKey, result.material.privateKey)
        assertTrue(issued.all { c -> c.all { it == '\u0000' } })
    }
    @Test fun `missing Android key falls back to phrase without making a key`() = operationTest {
        active(deviceWrap = true); deviceKeys.clear(); phrase()
        assertTrue(coordinator.read(activity, "a", "Read", "Auth") is VaultAccessResult.Success)
        assertEquals(0, wrapCount)
    }
    @Test fun `failed cryptographic device auth cannot release material`() = operationTest {
        active(deviceWrap = true); unwrapResult = DeviceVaultResult.Unavailable("authentication-required"); phrase("wrong long passphrase")
        assertTrue(coordinator.read(activity, "a", "Read", "Auth") is VaultAccessResult.Unavailable)
    }
    @Test fun `cancelled passphrase setup does not persist`() = operationTest {
        phrase(null)
        assertEquals(VaultAccessResult.Cancelled, coordinator.persistNew(activity, "a", material))
        assertFalse(store.hasProfile()); assertNull(store.get("a"))
    }
    @Test fun `short app PIN cannot enroll vault`() = operationTest {
        phrase("123456")
        assertTrue(coordinator.persistNew(activity, "a", material) is VaultAccessResult.Unavailable)
        assertFalse(store.hasProfile())
    }
    @Test fun `existing installation rejects another passphrase without mutation`() = operationTest {
        active(); phrase(next)
        assertTrue(coordinator.persistNew(activity, "b", material) is VaultAccessResult.Unavailable)
        assertNull(store.get("b")); assertTrue(store.verifyPassphrase(pass.toCharArray()))
    }
    @Test fun `wrong metadata or missing metadata never releases decrypted key`() = operationTest {
        active(); val row = db.walletDao().getById("a")!!
        db.walletDao().update(row.copy(testnetAddress = "wrong")); phrase()
        assertTrue(coordinator.read(activity, "a", "Read", "Auth") is VaultAccessResult.Unavailable)
        db.walletDao().delete("a"); phrase()
        assertTrue(coordinator.read(activity, "a", "Read", "Auth") is VaultAccessResult.Unavailable)
    }
    @Test fun `cleanup failure preserves archived source and hides material until retry`() = operationTest {
        wallet(); val source = KeyMaterialEntity("a", byteArrayOf(8), null, byteArrayOf(7), "raw_key", true, 1, 2)
        db.keyMaterialDao().upsert(source); cleanupOk = false; phrase()
        assertEquals(VaultAccessResult.CleanupRequired, coordinator.protectLegacy(activity, "a", material, source.iv))
        assertEquals(source, db.walletVaultDao().legacy("a")!!.source())
        cleanupOk = true; phrase()
        assertTrue(coordinator.read(activity, "a", "Read", "Auth") is VaultAccessResult.Success)
        assertNull(db.walletVaultDao().legacy("a")); assertTrue(store.pendingLegacyCleanup().isEmpty())
    }
    @Test fun `legacy migration refuses a changed source before starting credential setup`() = operationTest {
        wallet()
        val authenticated = KeyMaterialEntity("a", byteArrayOf(8), null, byteArrayOf(7), "raw_key", true, 1, 2)
        val replacement = authenticated.copy(iv = byteArrayOf(9), encryptedPrivateKey = byteArrayOf(10))
        db.keyMaterialDao().upsert(replacement); phrase()
        assertTrue(coordinator.protectLegacy(activity, "a", material, authenticated.iv) is VaultAccessResult.Unavailable)
        assertEquals(replacement, db.keyMaterialDao().getByWalletId("a"))
        assertNull(store.get("a"))
    }
    @Test fun `cleanup IO failure is retryable without exposing material`() = operationTest {
        wallet(); phrase(); cleanupThrows = true
        assertEquals(VaultAccessResult.CleanupRequired, coordinator.recoverExisting(activity, "a", material))
        assertTrue(store.pendingLegacyCleanup().contains("a")); assertNotNull(store.get("a"))
        cleanupThrows = false; phrase()
        assertTrue(coordinator.read(activity, "a", "Read", "Auth") is VaultAccessResult.Success)
    }
    @Test fun `optional device wrap follows independent setup and decrypted material matches`() = operationTest {
        chooseWrap = true; phrase()
        assertTrue(coordinator.persistNew(activity, "a", material) is VaultAccessResult.Success)
        assertTrue(store.verifyPassphrase(pass.toCharArray())); assertNotNull(store.get("a")!!.deviceWrap)
        wallet()
        val result = coordinator.read(activity, "a", "Read", "Auth") as VaultAccessResult.Success
        assertEquals(material.privateKey, result.material.privateKey)
    }
    @Test fun `recovery requires an existing wallet`() = operationTest {
        phrase()
        assertTrue(coordinator.recoverExisting(activity, "a", material) is VaultAccessResult.Unavailable)
        assertNull(store.get("a"))
    }
    @Test fun `known passphrase rotation retains all wallets and clears device wraps`() = operationTest {
        active("a", true); active("b"); phrase(); phrase(next)
        val result = coordinator.changePassphrase(activity) as VaultMaintenanceResult.Completed
        assertEquals(setOf("a", "b"), result.completedWalletIds)
        assertTrue(store.verifyPassphrase(next.toCharArray())); assertFalse(store.verifyPassphrase(pass.toCharArray()))
        assertNull(store.get("a")!!.deviceWrap)
    }
    @Test fun `forgotten reset authenticates each wallet and preserves inaccessible bytes`() = operationTest {
        active("a", true); active("b"); val old = WalletEnvelopeCodec.encode(store.get("b")!!); phrase(next)
        val result = coordinator.resetPassphrase(activity) as VaultMaintenanceResult.Completed
        assertEquals(setOf("a"), result.completedWalletIds); assertEquals(setOf("b"), result.unrecoveredWalletIds)
        assertArrayEquals(old, WalletEnvelopeCodec.encode(store.get("b")!!))
        assertTrue(store.verifyPassphrase(next.toCharArray()))
        phrase(pass); phrase(next)
        assertTrue(coordinator.read(activity, "b", "Read", "Auth") is VaultAccessResult.Success)
        assertTrue(store.snapshot().unrecoveredWalletIds.isEmpty())
    }
    @Test fun `mnemonic accepts default derivation but rejects substituted explicit path`() = operationTest {
        val words = ("abandon ".repeat(11) + "about").split(" ")
        val manager = MnemonicManager()
        val seed = manager.mnemonicToSeed(words)
        val derived = try { manager.derivePrivateKey(seed) } finally { seed.fill(0) }
        try {
            val info = WalletDerivation.walletInfo(derived)
            val bundle = material.copy(privateKey = derived.joinToString("") { "%02x".format(it) },
                walletType = "mnemonic", mnemonic = words.joinToString(" "))
            val row = WalletEntity("m", "Mnemonic", "mnemonic", null, null,
                mainnetAddress = info.mainnetAddress, testnetAddress = info.testnetAddress)
            assertTrue(validator.validate(bundle, null))
            assertTrue(validator.validate(bundle, row))
            assertTrue(validator.validate(bundle, row.copy(derivationPath = "m/44'/309'/0'/0/0")))
            assertFalse(validator.validate(bundle, row.copy(derivationPath = "m/44'/309'/0'/1/0")))
            assertFalse(validator.validate(bundle, row.copy(derivationPath = "m/44'/309'/2147483648'/0/0")))
            assertFalse(validator.validate(bundle, row.copy(accountIndex = 1)))
        } finally { derived.fill(0) }
    }
    @Test fun `explicit device reenrollment authenticates current phrase and replaces only convenience wrap`() = operationTest {
        active(deviceWrap = true); deviceKeys.clear(); chooseWrap = true; phrase()
        val prior = store.get("a")!!
        assertTrue(coordinator.enrollDevice(activity, "a") is VaultAccessResult.Success)
        val current = store.get("a")!!
        assertEquals(prior.generation, current.generation)
        assertArrayEquals(prior.ciphertext, current.ciphertext); assertArrayEquals(prior.nonce, current.nonce)
        assertTrue(store.verifyPassphrase(pass.toCharArray()))
        assertTrue(coordinator.read(activity, "a", "Read", "Auth") is VaultAccessResult.Success)
    }
    @Test fun `reenrollment wrong phrase or crypto cancellation preserves existing envelope`() = operationTest {
        active(deviceWrap = true); chooseWrap = true
        val prior = WalletEnvelopeCodec.encode(store.get("a")!!)
        phrase("incorrect long wallet phrase")
        assertTrue(coordinator.enrollDevice(activity, "a") is VaultAccessResult.Unavailable)
        assertArrayEquals(prior, WalletEnvelopeCodec.encode(store.get("a")!!))
        phrase(); wrapCancelled = true
        assertEquals(VaultAccessResult.Cancelled, coordinator.enrollDevice(activity, "a"))
        assertArrayEquals(prior, WalletEnvelopeCodec.encode(store.get("a")!!))
    }
    @Test fun `reenrollment generation CAS cannot overwrite concurrent rotation`() = operationTest {
        active(); chooseWrap = true; phrase()
        onWrap = { store.rotate(pass.toCharArray(), next.toCharArray()) }
        assertTrue(coordinator.enrollDevice(activity, "a") is VaultAccessResult.Unavailable)
        val current = store.get("a")!!
        assertEquals(1L, current.generation); assertNull(current.deviceWrap)
        assertTrue(store.verifyPassphrase(next.toCharArray()))
    }
    @Test fun `reenrollment refuses unchecked cleanup or mismatched wallet metadata`() = operationTest {
        wallet(); phrase(); cleanupOk = false
        assertEquals(VaultAccessResult.CleanupRequired, coordinator.recoverExisting(activity, "a", material))
        chooseWrap = true; phrase()
        assertEquals(VaultAccessResult.CleanupRequired, coordinator.enrollDevice(activity, "a"))
        assertNull(store.get("a")!!.deviceWrap)
        cleanupOk = true
        val row = db.walletDao().getById("a")!!
        db.walletDao().update(row.copy(mainnetAddress = "wrong")); phrase()
        assertTrue(coordinator.enrollDevice(activity, "a") is VaultAccessResult.Unavailable)
        assertNull(store.get("a")!!.deviceWrap)
    }
    @Test fun `reenrollment never releases an old profile wallet even when reset reused its phrase`() = operationTest {
        active("a", true); active("b"); phrase()
        val reset = coordinator.resetPassphrase(activity) as VaultMaintenanceResult.Completed
        assertEquals(setOf("b"), reset.unrecoveredWalletIds)
        chooseWrap = true; phrase()
        assertTrue(coordinator.enrollDevice(activity, "b") is VaultAccessResult.Unavailable)
        assertTrue(store.snapshot().unrecoveredWalletIds.contains("b")); assertNull(store.get("b")!!.deviceWrap)
    }
    private fun mnemonicFixture(): Pair<KeyMaterial, WalletEntity> {
        val words = "abandon ".repeat(11) + "about"
        val manager = MnemonicManager(); val seed = manager.mnemonicToSeed(words.split(" "))
        val derived = try { manager.derivePrivateKey(seed) } finally { seed.fill(0) }
        try {
            val info = WalletDerivation.walletInfo(derived)
            val bundle = material.copy(privateKey = derived.joinToString("") { "%02x".format(it) }, walletType = "mnemonic", mnemonic = words)
            return bundle to WalletEntity("m", "Mnemonic origin", "mnemonic", "m/44'/309'/0'/0/0", null,
                mainnetAddress = info.mainnetAddress, testnetAddress = info.testnetAddress)
        } finally { derived.fill(0) }
    }
    @Test fun `private key recovery restores mnemonic origin spending without claiming seed or changing other wallets`() = operationTest {
        active("b"); val unrelated = WalletEnvelopeCodec.encode(store.get("b")!!)
        val (mnemonic, row) = mnemonicFixture(); db.walletDao().insert(row)
        db.keyMaterialDao().upsert(KeyMaterialEntity("m", byteArrayOf(9), null, byteArrayOf(8), "mnemonic", true, 1, 2))
        val raw = material.copy(privateKey = mnemonic.privateKey)
        phrase()
        val recovered = coordinator.recoverExisting(activity, "m", raw) as VaultAccessResult.Success
        assertEquals("raw_key", recovered.material.walletType); assertNull(recovered.material.mnemonic)
        assertEquals("mnemonic", db.walletDao().getById("m")!!.type)
        assertArrayEquals(unrelated, WalletEnvelopeCodec.encode(store.get("b")!!))
        phrase()
        val read = coordinator.read(activity, "m", "Read", "Auth") as VaultAccessResult.Success
        assertEquals(raw.privateKey, read.material.privateKey); assertEquals("raw_key", read.material.walletType); assertNull(read.material.mnemonic)
    }
    @Test fun `private key recovery preserves an already accessible matching mnemonic`() = operationTest {
        val (mnemonic, row) = mnemonicFixture(); db.walletDao().insert(row)
        val bytes = Json.encodeToString(mnemonic).toByteArray()
        val envelope = try { crypto.create("m", 0, bytes, pass.toCharArray()) } finally { bytes.fill(0) }
        store.activate(KeyMaterialEntity("m", byteArrayOf(), null, byteArrayOf(), "mnemonic", false, 0), null, envelope, pass.toCharArray())
        store.markLegacyCleanupComplete("m", 0)
        phrase()
        val recovered = coordinator.recoverExisting(activity, "m", material.copy(privateKey = mnemonic.privateKey)) as VaultAccessResult.Success
        assertEquals("mnemonic", recovered.material.walletType); assertEquals(mnemonic.mnemonic, recovered.material.mnemonic)
        phrase()
        val read = coordinator.read(activity, "m", "Read", "Auth") as VaultAccessResult.Success
        assertEquals(mnemonic.mnemonic, read.material.mnemonic)
    }
    @Test fun `background during pending biometric enrollment cancels and wipes owned DEK`() = operationTest {
        active(); chooseWrap = true; phrase()
        val started = CompletableDeferred<Unit>()
        onWrap = { started.complete(Unit); awaitCancellation() }
        val running = launch { coordinator.enrollDevice(activity, "a") }
        try {
            started.await(); activityController.pause().stop(); yield()
            withTimeout(1_000) { running.join() }
            assertTrue(running.isCancelled)
            assertTrue(activeWrapKey!!.all { it == 0.toByte() }); assertTrue(issued.all { chars -> chars.all { it == '\u0000' } })
            assertNull(store.get("a")!!.deviceWrap)
        } finally { running.cancelAndJoin() }
    }
    @Test fun `background during pending biometric reset cancels and wipes new passphrase`() = operationTest {
        active(deviceWrap = true); phrase(next)
        val started = CompletableDeferred<Unit>()
        onUnwrap = { started.complete(Unit); awaitCancellation() }
        val running = launch { coordinator.resetPassphrase(activity) }
        try {
            started.await(); activityController.pause().stop(); yield()
            withTimeout(1_000) { running.join() }
            assertTrue(running.isCancelled)
            assertTrue(issued.all { chars -> chars.all { it == '\u0000' } })
            assertTrue(store.verifyPassphrase(pass.toCharArray())); assertFalse(store.verifyPassphrase(next.toCharArray()))
        } finally { running.cancelAndJoin() }
    }
    @Test fun `phrase only read bypasses available device UI but still checks cleanup and metadata`() = operationTest {
        wallet(); cleanupOk = false; phrase()
        assertEquals(VaultAccessResult.CleanupRequired, coordinator.recoverExisting(activity, "a", material))
        val envelope = store.get("a")!!
        deviceKeys["a"] = crypto.unwrapDataKey(envelope, "a", pass.toCharArray())
        store.updateDeviceWrap("a", envelope.generation, DeviceKeyWrap("test-a", ByteArray(48), ByteArray(12)))
        onUnwrap = { error("Device UI must not run for explicit passphrase route") }
        phrase()
        assertEquals(VaultAccessResult.CleanupRequired, coordinator.read(activity, "a", "Read", "Auth", VaultReadPreference.PASSPHRASE))
        cleanupOk = true; phrase()
        assertTrue(coordinator.read(activity, "a", "Read", "Auth", VaultReadPreference.PASSPHRASE) is VaultAccessResult.Success)
        assertTrue(store.pendingLegacyCleanup().isEmpty())
        val row = db.walletDao().getById("a")!!; db.walletDao().update(row.copy(testnetAddress = "wrong")); phrase()
        assertTrue(coordinator.read(activity, "a", "Read", "Auth", VaultReadPreference.PASSPHRASE) is VaultAccessResult.Unavailable)
    }
    @Test fun `phrase only read migrates retained old profile before release`() = operationTest {
        active("a", true); active("b", true); deviceKeys.remove("b"); phrase(next)
        val reset = coordinator.resetPassphrase(activity) as VaultMaintenanceResult.Completed
        assertEquals(setOf("b"), reset.unrecoveredWalletIds)
        onUnwrap = { error("Device UI must not run for explicit passphrase route") }
        phrase(); phrase(next)
        val read = coordinator.read(activity, "b", "Read", "Auth", VaultReadPreference.PASSPHRASE) as VaultAccessResult.Success
        assertEquals(material.privateKey, read.material.privateKey)
        assertTrue(store.snapshot().unrecoveredWalletIds.isEmpty())
    }
    @Test @Config(sdk = [30]) fun `credential route captures selected mode and permits only its legitimate handoff`() = operationTest {
        active(deviceWrap = true)
        for (preference in listOf(VaultReadPreference.DEFAULT, VaultReadPreference.CREDENTIAL_ONLY)) {
            deviceModes = if (preference == VaultReadPreference.DEFAULT) setOf(VaultDevicePromptMode.CREDENTIAL_ONLY)
                else setOf(VaultDevicePromptMode.BIOMETRIC_ONLY, VaultDevicePromptMode.CREDENTIAL_ONLY)
            val started = CompletableDeferred<Unit>(); val callback = CompletableDeferred<Unit>()
            onUnwrap = { started.complete(Unit); callback.await() }
            var stopped = false
            val running = async { coordinator.read(activity, "a", "Read", "Auth", preference) }
            try {
                started.await(); assertEquals(VaultDevicePromptMode.CREDENTIAL_ONLY, lastDeviceMode)
                activityController.pause().stop(); stopped = true; yield(); assertTrue(running.isActive)
                callback.complete(Unit); yield(); assertFalse(running.isCompleted)
                activityController.start().resume(); stopped = false
                assertTrue(running.await() is VaultAccessResult.Success)
            } finally {
                if (stopped) activityController.start().resume()
                running.cancelAndJoin()
            }
        }
    }
    @Test fun `validator rejects malformed derivation and inconsistent mnemonic`() = operationTest {
        val row = wallet()
        assertTrue(validator.validate(material, row))
        assertFalse(validator.validate(material, row.copy(derivationPath = "garbage")))
        assertFalse(validator.validate(material.copy(walletType = "mnemonic", mnemonic = "abandon ".repeat(11) + "about"), null))
    }

    @Test fun `recovery reports missing device key without consuming independent passphrase`() = operationTest {
        active(deviceWrap = true); deviceKeys.clear(); unwrapResult = DeviceVaultResult.Unavailable("missing-device-key"); phrase()
        val result = coordinator.readForRecovery(activity, "a") as VaultAccessResult.Unavailable
        assertEquals(VaultFailureKind.MISSING_DEVICE_KEY, result.kind)
        assertEquals(1, credentials.size)
    }
    @Test fun `recovery distinguishes invalidated key and temporary device failure`() = operationTest {
        active(deviceWrap = true)
        for ((reason, kind) in listOf("invalidated-device-key" to VaultFailureKind.INVALIDATED_DEVICE_KEY,
            "device-authentication-failed" to VaultFailureKind.TEMPORARY_DEVICE_FAILURE)) {
            unwrapResult = DeviceVaultResult.Unavailable(reason)
            assertEquals(kind, (coordinator.readForRecovery(activity, "a") as VaultAccessResult.Unavailable).kind)
        }
    }
    @Test fun `absent device wrap explicitly offers independent credentials without claiming erased seed`() = operationTest {
        active(); phrase()
        assertEquals(VaultFailureKind.NO_DEVICE_WRAP, (coordinator.readForRecovery(activity, "a") as VaultAccessResult.Unavailable).kind)
        assertEquals(1, credentials.size)
    }
    @Test fun `wrong phrase is distinguishable from malformed envelope and never authorizes recovery`() = operationTest {
        active(); phrase("wrong public wallet passphrase")
        assertEquals(VaultFailureKind.CREDENTIAL_OR_WRAP_INTEGRITY,
            (coordinator.read(activity, "a", "Read", "Read", VaultReadPreference.PASSPHRASE) as VaultAccessResult.Unavailable).kind)
        db.openHelper.writableDatabase.execSQL("UPDATE wallet_vault SET envelope=X'00' WHERE walletId='a'")
        assertEquals(VaultFailureKind.MALFORMED_OR_UNSUPPORTED,
            (coordinator.readForRecovery(activity, "a") as VaultAccessResult.Unavailable).kind)
        assertArrayEquals(byteArrayOf(0), db.walletVaultDao().get("a")!!.envelope)
    }
}
