package com.rjnr.pocketnode.ui.education

import com.rjnr.pocketnode.ui.screens.help.FaqEntries
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class EducationTopicFaqLinkTest {

    @Test
    fun `every EducationTopic faqAnchor maps to a real FaqEntry`() {
        for (topic in EducationTopic.all) {
            assertNotNull(
                "Topic $topic has anchor '${topic.faqAnchor}' but no matching FaqEntry — " +
                "FAQ entry with that anchor must exist in FaqEntries.v1",
                FaqEntries.byAnchor(topic.faqAnchor)
            )
        }
    }

    @Test
    fun `FaqEntry anchors are unique`() {
        val anchors = FaqEntries.v1.map { it.anchor }
        assertEquals(
            "Duplicate FAQ anchors: ${anchors.groupBy { it }.filter { it.value.size > 1 }.keys}",
            anchors.size, anchors.toSet().size,
        )
    }

    // NOTE: We intentionally do NOT assert the reverse direction
    // (every FaqEntry anchor must be referenced by some EducationTopic).
    // FAQ entries `pending`, `confirmations`, `balance_delay`, `safe_during_sync`,
    // `close_during_sync`, `internet_required` exist only as deep-link targets
    // from the `Activity` topic body — they have no dedicated `?` icon. That
    // asymmetry is by design.
}
