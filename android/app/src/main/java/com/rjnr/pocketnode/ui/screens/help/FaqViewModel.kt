package com.rjnr.pocketnode.ui.screens.help

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject

@HiltViewModel
class FaqViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val initialAnchor: String? = savedStateHandle.get<String?>("anchor")
        ?.takeIf { it.isNotBlank() }

    private val _expandedAnchors = MutableStateFlow(
        if (initialAnchor == null) emptySet() else setOf(initialAnchor)
    )
    val expandedAnchors: StateFlow<Set<String>> = _expandedAnchors.asStateFlow()

    /** Returns the index of the deep-linked anchor in [FaqEntries.v1], or -1 if absent. */
    fun initialScrollIndex(): Int =
        initialAnchor?.let { anchor -> FaqEntries.v1.indexOfFirst { it.anchor == anchor } } ?: -1

    fun toggle(anchor: String) {
        _expandedAnchors.update { current ->
            if (anchor in current) current - anchor else current + anchor
        }
    }
}
