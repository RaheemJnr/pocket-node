package com.rjnr.pocketnode.data.wallet

import androidx.room.withTransaction
import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.rjnr.pocketnode.core.log.Logger
import com.rjnr.pocketnode.core.crypto.Secp256k1Signer
import com.rjnr.pocketnode.core.crypto.hexToByteArray
import com.rjnr.pocketnode.data.auth.AuthManager
import com.rjnr.pocketnode.data.crypto.KeyBackupManager
import com.rjnr.pocketnode.data.crypto.KeyMaterial
import com.rjnr.pocketnode.data.gateway.models.Script
import com.rjnr.pocketnode.data.crypto.DecryptedKeyData
import com.rjnr.pocketnode.data.crypto.KeyMaterialUnreadableException
import com.rjnr.pocketnode.data.crypto.KeyStoreMigrationHelper
import com.rjnr.pocketnode.data.crypto.WalletKeyBundle
import androidx.annotation.VisibleForTesting
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ## Memory-hygiene boundary (#321 / #335)
 *
 * Transient `ByteArray` private keys returned by the read paths are zeroed
 * by callers after signing (`fill(0)` in SendViewModel and the
 * GatewayRepository DAO overloads), and KeyBackupManager zeroes its
 * plaintext buffers around encrypt/decrypt. What canNOT be zeroed on the
 * JVM, deliberately accepted:
 *
 *  - Hex `String`s handed out by EncryptedSharedPreferences / Room TEXT
 *    columns — immutable, GC-managed.
 *  - libsecp256k1's internal copies of the key made during signing.
 *  - The plaintext 32-byte key [getPrivateKey] materialises. [sign] and
 *    [getWalletInfo] call it and do NOT zero the array they get back, so a
 *    readable copy of the key survives on the heap until GC after every
 *    in-process signature. Callers that hold the key themselves (SendViewModel,
 *    the GatewayRepository DAO overloads) do zero theirs; these two do not, and
 *    fixing it properly means the ByteArray storage rewrite in #335 rather than
 *    a local `fill(0)` that still leaves the hex `String` behind.
 *
 * The full String→ByteArray storage rewrite (prefs re-encode, Room BLOB
 * migration, backup format v3) is tracked in #335 and intentionally NOT
 * bundled into routine changes — a migration bug there is unrecoverable
 * key loss.
 */
@Singleton
class KeyManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val mnemonicManager: MnemonicManager,
    private val logger: Logger,
) {
    internal val walletStorage = WalletStorageBoundary(context)
    private val legacyFiles = LegacyWalletFiles(context)

    internal fun deleteInstallationFiles() = walletStorage.files {
        clearSessionPin()
        authManager?.clearSession()
        testPrefs?.edit()?.clear()?.commit()?.let { check(it) }
        testWalletPrefs.values.forEach { check(it.edit().clear().commit()) }
        legacyFiles.deleteAll()
        // The production backup manager is wired by Hilt; discover files even before that wiring.
        val backup = keyBackupManager ?: KeyBackupManager(java.io.File(context.filesDir, "key_backups"), logger, walletStorage)
        backup.deleteAllChecked()
    }

    internal fun verifyInstallationFilesEmpty() = walletStorage.files {
        legacyFiles.verifyEmpty()
        val backup = keyBackupManager ?: KeyBackupManager(java.io.File(context.filesDir, "key_backups"), logger, walletStorage)
        check(!backup.hasAnyBackups()) { "Wallet backup copies remain." }
    }

    @VisibleForTesting
    internal var testPrefs: SharedPreferences? = null

    @VisibleForTesting
    internal var keyBackupManager: KeyBackupManager? = null

    /**
     * Per-wallet stand-ins for the deprecated EncryptedSharedPreferences
     * files, keyed by walletId. EncryptedSharedPreferences needs a real
     * KeyStore, so unit tests covering the legacy fallback path substitute
     * plain SharedPreferences here (same role as [testPrefs] for the
     * "default" bucket).
     *
     * Synchronized because [getWalletPrefs] is reached from whatever
     * dispatcher a suspend read happens to be on; always empty in production.
     */
    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    internal val testWalletPrefs: MutableMap<String, SharedPreferences> =
        java.util.Collections.synchronizedMap(mutableMapOf())

    @VisibleForTesting
    internal var keyStoreMigrationHelper: KeyStoreMigrationHelper? = null

    // AuthManager is the single source of truth for the session PIN used to encrypt
    // KeyBackupManager material. Wired via provideKeyManager in AppModule (the
    // @Provides factory — Hilt does not fire @Inject setters on @Provides builds).
    @VisibleForTesting
    internal var authManager: AuthManager? = null

    // Fallback PIN holder, only used by unit tests that don't wire an AuthManager.
    // Like AuthManager, publication takes files -> session; clearing never waits for files.
    private val sessionLock = Any()
    private var fallbackSessionPin: CharArray? = null

    @VisibleForTesting
    internal val sessionPin: CharArray?
        get() = synchronized(sessionLock) { fallbackSessionPin?.copyOf() }

    fun setSessionPin(pin: CharArray) = walletStorage.readOrWriteFiles {
        synchronized(sessionLock) {
            fallbackSessionPin?.fill('\u0000')
            fallbackSessionPin = pin.copyOf()
        }
    }

    fun clearSessionPin() = synchronized(sessionLock) {
        fallbackSessionPin?.fill('\u0000')
        fallbackSessionPin = null
    }

    /**
     * Pure-crypto plaintext bundle producer. Used by the new V2 write path
     * (see [WalletKeyWriter]). The caller is responsible for persistence.
     */
    fun encodePlaintextBundle(
        privateKey: ByteArray,
        mnemonic: List<String>?,
    ): WalletKeyBundle = WalletDerivation.encodePlaintextBundle(privateKey, mnemonic)

    @Inject
    fun setBackupManager(backupManager: KeyBackupManager) {
        this.keyBackupManager = backupManager
    }

    @Inject
    fun setMigrationHelper(helper: KeyStoreMigrationHelper) {
        this.keyStoreMigrationHelper = helper
    }

    // @Deprecated ESP fallback — remove after one release cycle (v1.6.0+)
    private var walletResetDueToCorruption = false

    @Deprecated("ESP fallback — remove after one release cycle")
    fun wasResetDueToCorruption(): Boolean = walletResetDueToCorruption

    @Deprecated("ESP fallback — remove after one release cycle")
    fun resetCorruptionFlag() {
        walletResetDueToCorruption = false
    }

    @Deprecated("ESP fallback — remove after one release cycle")
    private val prefs: SharedPreferences
        get() = walletStorage.readOrWriteFiles { testPrefs ?: encryptedPrefs }

    @Deprecated("ESP fallback — remove after one release cycle")
    private val encryptedPrefs: SharedPreferences by lazy {
        try {
            createEncryptedPrefs(useStrongBox = true)
        } catch (e: Exception) {
            logger.w(TAG, "StrongBox-backed prefs failed, trying without StrongBox", e)
            // NEVER delete the prefs file — it may contain the user's only copy of
            // their private key and mnemonic. Try without StrongBox instead.
            try {
                createEncryptedPrefs(useStrongBox = false)
            } catch (e2: Exception) {
                // Both attempts failed — the keystore or prefs file is genuinely corrupted.
                // Surface the error so the user sees a warning. Do NOT delete the file.
                logger.e(TAG, "EncryptedSharedPreferences completely unreadable", e2)
                walletResetDueToCorruption = true
                // Last resort: try StrongBox one more time (sometimes transient)
                createEncryptedPrefs(useStrongBox = true)
            }
        }
    }

    @Deprecated("ESP fallback — remove after one release cycle")
    private fun createEncryptedPrefs(useStrongBox: Boolean = true): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .setRequestStrongBoxBacked(useStrongBox)
            .build()

        return EncryptedSharedPreferences.create(
            context,
            LegacyWalletNamespace.DEFAULT,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    suspend fun hasWallet(): Boolean {
        if (walletStorage.isClosed()) return false
        val helper = keyStoreMigrationHelper
        if (helper != null) {
            val hasKeys = helper.hasAnyKeys()
            if (hasKeys) return true
        }
        return walletStorage.readOrWriteFiles {
            if (testPrefs == null && !legacyFiles.exists("default")) false
            else prefs.contains(KEY_PRIVATE_KEY)
        }
    }

    /**
     * Diagnostic-only (#424): "total/V1/V2 key_material rows, espKey" for the
     * startup-gate log. Read-only: never logs key bytes, only counts and an
     * existence flag.
     *
     * Side-effect note: the ESP presence check is skipped when Room already
     * holds keys (`total > 0`), exactly mirroring [hasWallet]'s short-circuit.
     * Touching `prefs` lazily initializes `encryptedPrefs`, which can flip
     * `walletResetDueToCorruption` on an unreadable legacy file; gating on
     * `total > 0` keeps a migrated wallet's routing untouched (Codex/CodeRabbit
     * review on #425). Each probe is independently guarded so one failure still
     * yields a usable line rather than dropping the whole log event.
     */
    suspend fun diagnosticKeyState(): String {
        val helper = keyStoreMigrationHelper
        val (total, v1, v2) = try {
            helper?.keyMaterialCounts() ?: Triple(-1, -1, -1)
        } catch (e: Exception) {
            Triple(-2, -2, -2) // -2 marks a count-probe failure
        }
        val espKey = when {
            total > 0 -> "skip" // Room has keys; don't touch ESP (no side effect)
            else -> try {
                @Suppress("DEPRECATION")
                prefs.contains(KEY_PRIVATE_KEY).toString()
            } catch (e: Exception) {
                "err(${e.javaClass.simpleName})"
            }
        }
        return "keyMaterial=$total(v1=$v1,v2=$v2) espKey=$espKey"
    }

    /** Startup-only bootstrap, never an authenticated operation or a fallback for a vault marker. */
    internal suspend fun legacyDefaultMaterialForMigration(walletDao: com.rjnr.pocketnode.data.database.dao.WalletDao): DecryptedKeyData? {
        check(walletDao.count() == 0) { "Legacy startup migration is already complete." }
        val helper = keyStoreMigrationHelper ?: throw WalletSecretRequiresAuthenticationException()
        val version = helper.keyMaterialVersion(DEFAULT_WALLET_ID)
        check(helper.keyMaterialCounts().first == if (version == null) 0 else 1) {
            "Legacy startup migration cannot replace existing wallet key storage."
        }
        if (version != null) {
            if (version != 1) throw KeyMaterialUnreadableException(DEFAULT_WALLET_ID)
            return helper.readDecryptedKey(DEFAULT_WALLET_ID)
        }
        val hex = prefs.getString(KEY_PRIVATE_KEY, null) ?: return null
        val mnemonic = prefs.getString(KEY_MNEMONIC, null)
        val type = prefs.getString(KEY_WALLET_TYPE, null)
            ?: if (mnemonic == null) WALLET_TYPE_RAW_KEY else WALLET_TYPE_MNEMONIC
        return DecryptedKeyData(hex, mnemonic, type, prefs.getBoolean(KEY_MNEMONIC_BACKED_UP, false))
    }

    /** Independent XML and backup fingerprints; never opens/restores SharedPreferences. */
    internal fun preferenceCleanupSnapshot(walletId: String): List<String?> {
        require(walletId.matches(Regex("[A-Za-z0-9_-]{1,128}")))
        val name = LegacyWalletNamespace.preferences(walletId)
        val file = java.io.File(context.filesDir.parentFile, "shared_prefs/$name.xml")
        return listOf(file, java.io.File(file.path + ".bak")).map { source ->
            if (!source.exists()) null else source.inputStream().use { input ->
                val digest = java.security.MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(8192)
                try {
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                    }
                    digest.digest().joinToString("") { "%02x".format(it) }
                } finally { buffer.fill(0) }
            }
        }
    }
    internal fun defaultPreferenceCleanupSnapshot() = preferenceCleanupSnapshot("default")

    /**
     * Decrypts each captured XML/.bak independently using Android ESP over immutable preferences.
     * Never opens the original SharedPreferences, restores a backup, or constructs a MasterKey.
     * Both keysets must already exist: pinned Tink's existing-keyset path only reads its master
     * AEAD, while missing keysets (its key-generation path) are rejected before calling ESP.
     */
    internal fun preferencesForVaultCleanup(walletId: String): DecryptedKeyData? { return try {
        walletStorage.requireOpen()
        val before = preferenceCleanupSnapshot(walletId)
        val standIn = if (walletId == "default") testPrefs else testWalletPrefs[walletId]
        val name = LegacyWalletNamespace.preferences(walletId)
        val file = java.io.File(context.filesDir.parentFile, "shared_prefs/$name.xml")
        val copies = listOf(file, java.io.File(file.path + ".bak")).mapIndexedNotNull { index, source ->
            if (before[index] == null) null else {
                readCapturedPreference(name, readPreferenceXml(source), standIn != null) ?: return null
            }
        }
        val materials = if (before.all { it == null } && standIn != null) {
            // Existing plain-preference test seam; never available in production.
            listOfNotNull(readCapturedPreference(name, standIn.all.filterValues { it != null }.mapValues { it.value!! }, true))
        } else copies
        if (preferenceCleanupSnapshot(walletId) != before || materials.isEmpty() ||
            materials.map { it.privateKeyHex }.distinct().size != 1 ||
            materials.mapNotNull { it.mnemonic }.distinct().size > 1) null
        else materials.firstOrNull { it.mnemonic != null } ?: materials.first()
    } catch (_: Exception) { null } }

    @Suppress("DEPRECATION") // Legacy-only ESP reader; String-alias overload avoids creating a MasterKey.
    internal fun readCapturedPreference(name: String, entries: Map<String, Any>, plainTestCopy: Boolean = false,
        masterAlias: String = MasterKey.DEFAULT_MASTER_KEY_ALIAS): DecryptedKeyData? {
        val frozen = CapturedLegacyPreferences(entries)
        val source = if (plainTestCopy) frozen else {
            require(listOf("__androidx_security_crypto_encrypted_prefs_key_keyset__",
                "__androidx_security_crypto_encrypted_prefs_value_keyset__").all { (entries[it] as? String)?.isNotEmpty() == true })
            require(java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.containsAlias(masterAlias))
            val capturedContext = object : android.content.ContextWrapper(context) {
                override fun getApplicationContext(): Context = this
                override fun getSharedPreferences(requested: String, mode: Int): SharedPreferences {
                    require(requested == name)
                    return frozen
                }
            }
            EncryptedSharedPreferences.create(name, masterAlias, capturedContext,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
        }
        val hex = source.getString(KEY_PRIVATE_KEY, null) ?: return null
        val words = source.getString(KEY_MNEMONIC, null)
        return DecryptedKeyData(hex, words,
            source.getString(KEY_WALLET_TYPE, null) ?: if (words == null) WALLET_TYPE_RAW_KEY else WALLET_TYPE_MNEMONIC,
            source.getBoolean(KEY_MNEMONIC_BACKED_UP, false))
    }

    /** ESP can construct its keyset writer, but every attempted write fails without side effects. */
    private class CapturedLegacyPreferences(entries: Map<String, Any>) : SharedPreferences {
        private val values = entries.toMap()
        override fun getAll(): Map<String, *> = values.toMap()
        override fun contains(key: String) = values.containsKey(key)
        override fun getString(key: String, defValue: String?): String? = values[key]?.let { it as String } ?: defValue
        override fun getBoolean(key: String, defValue: Boolean) = values[key]?.let { it as Boolean } ?: defValue
        override fun getInt(key: String, defValue: Int) = values[key]?.let { it as Int } ?: defValue
        override fun getLong(key: String, defValue: Long) = values[key]?.let { it as Long } ?: defValue
        override fun getFloat(key: String, defValue: Float) = values[key]?.let { it as Float } ?: defValue
        override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? = error("Unsupported captured preference")
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) = Unit
        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            override fun putString(key: String?, value: String?) = denied()
            override fun putStringSet(key: String?, values: MutableSet<String>?) = denied()
            override fun putInt(key: String?, value: Int) = denied()
            override fun putLong(key: String?, value: Long) = denied()
            override fun putFloat(key: String?, value: Float) = denied()
            override fun putBoolean(key: String?, value: Boolean) = denied()
            override fun remove(key: String?) = denied()
            override fun clear() = denied()
            override fun commit(): Boolean = error("Captured preferences are read only")
            override fun apply(): Unit = error("Captured preferences are read only")
            private fun denied(): SharedPreferences.Editor = error("Captured preferences are read only")
        }
    }

    internal fun defaultPreferencesForVaultCleanup() = preferencesForVaultCleanup("default")

    /** ESP backing files contain strings; booleans are accepted for the existing test stand-ins. */
    private fun readPreferenceXml(file: java.io.File): Map<String, Any> = file.inputStream().use { input ->
        require(file.length() in 1..1_048_576)
        val parser = android.util.Xml.newPullParser()
        parser.setInput(input, "UTF-8")
        val entries = linkedMapOf<String, Any>()
        var root = false
        while (parser.next() != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            require(parser.eventType != org.xmlpull.v1.XmlPullParser.DOCDECL)
            if (parser.eventType != org.xmlpull.v1.XmlPullParser.START_TAG) continue
            if (!root) { require(parser.name == "map"); root = true; continue }
            val name = parser.getAttributeValue(null, "name") ?: error("Invalid preferences")
            require(name !in entries)
            entries[name] = when (parser.name) {
                "string" -> parser.nextText()
                "boolean" -> parser.getAttributeValue(null, "value").let { require(it == "true" || it == "false"); it == "true" }
                else -> error("Unsupported preferences")
            }
        }
        require(root)
        entries
    }

    /** UI-free access cannot authenticate wallet operations, including legacy software/ESP keys. */
    private suspend fun requireAuthenticatedReader(walletId: String): Nothing {
        if (keyStoreMigrationHelper?.keyMaterialVersion(walletId) == 2) {
            throw com.rjnr.pocketnode.data.crypto.V2KeyMaterialRequiresAuthException(walletId)
        }
        throw WalletSecretRequiresAuthenticationException()
    }

    suspend fun getMnemonic(): List<String>? = requireAuthenticatedReader(DEFAULT_WALLET_ID)

    suspend fun getWalletType(): String {
        // Flag-only read: walletType is a plaintext column. Decrypting the
        // key bundle here would throw on V2 rows (post-#275 keystore
        // migration) and crash the auth/nav path.
        val helper = keyStoreMigrationHelper
        if (helper != null) {
            helper.getWalletTypeFlag("default")?.let { return it }
        }
        // Fallback to ESP
        return prefs.getString(KEY_WALLET_TYPE, WALLET_TYPE_RAW_KEY) ?: WALLET_TYPE_RAW_KEY
    }

    suspend fun hasMnemonicBackup(): Boolean {
        // Flag-only read; see getWalletType for the V2 reasoning.
        val helper = keyStoreMigrationHelper
        if (helper != null) {
            helper.getMnemonicBackedUpFlag("default")?.let { return it }
        }
        // Fallback to ESP
        return prefs.getBoolean(KEY_MNEMONIC_BACKED_UP, false)
    }

    suspend fun setMnemonicBackedUp(backedUp: Boolean) {
        // Metadata only. Legacy PIN-only ciphertext is never refreshed or created.
        val helper = keyStoreMigrationHelper
        if (helper != null && helper.setMnemonicBackedUpFlag("default", backedUp)) {
            return
        }
        // ESP fallback
        walletStorage.readOrWriteFiles { check(prefs.edit().putBoolean(KEY_MNEMONIC_BACKED_UP, backedUp).commit()) }
    }

    // -- Shared methods --

    suspend fun getWalletInfo(): WalletInfo = WalletDerivation.walletInfo(getPrivateKey())

    suspend fun getPrivateKey(): ByteArray = requireAuthenticatedReader(DEFAULT_WALLET_ID)

    fun derivePublicKey(privateKey: ByteArray): ByteArray =
        WalletDerivation.publicKey(privateKey)

    // --- #382 Tier 3: thin pass-throughs so the sweep can re-derive
    // chain-axis keys without a second MnemonicManager injection point.
    // Callers own wiping the returned material.

    fun mnemonicToSeed(words: List<String>): ByteArray =
        mnemonicManager.mnemonicToSeed(words)

    fun deriveChainKey(seed: ByteArray, chainIndex: Int, addressIndex: Int): ByteArray =
        mnemonicManager.derivePrivateKey(
            seed, accountIndex = 0, chainIndex = chainIndex, addressIndex = addressIndex
        )

    fun deriveLockScript(publicKey: ByteArray): Script =
        WalletDerivation.lockScript(publicKey)

    /**
     * Recover a wallet's lock script from a cached CKB address without
     * touching key material. Used by init/sync paths that previously read
     * the private key just to compute the lock args (those reads would
     * now require a BiometricPrompt for V2 wallets and crash app startup).
     *
     * The bech32 address encodes the same args+codeHash+hashType triple
     * that [deriveLockScript] would produce, so the round-trip is exact.
     */
    fun deriveLockScriptFromAddress(address: String): Script =
        WalletDerivation.lockScriptFromAddress(address)

    /**
     * Derive [WalletInfo] from a Room [com.rjnr.pocketnode.data.database.entity.WalletEntity]'s
     * cached addresses. No key access required — safe to call for V2
     * wallets during app boot, wallet switch, and multi-wallet sync setup.
     *
     * `publicKey` is returned as an empty string because the address-only
     * path cannot recover the SECP256K1 public key. Callers that need the
     * raw public key (currently none — see #213 sub-PR 5 audit) must
     * obtain the private key explicitly via [WalletKeyReader].
     */
    fun deriveWalletInfoFromEntity(
        wallet: com.rjnr.pocketnode.data.database.entity.WalletEntity
    ): WalletInfo = WalletDerivation.walletInfoFromAddresses(
        testnetAddress = wallet.testnetAddress,
        mainnetAddress = wallet.mainnetAddress,
    )

    suspend fun sign(message: ByteArray): ByteArray {
        return Secp256k1Signer.signRecoverable(message, getPrivateKey())
    }

    suspend fun deleteWallet() = deleteWalletKeys(DEFAULT_WALLET_ID)

    // -- Wallet-scoped key storage (multi-wallet support) --

    @Deprecated("ESP fallback — remove after one release cycle")
    private fun getWalletPrefs(walletId: String): SharedPreferences {
        synchronized(walletStorage.fileLock) {
            walletStorage.requireOpen()
            testWalletPrefs[walletId]?.let { return it }
            if (walletId == DEFAULT_WALLET_ID) return prefs
            val fileName = LegacyWalletNamespace.preferences(walletId)
            return try {
                createEncryptedPrefsForWallet(fileName, useStrongBox = true)
            } catch (e: Exception) {
                logger.w(TAG, "Wallet prefs ($walletId) StrongBox failed, trying without", e)
                // NEVER delete — may contain user's only key material
                try {
                    createEncryptedPrefsForWallet(fileName, useStrongBox = false)
                } catch (e2: Exception) {
                    logger.e(TAG, "Wallet prefs ($walletId) completely unreadable", e2)
                    createEncryptedPrefsForWallet(fileName, useStrongBox = true)
                }
            }
        }
    }

    @Deprecated("ESP fallback — remove after one release cycle")
    private fun createEncryptedPrefsForWallet(fileName: String, useStrongBox: Boolean): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .apply { if (useStrongBox) setRequestStrongBoxBacked(true) }
            .build()
        return EncryptedSharedPreferences.create(
            context, fileName, masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    suspend fun getMnemonicForWallet(walletId: String): List<String>? = requireAuthenticatedReader(walletId)

    suspend fun getPrivateKeyForWallet(walletId: String): ByteArray? = requireAuthenticatedReader(walletId)

    /**
     * Derive WalletInfo (public key, lock script, addresses) from a raw private key.
     * Used when switching wallets — the active wallet's private key is loaded from
     * its per-wallet EncryptedSharedPreferences and info is derived without touching
     * the legacy "default" prefs.
     */
    fun deriveWalletInfo(privateKey: ByteArray): WalletInfo =
        WalletDerivation.walletInfo(privateKey)

    suspend fun setMnemonicBackedUpForWallet(walletId: String, backedUp: Boolean) {
        // See [setMnemonicBackedUp] for the V2 flag-only rationale.
        val helper = keyStoreMigrationHelper
        if (helper != null && helper.setMnemonicBackedUpFlag(walletId, backedUp)) {
            return
        }
        // ESP fallback
        walletStorage.readOrWriteFiles { check(getWalletPrefs(walletId).edit().putBoolean(KEY_MNEMONIC_BACKED_UP, backedUp).commit()) }
    }

    suspend fun hasMnemonicBackupForWallet(walletId: String): Boolean {
        // Flag-only read; see [getWalletType] for the V2 reasoning. The
        // old code decrypted the bundle just to pull this boolean, which
        // threw V2KeyMaterialRequiresAuthException on every launch
        // post-keystore-migration.
        val helper = keyStoreMigrationHelper
        if (helper != null) {
            helper.getMnemonicBackedUpFlag(walletId)?.let { return it }
        }
        // Fallback to ESP
        return getWalletPrefs(walletId).getBoolean(KEY_MNEMONIC_BACKED_UP, false)
    }

    /** [keepBackup]: leave the PIN-encrypted backup file in place (Recovery's remove). */
    suspend fun deleteWalletKeys(walletId: String, keepBackup: Boolean = false) {
        walletStorage.files {
            if (!keepBackup) {
                val backups = keyBackupManager ?: KeyBackupManager(java.io.File(context.filesDir, "key_backups"), logger, walletStorage)
                check(backups.deleteBackupChecked(walletId)) { "Wallet backup could not be removed." }
            }
            val standIn = if (walletId == DEFAULT_WALLET_ID) testPrefs else testWalletPrefs[walletId]
            standIn?.let { check(it.edit().clear().commit()) }
            legacyFiles.deleteWallet(walletId)
        }
        keyStoreMigrationHelper?.deleteKey(walletId)
    }

    /**
     * One-time migration: read all wallet keys from ESP, encrypt and store in Room.
     * Verifies round-trip for each wallet. Only marks complete when ALL wallets succeed.
     * Safe to call multiple times — no-op if already complete.
     */
    /**
     * True when [walletId]'s keys still sit in its legacy per-wallet
     * EncryptedSharedPreferences file, waiting for [migrateEspToRoomIfNeeded].
     * That is the state of a wallet row created before the `key_material`
     * table (DB v3/v4 installs) on the first launches after the upgrade: the
     * migration runs in the background (GatewayRepository init) and may not
     * have finished before the startup route is chosen.
     *
     * A file-presence check only, the same one the migration uses: it never
     * opens the file, so no Keystore access, no prompt and no side effect on
     * the corruption flag.
     */
    fun hasLegacyKeyFile(walletId: String): Boolean {
        if (walletStorage.isClosed()) return false
        if (testWalletPrefs[walletId]?.contains(KEY_PRIVATE_KEY) == true) return true
        return legacyFiles.exists(walletId)
    }

    suspend fun migrateEspToRoomIfNeeded(database: com.rjnr.pocketnode.data.database.AppDatabase) = database.withTransaction {
        if (walletStorage.isClosed()) return@withTransaction
        migrateEspToRoomOpen(database.walletDao())
    }

    private suspend fun migrateEspToRoomOpen(walletDao: com.rjnr.pocketnode.data.database.dao.WalletDao) {
        val helper = keyStoreMigrationHelper ?: return
        if (helper.isMigrationComplete()) return

        try {
            val wallets = walletDao.getAll()
            for (wallet in wallets) {
                // Skip if already migrated (idempotent)
                if (helper.hasKeyMaterialRow(wallet.walletId)) continue

                // Skip Room-only wallets whose ESP file doesn't exist on disk — calling
                // getWalletPrefs for a non-existent file lazily creates an empty ESP file
                // that deleteEspFilesIfSafe would then have to clean up.
                val espFile = java.io.File(
                    context.filesDir.parent,
                    "shared_prefs/${LegacyWalletNamespace.preferences(wallet.walletId)}.xml"
                )
                if (!espFile.exists()) continue

                // Read ESP directly instead of via getPrivateKeyForWallet /
                // getMnemonicForWallet: those now fail closed when a key_material
                // row exists but cannot be decrypted (#496), and this loop is
                // precisely the path that repairs such a row from the legacy copy.
                // Room has already been shown to hold nothing readable (the
                // `continue` above) and the ESP file is known to exist.
                //
                // Opening the ESP file is itself inside the try: one wallet with
                // an unreadable prefs file must skip that wallet, not abort the
                // migration for every wallet after it.
                val espRead = try {
                    val espPrefs = getWalletPrefs(wallet.walletId)
                    val hex = espPrefs.getString(KEY_PRIVATE_KEY, null)
                    val words = espPrefs.getString(KEY_MNEMONIC, null)
                    // ESP stored the authoritative walletType via savePrivateKey/storeKeysForWallet.
                    // Fall back to the mnemonic-presence inference only when the pref is absent
                    // (e.g. corrupted prefs), so future wallet types round-trip unchanged.
                    val type = espPrefs.getString(KEY_WALLET_TYPE, null)
                    Triple(hex, words, type)
                } catch (e: Exception) {
                    logger.w(TAG, "Cannot read ESP key for ${wallet.walletId}, skipping", e)
                    continue
                }
                val privKeyHex = espRead.first ?: continue
                val mnemonic = espRead.second
                val walletType = espRead.third
                    ?: if (mnemonic != null) WALLET_TYPE_MNEMONIC else WALLET_TYPE_RAW_KEY
                val backed = hasMnemonicBackupForWallet(wallet.walletId)

                helper.migrateWallet(wallet.walletId, privKeyHex, mnemonic, walletType, backed)

                // Verify round-trip
                val check = helper.readDecryptedKey(wallet.walletId)
                if (check == null || check.privateKeyHex != privKeyHex) {
                    logger.e(TAG, "Round-trip verification failed for ${wallet.walletId}")
                    return // Abort — don't mark complete, retry next launch
                }
            }

            helper.markMigrationComplete()
            logger.i(TAG, "ESP to Room migration complete for ${wallets.size} wallets")
        } catch (e: Exception) {
            logger.e(TAG, "ESP to Room migration failed", e)
        }
    }

    /** Legacy sources are now retired per wallet only after verified vault activation. */
    fun deleteEspFilesIfSafe() = Unit

    companion object {
        private const val TAG = "KeyManager"

        /** The legacy single-wallet bucket, before multi-wallet ids existed. */
        private const val DEFAULT_WALLET_ID = "default"
        private const val KEY_PRIVATE_KEY = "private_key"
        private const val KEY_MNEMONIC = "mnemonic_words"
        private const val KEY_MNEMONIC_BACKED_UP = "mnemonic_backed_up"
        private const val KEY_WALLET_TYPE = "wallet_type"
        const val WALLET_TYPE_MNEMONIC = "mnemonic"
        const val WALLET_TYPE_RAW_KEY = "raw_key"
    }
}
