package com.rjnr.pocketnode.ui.components

import android.app.Application
import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
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

/**
 * #563 review F7: the sync sheet's Cancel / Apply row with the longer
 * "Cancel adding wallet" label at 360dp (312dp of content after the sheet's
 * 24dp side padding). Apply keeps its full size and stays inside the row,
 * and the Cancel label wraps to at most two lines instead of pushing Apply
 * out, also at a large font scale.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, qualifiers = "w360dp-h800dp")
// Real text measurement (the legacy shadows measure every glyph as ~1px).
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SyncSheetActionsLayoutTest {

    private val rule = createAndroidComposeRule<LayoutHostActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain
        .outerRule(object : ExternalResource() {
            override fun before() {
                val context = ApplicationProvider.getApplicationContext<Application>()
                shadowOf(context.packageManager).addActivityIfNotPresent(
                    ComponentName(context, LayoutHostActivity::class.java)
                )
            }
        })
        .around(rule)

    private fun layOut(fontScale: Float) {
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                Box(Modifier.width(CONTENT_WIDTH)) {
                    SyncSheetActions(
                        cancelLabel = CANCEL,
                        applyLabel = APPLY,
                        onCancel = {},
                        onApply = {},
                        cancelEnabled = true,
                        applyEnabled = true,
                    )
                }
            }
        }
        rule.waitForIdle()
    }

    private fun assertRowFits(maxCancelLines: Int) {
        val cancel = rule.onNodeWithText(CANCEL).getUnclippedBoundsInRoot()
        val cancelText = rule.onNodeWithText(CANCEL, useUnmergedTree = true).getUnclippedBoundsInRoot()
        val apply = rule.onNodeWithText(APPLY).getUnclippedBoundsInRoot()
        val applyText = rule.onNodeWithText(APPLY, useUnmergedTree = true).getUnclippedBoundsInRoot()
        val lineHeight = applyText.bottom - applyText.top

        assertTrue("Apply inside the row: ${apply.right} <= $CONTENT_WIDTH", apply.right <= CONTENT_WIDTH)
        assertTrue("Cancel ends before Apply: ${cancel.right} <= ${apply.left}", cancel.right <= apply.left)
        // Apply's label is never squeezed: it is wider than it is tall, on one line.
        assertTrue(
            "Apply label on one line: text=$applyText button=$apply cancel=$cancel cancelText=$cancelText",
            (applyText.right - applyText.left) > lineHeight,
        )
        val cancelLines = (cancelText.bottom - cancelText.top) / lineHeight
        assertTrue("Cancel label in at most $maxCancelLines line(s), was ~$cancelLines", cancelLines < maxCancelLines + 0.5f)
    }

    @Test
    fun `at the default font scale both labels sit on one line`() {
        layOut(fontScale = 1f)
        assertRowFits(maxCancelLines = 1)
    }

    @Test
    fun `at a large font scale Cancel wraps to two lines and Apply keeps its size`() {
        layOut(fontScale = 1.5f)
        assertRowFits(maxCancelLines = 2)
    }

    private companion object {
        const val CANCEL = "Cancel adding wallet"
        const val APPLY = "Apply"
        val CONTENT_WIDTH = 312.dp
    }
}

/** A plain host for the layout test, registered with Robolectric by the rule above. */
class LayoutHostActivity : ComponentActivity()
