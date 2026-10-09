package com.rjnr.pocketnode.data.gateway

import com.rjnr.pocketnode.core.log.NoopLogger
import com.rjnr.pocketnode.data.database.AppDatabase
import com.rjnr.pocketnode.data.database.dao.SyncProgressDao
import com.rjnr.pocketnode.data.migration.WalletMigrationHelper
import com.rjnr.pocketnode.data.sync.SyncEngine
import com.rjnr.pocketnode.data.wallet.KeyManager
import com.rjnr.pocketnode.data.wallet.WalletPreferences
import io.mockk.mockk
import kotlinx.serialization.json.Json

/**
 * A real [GatewayRepository] over an in-memory [AppDatabase] and real
 * [WalletPreferences], with every other collaborator a relaxed mock unless
 * passed in. Lets a test drive the repository's own `Result` values, which
 * MockK 1.13.16 cannot return from a mocked suspend function (a stubbed
 * `Result.failure` arrives double-boxed and reads as success).
 *
 * Nothing here loads the JNI library as long as the code path under test
 * stays on the injected seams ([NodeLifecycle], [LightClientReadOnly],
 * [SyncCoordinator]'s [LightClientApi]).
 */
internal fun testGatewayRepository(
    db: AppDatabase,
    walletPreferences: WalletPreferences,
    nodeLifecycle: NodeLifecycle,
    syncCoordinator: SyncCoordinator = mockk(relaxed = true),
    keyManager: KeyManager = mockk(relaxed = true),
    lightClient: LightClientReadOnly = mockk(relaxed = true),
    syncEngine: SyncEngine = mockk(relaxed = true),
    daoGateway: DaoGateway = mockk(relaxed = true),
    ledgerReader: LedgerReader = mockk(relaxed = true),
    json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
    syncProgressDao: SyncProgressDao = db.syncProgressDao(),
    walletMigrationHelper: WalletMigrationHelper = mockk(relaxed = true),
): GatewayRepository = GatewayRepository(
    keyManager = keyManager,
    walletPreferences = walletPreferences,
    json = json,
    cacheManager = mockk(relaxed = true),
    walletMigrationHelper = walletMigrationHelper,
    walletDao = db.walletDao(),
    appDatabase = mockk(relaxed = true),
    syncProgressDao = syncProgressDao,
    sendPipeline = mockk(relaxed = true),
    syncCoordinator = syncCoordinator,
    daoGateway = daoGateway,
    gapLimitGateway = mockk(relaxed = true),
    lightClient = lightClient,
    ledgerReader = ledgerReader,
    subAccountReconciler = mockk(relaxed = true),
    syncServiceCommands = mockk(relaxed = true),
    nodeLifecycle = nodeLifecycle,
    syncEngine = syncEngine,
    startupReconciler = mockk(relaxed = true),
    logger = NoopLogger,
)
