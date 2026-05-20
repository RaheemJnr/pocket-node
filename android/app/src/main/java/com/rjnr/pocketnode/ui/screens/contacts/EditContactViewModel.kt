package com.rjnr.pocketnode.ui.screens.contacts

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rjnr.pocketnode.R
import com.rjnr.pocketnode.data.contacts.ContactRepository
import com.rjnr.pocketnode.ui.util.UiMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class EditContactViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val contactRepository: ContactRepository,
) : ViewModel() {

    private val contactId: String = savedStateHandle["id"]
        ?: error("EditContactScreen requires an 'id' nav arg")

    data class UiState(
        val name: String = "",
        val address: String = "",
        val notes: String = "",
        val isLoading: Boolean = true,
        val isSaving: Boolean = false,
        val saved: Boolean = false,
        val error: UiMessage? = null,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val contact = contactRepository.get(contactId)
            if (contact == null) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = UiMessage.Resource(R.string.contact_detail_not_found),
                    )
                }
                return@launch
            }
            _uiState.update {
                it.copy(
                    isLoading = false,
                    name = contact.name,
                    address = contact.address,
                    notes = contact.notes.orEmpty(),
                )
            }
        }
    }

    fun onNameChange(v: String) = _uiState.update { it.copy(name = v) }
    fun onNotesChange(v: String) = _uiState.update { it.copy(notes = v) }
    fun clearError() = _uiState.update { it.copy(error = null) }

    fun save() {
        val state = _uiState.value
        _uiState.update { it.copy(isSaving = true, error = null) }
        viewModelScope.launch {
            contactRepository.update(
                id = contactId,
                name = state.name,
                notes = state.notes.ifBlank { null },
                tags = null,
            )
                .onSuccess { _uiState.update { it.copy(isSaving = false, saved = true) } }
                .onFailure { e ->
                    _uiState.update {
                        it.copy(
                            isSaving = false,
                            error = e.message?.let(UiMessage::Raw)
                                ?: UiMessage.Resource(R.string.contact_error_generic_save),
                        )
                    }
                }
        }
    }
}
