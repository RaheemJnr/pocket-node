package com.example.mini

import androidx.lifecycle.ViewModel
import javax.inject.Inject

class MiniViewModel @Inject constructor(
    private val repository: MiniRepository,
) : ViewModel() {
    fun refresh(): Long {
        return repository.cellCount()
    }
}
