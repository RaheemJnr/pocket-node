package com.rjnr.pocketnode.data.gateway

import com.rjnr.pocketnode.core.log.NoopLogger
import com.rjnr.pocketnode.data.database.AppDatabase
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
 * [SyncCoordinator]'s [LightClientBridge]).
 */
internal fun testGatewayRepository(
    db: AppDatabase,
    walletPreferences: WalletPreferences,
    nodeLifecycle: NodeLifecycle,
    syncCoordinator: SyncCoordinator = mockk(relaxed = true),
    keyManager: KeyManager = mockk(relaxed = true),
    lightClient: LightClientReadOnly = mockk(relaxed = true),
    syncPoller: SyncPoller = mockk(relaxed = true),
    json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
): GatewayRepository = GatewayRepository(
    keyManager = keyManager,
    walletPreferences = walletPreferences,
    json = json,
    transactionBuilder = mockk(relaxed = true),
    cacheManager = mockk(relaxed = true),
    daoSyncManager = mockk(relaxed = true),
    walletMigrationHelper = mockk(relaxed = true),
    walletDao = db.walletDao(),
    appDatabase = mockk(relaxed = true),
    headerCacheDao = mockk(relaxed = true),
    syncProgressDao = db.syncProgressDao(),
    pendingBroadcastDao = mockk(relaxed = true),
    broadcastClient = mockk(relaxed = true),
    syncCoordinator = syncCoordinator,
    daoHeaderResolver = mockk(relaxed = true),
    daoDepositReader = mockk(relaxed = true),
    lightClient = lightClient,
    subAccountReconciler = mockk(relaxed = true),
    subAccountDiscovery = mockk(relaxed = true),
    syncServiceCommands = mockk(relaxed = true),
    nodeLifecycle = nodeLifecycle,
    syncPoller = syncPoller,
    startupReconciler = mockk(relaxed = true),
    logger = NoopLogger,
)
