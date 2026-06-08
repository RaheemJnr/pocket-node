package com.rjnr.pocketnode.data.migration

import android.util.Log
import androidx.room.withTransaction
import com.rjnr.pocketnode.data.database.AppDatabase
import com.rjnr.pocketnode.data.database.dao.SyncProgressDao
import com.rjnr.pocketnode.data.database.dao.WalletDao
import com.rjnr.pocketnode.data.database.entity.SyncProgressEntity
import com.rjnr.pocketnode.data.database.entity.WalletEntity
import com.rjnr.pocketnode.data.gateway.models.NetworkType
import com.rjnr.pocketnode.data.wallet.KeyManager
import com.rjnr.pocketnode.data.wallet.WalletPreferences
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "WalletMigrationHelper"

/**
 * Idempotent migration from single-wallet (global EncryptedSharedPreferences)
 * to multi-wallet (Room + wallet-scoped prefs).
 *
 * Runs once at startup. If the wallets table is empty but the legacy KeyManager
 * has a private key, it creates a WalletEntity, copies keys to wallet-scoped prefs,
 * and backfills walletId in existing cached data.
 */
@Singleton
class WalletMigrationHelper @Inject constructor(
    private val walletDao: WalletDao,
    private val keyManager: KeyManager,
    private val walletPreferences: WalletPreferences,
    private val database: AppDatabase,
    private val syncProgressDao: SyncProgressDao,
    private val keyStoreMigrationHelper: KeyStoreMigrationHelper,
) {
    /**
     * Migrate the legacy single-wallet to the multi-wallet Room table.
     * No-op if migration has already run (wallets table is non-empty).
     *
     * Post-#289 note: writes a V1 (kdfVersion=1) key_material row by calling
     * [KeyStoreMigrationHelper.migrateWallet] directly. This is the ONLY
     * remaining V1-write path in the app — it exists exclusively for the
     * legacy ESP-to-Room migration on first cold start after an upgrade
     * from v1.6.x or earlier. Fresh installs from v1.7.2+ never enter
     * this branch (the wallets table is empty AND there's no legacy ESP
     * material). The runner-driven V2 migration ([KeystoreV2MigrationRunner])
     * picks up the freshly-written V1 row on the next cold start (or
     * immediately, depending on which migration runs first) and upgrades
     * it to V2 via an authenticated Cipher.
     *
     * Why not call the V2 writer directly here? It requires an Activity
     * for BiometricPrompt, which this helper does not have. Routing legacy
     * upgrades through V1 → V2 keeps this path Activity-free without
     * regressing the user-facing migration UX.
     */
    suspend fun migrateIfNeeded() {
        if (walletDao.count() > 0) return  // already migrated
        if (!keyManager.hasWallet()) return // no legacy wallet to migrate

        Log.d(TAG, "Migrating legacy single-wallet to multi-wallet schema")

        try {
            val info = keyManager.getWalletInfo()
            val privateKey = keyManager.getPrivateKey()
            val mnemonic = keyManager.getMnemonic()
            val walletType = keyManager.getWalletType()
            val walletId = UUID.randomUUID().toString()
            val mnemonicBackedUp = keyManager.hasMnemonicBackup()

            // Write a V1 row directly. See class-level KDoc on migrateIfNeeded
            // for why we don't go through WalletKeyWriter here.
            val privateKeyHex = privateKey.joinToString("") { "%02x".format(it) }
            keyStoreMigrationHelper.migrateWallet(
                walletId = walletId,
                privateKeyHex = privateKeyHex,
                mnemonic = mnemonic?.joinToString(" "),
                walletType = walletType,
                mnemonicBackedUp = mnemonicBackedUp,
            )

            val entity = WalletEntity(
                walletId = walletId,
                name = "Primary Wallet",
                type = walletType,
                derivationPath = if (walletType == KeyManager.WALLET_TYPE_MNEMONIC) "m/44'/309'/0'/0/0" else null,
                parentWalletId = null,
                accountIndex = 0,
                mainnetAddress = info.mainnetAddress,
                testnetAddress = info.testnetAddress,
                isActive = true,
                createdAt = System.currentTimeMillis()
            )

            walletDao.insert(entity)

            // Backfill walletId in existing cached data
            val db = database.openHelper.writableDatabase
            db.execSQL("UPDATE transactions SET walletId = ? WHERE walletId = ''", arrayOf(walletId))
            db.execSQL("UPDATE balance_cache SET walletId = ? WHERE walletId = ''", arrayOf(walletId))
            db.execSQL("UPDATE dao_cells SET walletId = ? WHERE walletId = ''", arrayOf(walletId))

            // Set active wallet ID in preferences
            walletPreferences.setActiveWalletId(walletId)

            Log.d(TAG, "Migration complete: created wallet $walletId")
        } catch (e: Exception) {
            Log.e(TAG, "Migration failed — legacy wallet intact, will retry next launch", e)
        }
    }

    /**
     * Idempotent migration: copy per-wallet `lastSyncedBlock` from SharedPreferences
     * to the v7 `sync_progress` Room table, then delete the prefs keys.
     * Returns true if the migration ran (regardless of whether rows were written),
     * false if the guard flag was already set.
     *
     * Key format read from SharedPrefs: "${walletId}_${network.lowercase()}_last_synced_block"
     * (matches WalletPreferences.walletNetworkKey at WalletPreferences.kt:78-79, 144).
     *
     * Migrated rows seed `lightStartBlockNumber = localSavedBlockNumber` because
     * the original start block was never recorded.
     */
    suspend fun migrateSyncProgressToRoomIfNeeded(): Boolean {
        if (walletPreferences.isSyncProgressMigratedToRoom()) return false

        val now = System.currentTimeMillis()
        val wallets = walletDao.getAll()
        val networks = listOf(NetworkType.MAINNET, NetworkType.TESTNET)

        database.withTransaction {
            for (wallet in wallets) {
                for (net in networks) {
                    val block = walletPreferences.getLegacySyncedBlock(wallet.walletId, net)
                        ?: continue
                    syncProgressDao.upsert(
                        SyncProgressEntity(
                            walletId = wallet.walletId,
                            network = net.name,
                            lightStartBlockNumber = block,
                            localSavedBlockNumber = block,
                            updatedAt = now
                        )
                    )
                }
            }
        }

        // Atomic: removes every legacy key + sets the guard flag in one commit.
        // Runs AFTER the Room txn commits so a crash mid-write leaves the guard
        // unset and the migration retries safely on next launch.
        walletPreferences.clearLegacySyncedBlocksAndMarkMigrated(
            wallets.map { it.walletId },
            networks
        )

        Log.d(TAG, "sync_progress migration complete: ${wallets.size} wallets x ${networks.size} networks")
        return true
    }
}
