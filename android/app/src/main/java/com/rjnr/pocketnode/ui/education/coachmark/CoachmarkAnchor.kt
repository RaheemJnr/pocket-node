package com.rjnr.pocketnode.ui.education.coachmark

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned

/**
 * Tracks layout bounds of components that may host a coachmark overlay.
 * Anchors register their bounds via [Modifier.coachmarkAnchor]; the
 * [SyncCoachmark] overlay reads them by key.
 *
 * Decoupled by design: the anchor doesn't know a coachmark exists; the
 * coachmark doesn't know which Composable is the anchor.
 */
class CoachmarkRegistry {
    val bounds: SnapshotStateMap<String, Rect> = mutableStateMapOf()
}

val LocalCoachmarkRegistry = compositionLocalOf<CoachmarkRegistry?> { null }

@Composable
fun rememberCoachmarkRegistry(): CoachmarkRegistry = remember { CoachmarkRegistry() }

/**
 * Records this Composable's window-relative bounds in the ambient
 * [CoachmarkRegistry] under [key]. No-op if no registry is provided
 * via `CompositionLocalProvider(LocalCoachmarkRegistry provides ...)`.
 */
@Composable
fun Modifier.coachmarkAnchor(key: String): Modifier {
    val registry = LocalCoachmarkRegistry.current ?: return this
    return this.then(
        Modifier.onGloballyPositioned { coords ->
            registry.bounds[key] = coords.boundsInWindow()
        }
    )
}
