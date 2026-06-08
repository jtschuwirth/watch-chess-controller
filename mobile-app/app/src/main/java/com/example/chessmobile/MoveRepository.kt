package com.example.chessmobile

import android.content.Context
import android.speech.tts.TextToSpeech
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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

    private val _gameStartError = MutableStateFlow<String?>(null)
    val gameStartError: StateFlow<String?> = _gameStartError.asStateFlow()
    fun clearGameStartError() { _gameStartError.value = null }

    private val _isTtsEnabled = MutableStateFlow(false)
    val isTtsEnabled: StateFlow<Boolean> = _isTtsEnabled.asStateFlow()

    private var appContext: android.content.Context? = null

    // Polling: detect missed stream events by tracking when we started waiting for an opponent move
    @Volatile private var waitingForOpponentSince: Long? = null
    @Volatile private var immediateUpdateOnNextWait = false
    private var pollingJob: Job? = null

    private val providerCallbacks = object : GameProviderCallbacks {
        override fun onOpponentMove(san: String, sendToWatch: Boolean) {
            stopWaitingForOpponent()
            recordOpponentMove(san)
            updateLastMoveStatus(MoveStatus.EXECUTED)
            if (sendToWatch) {
                val ctx = appContext ?: return
                com.google.android.gms.wearable.Wearable.getNodeClient(ctx).connectedNodes
                    .addOnSuccessListener { nodes ->
                        nodes.forEach { node ->
                            com.google.android.gms.wearable.Wearable.getMessageClient(ctx)
                                .sendMessage(node.id, "/chess/move", san.toByteArray(Charsets.UTF_8))
                        }
                    }
            }
        }
        override fun onGameEnded(status: String, winner: String?) {
            val ctx = appContext ?: return
            endGameWithResult(status, winner, ctx)
        }
        override fun onColorAssigned(color: ChessColor) {
            val ctx = appContext ?: return
            updateGameColor(color, ctx)
        }
        override fun onHistoryRestored(entries: List<MoveEntry>) {
            restoreHistory(entries)
            val info = gameInfo.value ?: return
            // MoveEntry invariant: move = White's half, response = Black's half (always).
            // Determine if it is currently the opponent's turn so polling can track it.
            val isOpponentTurn = when {
                entries.isEmpty() -> info.playerColor == ChessColor.BLACK  // White (opponent) moves first
                info.playerColor == ChessColor.WHITE -> entries.last().response == null  // waiting for Black
                else -> entries.last().response != null  // Black played; waiting for White's next
            }
            if (isOpponentTurn) startWaitingForOpponent() else stopWaitingForOpponent()
        }
        override fun onGameStartFailed(error: String) {
            val ctx = appContext ?: return
            _gameStartError.value = error
            endGame(ctx)
            _navigateToHome.value = true
        }
    }

    private var tts: TextToSpeech? = null

    fun initTts(context: Context) {
        if (tts != null) return
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.ERROR) tts = null
        }
    }

    fun toggleTts() {
        _isTtsEnabled.value = !_isTtsEnabled.value
    }

    private fun startWaitingForOpponent() {
        waitingForOpponentSince = System.currentTimeMillis()
        if (immediateUpdateOnNextWait) {
            immediateUpdateOnNextWait = false
            _activeProvider.value?.requestMoveUpdate()
        }
    }

    private fun stopWaitingForOpponent() {
        waitingForOpponentSince = null
    }

    private fun startPolling() {
        pollingJob?.cancel()
        pollingJob = CoroutineScope(Dispatchers.Default + SupervisorJob()).launch {
            while (isActive) {
                delay(5_000L)
                val since = waitingForOpponentSince ?: continue
                if (System.currentTimeMillis() - since >= 5_000L) {
                    _activeProvider.value?.requestMoveUpdate()
                }
            }
        }
    }

    private fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
        waitingForOpponentSince = null
        immediateUpdateOnNextWait = false
    }

    private fun speakIfEnabled(move: String) {
        if (_isTtsEnabled.value) {
            tts?.speak(sanToSpoken(move), TextToSpeech.QUEUE_FLUSH, null, null)
        }
    }

    /**
     * Records an opponent move in the move log and announces it via TTS if enabled.
     * This is the single entry point for all opponent moves regardless of provider.
     * For Black player: opponent (White) starts a new entry via addMove.
     * For White player: opponent (Black) fills the response of the player's entry.
     */
    fun recordOpponentMove(san: String) {
        val isBlackPlayer = gameInfo.value?.playerColor == ChessColor.BLACK
        if (isBlackPlayer) {
            addMove(san)
        } else {
            setLastMoveResponse(san)
        }
        speakIfEnabled(san)
    }

    fun sanToSpoken(san: String): String {
        if (san.startsWith("O-O-O") || san.startsWith("0-0-0")) return "Queenside castle"
        if (san.startsWith("O-O") || san.startsWith("0-0")) return "Kingside castle"

        var s = san

        val suffix = when {
            s.endsWith("#") -> { s = s.dropLast(1); ", checkmate" }
            s.endsWith("+") -> { s = s.dropLast(1); ", check" }
            else -> ""
        }

        val promotionPiece: Char?
        if (s.contains('=')) {
            promotionPiece = s.substringAfter("=").firstOrNull()
            s = s.substringBefore("=")
        } else {
            promotionPiece = null
        }

        val captures = s.contains('x')
        s = s.replace("x", "")

        val piece = when (s.firstOrNull()) {
            'K' -> "King"
            'Q' -> "Queen"
            'R' -> "Rook"
            'B' -> "Bishop"
            'N' -> "Knight"
            else -> "Pawn"
        }

        val dest = if (s.length >= 2) s.takeLast(2) else s

        val promotionStr = if (promotionPiece != null) {
            val pieceName = when (promotionPiece) {
                'Q' -> "Queen"; 'R' -> "Rook"; 'B' -> "Bishop"; 'N' -> "Knight"
                else -> promotionPiece.toString()
            }
            ", promotes to $pieceName"
        } else ""

        val verb = if (captures) "captures" else "to"
        return "$piece $verb $dest$promotionStr$suffix"
    }

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
        appContext = context.applicationContext
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
        initTts(context)
        startForegroundService(context)
        // Tell the watch which color the player is — this activates the keyboard on the watch
        val colorChar = if (playerColor == ChessColor.WHITE) "W" else "B"
        Wearable.getNodeClient(context).connectedNodes.addOnSuccessListener { nodes ->
            nodes.forEach { node ->
                Wearable.getMessageClient(context)
                    .sendMessage(node.id, "/chess/game/start", colorChar.toByteArray(Charsets.UTF_8))
            }
        }
        provider.setCallbacks(providerCallbacks)
        provider.onGameStart(config, context)
        // For async providers (Lichess), if we play Black the opponent moves first — start waiting
        if (playerColor == ChessColor.BLACK && provider.executesMovesExternally) {
            startWaitingForOpponent()
        }
        startPolling()
    }

    fun endGame(context: Context) {
        stopPolling()
        _activeProvider.value?.onGameEnd()
        _activeProvider.value = null
        _gameInfo.value = null
        _lastStatus.value = null
        _gameResult.value = null
        appContext = null
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

        // Stop polling before pausing — no point checking for moves while paused
        stopPolling()

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
        appContext = context.applicationContext

        val pausedSession = _pausedSessions.value[providerName]

        // Signal that once we know whose turn it is (onHistoryRestored), we should immediately
        // request the latest state from Lichess in case we missed moves while paused.
        immediateUpdateOnNextWait = true

        if (pausedSession != null) {
            // Warm resume: live provider instance still exists
            _activeProvider.value = pausedSession.provider
            _gameInfo.value = pausedSession.gameInfo
            _pausedSessions.update { it - providerName }
            updateResumableSessions()

            // Always start empty — provider calls onHistoryRestored if it cannot rebuild from stream
            _moves.value = emptyList()

            pausedSession.provider.setCallbacks(providerCallbacks)
            pausedSession.provider.onGameResume(context, pausedSession.moves)
        } else {
            // Cold resume: new provider instance, read stored session from SharedPreferences
            val stored = GameSessionStore.loadLichessSession(context) ?: return
            val provider = LichessBotProvider()
            _activeProvider.value = provider
            _gameInfo.value = GameInfo(stored.playerColor, "Lichess Bots")
            _moves.value = emptyList() // Will be rebuilt from Lichess stream
            _storedSessions.update { it - "Lichess Bots" }
            updateResumableSessions()
            provider.setCallbacks(providerCallbacks)
            provider.onGameResume(context, emptyList())
        }

        startPolling()
        initTts(context)
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
        stopPolling()
        val resultMsg = formatGameResult(status, winner)
        _activeProvider.value?.onGameEnd()
        _activeProvider.value = null
        _gameResult.value = resultMsg
        _lastStatus.value = resultMsg
        appContext = null
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
                    recordOpponentMove(opponentMove)
                    if (isBlackPlayer) updateLastMoveStatus(MoveStatus.EXECUTED)
                }
                if (provider.executesMovesExternally) {
                    updateLastMoveStatus(MoveStatus.EXECUTED)
                    if (opponentMove == null) startWaitingForOpponent()
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
