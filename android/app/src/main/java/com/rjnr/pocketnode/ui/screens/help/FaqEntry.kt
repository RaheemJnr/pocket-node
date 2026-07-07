package com.rjnr.pocketnode.ui.screens.help

import androidx.annotation.StringRes
import com.rjnr.pocketnode.R

data class FaqEntry(
    val anchor: String,
    @StringRes val titleRes: Int,
    @StringRes val bodyRes: Int,
)

object FaqEntries {
    val v1: List<FaqEntry> = listOf(
        FaqEntry("sync",              R.string.faq_sync_title,              R.string.faq_sync_body),
        FaqEntry("sync_mode",         R.string.faq_sync_mode_title,         R.string.faq_sync_mode_body),
        FaqEntry("block_height",      R.string.faq_block_height_title,      R.string.faq_block_height_body),
        FaqEntry("activity",          R.string.faq_activity_title,          R.string.faq_activity_body),
        FaqEntry("pending",           R.string.faq_pending_title,           R.string.faq_pending_body),
        FaqEntry("confirmations",     R.string.faq_confirmations_title,     R.string.faq_confirmations_body),
        FaqEntry("balance_delay",     R.string.faq_balance_delay_title,     R.string.faq_balance_delay_body),
        FaqEntry("safe_during_sync",  R.string.faq_safe_during_sync_title,  R.string.faq_safe_during_sync_body),
        FaqEntry("close_during_sync", R.string.faq_close_during_sync_title, R.string.faq_close_during_sync_body),
        FaqEntry("internet_required", R.string.faq_internet_required_title, R.string.faq_internet_required_body),
        FaqEntry("imported_funds",    R.string.faq_imported_funds_title,    R.string.faq_imported_funds_body),
    )

    fun byAnchor(anchor: String): FaqEntry? = v1.firstOrNull { it.anchor == anchor }
}
