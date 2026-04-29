package com.rjnr.pocketnode.ui.education

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class EducationTopicTest {

    @Test
    fun `every topic has non-zero titleRes and bodyRes`() {
        for (topic in EducationTopic.all) {
            assertNotEquals("titleRes for $topic", 0, topic.titleRes)
            assertNotEquals("bodyRes for $topic",  0, topic.bodyRes)
        }
    }

    @Test
    fun `topic title resources are unique`() {
        val titles = EducationTopic.all.map { it.titleRes }
        assertEquals(
            "Two topics share the same titleRes — likely a copy-paste regression",
            titles.size, titles.toSet().size,
        )
    }

    @Test
    fun `topic body resources are unique`() {
        val bodies = EducationTopic.all.map { it.bodyRes }
        assertEquals(
            "Two topics share the same bodyRes — likely a copy-paste regression",
            bodies.size, bodies.toSet().size,
        )
    }
}
