package com.rjnr.pocketnode.ui.education

/**
 * Enum (not sealed class) so `rememberSaveable` default Saver handles it
 * without a custom `Saver` — enums are saved by name out of the box.
 */
enum class EducationTopic(val faqAnchor: String) {
    Sync("sync"),
    SyncModeNew("sync_mode"),
    SyncModeRecent("sync_mode"),
    SyncModeCustom("sync_mode"),
    SyncModeFull("sync_mode"),
    BlockHeight("block_height"),
    Activity("activity");

    companion object {
        fun all(): List<EducationTopic> = entries.toList()
    }
}
