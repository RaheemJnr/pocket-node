package com.rjnr.pocketnode.ui.education

import com.rjnr.pocketnode.R

object EducationCopy {
    fun lookup(topic: EducationTopic): EducationContent = when (topic) {
        EducationTopic.Sync -> EducationContent(
            titleRes = R.string.edu_sync_title,
            bodyRes  = R.string.edu_sync_body,
            faqAnchor = topic.faqAnchor
        )
        EducationTopic.SyncModeNew -> EducationContent(
            titleRes = R.string.edu_sync_mode_new_title,
            bodyRes  = R.string.edu_sync_mode_new_body,
            faqAnchor = topic.faqAnchor
        )
        EducationTopic.SyncModeRecent -> EducationContent(
            titleRes = R.string.edu_sync_mode_recent_title,
            bodyRes  = R.string.edu_sync_mode_recent_body,
            faqAnchor = topic.faqAnchor
        )
        EducationTopic.SyncModeCustom -> EducationContent(
            titleRes = R.string.edu_sync_mode_custom_title,
            bodyRes  = R.string.edu_sync_mode_custom_body,
            faqAnchor = topic.faqAnchor
        )
        EducationTopic.SyncModeFull -> EducationContent(
            titleRes = R.string.edu_sync_mode_full_title,
            bodyRes  = R.string.edu_sync_mode_full_body,
            faqAnchor = topic.faqAnchor
        )
        EducationTopic.BlockHeight -> EducationContent(
            titleRes = R.string.edu_block_height_title,
            bodyRes  = R.string.edu_block_height_body,
            faqAnchor = topic.faqAnchor
        )
        EducationTopic.Activity -> EducationContent(
            titleRes = R.string.edu_activity_title,
            bodyRes  = R.string.edu_activity_body,
            faqAnchor = topic.faqAnchor
        )
    }
}
