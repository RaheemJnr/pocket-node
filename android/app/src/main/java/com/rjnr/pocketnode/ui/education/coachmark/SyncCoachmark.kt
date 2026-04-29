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
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
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

    val scrimColor = Color.Black.copy(alpha = 0.6f)

    Box(
        modifier = Modifier
            .fillMaxSize()
            // Swallow taps so taps outside the tooltip don't hit underlying UI.
            .pointerInput(Unit) { detectTapGestures { /* swallow */ } },
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            // Top band: above the spotlight, full width.
            if (rect.top > 0f) {
                drawRect(
                    color = scrimColor,
                    topLeft = Offset(0f, 0f),
                    size = Size(size.width, rect.top),
                )
            }
            // Bottom band: below the spotlight, full width.
            if (rect.bottom < size.height) {
                drawRect(
                    color = scrimColor,
                    topLeft = Offset(0f, rect.bottom),
                    size = Size(size.width, size.height - rect.bottom),
                )
            }
            // Left band: at spotlight row, left of spotlight.
            if (rect.left > 0f) {
                drawRect(
                    color = scrimColor,
                    topLeft = Offset(0f, rect.top),
                    size = Size(rect.left, rect.height),
                )
            }
            // Right band: at spotlight row, right of spotlight.
            if (rect.right < size.width) {
                drawRect(
                    color = scrimColor,
                    topLeft = Offset(rect.right, rect.top),
                    size = Size(size.width - rect.right, rect.height),
                )
            }
        }

        // Tooltip below the spotlight.
        val density = LocalDensity.current
        val tooltipTopDp = with(density) { rect.bottom.toDp() } + 12.dp
        Card(
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 16.dp, end = 16.dp, top = tooltipTopDp)
                .fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(R.string.coachmark_sync_body),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    Button(onClick = onDismiss) {
                        Text(stringResource(R.string.edu_got_it))
                    }
                }
            }
        }
    }
}
