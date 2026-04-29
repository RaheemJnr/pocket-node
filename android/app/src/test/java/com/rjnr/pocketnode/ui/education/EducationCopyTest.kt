package com.rjnr.pocketnode.ui.education

import org.junit.Assert.assertNotEquals
import org.junit.Test

class EducationCopyTest {

    @Test
    fun `every topic has non-zero titleRes and bodyRes`() {
        for (topic in EducationTopic.all()) {
            val content = EducationCopy.lookup(topic)
            assertNotEquals("titleRes for $topic", 0, content.titleRes)
            assertNotEquals("bodyRes for $topic",  0, content.bodyRes)
        }
    }
}
