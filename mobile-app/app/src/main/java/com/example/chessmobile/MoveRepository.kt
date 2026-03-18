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

data class GameSession(
    val gameInfo: GameInfo,
    val moves: List<MoveEntry>,
    val provider: GameProvider
)

data class ResumableSession(
    val providerName: String,
    val playerColor: ChessColor,
    val gameId: String? = null
)

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

    // Paused sessions: in-memory sessions the user navigated away from mid-game
    private val _pausedSessions = MutableStateFlow<Map<String, GameSession>>(emptyMap())

    // Stored sessions loaded from SharedPreferences on cold start (Lichess only)
    private val _storedSessions = MutableStateFlow<Map<String, ResumableSession>>(emptyMap())

    // Combined view for the UI: paused sessions + cold-start stored sessions
    private val _resumableSessions = MutableStateFlow<Map<String, ResumableSession>>(emptyMap())
    val resumableSessions: StateFlow<Map<String, ResumableSession>> = _resumableSessions.asStateFlow()

    private fun startForegroundService(context: Context) {
        context.startForegroundService(
            android.content.Intent(context, ChessGameForegroundService::class.java)
        )
    }

    private fun stopForegroundService(context: Context) {
        context.stopService(
            android.content.Intent(context, ChessGameForegroundService::class.java)
        )
    }

    private fun updateResumableSessions() {
        val result = mutableMapOf<String, ResumableSession>()
        result.putAll(_storedSessions.value)
        _pausedSessions.value.forEach { (key, session) ->
            result[key] = ResumableSession(providerName = key, playerColor = session.gameInfo.playerColor)
        }
        _resumableSessions.value = result
    }

    /** Call once from MainActivity.onCreate to restore any Lichess session persisted across app restarts. */
    fun loadPersistedSessions(context: Context) {
        val stored = mutableMapOf<String, ResumableSession>()
        GameSessionStore.loadLichessSession(context)?.let { session ->
            // Only surface if there is no live paused session for this provider already
            if (!_pausedSessions.value.containsKey(session.providerName)) {
                stored[session.providerName] = session
            }
        }
        _storedSessions.value = stored
        updateResumableSessions()
    }

    fun clearNavigateToHome() { _navigateToHome.value = false }
    fun setPhoneInputError(msg: String?) { _phoneInputError.value = msg }
    fun clearPhoneInputError() { _phoneInputError.value = null }

    fun setWatchConnected(connected: Boolean) {
        _watchConnected.value = connected
    }

    fun startGame(config: ProviderConfig, provider: GameProvider, context: Context) {
        // End any paused session for this provider so it doesn't linger
        val existingPaused = _pausedSessions.value[provider.name]
        if (existingPaused != null) {
            existingPaused.provider.onGameEnd()
            _pausedSessions.update { it - provider.name }
        }
        // Clear any cold-start stored entry for this provider
        if (_storedSessions.value.containsKey(provider.name)) {
            _storedSessions.update { it - provider.name }
        }
        updateResumableSessions()

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
        startForegroundService(context)
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
        stopForegroundService(context)
        // Tell the watch the game is over so it hides the keyboard and resets its state
        Wearable.getNodeClient(context).connectedNodes.addOnSuccessListener { nodes ->
            nodes.forEach { node ->
                Wearable.getMessageClient(context)
                    .sendMessage(node.id, "/chess/game/end", ByteArray(0))
            }
        }
    }

    /**
     * Leaves the current game without ending it. The session is preserved in memory and (for
     * Lichess) in SharedPreferences so the user can resume later. The watch is notified with
     * /chess/game/pause so it shows a "paused" state rather than fully resetting.
     */
    fun pauseGame(context: Context) {
        val provider = _activeProvider.value ?: return
        val gameInfo = _gameInfo.value ?: return

        // Save current session for later resumption
        val session = GameSession(gameInfo, _moves.value, provider)
        _pausedSessions.update { it + (provider.name to session) }

        // Let the provider pause itself (Lichess: saves to store & disconnects stream)
        provider.onGamePause()

        // Remove any cold-start stored entry for this provider (replaced by warm paused session)
        _storedSessions.update { it - provider.name }
        updateResumableSessions()

        // Clear active flat state
        _activeProvider.value = null
        _gameInfo.value = null
        _lastStatus.value = null
        _moves.value = emptyList()
        stopForegroundService(context)

        // Tell the watch the game is paused
        Wearable.getNodeClient(context).connectedNodes.addOnSuccessListener { nodes ->
            nodes.forEach { node ->
                Wearable.getMessageClient(context)
                    .sendMessage(node.id, "/chess/game/pause", ByteArray(0))
            }
        }

        _navigateToHome.value = true
    }

    /**
     * Resumes a previously paused session. For Lichess, reconnects the stream (or reconnects
     * cold from SharedPreferences if the app was restarted). For Random Moves, restores history.
     */
    fun resumeGame(providerName: String, context: Context) {
        val pausedSession = _pausedSessions.value[providerName]

        if (pausedSession != null) {
            // Warm resume: live provider instance still exists
            _activeProvider.value = pausedSession.provider
            _gameInfo.value = pausedSession.gameInfo
            _pausedSessions.update { it - providerName }
            updateResumableSessions()

            // For RandomMoves there is no stream to rebuild from, so restore moves directly
            if (pausedSession.provider !is LichessProvider) {
                _moves.value = pausedSession.moves
            } else {
                _moves.value = emptyList() // Lichess stream's gameFull event will rebuild
            }

            pausedSession.provider.onGameResume(context)
        } else {
            // Cold resume: new provider instance, read stored session from SharedPreferences
            val stored = GameSessionStore.loadLichessSession(context) ?: return
            val provider = LichessProvider()
            _activeProvider.value = provider
            _gameInfo.value = GameInfo(stored.playerColor, "Lichess")
            _moves.value = emptyList() // Will be rebuilt from Lichess stream
            _storedSessions.update { it - "Lichess" }
            updateResumableSessions()
            provider.onGameResume(context)
        }

        startForegroundService(context)

        // Notify watch that the game is active again
        val colorChar = if (_gameInfo.value?.playerColor == ChessColor.WHITE) "W" else "B"
        Wearable.getNodeClient(context).connectedNodes.addOnSuccessListener { nodes ->
            nodes.forEach { node ->
                Wearable.getMessageClient(context)
                    .sendMessage(node.id, "/chess/game/start", colorChar.toByteArray(Charsets.UTF_8))
            }
        }
    }

    /** Called by WearMessageListenerService when the watch sends /chess/home. */
    fun requestPauseGame(context: Context) {
        if (_activeProvider.value != null) {
            pauseGame(context)
        } else {
            // Game already over or not active — just navigate home
            _navigateToHome.value = true
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
        stopForegroundService(context)
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

        // For Black player the entry model is: move=White's half, response=Black's half.
        // The player's (Black's) move fills the response field of the current White entry.
        // For White player the entry model is unchanged: move=White's half, response=Black's half.
        val isBlackPlayer = gameInfo.value?.playerColor == ChessColor.BLACK

        if (isBlackPlayer) {
            setLastMoveResponse(move)
            updateLastMoveStatus(MoveStatus.PENDING)
        } else {
            addMove(move)
        }

        val provider = activeProvider.value
            ?: run { updateLastMoveStatus(MoveStatus.FAILED, "No active game provider."); return "No game" }

        return suspendCancellableCoroutine { cont ->
            provider.onPlayerMove(move, context) { opponentMove, error ->
                if (error != null) {
                    if (isBlackPlayer) clearLastMoveResponse() else removeLastMove()
                    setLastStatus("Invalid move: $move")
                    cont.resume(error.take(12))
                    return@onPlayerMove
                }
                if (opponentMove != null) {
                    // For Black player, the opponent's reply is White's next move → new entry.
                    // For White player, the opponent's reply is Black's response → fill response.
                    if (isBlackPlayer) {
                        addMove(opponentMove)
                        updateLastMoveStatus(MoveStatus.EXECUTED)
                    } else {
                        setLastMoveResponse(opponentMove)
                    }
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

    fun restoreHistory(entries: List<MoveEntry>) {
        _moves.value = entries
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

    /** Clears the response field of the last entry (undo for Black player's optimistic response). */
    fun clearLastMoveResponse() {
        _moves.update { list ->
            if (list.isEmpty()) list
            else list.dropLast(1) + list.last().copy(response = null, status = MoveStatus.EXECUTED)
        }
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
