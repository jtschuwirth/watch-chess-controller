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

    private val _gameResult = MutableStateFlow<String?>(null)
    val gameResult: StateFlow<String?> = _gameResult.asStateFlow()

    fun setWatchConnected(connected: Boolean) {
        _watchConnected.value = connected
    }

    fun startGame(config: ProviderConfig, provider: GameProvider, context: Context) {
        val playerColor = when (config) {
            is ProviderConfig.RandomMoves -> resolveColor(config.color)
            // For Lichess use the requested color as a placeholder; updateGameColor() will
            // correct it once the Lichess API responds (relevant when color=RANDOM).
            is ProviderConfig.Lichess -> resolveColor(config.color)
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
        _gameResult.value = null
        // Tell the watch the game is over so it hides the keyboard and resets its state
        Wearable.getNodeClient(context).connectedNodes.addOnSuccessListener { nodes ->
            nodes.forEach { node ->
                Wearable.getMessageClient(context)
                    .sendMessage(node.id, "/chess/game/end", ByteArray(0))
            }
        }
    }

    /**
     * Called when Lichess ends the game (checkmate, resign, draw, timeout, etc.).
     * Stops the active provider, stores the human-readable result for display,
     * and pushes the result to the watch. [_gameInfo] is intentionally kept so the
     * phone can show the result screen before the user dismisses it.
     */
    fun endGameWithResult(status: String, winner: String?, context: Context) {
        val resultMsg = formatGameResult(status, winner)
        _activeProvider.value?.onGameEnd()
        _activeProvider.value = null
        _gameResult.value = resultMsg
        _lastStatus.value = resultMsg
        // _gameInfo is left intact — cleared when the user dismisses the result screen
        val payload = resultMsg.toByteArray(Charsets.UTF_8)
        Wearable.getNodeClient(context).connectedNodes.addOnSuccessListener { nodes ->
            nodes.forEach { node ->
                Wearable.getMessageClient(context)
                    .sendMessage(node.id, "/chess/game/end", payload)
            }
        }
    }

    /** Clears the game result and all game state after the user dismisses the result screen. */
    fun clearGameResult(context: Context) {
        _gameResult.value = null
        _gameInfo.value = null
        _lastStatus.value = null
        _moves.value = emptyList()
    }

    private fun formatGameResult(status: String, winner: String?): String {
        val winnerStr = when (winner) {
            "white" -> "White"
            "black" -> "Black"
            else -> null
        }
        return when (status) {
            "mate"       -> if (winnerStr != null) "Checkmate! $winnerStr wins." else "Checkmate!"
            "resign"     -> if (winnerStr != null) "$winnerStr wins by resignation." else "Resignation."
            "draw"       -> "Draw."
            "stalemate"  -> "Stalemate — Draw."
            "timeout",
            "outoftime"  -> if (winnerStr != null) "$winnerStr wins on time." else "Draw on time."
            "aborted"    -> "Game aborted."
            "cheat"      -> if (winnerStr != null) "$winnerStr wins (cheat detected)." else "Cheat detected."
            "noStart"    -> "Game did not start."
            "finish"     -> if (winnerStr != null) "$winnerStr wins." else "Game over."
            else         -> if (winnerStr != null) "$winnerStr wins." else "Game over."
        }
    }

    /**
     * Updates the player's color after a Lichess game start confirms the actual color
     * (relevant when ColorChoice.RANDOM was selected). Notifies the watch of the change.
     */
    fun updateGameColor(color: ChessColor, context: Context) {
        val current = _gameInfo.value ?: return
        if (current.playerColor == color) return
        _gameInfo.value = current.copy(playerColor = color)
        _lastStatus.value = "You are playing as ${color.name.lowercase()}."
        val colorChar = if (color == ChessColor.WHITE) "W" else "B"
        Wearable.getNodeClient(context).connectedNodes.addOnSuccessListener { nodes ->
            nodes.forEach { node ->
                Wearable.getMessageClient(context)
                    .sendMessage(node.id, "/chess/game/start", colorChar.toByteArray(Charsets.UTF_8))
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

    /** Removes the last move entry (used to undo an optimistic add when a move is rejected). */
    fun removeLastMove() {
        _moves.update { list -> if (list.isEmpty()) list else list.dropLast(1) }
    }

    fun setLastStatus(message: String) {
        _lastStatus.value = message
    }
}
