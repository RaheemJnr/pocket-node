package com.rjnr.pocketnode.ui.screens.dao

import android.app.Application
import android.content.ComponentName
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.rjnr.pocketnode.data.gateway.models.*
import com.rjnr.pocketnode.ui.screens.auth.NamedStringsActivity
import com.rjnr.pocketnode.ui.screens.dao.components.DaoDepositCard
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class DaoDepositCardCountdownTest {
    private val compose = createAndroidComposeRule<NamedStringsActivity>()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(object : ExternalResource() {
        override fun before() {
            val context = ApplicationProvider.getApplicationContext<Application>()
            shadowOf(context.packageManager).addActivityIfNotPresent(ComponentName(context, NamedStringsActivity::class.java))
        }
    }).around(compose)
    private val position = DaoDeposit(OutPoint("0xabc", "0x0"), 100_000_000_000,
        DaoCellStatus.LOCKED, 100, unlockEpoch = EpochInfo(200, 0, 1800), lockRemainingHours = 0)

    @Test fun `active recovery shows checking while pending completed and outside window retain identity`() {
        val current = mutableStateOf(position.copy(unlockEpoch = null, outsideSyncWindow = true))
        compose.setContent { DaoDepositCard(current.value, {}, {}, checkingWithdrawal = true) }
        compose.onNodeWithText("dao_checking_withdrawal_details").assertExists()
        compose.onNodeWithText("dao_card_unlock").assertDoesNotExist()
        compose.runOnIdle { current.value = current.value.copy(status = DaoCellStatus.UNLOCKING) }
        compose.onNodeWithText("Confirming...").assertExists()
        compose.onNodeWithText("dao_checking_withdrawal_details").assertDoesNotExist()
        compose.runOnIdle { current.value = current.value.copy(status = DaoCellStatus.COMPLETED) }
        compose.onNodeWithText("Completed").assertExists()
        compose.onNodeWithText("dao_checking_withdrawal_details").assertDoesNotExist()
    }

    @Test fun `unavailable withdrawal details stay blocked after checking ends`() {
        val checking = mutableStateOf(true)
        compose.setContent { DaoDepositCard(position.copy(withdrawBlockNumber = 200, unlockEpoch = null), {}, {}, checkingWithdrawal = checking.value) }
        compose.onNodeWithText("dao_checking_withdrawal_details").assertExists()
        compose.runOnIdle { checking.value = false }
        compose.onNodeWithText("dao_withdrawal_details_unavailable").assertExists()
        compose.onNodeWithText("dao_card_unlock").assertDoesNotExist()
    }

    @Test fun `known zero hour locked wait uses localized sub-hour copy`() {
        compose.setContent { DaoDepositCard(position, {}, {}) }
        compose.onNodeWithText("dao_unlock_less_than_hour").assertExists()
        compose.onNodeWithText("Withdrawal in progress · Unlock in 0h").assertDoesNotExist()
        compose.onNodeWithText("dao_card_unlock").assertDoesNotExist()
    }

    @Test fun `unknown timing keeps waiting for evidence`() {
        val current = mutableStateOf(position.copy(unlockEpoch = null))
        compose.setContent { DaoDepositCard(current.value, {}, {}) }
        compose.onNodeWithText("dao_waiting_deposit_details").assertExists()
        compose.runOnIdle { current.value = position.copy(lockRemainingHours = null) }
        compose.onNodeWithText("dao_waiting_deposit_details").assertExists()
    }

    @Test fun `whole hour day and nonlocked statuses retain their copy`() {
        val current = mutableStateOf(position.copy(lockRemainingHours = 1))
        compose.setContent { DaoDepositCard(current.value, {}, {}) }
        for ((hours, text) in listOf(1 to "1h", 24 to "1d", 25 to "1d 1h")) {
            compose.runOnIdle { current.value = position.copy(lockRemainingHours = hours) }
            compose.onNodeWithText("Withdrawal in progress · Unlock in $text").assertExists()
        }
        compose.runOnIdle { current.value = position.copy(status = DaoCellStatus.UNLOCKABLE) }
        compose.onNodeWithText("Ready to unlock!").assertExists()
        compose.runOnIdle { current.value = position.copy(status = DaoCellStatus.UNLOCKING) }
        compose.onNodeWithText("Confirming...").assertExists()
    }
}
