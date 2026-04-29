package com.rjnr.pocketnode.ui.education.coachmark

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.rjnr.pocketnode.R

/**
 * One-time spotlight + tooltip overlay anchored to a component registered
 * via [Modifier.coachmarkAnchor]. Reads its anchor bounds from
 * [LocalCoachmarkRegistry] by [anchorKey].
 *
 * The scrim swallows taps so users cannot interact with backgrounded UI;
 * dismissal is the tooltip's "Got it" button.
 */
@Composable
fun SyncCoachmark(
    show: Boolean,
    anchorKey: String,
    onDismiss: () -> Unit,
) {
    if (!show) return
    val registry = LocalCoachmarkRegistry.current ?: return
    val rect: Rect = registry.bounds[anchorKey] ?: return
    if (rect.width <= 0f || rect.height <= 0f) return

    val scrimColor = Color.Black.copy(alpha = 0.65f)
    val density = LocalDensity.current
    val cornerRadiusPx = with(density) { 16.dp.toPx() }
    val spotlightInsetPx = with(density) { 4.dp.toPx() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            // Swallow taps so taps outside the tooltip don't hit underlying UI.
            .pointerInput(Unit) { detectTapGestures { /* swallow */ } },
    ) {
        // Single-pass scrim with a rounded-rect cutout for the spotlight.
        // Using Path.op(Difference) gives us rounded corners around the
        // anchor that match the sync card's own rounded shape.
        Canvas(modifier = Modifier.fillMaxSize()) {
            val full = Path().apply {
                addRect(Rect(0f, 0f, size.width, size.height))
            }
            val spotlight = Path().apply {
                addRoundRect(
                    RoundRect(
                        rect = Rect(
                            left = rect.left - spotlightInsetPx,
                            top = rect.top - spotlightInsetPx,
                            right = rect.right + spotlightInsetPx,
                            bottom = rect.bottom + spotlightInsetPx,
                        ),
                        cornerRadius = CornerRadius(cornerRadiusPx, cornerRadiusPx),
                    )
                )
            }
            val scrim = Path().apply {
                op(full, spotlight, PathOperation.Difference)
            }
            drawPath(path = scrim, color = scrimColor)
        }

        // Tooltip below the spotlight.
        val tooltipTopDp = with(density) { rect.bottom.toDp() } + 16.dp
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 16.dp, end = 16.dp, top = tooltipTopDp)
                .fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = stringResource(R.string.coachmark_sync_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    Button(
                        onClick = onDismiss,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                    ) {
                        Text(
                            text = stringResource(R.string.edu_got_it),
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }
        }
    }
}
