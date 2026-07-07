package com.rjnr.pocketnode.data.gateway

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.rjnr.pocketnode.data.database.AppDatabase
import com.rjnr.pocketnode.data.database.entity.SubAccountCandidateEntity
import com.rjnr.pocketnode.data.gateway.models.JniScriptStatus
import com.rjnr.pocketnode.data.gateway.models.Script
import com.rjnr.pocketnode.data.wallet.KeyManager
import com.rjnr.pocketnode.data.wallet.MnemonicManager
import com.rjnr.pocketnode.data.wallet.SubAccountDiscovery
import com.rjnr.pocketnode.data.wallet.WalletPreferences
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * #382: `registeredFromBlock` powers the reconciler's EMPTY coverage gate
 * but was never written by any production path — every candidate sat at 0
 * and could never retire. [SyncCoordinator.recordCandidateRegistrations]
 * closes that: after a successful CMD_SET_SCRIPTS_ALL, each included
 * candidate records the block it scans from, keep-min so a later shallower
 * registration never erases deeper coverage.
 */
@RunWith(RobolectricTestRunner::class)
class CandidateRegistrationRecordingTest {

    private lateinit var db: AppDatabase
    private lateinit var coordinator: SyncCoordinator

    private val parent = "parent-1"
    private val path = SubAccountDiscovery.chainPath(1, 4)

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val mnemonicManager = MnemonicManager()
        val fakeBridge = object : LightClientBridge {
            override suspend fun setScripts(scriptsJson: String, command: Int) = true
            override suspend fun getTipHeaderRaw(): String? = null
            override suspend fun getScriptsRaw(): String? = null
        }
        coordinator = SyncCoordinator(
            db.walletDao(),
            db.syncProgressDao(),
            WalletPreferences(context),
            KeyManager(context, mnemonicManager),
            Json { ignoreUnknownKeys = true },
            fakeBridge,
            db.subAccountCandidateDao(),
        )
    }

    @After
    fun tearDown() = db.close()

    private suspend fun seedCandidate() {
        db.subAccountCandidateDao().insertAll(
            listOf(
                SubAccountCandidateEntity(
                    parentWalletId = parent,
                    derivationPath = path,
                    accountIndex = 0,
                    scriptArgs = "0xcc",
                    createdAt = 1L,
                )
            )
        )
    }

    private fun registration(fromBlockHex: String) = SyncCoordinator.CandidateRegistration(
        candidate = SubAccountCandidateEntity(
            parentWalletId = parent,
            derivationPath = path,
            accountIndex = 0,
            scriptArgs = "0xcc",
            createdAt = 1L,
        ),
        status = JniScriptStatus(
            script = Script(Script.SECP256K1_CODE_HASH, "type", "0xcc"),
            scriptType = "lock",
            blockNumber = fromBlockHex,
        ),
    )

    private suspend fun storedFromBlock(): Long =
        db.subAccountCandidateDao().getForParent(parent).single().registeredFromBlock

    @Test
    fun `recording persists the scan-from block`() = runTest {
        seedCandidate()
        coordinator.recordCandidateRegistrations(listOf(registration("0x100")))
        assertEquals(256L, storedFromBlock())
    }

    @Test
    fun `keep-min - a shallower later registration never erases deeper coverage`() = runTest {
        seedCandidate()
        coordinator.recordCandidateRegistrations(listOf(registration("0x100")))
        coordinator.recordCandidateRegistrations(listOf(registration("0x9000")))
        assertEquals(256L, storedFromBlock())
        coordinator.recordCandidateRegistrations(listOf(registration("0x80")))
        assertEquals(128L, storedFromBlock())
    }

    @Test
    fun `unparseable block hex is skipped without touching the row`() = runTest {
        seedCandidate()
        coordinator.recordCandidateRegistrations(listOf(registration("not-hex")))
        assertEquals(0L, storedFromBlock())
    }
}
