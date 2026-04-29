package com.rjnr.pocketnode.ui.education

import androidx.annotation.StringRes
import com.rjnr.pocketnode.R

/**
 * Topics surfaced by `?` icons across the app. Each topic carries its own
 * localized title/body string resources plus a stable FAQ anchor for deep-linking.
 *
 * Enum (not sealed class) so `rememberSaveable` default Saver handles it
 * by name without needing a custom `Saver`.
 */
enum class EducationTopic(
    @StringRes val titleRes: Int,
    @StringRes val bodyRes: Int,
    val faqAnchor: String,
) {
    Sync(R.string.edu_sync_title, R.string.edu_sync_body, "sync"),
    SyncModeNew(R.string.edu_sync_mode_new_title, R.string.edu_sync_mode_new_body, "sync_mode"),
    SyncModeRecent(R.string.edu_sync_mode_recent_title, R.string.edu_sync_mode_recent_body, "sync_mode"),
    SyncModeCustom(R.string.edu_sync_mode_custom_title, R.string.edu_sync_mode_custom_body, "sync_mode"),
    SyncModeFull(R.string.edu_sync_mode_full_title, R.string.edu_sync_mode_full_body, "sync_mode"),
    BlockHeight(R.string.edu_block_height_title, R.string.edu_block_height_body, "block_height"),
    Activity(R.string.edu_activity_title, R.string.edu_activity_body, "activity");

    companion object {
        /** Direct alias for `entries`; some call sites prefer a verb-noun name. */
        val all: List<EducationTopic> = entries
    }
}
