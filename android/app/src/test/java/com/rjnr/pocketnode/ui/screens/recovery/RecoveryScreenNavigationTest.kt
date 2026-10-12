package com.rjnr.pocketnode.ui.screens.recovery

import android.app.Application
import android.content.ComponentName
import android.content.Context
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.rjnr.pocketnode.core.log.NoopLogger
import com.rjnr.pocketnode.data.auth.AuthManager
import com.rjnr.pocketnode.data.auth.PinManager
import com.rjnr.pocketnode.data.crypto.Blake2b
import com.rjnr.pocketnode.data.crypto.KeyBackupManager
import com.rjnr.pocketnode.data.crypto.KeyMaterial
import com.rjnr.pocketnode.data.gateway.LocalWalletReset
import com.rjnr.pocketnode.data.database.entity.WalletEntity
import com.rjnr.pocketnode.data.wallet.RecoveredKeyRestorer
import com.rjnr.pocketnode.ui.screens.auth.NamedStringsActivity
import com.rjnr.pocketnode.util.AppRestarter
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Every way out of the Recovery screen, as the nav graph wires it: its only
 * exits are `onRecoveryComplete` (after the backup PIN) and
 * `onMnemonicRestore` (the phrase restore, which then reaches Home). With an
 * app PIN set, the phrase restore opens only after that PIN, or after the
 * wallets and the PIN were removed. Strings show as their resource names
 * (see [NamedStringsActivity]).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, qualifiers = "w411dp-h1000dp")
class RecoveryScreenNavigationTest {

    private val compose = createAndroidComposeRule<NamedStringsActivity>()
    private val tempDir = TemporaryFolder()

    @get:Rule
    val rules: RuleChain = RuleChain
        .outerRule(tempDir)
        .around(object : ExternalResource() {
            override fun before() {
                val context = ApplicationProvider.getApplicationContext<Application>()
                shadowOf(context.packageManager).addActivityIfNotPresent(
                    ComponentName(context, NamedStringsActivity::class.java)
                )
            }
        })
        .around(compose)

    private val localWalletReset = mockk<LocalWalletReset>(relaxed = true)
    private val appRestarter = mockk<AppRestarter>(relaxed = true)
    // wallet1 is the one wallet row; it stays missing until restored.
    private val missing = mutableSetOf("wallet1")
    private val walletRow = WalletEntity(
        walletId = "wallet1", name = "Main", type = "mnemonic", derivationPath = null, parentWalletId = null,
    )
    private val keyRestorer = mockk<RecoveredKeyRestorer>(relaxed = true).also {
        coEvery { it.missingWallets() } answers { if ("wallet1" in missing) listOf(walletRow) else emptyList() }
        coEvery { it.walletIds() } returns setOf("wallet1")
        coEvery { it.completeIfDone() } answers { missing.isEmpty() }
        coEvery { it.restore(any(), any(), any(), any()) } answers {
            missing.remove(secondArg<String>())
            RecoveredKeyRestorer.Outcome.Restored
        }
    }
    private var phraseRestores = 0
    private var recoveries = 0
    private var fakeTimeMs = 1_000_000L
    private val material = KeyMaterial(
        privateKey = "a".repeat(64),
        mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about",
        walletType = "mnemonic",
        mnemonicBackedUp = true,
        createdAt = "2026-01-01T00:00:00Z"
    )

    private fun pinManager(pin: String?): PinManager {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        return PinManager(ctx, Blake2b(), NoopLogger).apply {
            testPrefs = ctx.getSharedPreferences("test_recovery_nav_pin", Context.MODE_PRIVATE)
            testPrefs!!.edit().clear().commit()
            timeProvider = { fakeTimeMs }
            argon2Iterations = 1
            argon2MemoryKb = 8
            argon2Parallelism = 1
            if (pin != null) setPin(pin)
        }
    }

    private fun show(pinManager: PinManager, withBackup: Boolean = false) {
        val backups = KeyBackupManager(tempDir.root, NoopLogger).apply {
            argon2Iterations = 1
            argon2MemoryKb = 8
            argon2Parallelism = 1
            if (withBackup) writeBackup("wallet1", material, "123456".toCharArray())
        }
        val vm = RecoveryViewModel(
            backups,
            pinManager,
            AuthManager(ApplicationProvider.getApplicationContext()),
            localWalletReset,
            appRestarter,
            keyRestorer,
            Dispatchers.Unconfined,
        )
        compose.setContent {
            RecoveryScreen(
                onRecoveryComplete = { recoveries++ },
                onMnemonicRestore = { phraseRestores++ },
                viewModel = vm,
            )
        }
        compose.waitForIdle()
    }

    private fun enterPin(pin: String) {
        compose.onNode(hasSetTextAction()).performTextInput(pin)
        compose.onNodeWithText("recovery_phrase_pin_continue").performClick()
        compose.waitForIdle()
    }

    @Test
    fun `with a PIN set the phrase button leaves Recovery only after the right PIN`() {
        show(pinManager("123456"))

        compose.onNodeWithText("recovery_enter_phrase").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("recovery_phrase_pin_message").assertExists()
        assertEquals(0, phraseRestores)

        enterPin("999999")
        assertEquals(0, phraseRestores)

        // Cancel goes back to the phrase button, still on Recovery.
        compose.onNodeWithText("recovery_phrase_pin_cancel").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("recovery_enter_phrase").performClick()
        compose.waitForIdle()
        assertEquals(0, phraseRestores)

        enterPin("123456")
        assertEquals(1, phraseRestores)
        assertEquals(0, recoveries)
        coVerify(exactly = 0) { localWalletReset.removeAllWalletsAndPin() }
    }

    @Test
    fun `on the backup PIN stage, Use recovery phrase instead also needs the PIN`() {
        show(pinManager("123456"), withBackup = true)
        compose.onNodeWithText("recovery_with_pin").assertExists()

        compose.onNodeWithText("recovery_use_phrase_instead").performClick()
        compose.waitForIdle()
        assertEquals(0, phraseRestores)
        compose.onNodeWithText("recovery_phrase_pin_message").assertExists()

        enterPin("123456")
        assertEquals(1, phraseRestores)
        assertEquals(0, recoveries)
    }

    @Test
    fun `with the PIN locked for good the phrase button leaves Recovery only through the wipe and a restart`() {
        val pins = pinManager("123456")
        val windows = longArrayOf(0, 0, 0, 0, 31_000, 61_000, 301_000, 1_801_000, 3_601_000, 3_601_000)
        for (w in windows) {
            fakeTimeMs += w
            pins.verifyPin("999999")
        }
        assertTrue(pins.isPermanentlyLocked())
        coEvery { localWalletReset.removeAllWalletsAndPin() } coAnswers { pins.removePin(force = true) }
        show(pins)

        compose.onNodeWithText("recovery_enter_phrase").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("recovery_start_fresh_title").assertExists()
        compose.onNodeWithText("recovery_start_fresh_cancel").performClick()
        compose.waitForIdle()
        assertEquals(0, phraseRestores)
        assertTrue(pins.hasPin())

        compose.onNodeWithText("recovery_enter_phrase").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("recovery_start_fresh_confirm").performClick()
        compose.waitForIdle()

        // The app restarts on a clean slate; nothing opens in this process.
        assertEquals(0, phraseRestores)
        verify(exactly = 1) { appRestarter.restart() }
        coVerify(exactly = 1) { localWalletReset.removeAllWalletsAndPin() }
        assertFalse(pins.hasPin())
    }

    @Test
    fun `with no PIN set the phrase button opens the phrase restore as before`() {
        show(pinManager(null))

        compose.onNodeWithText("recovery_enter_phrase").performClick()
        compose.waitForIdle()

        assertEquals(1, phraseRestores)
        coVerify(exactly = 0) { localWalletReset.removeAllWalletsAndPin() }
    }

    private fun enterBackupPin(pin: String) {
        compose.onNode(hasSetTextAction()).performTextInput(pin)
        compose.onNodeWithText("recovery_with_pin").performClick()
        compose.waitForIdle()
    }

    @Test
    fun `the backup restore leaves Recovery only after the right PIN and the keys are written back`() {
        show(pinManager("123456"), withBackup = true)

        enterBackupPin("999999")
        assertEquals(0, recoveries)
        coVerify(exactly = 0) { keyRestorer.restore(any(), any(), any(), any()) }

        enterBackupPin("123456")
        assertEquals(1, recoveries)
        coVerify(exactly = 1) { keyRestorer.restore(any(), "wallet1", any(), any()) }
        coVerify(exactly = 1) { keyRestorer.completeIfDone() }
    }

    @Test
    fun `a backup that does not match its wallet stays on Recovery with the phrase restore offered`() {
        coEvery { keyRestorer.restore(any(), any(), any(), any()) } returns
            RecoveredKeyRestorer.Outcome.AddressMismatch
        show(pinManager("123456"), withBackup = true)

        enterBackupPin("123456")

        assertEquals(0, recoveries)
        compose.onNodeWithText("recovery_missing_wallets").assertExists()
        compose.onNodeWithText("recovery_restore_retry").assertDoesNotExist()
        compose.onNodeWithText("recovery_restore_with_phrase").performClick()
        compose.waitForIdle()
        // The PIN was verified on this screen, so the phrase restore opens.
        assertEquals(1, phraseRestores)
    }

    @Test
    fun `with no screen lock the import consent dialog shows before anything is stored`() {
        every { keyRestorer.needsNoLockConsent() } returns true
        show(pinManager("123456"), withBackup = true)

        enterBackupPin("123456")
        compose.onNodeWithText("Continue without a device lock?").assertExists()
        coVerify(exactly = 0) { keyRestorer.restore(any(), any(), any(), any()) }

        compose.onNodeWithText("Cancel").performClick()
        compose.waitForIdle()
        assertEquals(0, recoveries)
        coVerify(exactly = 0) { keyRestorer.restore(any(), any(), any(), any()) }
        // Try again goes back to the PIN: the decrypted backups were dropped.
        compose.onNodeWithText("recovery_restore_retry").performClick()
        compose.waitForIdle()
        enterBackupPin("123456")

        compose.onNodeWithText("Continue anyway").performClick()
        compose.waitForIdle()
        coVerify(exactly = 1) { keyRestorer.restore(any(), "wallet1", any(), true) }
        assertEquals(1, recoveries)
    }
}
