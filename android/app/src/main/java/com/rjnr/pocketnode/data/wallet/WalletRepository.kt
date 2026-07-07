package com.rjnr.pocketnode.data.wallet

import android.util.Log
import com.rjnr.pocketnode.data.database.AppDatabase
import com.rjnr.pocketnode.data.database.DatabaseMaintenanceUtil
import com.rjnr.pocketnode.data.database.dao.BalanceCacheDao
import com.rjnr.pocketnode.data.database.dao.DaoCellDao
import com.rjnr.pocketnode.data.database.dao.KeyMaterialDao
import com.rjnr.pocketnode.data.database.dao.TransactionDao
import com.rjnr.pocketnode.data.database.dao.WalletDao
import com.rjnr.pocketnode.data.database.entity.WalletEntity
import androidx.room.withTransaction
import com.rjnr.pocketnode.data.gateway.models.NetworkType
import com.rjnr.pocketnode.data.gateway.models.SyncMode
import com.rjnr.pocketnode.data.migration.WalletKeyBundle
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
    private val daoCellDao: DaoCellDao,
    private val keyMaterialDao: KeyMaterialDao,
    private val subAccountCandidateDao: com.rjnr.pocketnode.data.database.dao.SubAccountCandidateDao,
    private val subAccountDiscovery: SubAccountDiscovery,
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
     * Create a new mnemonic wallet. Persistence of key material is delegated
     * to the supplied [persistKeys] closure, which the caller wires to
     * [WalletKeyWriter.persistNewWallet] (the Activity-aware V2 writer).
     *
     * The closure receives the [walletId] generated here (so the writer can
     * pass it to `writeNewV2Row`) and a plaintext [WalletKeyBundle] produced
     * via [KeyManager.encodePlaintextBundle]. On non-Success a
     * [WalletKeyWriter.PersistException] is thrown so callers can dispatch on
     * the typed [WalletKeyWriter.Result] via `result.onFailure { }`.
     *
     * NOTE: this no longer returns the freshly generated mnemonic. Callers
     * that need to display it must split the flow into mnemonic-first
     * generation + an explicit import call.
     */
    suspend fun createWallet(
        name: String,
        persistKeys: suspend (walletId: String, bundle: WalletKeyBundle) -> WalletKeyWriter.Result,
        wordCount: MnemonicManager.WordCount = MnemonicManager.WordCount.TWELVE,
    ): Result<WalletEntity> = runCatching {
        validateUniqueName(name)

        val words = mnemonicManager.generateMnemonic(wordCount)
        val privateKey = mnemonicManager.mnemonicToPrivateKey(words)
        val info = keyManager.deriveWalletInfo(privateKey)
        val walletId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val colorIndex = walletDao.count() % 8

        val bundle = keyManager.encodePlaintextBundle(privateKey, words)
        val persistResult = persistKeys(walletId, bundle)
        if (persistResult !is WalletKeyWriter.Result.Success) {
            throw WalletKeyWriter.PersistException(persistResult)
        }

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

        try {
            walletDao.deactivateAll()
            walletDao.insert(entity)
            walletPreferences.setActiveWalletId(walletId)
            markFreshWalletSyncMode(walletId)
        } catch (e: Throwable) {
            Log.e(TAG, "Post-persist entity insert failed for $walletId; attempting rollback", e)
            runCatching { keyMaterialDao.delete(walletId) }
                .onFailure { Log.e(TAG, "Rollback delete failed for $walletId", it) }
            throw e
        }

        Log.d(TAG, "Created wallet: ${entity.walletId} (${entity.name})")
        entity
    }

    /**
     * Import a wallet from a mnemonic phrase. See [createWallet] for the
     * callback-based persistence contract.
     */
    suspend fun importFromMnemonic(
        words: List<String>,
        name: String,
        persistKeys: suspend (walletId: String, bundle: WalletKeyBundle) -> WalletKeyWriter.Result,
        passphrase: String = "",
    ): Result<WalletEntity> = runCatching {
        validateUniqueName(name)
        require(mnemonicManager.validateMnemonic(words)) { "Invalid mnemonic" }
        val privateKey = mnemonicManager.mnemonicToPrivateKey(words, passphrase)
        val info = keyManager.deriveWalletInfo(privateKey)
        validateUniqueAddress(info.mainnetAddress, info.testnetAddress)
        val walletId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val colorIndex = walletDao.count() % 8

        val bundle = keyManager.encodePlaintextBundle(privateKey, words)
        val persistResult = persistKeys(walletId, bundle)
        if (persistResult !is WalletKeyWriter.Result.Success) {
            throw WalletKeyWriter.PersistException(persistResult)
        }

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

        try {
            walletDao.deactivateAll()
            walletDao.insert(entity)
            walletPreferences.setActiveWalletId(walletId)
        } catch (e: Throwable) {
            Log.e(TAG, "Post-persist entity insert failed for $walletId; attempting rollback", e)
            runCatching { keyMaterialDao.delete(walletId) }
                .onFailure { Log.e(TAG, "Rollback delete failed for $walletId", it) }
            throw e
        }

        // #82 phase 1: record derivable sub-account slots while the mnemonic
        // is in memory. Args only, no keys. Never allowed to fail the import —
        // discovery is an enhancement, the wallet row above is the product.
        runCatching {
            val now2 = System.currentTimeMillis()
            subAccountCandidateDao.insertAll(
                subAccountDiscovery.deriveCandidates(words, passphrase).map {
                    com.rjnr.pocketnode.data.database.entity.SubAccountCandidateEntity(
                        parentWalletId = walletId,
                        derivationPath = it.derivationPath,
                        accountIndex = it.accountIndex,
                        scriptArgs = it.scriptArgs,
                        createdAt = now2,
                    )
                }
            )
        }.onFailure { Log.w(TAG, "Sub-account candidate derivation failed (non-fatal)", it) }

        Log.d(TAG, "Imported wallet: ${entity.walletId} (${entity.name})")
        entity
    }

    /**
     * Import a wallet from a raw private key hex string. See [createWallet]
     * for the callback-based persistence contract.
     */
    suspend fun importRawKey(
        privateKeyHex: String,
        name: String,
        persistKeys: suspend (walletId: String, bundle: WalletKeyBundle) -> WalletKeyWriter.Result,
    ): Result<WalletEntity> = runCatching {
        validateUniqueName(name)
        val privateKeyBytes = Numeric.hexStringToByteArray(privateKeyHex)
        require(privateKeyBytes.size == 32) { "Private key must be 32 bytes" }
        val info = keyManager.deriveWalletInfo(privateKeyBytes)
        validateUniqueAddress(info.mainnetAddress, info.testnetAddress)
        val walletId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val colorIndex = walletDao.count() % 8

        val bundle = keyManager.encodePlaintextBundle(privateKeyBytes, mnemonic = null)
        val persistResult = persistKeys(walletId, bundle)
        if (persistResult !is WalletKeyWriter.Result.Success) {
            throw WalletKeyWriter.PersistException(persistResult)
        }

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

        try {
            walletDao.deactivateAll()
            walletDao.insert(entity)
            walletPreferences.setActiveWalletId(walletId)
        } catch (e: Throwable) {
            Log.e(TAG, "Post-persist entity insert failed for $walletId; attempting rollback", e)
            runCatching { keyMaterialDao.delete(walletId) }
                .onFailure { Log.e(TAG, "Rollback delete failed for $walletId", it) }
            throw e
        }

        Log.d(TAG, "Imported raw key wallet: ${entity.walletId} (${entity.name})")
        entity
    }

    /**
     * Create a sub-account derived from a parent mnemonic wallet.
     *
     * The parent mnemonic is now a mandatory parameter — callers must
     * pre-unlock the parent via [WalletKeyReader.readKeyMaterial] (which
     * fires its own BiometricPrompt) and pass the recovered words in. The
     * previous fallback that silently read V1 storage (and crashed on V2
     * parents — #213 sub-PR 5) is gone.
     *
     * Persistence of the new sub-account's key material flows through
     * [persistKeys] (same callback contract as [createWallet]).
     */
    suspend fun createSubAccount(
        parentWalletId: String,
        name: String,
        parentMnemonic: List<String>,
        explicitIndex: Int? = null,
        persistKeys: suspend (walletId: String, bundle: WalletKeyBundle) -> WalletKeyWriter.Result,
    ): Result<WalletEntity> = runCatching {
        val parent = walletDao.getById(parentWalletId)
            ?: throw IllegalArgumentException("Parent wallet not found")
        require(parent.type == KeyManager.WALLET_TYPE_MNEMONIC) {
            "Sub-accounts require a mnemonic wallet"
        }

        val existingSubs = walletDao.getSubAccountsList(parentWalletId)
        // explicitIndex = discovery restore (#82): recreate the account at the
        // exact index whose script showed on-chain history. Default path keeps
        // the contiguous max+1 scheme.
        val nextIndex = if (explicitIndex != null) {
            require(explicitIndex >= 1) { "Sub-account index must be >= 1" }
            require(existingSubs.none { it.accountIndex == explicitIndex }) {
                "Sub-account index $explicitIndex already exists"
            }
            explicitIndex
        } else {
            if (existingSubs.isEmpty()) 1 else existingSubs.maxOf { it.accountIndex } + 1
        }
        val seed = mnemonicManager.mnemonicToSeed(parentMnemonic)
        val privateKey = mnemonicManager.derivePrivateKey(seed, accountIndex = nextIndex)
        val publicKey = keyManager.derivePublicKey(privateKey)
        val lockScript = keyManager.deriveLockScript(publicKey)
        val mainnetAddress = AddressUtils.encode(lockScript, NetworkType.MAINNET)
        val testnetAddress = AddressUtils.encode(lockScript, NetworkType.TESTNET)

        val walletId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val colorIndex = walletDao.count() % 8

        // Sub-accounts don't store mnemonic — only the parent holds it.
        val bundle = keyManager.encodePlaintextBundle(privateKey, mnemonic = null)
        val persistResult = persistKeys(walletId, bundle)
        if (persistResult !is WalletKeyWriter.Result.Success) {
            throw WalletKeyWriter.PersistException(persistResult)
        }

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

        try {
            walletDao.deactivateAll()
            walletDao.insert(entity)
            walletPreferences.setActiveWalletId(walletId)
            if (explicitIndex != null) {
                // Discovery restore: the account has on-chain HISTORY — fresh
                // from-tip sync would hide exactly what made it discoverable.
                // Inherit the parent's sync window per network instead.
                for (net in listOf(NetworkType.MAINNET, NetworkType.TESTNET)) {
                    walletPreferences.setSyncMode(
                        walletPreferences.getSyncMode(net, parentWalletId), net, walletId
                    )
                    walletPreferences.setCustomBlockHeight(
                        walletPreferences.getCustomBlockHeight(net, parentWalletId), net, walletId
                    )
                }
                // Also inherit the parent's sync PROGRESS. The candidate
                // script already scanned the window alongside the parent
                // during discovery (its matched txs sit in light-client
                // storage); without a progress row the fresh wallet
                // registered from the window start and re-scanned ~200k
                // blocks the device had just covered (device-test 2026-07).
                runCatching {
                    val spDao = appDatabase.syncProgressDao()
                    for (net in listOf(NetworkType.MAINNET, NetworkType.TESTNET)) {
                        spDao.get(parentWalletId, net.name)?.let { parentRow ->
                            spDao.upsert(
                                parentRow.copy(
                                    walletId = walletId,
                                    updatedAt = System.currentTimeMillis(),
                                )
                            )
                        }
                    }
                }.onFailure { Log.w(TAG, "Progress inherit failed (non-fatal)", it) }
            } else {
                markFreshWalletSyncMode(walletId)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Post-persist sub-account insert failed for $walletId; attempting rollback", e)
            runCatching { keyMaterialDao.delete(walletId) }
                .onFailure { Log.e(TAG, "Rollback delete failed for $walletId", it) }
            throw e
        }

        // Retire the discovery candidate at this index if one exists (any
        // creation at index N supersedes the candidate row; IGNORE-on-insert
        // means a plain no-candidate create is a harmless 0-row update).
        runCatching {
            subAccountCandidateDao.updateState(
                parentWalletId, SubAccountDiscovery.accountPath(nextIndex),
                com.rjnr.pocketnode.data.database.entity.SubAccountCandidateEntity.STATE_RESTORED,
            )
        }

        Log.d(TAG, "Created sub-account: $walletId (parent: $parentWalletId, index: $nextIndex)")
        entity
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

    /**
     * Destructive recovery path for the Forgot-PIN flow. Wipes every
     * wallet (parents + sub-accounts), all wallet-scoped Room caches,
     * and all stored key material from the device. Does NOT touch the
     * PIN itself ([PinManager.removePin] is the caller's responsibility)
     * or the process JNI state (the caller should restart the process
     * after this returns to flush the embedded light client).
     *
     * The user's funds remain on-chain — only their seed phrase can
     * restore the wallet after this runs.
     *
     * Implementation notes
     *
     *   - Iterates the wallet list rather than truncating the Room
     *     tables because individual [deleteWallet] calls handle each
     *     wallet's keys, caches, and sub-accounts in a transaction.
     *     A bulk DELETE would orphan key material under
     *     EncryptedSharedPreferences / Room key_material.
     *   - Active-wallet guard in [deleteWallet] is bypassed by
     *     clearing the active-wallet preference first; this is the
     *     only place where that guard is intentionally skipped.
     */
    suspend fun factoryReset() {
        // Clear both the preference pointer and the DB row's `isActive`
        // flag up front so the per-wallet delete loop doesn't re-throw
        // "Cannot delete the active wallet" on the currently-active row.
        walletPreferences.clearActiveWalletId()
        walletDao.deactivateAll()
        // Snapshot the wallet list before mutation; iterating the live
        // result would skip rows as deletions land.
        val parents = walletDao.getAll().filter { it.parentWalletId == null }
        for (parent in parents) {
            val subs = walletDao.getSubAccountsList(parent.walletId)
            for (sub in subs) {
                runCatching { deleteWallet(sub.walletId) }
                    .onFailure { Log.w(TAG, "factoryReset: sub ${sub.walletId} delete failed", it) }
            }
            runCatching { deleteWallet(parent.walletId) }
                .onFailure { Log.w(TAG, "factoryReset: parent ${parent.walletId} delete failed", it) }
        }
        Log.d(TAG, "factoryReset: ${parents.size} parent wallet(s) wiped")
    }
}
