package com.example.chessmobile

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class MoveStatus(val label: String) {
    PENDING("Pending"),
    EXECUTED("Executed"),
    FAILED("Failed");

    @Composable
    fun color(): Color = when (this) {
        PENDING -> MaterialTheme.colorScheme.onSurfaceVariant
        EXECUTED -> Color(0xFF2E7D32)
        FAILED -> MaterialTheme.colorScheme.error
    }
}

data class MoveEntry(val move: String, val status: MoveStatus)

object MoveRepository {
    private val _moves = MutableStateFlow<List<MoveEntry>>(emptyList())
    val moves: StateFlow<List<MoveEntry>> = _moves.asStateFlow()

    private val _lastStatus = MutableStateFlow<String?>(null)
    val lastStatus: StateFlow<String?> = _lastStatus.asStateFlow()

    fun addMove(move: String) {
        _moves.update { it + MoveEntry(move, MoveStatus.PENDING) }
        _lastStatus.value = "Received move: $move — attempting execution..."
    }

    fun updateLastMoveStatus(status: MoveStatus, detail: String? = null) {
        _moves.update { list ->
            if (list.isEmpty()) list
            else list.dropLast(1) + list.last().copy(status = status)
        }
        _lastStatus.value = detail ?: when (status) {
            MoveStatus.EXECUTED -> "Move executed successfully."
            MoveStatus.FAILED -> "Move execution failed."
            MoveStatus.PENDING -> null
        }
    }
}
