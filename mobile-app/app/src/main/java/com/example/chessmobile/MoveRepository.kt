package com.example.chessmobile

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.google.android.gms.wearable.Wearable
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

data class MoveEntry(
    val moveNumber: Int,
    val move: String,
    val status: MoveStatus,
    val response: String? = null
)

enum class ChessColor { WHITE, BLACK }

data class GameInfo(val playerColor: ChessColor, val providerName: String)

object MoveRepository {
    private val _moves = MutableStateFlow<List<MoveEntry>>(emptyList())
    val moves: StateFlow<List<MoveEntry>> = _moves.asStateFlow()

    private val _lastStatus = MutableStateFlow<String?>(null)
    val lastStatus: StateFlow<String?> = _lastStatus.asStateFlow()

    private val _watchConnected = MutableStateFlow(false)
    val watchConnected: StateFlow<Boolean> = _watchConnected.asStateFlow()

    private val _activeProvider = MutableStateFlow<GameProvider?>(null)
    val activeProvider: StateFlow<GameProvider?> = _activeProvider.asStateFlow()

    private val _gameInfo = MutableStateFlow<GameInfo?>(null)
    val gameInfo: StateFlow<GameInfo?> = _gameInfo.asStateFlow()

    fun setWatchConnected(connected: Boolean) {
        _watchConnected.value = connected
    }

    fun startGame(config: ProviderConfig, provider: GameProvider, context: Context) {
        val playerColor = when (config) {
            is ProviderConfig.RandomMoves -> resolveColor(config.color)
            is ProviderConfig.Lichess -> ChessColor.WHITE
        }
        _activeProvider.value = provider
        _gameInfo.value = GameInfo(playerColor, provider.name)
        _moves.value = emptyList()
        _lastStatus.value = "Game started. You are playing as ${playerColor.name}."
        // Tell the watch which color the player is — this activates the keyboard on the watch
        val colorChar = if (playerColor == ChessColor.WHITE) "W" else "B"
        Wearable.getNodeClient(context).connectedNodes.addOnSuccessListener { nodes ->
            nodes.forEach { node ->
                Wearable.getMessageClient(context)
                    .sendMessage(node.id, "/chess/game/start", colorChar.toByteArray(Charsets.UTF_8))
            }
        }
        provider.onGameStart(config, context)
    }

    fun endGame(context: Context) {
        _activeProvider.value?.onGameEnd()
        _activeProvider.value = null
        _gameInfo.value = null
        _lastStatus.value = null
        // Tell the watch the game is over so it hides the keyboard and resets its state
        Wearable.getNodeClient(context).connectedNodes.addOnSuccessListener { nodes ->
            nodes.forEach { node ->
                Wearable.getMessageClient(context)
                    .sendMessage(node.id, "/chess/game/end", ByteArray(0))
            }
        }
    }

    fun triggerRandomFirstMove(context: Context) {
        val openingMoves = listOf("e4", "d4", "c4")
        val randomMove = openingMoves.random()
        Wearable.getNodeClient(context).connectedNodes.addOnSuccessListener { nodes ->
            addMove(randomMove)
            nodes.forEach { node ->
                // game/start was already sent by startGame(); just send the opening move
                Wearable.getMessageClient(context)
                    .sendMessage(node.id, "/chess/move", randomMove.toByteArray(Charsets.UTF_8))
                    .addOnSuccessListener { updateLastMoveStatus(MoveStatus.EXECUTED) }
                    .addOnFailureListener { updateLastMoveStatus(MoveStatus.FAILED, "Failed to send opening move") }
            }
        }
    }

    fun addMove(move: String) {
        _moves.update { list ->
            list + MoveEntry(moveNumber = list.size + 1, move = move, status = MoveStatus.PENDING)
        }
        _lastStatus.value = "Received: $move"
    }

    fun setLastMoveResponse(response: String) {
        _moves.update { list ->
            if (list.isEmpty()) list
            else list.dropLast(1) + list.last().copy(response = response)
        }
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
