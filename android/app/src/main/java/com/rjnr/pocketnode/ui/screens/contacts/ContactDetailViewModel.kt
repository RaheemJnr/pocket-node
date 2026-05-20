package com.rjnr.pocketnode.ui.screens.contacts

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rjnr.pocketnode.R
import com.rjnr.pocketnode.data.contacts.ContactRepository
import com.rjnr.pocketnode.data.database.entity.ContactEntity
import com.rjnr.pocketnode.ui.util.UiMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ContactDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val contactRepository: ContactRepository,
) : ViewModel() {

    private val contactId: String = savedStateHandle["id"]
        ?: error("ContactDetailScreen requires an 'id' nav arg")

    data class UiState(
        val contact: ContactEntity? = null,
        val isLoading: Boolean = true,
        val showDeleteConfirm: Boolean = false,
        val deleted: Boolean = false,
        val error: UiMessage? = null,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init { reload() }

    fun reload() {
        viewModelScope.launch {
            val c = contactRepository.get(contactId)
            _uiState.update { it.copy(isLoading = false, contact = c) }
        }
    }

    fun requestDelete() = _uiState.update { it.copy(showDeleteConfirm = true) }
    fun cancelDelete() = _uiState.update { it.copy(showDeleteConfirm = false) }

    fun confirmDelete() {
        viewModelScope.launch {
            contactRepository.delete(contactId)
                .onSuccess { _uiState.update { it.copy(showDeleteConfirm = false, deleted = true) } }
                .onFailure { e ->
                    _uiState.update {
                        it.copy(
                            showDeleteConfirm = false,
                            error = e.message?.let(UiMessage::Raw)
                                ?: UiMessage.Resource(R.string.contact_error_generic_save),
                        )
                    }
                }
        }
    }

    fun clearError() = _uiState.update { it.copy(error = null) }
}
