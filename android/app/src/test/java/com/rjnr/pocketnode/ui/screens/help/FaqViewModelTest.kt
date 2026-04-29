package com.rjnr.pocketnode.ui.screens.help

import androidx.lifecycle.SavedStateHandle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FaqViewModelTest {

    @Test
    fun `initial expanded set is empty when no anchor`() {
        val vm = FaqViewModel(SavedStateHandle())
        assertTrue(vm.expandedAnchors.value.isEmpty())
    }

    @Test
    fun `initial expanded set contains anchor when provided`() {
        val vm = FaqViewModel(SavedStateHandle(mapOf("anchor" to "sync_mode")))
        assertEquals(setOf("sync_mode"), vm.expandedAnchors.value)
    }

    @Test
    fun `blank anchor produces empty initial set`() {
        val vm = FaqViewModel(SavedStateHandle(mapOf("anchor" to "")))
        assertTrue(vm.expandedAnchors.value.isEmpty())
    }

    @Test
    fun `toggle adds and removes`() {
        val vm = FaqViewModel(SavedStateHandle())
        vm.toggle("sync")
        assertEquals(setOf("sync"), vm.expandedAnchors.value)
        vm.toggle("sync")
        assertTrue(vm.expandedAnchors.value.isEmpty())
    }

    @Test
    fun `toggle preserves other expanded anchors`() {
        val vm = FaqViewModel(SavedStateHandle(mapOf("anchor" to "sync")))
        vm.toggle("activity")
        assertEquals(setOf("sync", "activity"), vm.expandedAnchors.value)
    }
}
