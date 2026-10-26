package com.rjnr.pocketnode.ui.screens.send

import android.app.Application
import android.content.ComponentName
import android.content.res.Resources
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import com.rjnr.pocketnode.R
import com.rjnr.pocketnode.data.gateway.models.NetworkType
import com.rjnr.pocketnode.data.gateway.models.Script
import com.rjnr.pocketnode.data.send.ReviewedTransfer
import com.rjnr.pocketnode.data.transaction.RecipientOutput
import com.rjnr.pocketnode.data.wallet.AddressUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, qualifiers = "w360dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SendReviewSheetTest {
    private val rule = createAndroidComposeRule<ReviewHostActivity>()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(object : ExternalResource() {
        override fun before() {
            val context = ApplicationProvider.getApplicationContext<Application>()
            shadowOf(context.packageManager).addActivityIfNotPresent(ComponentName(context, ReviewHostActivity::class.java))
        }
    }).around(rule)

    private fun address(byte: Int) = AddressUtils.encode(
        Script("0x9bd7e06f3ecf4be0f2fcd2188b23f1b9fcc88e5d4b65a8637b17723bbda3cce8", "type", "0x" + "%02x".format(byte).repeat(20)),
        NetworkType.TESTNET,
    )

    private fun review(address: String, name: String? = null) = SendReview(
        isBulk = false, recipientAddress = address, recipientName = name, recipientCount = 1,
        amountShannons = 10_000_000_000, feeShannons = 1000, totalShannons = 10_000_001_000,
    )

    private fun assertFullWrappedAddress(address: String) {
        val node = rule.onNodeWithText(address).performScrollTo().assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        assertEquals(address, layout.layoutInput.text.text)
        assertTrue("A full address must wrap at phone width", layout.lineCount > 1)
        assertFalse("The address must not be clipped horizontally", layout.didOverflowWidth)
        assertFalse("The address must not be clipped vertically", layout.didOverflowHeight)
        assertFalse(layout.isLineEllipsized(layout.lineCount - 1))
    }

    @Test fun `same prefix addresses expose their complete differing args in approval`() {
        val first = address(0x11)
        val second = address(0x22)
        assertEquals(first.take(55), second.take(55))
        assertNotEquals(AddressUtils.parseAddress(first)!!.args, AddressUtils.parseAddress(second)!!.args)
        val current = mutableStateOf(review(first))
        rule.setContent { MaterialTheme { SendReviewSheet(current.value, {}, {}) } }
        assertFullWrappedAddress(first)
        rule.runOnIdle { current.value = review(second) }
        assertFullWrappedAddress(second)
        rule.onNodeWithText(first).assertDoesNotExist()
    }

    @Test fun `named contact retains complete address at large font scale and actions stay reachable`() {
        val recipient = address(0x33)
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                MaterialTheme { SendReviewSheet(review(recipient, "Alice"), {}, {}) }
            }
        }
        rule.onNodeWithText("Alice").performScrollTo().assertIsDisplayed()
        assertFullWrappedAddress(recipient)
        rule.onNodeWithText("Confirm").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Cancel").assertIsDisplayed()
    }

    @Test fun `custom entry indicator gives guidance without claiming validity`() {
        val custom = AddressUtils.encode(Script("0x" + "42".repeat(32), "data2", "0x" + "11".repeat(20)), NetworkType.TESTNET)
        rule.setContent { MaterialTheme { AddressValidationIndicator(custom, NetworkType.TESTNET) } }
        rule.onNodeWithText(com.rjnr.pocketnode.data.send.DestinationPolicy.CUSTOM_GUIDANCE).assertIsDisplayed()
    }

    @Test fun `single review displays custom guidance from its frozen transfer`() {
        val custom = AddressUtils.encode(Script("0x" + "42".repeat(32), "data2", "0x" + "11".repeat(20)), NetworkType.TESTNET)
        val intent = ReviewedTransfer("fixture", address(0x55), NetworkType.TESTNET,
            listOf(RecipientOutput(custom, 100_00000000L)), 1000, 1)
        rule.setContent { MaterialTheme { SendReviewSheet(review(custom).copy(transfers = listOf(intent)), {}, {}) } }
        assertFullWrappedAddress(custom)
        rule.onNodeWithText(com.rjnr.pocketnode.data.send.DestinationPolicy.CUSTOM_GUIDANCE).performScrollTo().assertIsDisplayed()
    }

    @Test fun `custom guidance follows each frozen recipient in bulk review`() {
        val custom = AddressUtils.encode(Script("0x" + "42".repeat(32), "data2", "0x" + "11".repeat(20)), NetworkType.TESTNET)
        val outputs = listOf(RecipientOutput(address(0x22), 100_00000000L), RecipientOutput(custom, 100_00000000L))
        val intent = ReviewedTransfer("fixture", address(0x55), NetworkType.TESTNET, outputs, 1000, 1, true)
        val frozen = SendReview(isBulk = true, transfers = listOf(intent), recipientAddress = "", recipientName = null,
            recipientCount = 2, amountShannons = 200_00000000L, feeShannons = 1000, totalShannons = 200_00001000L)
        rule.setContent { MaterialTheme { SendReviewSheet(frozen, {}, {}) } }
        assertFullWrappedAddress(custom)
        rule.onNodeWithText(com.rjnr.pocketnode.data.send.DestinationPolicy.CUSTOM_GUIDANCE).performScrollTo().assertIsDisplayed()
    }

    @Test fun `every frozen bulk recipient and individual amount can be inspected across batches`() {
        val recipients = (1..8).map { RecipientOutput(address(it), (100L + it) * 100_000_000) }
        val batches = recipients.chunked(3).map {
            ReviewedTransfer("fixture", address(0x55), NetworkType.TESTNET, it, 1000, 1, true)
        }
        val total = recipients.sumOf { it.amountShannons }
        val frozen = SendReview(
            isBulk = true, transfers = batches, recipientAddress = "", recipientName = null,
            recipientCount = recipients.size, amountShannons = total, feeShannons = 3000, totalShannons = total + 3000,
        )
        rule.setContent { MaterialTheme { SendReviewSheet(frozen, {}, {}) } }
        recipients.forEach { recipient ->
            assertFullWrappedAddress(recipient.address)
            rule.onNodeWithText("${formatReviewCkb(recipient.amountShannons)} CKB").performScrollTo().assertIsDisplayed()
        }
        rule.onNodeWithText("Confirm").performScrollTo().assertIsDisplayed()
    }
}

/** Resource names/formatting for a manifest-free Compose host, without starting the wallet app. */
class ReviewHostActivity : ComponentActivity() {
    private var fixtureResources: Resources? = null
    @Suppress("DEPRECATION")
    override fun getResources(): Resources {
        fixtureResources?.let { return it }
        val base = super.getResources()
        val names = R.string::class.java.fields.associate { it.getInt(null) to it.name }
        return object : Resources(base.assets, base.displayMetrics, base.configuration) {
            override fun getText(id: Int): CharSequence = getString(id)
            override fun getString(id: Int): String = when (id) {
                R.string.send_review_confirm -> "Confirm"
                R.string.send_review_cancel -> "Cancel"
                else -> names[id] ?: runCatching { base.getString(id) }.getOrDefault("resource-$id")
            }
            override fun getString(id: Int, vararg args: Any?): String =
                if (id == R.string.send_review_ckb) "${args[0]} CKB" else getString(id)
            override fun getQuantityString(id: Int, quantity: Int, vararg args: Any?): String = "$quantity recipients"
        }.also { fixtureResources = it }
    }
}
