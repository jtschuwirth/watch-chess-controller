package com.example.chessmobile

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MobileKeyboardViewModel(private val app: Application) : AndroidViewModel(app) {

    private val _currentInput = MutableStateFlow("")
    val currentInput: StateFlow<String> = _currentInput.asStateFlow()

    fun onAppend(char: String) { _currentInput.value += char }
    fun onBackspace() { _currentInput.value = _currentInput.value.dropLast(1) }
    fun onClear() { _currentInput.value = "" }

    fun onSend() {
        val move = _currentInput.value.trim()
        if (move.isBlank()) return
        _currentInput.value = ""
        MoveRepository.clearPhoneInputError()
        viewModelScope.launch {
            val error = MoveRepository.processPlayerMove(move, app.applicationContext)
            if (error != null) {
                MoveRepository.setPhoneInputError(error)
            } else {
                MoveRepository.notifyWatchOfPhoneMove(move, app.applicationContext)
            }
        }
    }

    fun onResign() { MoveRepository.activeProvider.value?.onResign(app.applicationContext) }
    fun onDrawOffer() { MoveRepository.activeProvider.value?.onDrawOffer(app.applicationContext) }
}
