package com.rjnr.pocketnode.ui.screens.contacts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rjnr.pocketnode.data.contacts.ContactRepository
import com.rjnr.pocketnode.data.gateway.GatewayRepository
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
        val error: String? = null,
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
            _uiState.update { it.copy(error = "Name and address are required") }
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
                    _uiState.update { it.copy(isSaving = false, error = formatError(e)) }
                }
        }
    }

    private fun formatError(e: Throwable): String = when (e) {
        is ContactRepository.ContactError.InvalidAddress -> "Address could not be decoded — check the format"
        is ContactRepository.ContactError.WrongNetwork ->
            "This address is for ${e.actual.name.lowercase()}; switch networks or pick a different address"
        is ContactRepository.ContactError.DuplicateAddress -> "An existing contact already uses this address"
        is ContactRepository.ContactError.InvalidName -> "Name must be 1-64 characters"
        is ContactRepository.ContactError.NotesTooLong -> "Notes must be 256 characters or fewer"
        is ContactRepository.ContactError.NoActiveWallet -> "No active wallet to scope this contact to"
        else -> e.message ?: "Could not save contact"
    }
}
