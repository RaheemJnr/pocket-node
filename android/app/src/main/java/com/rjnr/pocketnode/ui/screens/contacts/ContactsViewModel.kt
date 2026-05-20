package com.rjnr.pocketnode.ui.screens.contacts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rjnr.pocketnode.data.contacts.ContactRepository
import com.rjnr.pocketnode.data.database.entity.ContactEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Address book list screen ([#193](https://github.com/RaheemJnr/pocket-node/issues/193)).
 *
 * Observes the wallet-scoped contact list from [ContactRepository] and
 * exposes a search query that filters in-memory. Search is debounced
 * 200ms so each keystroke doesn't trigger a new Room query during
 * fast typing. The Flow is hot — Room emits a new list when any row
 * changes, so deletions or post-send `markUsed` bumps propagate
 * without a manual refresh.
 */
@HiltViewModel
class ContactsViewModel @Inject constructor(
    private val contactRepository: ContactRepository,
) : ViewModel() {

    data class UiState(
        val contacts: List<ContactEntity> = emptyList(),
        val query: String = "",
        val isLoading: Boolean = true,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val queryFlow = MutableStateFlow("")

    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    private val contactsFlow = queryFlow
        .debounce(200)
        .distinctUntilChanged()
        .flatMapLatest { q ->
            if (q.isBlank()) {
                contactRepository.observe()
            } else {
                flowOf(contactRepository.search(q))
            }
        }

    init {
        contactsFlow
            .onEach { list ->
                _uiState.update { it.copy(contacts = list, isLoading = false) }
            }
            .launchIn(viewModelScope)
    }

    fun onQueryChange(q: String) {
        _uiState.update { it.copy(query = q) }
        queryFlow.value = q
    }

    fun delete(id: String) {
        viewModelScope.launch {
            contactRepository.delete(id)
        }
    }
}
