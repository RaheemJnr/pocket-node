package com.rjnr.pocketnode.ui.screens.contacts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rjnr.pocketnode.R
import com.rjnr.pocketnode.data.contacts.ContactRepository
import com.rjnr.pocketnode.data.gateway.GatewayRepository
import com.rjnr.pocketnode.ui.util.UiMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AddContactViewModel @Inject constructor(
    private val contactRepository: ContactRepository,
    private val gatewayRepository: GatewayRepository,
) : ViewModel() {

    data class UiState(
        val name: String = "",
        val address: String = "",
        val notes: String = "",
        val isSaving: Boolean = false,
        val saved: Boolean = false,
        val error: UiMessage? = null,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    fun onNameChange(v: String) = _uiState.update { it.copy(name = v) }
    fun onAddressChange(v: String) = _uiState.update { it.copy(address = v.trim()) }
    fun onNotesChange(v: String) = _uiState.update { it.copy(notes = v) }
    fun clearError() = _uiState.update { it.copy(error = null) }

    fun save() {
        val state = _uiState.value
        if (state.name.isBlank() || state.address.isBlank()) {
            _uiState.update { it.copy(error = UiMessage.Resource(R.string.add_contact_required_error)) }
            return
        }
        _uiState.update { it.copy(isSaving = true, error = null) }
        viewModelScope.launch {
            val result = contactRepository.add(
                name = state.name,
                address = state.address,
                notes = state.notes.ifBlank { null },
                tags = null,
                activeNetwork = gatewayRepository.currentNetwork,
            )
            result
                .onSuccess { _uiState.update { it.copy(isSaving = false, saved = true) } }
                .onFailure { e ->
                    _uiState.update { it.copy(isSaving = false, error = errorMessage(e)) }
                }
        }
    }

    private fun errorMessage(e: Throwable): UiMessage = when (e) {
        is ContactRepository.ContactError.InvalidAddress ->
            UiMessage.Resource(R.string.contact_error_invalid_address)
        is ContactRepository.ContactError.WrongNetwork ->
            UiMessage.Resource(R.string.contact_error_wrong_network, listOf(e.actual.name.lowercase()))
        is ContactRepository.ContactError.DuplicateAddress ->
            UiMessage.Resource(R.string.contact_error_duplicate)
        is ContactRepository.ContactError.InvalidName ->
            UiMessage.Resource(R.string.contact_error_invalid_name)
        is ContactRepository.ContactError.NotesTooLong ->
            UiMessage.Resource(R.string.contact_error_notes_too_long)
        is ContactRepository.ContactError.NoActiveWallet ->
            UiMessage.Resource(R.string.contact_error_no_active_wallet)
        else -> e.message?.let { UiMessage.Raw(it) }
            ?: UiMessage.Resource(R.string.contact_error_generic_save)
    }
}
