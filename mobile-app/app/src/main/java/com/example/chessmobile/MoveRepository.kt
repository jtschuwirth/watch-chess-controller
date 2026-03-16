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
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

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
    val MOVE_REGEX = Regex(
        """^(""" +
        """O-O-O|O-O|""" +
        """[KQRBN]?[a-h]?[1-8]?x?[a-h][1-8](=[QRBN])?|""" +
        """[a-h][1-8](=[QRBN])?""" +
        """)[+#]?$"""
    )

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

    private val _phoneInputError = MutableStateFlow<String?>(null)
    val phoneInputError: StateFlow<String?> = _phoneInputError.asStateFlow()

    private val _navigateToHome = MutableStateFlow(false)
    val navigateToHome: StateFlow<Boolean> = _navigateToHome.asStateFlow()
    fun clearNavigateToHome() { _navigateToHome.value = false }
    fun setPhoneInputError(msg: String?) { _phoneInputError.value = msg }
    fun clearPhoneInputError() { _phoneInputError.value = null }

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
        // Notify watch to go home too
        Wearable.getNodeClient(context).connectedNodes.addOnSuccessListener { nodes ->
            nodes.forEach { node ->
                Wearable.getMessageClient(context)
                    .sendMessage(node.id, "/chess/home", ByteArray(0))
            }
        }
    }

    /** Called by WearMessageListenerService when the watch requests home navigation. */
    fun requestNavigateHome() {
        _activeProvider.value?.onGameEnd()
        _activeProvider.value = null
        _gameInfo.value = null
        _gameResult.value = null
        _lastStatus.value = null
        _moves.value = emptyList()
        _navigateToHome.value = true
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

    /**
     * Core move-processing logic shared by the watch path (WearMessageListenerService)
     * and the phone keyboard path (MobileKeyboardViewModel).
     * Returns an error string on rejection, or null on acceptance.
     */
    suspend fun processPlayerMove(move: String, context: Context): String? {
        if (!MOVE_REGEX.matches(move)) return "Invalid: $move"

        addMove(move)
        val provider = activeProvider.value
            ?: run { updateLastMoveStatus(MoveStatus.FAILED, "No active game provider."); return "No game" }

        return suspendCancellableCoroutine { cont ->
            provider.onPlayerMove(move, context) { opponentMove, error ->
                if (error != null) {
                    removeLastMove()
                    setLastStatus("Invalid move: $move")
                    cont.resume(error.take(12))
                    return@onPlayerMove
                }
                if (opponentMove != null) {
                    setLastMoveResponse(opponentMove)
                }
                if (provider is LichessProvider) {
                    updateLastMoveStatus(MoveStatus.EXECUTED)
                } else {
                    ChessAccessibilityService.instance?.executeMove(move)
                        ?: updateLastMoveStatus(MoveStatus.FAILED, "Accessibility service not enabled.")
                }
                cont.resume(null)
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

    /**
     * Notifies the watch that the player submitted a move from the phone keyboard.
     * Sends /chess/phone/move so the watch can add it to its history.
     * Also forwards any immediate opponent response (e.g. from RandomMovesProvider)
     * via the existing /chess/move path.
     */
    fun notifyWatchOfPhoneMove(move: String, context: Context) {
        val opponentMove = moves.value.lastOrNull()
            ?.takeIf { it.move == move }?.response
        Wearable.getNodeClient(context).connectedNodes.addOnSuccessListener { nodes ->
            nodes.forEach { node ->
                Wearable.getMessageClient(context)
                    .sendMessage(node.id, "/chess/phone/move", move.toByteArray(Charsets.UTF_8))
                if (opponentMove != null) {
                    Wearable.getMessageClient(context)
                        .sendMessage(node.id, "/chess/move", opponentMove.toByteArray(Charsets.UTF_8))
                }
            }
        }
    }
}
