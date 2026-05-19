package com.rjnr.pocketnode.data.wallet

import android.util.Log
import com.rjnr.pocketnode.data.database.AppDatabase
import com.rjnr.pocketnode.data.database.DatabaseMaintenanceUtil
import com.rjnr.pocketnode.data.database.dao.BalanceCacheDao
import com.rjnr.pocketnode.data.database.dao.DaoCellDao
import com.rjnr.pocketnode.data.database.dao.TransactionDao
import com.rjnr.pocketnode.data.database.dao.WalletDao
import com.rjnr.pocketnode.data.database.entity.WalletEntity
import androidx.room.withTransaction
import com.rjnr.pocketnode.data.gateway.models.NetworkType
import com.rjnr.pocketnode.data.gateway.models.SyncMode
import kotlinx.coroutines.flow.Flow
import org.nervos.ckb.utils.Numeric
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "WalletRepository"

@Singleton
class WalletRepository @Inject constructor(
    private val walletDao: WalletDao,
    private val keyManager: KeyManager,
    private val walletPreferences: WalletPreferences,
    private val mnemonicManager: MnemonicManager,
    private val appDatabase: AppDatabase,
    private val transactionDao: TransactionDao,
    private val balanceCacheDao: BalanceCacheDao,
    private val daoCellDao: DaoCellDao
) {
    val walletsFlow: Flow<List<WalletEntity>> = walletDao.getAllFlow()

    /**
     * Synchronous snapshot of the currently-active walletId, or null if
     * no wallet has been selected yet. Reads from
     * [WalletPreferences.getActiveWalletId] directly so it can be called
     * outside a coroutine; the underlying SharedPreferences read is fast.
     * Used by V2-aware call sites that need to peek the kdfVersion before
     * deciding whether a BiometricPrompt CryptoObject step is required.
     */
    fun activeWalletIdSnapshot(): String? = walletPreferences.getActiveWalletId()

    fun getActiveWallet(): Flow<WalletEntity?> = walletDao.getActiveWallet()

    fun getAllWallets(): Flow<List<WalletEntity>> = walletDao.getAllWallets()

    fun getSubAccounts(parentWalletId: String): Flow<List<WalletEntity>> =
        walletDao.getSubAccounts(parentWalletId)

    suspend fun getAll(): List<WalletEntity> = walletDao.getAll()

    suspend fun getActive(): WalletEntity? = walletDao.getActive()

    suspend fun getById(walletId: String): WalletEntity? = walletDao.getById(walletId)

    /**
     * Validate that a wallet name is unique (case-insensitive).
     */
    private suspend fun validateUniqueName(name: String) {
        val existing = walletDao.getAll()
        if (existing.any { it.name.equals(name, ignoreCase = true) }) {
            throw IllegalArgumentException("A wallet named \"$name\" already exists")
        }
    }

    /**
     * Reject imports that produce an address already tracked by another wallet.
     * Prevents two WalletEntity rows pointing at the same keypair, which would split
     * tx history, balance cache and DAO cells across duplicate records.
     */
    private suspend fun validateUniqueAddress(mainnetAddress: String, testnetAddress: String) {
        val existing = walletDao.getAll()
        val dup = existing.firstOrNull {
            it.mainnetAddress == mainnetAddress || it.testnetAddress == testnetAddress
        }
        if (dup != null) {
            throw IllegalArgumentException("This wallet is already imported as \"${dup.name}\"")
        }
    }

    /**
     * A newly generated wallet has no history, so default its sync mode to NEW_WALLET
     * (start from current tip) on both networks. Imports keep whatever sync mode the
     * import UI selected.
     */
    private fun markFreshWalletSyncMode(walletId: String) {
        walletPreferences.setSyncMode(SyncMode.NEW_WALLET, NetworkType.MAINNET, walletId)
        walletPreferences.setSyncMode(SyncMode.NEW_WALLET, NetworkType.TESTNET, walletId)
    }

    /**
     * Create a new mnemonic wallet. Stores keys in wallet-scoped encrypted prefs.
     */
    suspend fun createWallet(
        name: String,
        wordCount: MnemonicManager.WordCount = MnemonicManager.WordCount.TWELVE
    ): Pair<WalletEntity, List<String>> {
        validateUniqueName(name)
        // Derive everything locally. Going through keyManager.generateWalletWithMnemonic
        // would overwrite the legacy "default" Room key slot (and fire a PIN backup for
        // "default") on every M3 create, polluting legacy single-wallet fallback paths.
        val words = mnemonicManager.generateMnemonic(wordCount)
        val privateKey = mnemonicManager.mnemonicToPrivateKey(words)
        val info = keyManager.deriveWalletInfo(privateKey)
        val walletId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val colorIndex = walletDao.count() % 8

        keyManager.storeKeysForWallet(walletId, privateKey, words)

        val entity = WalletEntity(
            walletId = walletId,
            name = name,
            type = KeyManager.WALLET_TYPE_MNEMONIC,
            derivationPath = "m/44'/309'/0'/0/0",
            parentWalletId = null,
            accountIndex = 0,
            mainnetAddress = info.mainnetAddress,
            testnetAddress = info.testnetAddress,
            isActive = true,
            createdAt = now,
            lastActiveAt = now,
            colorIndex = colorIndex
        )

        walletDao.deactivateAll()
        walletDao.insert(entity)
        walletPreferences.setActiveWalletId(walletId)
        markFreshWalletSyncMode(walletId)
        Log.d(TAG, "Created wallet: ${entity.walletId} (${entity.name})")
        return Pair(entity, words)
    }

    /**
     * Import a wallet from a mnemonic phrase.
     */
    suspend fun importWallet(
        name: String,
        words: List<String>,
        passphrase: String = ""
    ): WalletEntity {
        validateUniqueName(name)
        // Derive locally — see comment in createWallet on why we bypass the legacy
        // keyManager.importWalletFromMnemonic path.
        require(mnemonicManager.validateMnemonic(words)) { "Invalid mnemonic" }
        val privateKey = mnemonicManager.mnemonicToPrivateKey(words, passphrase)
        val info = keyManager.deriveWalletInfo(privateKey)
        validateUniqueAddress(info.mainnetAddress, info.testnetAddress)
        val walletId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val colorIndex = walletDao.count() % 8

        keyManager.storeKeysForWallet(walletId, privateKey, words)
        keyManager.setMnemonicBackedUpForWallet(walletId, true)

        val entity = WalletEntity(
            walletId = walletId,
            name = name,
            type = KeyManager.WALLET_TYPE_MNEMONIC,
            derivationPath = "m/44'/309'/0'/0/0",
            parentWalletId = null,
            accountIndex = 0,
            mainnetAddress = info.mainnetAddress,
            testnetAddress = info.testnetAddress,
            isActive = true,
            createdAt = now,
            lastActiveAt = now,
            colorIndex = colorIndex
        )

        walletDao.deactivateAll()
        walletDao.insert(entity)
        walletPreferences.setActiveWalletId(walletId)
        Log.d(TAG, "Imported wallet: ${entity.walletId} (${entity.name})")
        return entity
    }

    /**
     * Import a wallet from a raw private key hex string.
     */
    suspend fun importRawKey(
        name: String,
        privateKeyHex: String
    ): WalletEntity {
        validateUniqueName(name)
        // Parse and derive locally — see comment in createWallet.
        val privateKeyBytes = Numeric.hexStringToByteArray(privateKeyHex)
        require(privateKeyBytes.size == 32) { "Private key must be 32 bytes" }
        val info = keyManager.deriveWalletInfo(privateKeyBytes)
        validateUniqueAddress(info.mainnetAddress, info.testnetAddress)
        val walletId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val colorIndex = walletDao.count() % 8

        keyManager.storeKeysForWallet(walletId, privateKeyBytes, null)
        keyManager.setMnemonicBackedUpForWallet(walletId, true)

        val entity = WalletEntity(
            walletId = walletId,
            name = name,
            type = KeyManager.WALLET_TYPE_RAW_KEY,
            derivationPath = null,
            parentWalletId = null,
            accountIndex = 0,
            mainnetAddress = info.mainnetAddress,
            testnetAddress = info.testnetAddress,
            isActive = true,
            createdAt = now,
            lastActiveAt = now,
            colorIndex = colorIndex
        )

        walletDao.deactivateAll()
        walletDao.insert(entity)
        walletPreferences.setActiveWalletId(walletId)
        Log.d(TAG, "Imported raw key wallet: ${entity.walletId} (${entity.name})")
        return entity
    }

    /**
     * Create a sub-account derived from a parent mnemonic wallet.
     * Derives a new key at the next account index from the parent's mnemonic.
     *
     * If [parentMnemonicOverride] is provided, the parent's mnemonic is
     * not read from key storage — useful when the caller has already
     * unlocked it via BiometricPrompt (V2 wallets). When null, the parent
     * mnemonic is read silently from V1 storage, which will throw for V2
     * parents (#213 sub-PR 5).
     */
    suspend fun createSubAccount(
        parentWalletId: String,
        name: String,
        parentMnemonicOverride: List<String>? = null,
    ): WalletEntity {
        val parent = walletDao.getById(parentWalletId)
            ?: throw IllegalArgumentException("Parent wallet not found")
        require(parent.type == KeyManager.WALLET_TYPE_MNEMONIC) {
            "Sub-accounts require a mnemonic wallet"
        }

        val parentMnemonic = parentMnemonicOverride
            ?: keyManager.getMnemonicForWallet(parentWalletId)
            ?: throw IllegalStateException("Parent mnemonic not found")

        // Use max existing sub-account index + 1 to avoid index collisions after deletions
        val existingSubs = walletDao.getSubAccountsList(parentWalletId)
        val nextIndex = if (existingSubs.isEmpty()) 1 else existingSubs.maxOf { it.accountIndex } + 1
        val seed = mnemonicManager.mnemonicToSeed(parentMnemonic)
        val privateKey = mnemonicManager.derivePrivateKey(seed, accountIndex = nextIndex)
        val publicKey = keyManager.derivePublicKey(privateKey)
        val lockScript = keyManager.deriveLockScript(publicKey)
        val mainnetAddress = AddressUtils.encode(lockScript, NetworkType.MAINNET)
        val testnetAddress = AddressUtils.encode(lockScript, NetworkType.TESTNET)

        val walletId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val colorIndex = walletDao.count() % 8

        // Sub-accounts don't store mnemonic — only the parent holds it
        keyManager.storeKeysForWallet(walletId, privateKey, null)

        val entity = WalletEntity(
            walletId = walletId,
            name = name,
            type = KeyManager.WALLET_TYPE_MNEMONIC,
            derivationPath = "m/44'/309'/$nextIndex'/0/0",
            parentWalletId = parentWalletId,
            accountIndex = nextIndex,
            mainnetAddress = mainnetAddress,
            testnetAddress = testnetAddress,
            isActive = true,
            createdAt = now,
            lastActiveAt = now,
            colorIndex = colorIndex
        )

        walletDao.deactivateAll()
        walletDao.insert(entity)
        walletPreferences.setActiveWalletId(walletId)
        markFreshWalletSyncMode(walletId)
        Log.d(TAG, "Created sub-account: $walletId (parent: $parentWalletId, index: $nextIndex)")

        return entity
    }

    /**
     * Switch active wallet. Updates Room, preferences, and last-active timestamp.
     */
    suspend fun switchActiveWallet(walletId: String) {
        val wallet = walletDao.getById(walletId)
            ?: throw IllegalArgumentException("Wallet not found: $walletId")
        walletDao.deactivateAll()
        walletDao.activate(walletId)
        walletDao.updateLastActiveAt(walletId, System.currentTimeMillis())
        walletPreferences.setActiveWalletId(walletId)
        Log.d(TAG, "Switched to wallet: $walletId")
    }

    /**
     * Rename a wallet. Rejects duplicates to stay consistent with create/import.
     */
    suspend fun renameWallet(walletId: String, newName: String) {
        val existing = walletDao.getAll()
        if (existing.any { it.walletId != walletId && it.name.equals(newName, ignoreCase = true) }) {
            throw IllegalArgumentException("A wallet named \"$newName\" already exists")
        }
        walletDao.updateName(walletId, newName)
    }

    /**
     * Delete a wallet and its keys. Runs VACUUM afterward to reclaim freed pages.
     * Refuses to delete the active wallet — callers must switch first.
     */
    suspend fun deleteWallet(walletId: String) {
        val wallet = walletDao.getById(walletId)
            ?: throw IllegalArgumentException("Wallet not found: $walletId")
        if (wallet.isActive || walletPreferences.getActiveWalletId() == walletId) {
            throw IllegalStateException("Cannot delete the active wallet. Switch to another wallet first.")
        }
        // Delete the wallet row and wallet-scoped caches first, all in one transaction.
        // Only destroy keys after the DB removal commits — otherwise a failure between
        // key destruction and row deletion leaves an orphaned wallet whose keys are gone.
        appDatabase.withTransaction {
            walletDao.delete(walletId)
            for (network in listOf("MAINNET", "TESTNET")) {
                transactionDao.deleteByWalletAndNetwork(walletId, network)
                balanceCacheDao.deleteByWalletAndNetwork(walletId, network)
                daoCellDao.deleteByWalletAndNetwork(walletId, network)
            }
        }
        keyManager.deleteWalletKeys(walletId)
        // VACUUM must run outside the transaction above — SQLite rejects VACUUM
        // when a transaction is open on the same connection.
        DatabaseMaintenanceUtil.vacuum(appDatabase)
        Log.d(TAG, "Deleted wallet and caches: $walletId")
    }

    suspend fun walletCount(): Int = walletDao.count()
}
