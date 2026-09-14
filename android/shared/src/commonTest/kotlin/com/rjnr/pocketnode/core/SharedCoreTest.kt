package com.rjnr.pocketnode.core

import kotlin.test.Test
import kotlin.test.assertTrue

class SharedCoreTest {
    @Test
    fun describeContainsVersion() {
        assertTrue(SharedCore.describe().contains("0.1.0"))
    }
}
