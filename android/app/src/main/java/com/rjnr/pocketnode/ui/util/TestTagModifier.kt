package com.rjnr.pocketnode.ui.util

import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId

/**
 * Adds a stable testTag and exposes it to UI Automator as a resource-id.
 * Compose's plain `testTag` only shows up in the semantics tree; UA reads
 * the View tree, so it needs `testTagsAsResourceId = true` as well.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
fun Modifier.uaTestTag(tag: String): Modifier =
    this
        .semantics { testTagsAsResourceId = true }
        .testTag(tag)
