package com.example.chessmobile

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.HttpURLConnection
import kotlin.random.Random

enum class ColorChoice { WHITE, BLACK, RANDOM }

enum class TimeControl(val label: String, val limitSeconds: Int?, val increment: Int) {
    TEN_MIN("10 min", 600, 0),
    THIRTY_MIN("30 min", 1800, 0),
    UNLIMITED("Unlimited", null, 0)
}

sealed class ProviderConfig {
    data class RandomMoves(val color: ColorChoice) : ProviderConfig()
    data class Lichess(
        val level: Int,
        val color: ColorChoice = ColorChoice.WHITE,
        val timeControl: TimeControl = TimeControl.TEN_MIN
    ) : ProviderConfig()
}

/**
 * Abstraction for a game opponent.
 *
 * [onPlayerMove] is called when the player submits a move from the watch. It is responsible
 * for submitting the move to the backend (if any) and reporting back:
 *   - (opponentMove, null)  — move accepted; opponentMove is non-null when the opponent reply
 *                             is delivered inside the callback (e.g. RandomMovesProvider).
 *                             null when the reply arrives via a separate channel (Lichess stream).
 *   - (null, errorMsg)      — move rejected; errorMsg describes the problem.
 *
 * For async providers, the opponent's reply arrives later via the internal stream
 * and is pushed directly to MoveRepository + the watch.
 */
interface GameProvider {
    val name: String
    fun onPlayerMove(san: String, context: Context, callback: (opponentMove: String?, error: String?) -> Unit)
    fun onGameStart(config: ProviderConfig, context: Context) {}
    fun onGameEnd() {}
    fun onGamePause() {}
    fun onGameResume(context: Context) {}
    fun onResign(context: Context) {}
    fun onDrawOffer(context: Context) {}
}

// ---------------------------------------------------------------------------
// Random Moves Provider
// ---------------------------------------------------------------------------

object RandomMovesProvider : GameProvider {
    override val name = "Random Moves"

    private var scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onPlayerMove(san: String, context: Context, callback: (String?, String?) -> Unit) {
        scope.launch {
            val delayMs = Random.nextLong(600L, 3001L)
            delay(delayMs)
            val file = ('a' + Random.nextInt(8))
            val rank = Random.nextInt(8) + 1
            callback("$file$rank", null)
        }
    }

    override fun onGameStart(config: ProviderConfig, context: Context) {
        val cfg = config as? ProviderConfig.RandomMoves ?: return
        val playerColor = resolveColor(cfg.color)
        if (playerColor == ChessColor.BLACK) {
            MoveRepository.triggerRandomFirstMove(context)
        }
    }

    override fun onGameEnd() {
        scope.cancel()
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    }

    override fun onResign(context: Context) {
        val gameInfo = MoveRepository.gameInfo.value ?: return
        val opponentSide = if (gameInfo.playerColor == ChessColor.WHITE) "black" else "white"
        MoveRepository.endGameWithResult("resign", opponentSide, context)
    }

    override fun onDrawOffer(context: Context) {
        MoveRepository.endGameWithResult("draw", null, context)
    }
}

// ---------------------------------------------------------------------------
// Lichess Provider
// ---------------------------------------------------------------------------

class LichessProvider : GameProvider {

    override val name = "Lichess"

    private var scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var apiKey: String = ""
    private var gameId: String? = null
    @Volatile private var playerColor: ChessColor = ChessColor.WHITE
    private val board = ChessBoard()
    @Volatile private var lastKnownMoveCount = 0
    @Volatile private var pendingMoveCallback: ((String?, String?) -> Unit)? = null
    @Volatile private var needsHistoryRebuild = false
    private var streamJob: Job? = null
    @Volatile private var streamConnection: HttpURLConnection? = null
    private var appContext: Context? = null

    override fun onGameStart(config: ProviderConfig, context: Context) {
        val cfg = config as? ProviderConfig.Lichess ?: return
        apiKey = LichessApiKeyManager.getApiKey(context) ?: run {
            Log.e("LichessProvider", "No API key stored")
            return
        }
        appContext = context.applicationContext
        // Clear any stale persisted session — we are starting fresh
        GameSessionStore.clearLichessSession(context)

        if (!scope.isActive) {
            scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        }

        val colorStr = when (cfg.color) {
            ColorChoice.WHITE -> "white"
            ColorChoice.BLACK -> "black"
            ColorChoice.RANDOM -> "random"
        }

        scope.launch {
            try {
                val result = LichessService.challengeAi(
                    apiKey = apiKey,
                    level = cfg.level,
                    color = colorStr,
                    clockLimitSeconds = cfg.timeControl.limitSeconds,
                    clockIncrement = cfg.timeControl.increment
                )
                gameId = result.gameId
                // When the user explicitly chose a color, trust that choice over the API
                // response (the API defaults to "white" when the field is absent, which
                // would incorrectly override a BLACK selection). Only use the API-reported
                // color when RANDOM was requested, since that's the only time the actual
                // assignment is unknown until the server responds.
                playerColor = if (cfg.color == ColorChoice.RANDOM) {
                    result.playerColor
                } else {
                    resolveColor(cfg.color)
                }
                board.reset()
                lastKnownMoveCount = 0

                // For RANDOM games, the API response tells us which color was assigned —
                // update the repository and notify the watch if it differs from the placeholder.
                val ctx = appContext ?: return@launch
                if (cfg.color == ColorChoice.RANDOM) {
                    val currentInfo = MoveRepository.gameInfo.value
                    if (currentInfo != null && currentInfo.playerColor != result.playerColor) {
                        MoveRepository.updateGameColor(result.playerColor, ctx)
                    }
                }

                // Start streaming game events
                streamJob = LichessService.streamGame(
                    scope = scope,
                    apiKey = apiKey,
                    gameId = result.gameId,
                    onConnected = { conn -> streamConnection = conn },
                    onGameFull = { initialMoves, _ ->
                        // Process any moves already played (e.g., if we reconnect mid-game)
                        if (initialMoves.isNotEmpty()) {
                            handleGameState(initialMoves)
                        }
                    },
                    onGameState = { moves ->
                        handleGameState(moves)
                    },
                    onGameEnd = { status, winner ->
                        val ctx = appContext ?: return@streamGame
                        MoveRepository.endGameWithResult(status, winner, ctx)
                    },
                    onError = { e ->
                        Log.e("LichessProvider", "Stream error: ${e.message}")
                    }
                )
            } catch (e: Exception) {
                Log.e("LichessProvider", "Failed to start Lichess game: ${e.message}")
                MoveRepository.updateLastMoveStatus(
                    MoveStatus.FAILED,
                    "Failed to start Lichess game: ${e.message?.take(40)}"
                )
            }
        }
    }

    override fun onPlayerMove(san: String, context: Context, callback: (String?, String?) -> Unit) {
        val gId = gameId ?: run { callback(null, "No active Lichess game"); return }
        val uci = synchronized(board) { board.sanToUci(san) } ?: run {
            callback(null, "Cannot convert move: $san")
            return
        }

        // Register callback — opponent's response will arrive via the stream
        pendingMoveCallback = callback

        scope.launch {
            try {
                val success = LichessService.makeMove(apiKey, gId, uci)
                if (success) {
                    // Do NOT apply to the local board here — the stream is the sole
                    // source of truth for board state (see handleGameState full rebuild).
                    callback(null, null)
                    pendingMoveCallback = null
                } else {
                    pendingMoveCallback = null
                    callback(null, "Lichess rejected the move")
                }
            } catch (e: Exception) {
                pendingMoveCallback = null
                callback(null, "Network error: ${e.message?.take(30)}")
            }
        }
    }

    override fun onResign(context: Context) {
        val gId = gameId ?: return
        scope.launch {
            try {
                LichessService.resign(apiKey, gId)
                // Stream will fire a gameState with status="resign" → MoveRepository.endGameWithResult
            } catch (e: Exception) {
                Log.e("LichessProvider", "Resign failed: ${e.message}")
            }
        }
    }

    override fun onDrawOffer(context: Context) {
        val gId = gameId ?: return
        scope.launch {
            try {
                LichessService.offerOrAcceptDraw(apiKey, gId)
                // Stream will fire a gameState with status="draw" → MoveRepository.endGameWithResult
            } catch (e: Exception) {
                Log.e("LichessProvider", "Draw offer failed: ${e.message}")
            }
        }
    }

    override fun onGameEnd() {
        streamConnection?.disconnect()  // Unblocks the readLine() call in the stream coroutine
        streamJob?.cancel()
        scope.cancel()
        appContext?.let { GameSessionStore.clearLichessSession(it) }
        gameId = null
        pendingMoveCallback = null
        streamConnection = null
        appContext = null
    }

    /**
     * Pauses the game by disconnecting the stream while preserving the game ID and board state.
     * Saves the session to SharedPreferences so it survives app restarts.
     */
    override fun onGamePause() {
        streamConnection?.disconnect()
        streamJob?.cancel()
        scope.cancel()
        // Save to persistent storage so we can resume after an app restart
        val ctx = appContext ?: return
        val gId = gameId ?: return
        val colorChar = if (playerColor == ChessColor.WHITE) "W" else "B"
        GameSessionStore.saveLichessSession(ctx, gId, colorChar)
    }

    /**
     * Resumes the game by reconnecting to the Lichess stream. Works for both warm resumes
     * (provider instance was kept in memory) and cold resumes (new instance after app restart).
     * On stream failure the session is cleared and a game-over result is reported.
     */
    override fun onGameResume(context: Context) {
        appContext = context.applicationContext

        // Load API key if this is a cold-start (fresh provider instance)
        if (apiKey.isBlank()) {
            apiKey = LichessApiKeyManager.getApiKey(context) ?: run {
                Log.e("LichessProvider", "No API key for resume")
                return
            }
        }

        // Load game ID from SharedPreferences if this is a cold-start
        val gId = gameId ?: run {
            val stored = GameSessionStore.loadLichessSession(context) ?: run {
                Log.e("LichessProvider", "No stored session for cold resume")
                return
            }
            gameId = stored.gameId
            playerColor = stored.playerColor
            stored.gameId ?: return
        }

        // Fresh scope and board for the reconnected stream
        if (!scope.isActive) {
            scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        }
        synchronized(board) { board.reset() }
        lastKnownMoveCount = 0
        needsHistoryRebuild = true

        streamJob = LichessService.streamGame(
            scope = scope,
            apiKey = apiKey,
            gameId = gId,
            onConnected = { conn -> streamConnection = conn },
            onGameFull = { initialMoves, _ ->
                if (initialMoves.isNotEmpty()) {
                    handleGameState(initialMoves)
                }
            },
            onGameState = { moves ->
                handleGameState(moves)
            },
            onGameEnd = { status, winner ->
                val ctx = appContext ?: return@streamGame
                GameSessionStore.clearLichessSession(ctx)
                MoveRepository.endGameWithResult(status, winner, ctx)
            },
            onError = { e ->
                Log.e("LichessProvider", "Stream resume error: ${e.message}")
                val ctx = appContext ?: return@streamGame
                GameSessionStore.clearLichessSession(ctx)
                MoveRepository.endGameWithResult("finish", null, ctx)
            }
        )
    }

    // -------------------------------------------------------------------------
    // Stream event handling
    // -------------------------------------------------------------------------

    private fun handleGameState(moves: String) {
        if (moves.isBlank()) {
            synchronized(board) { board.reset() }
            lastKnownMoveCount = 0
            return
        }
        val uciList = moves.trim().split(" ")
        val newCount = uciList.size
        if (newCount <= lastKnownMoveCount) return

        // Determine if the most recent new move was the opponent's.
        // Move indices are 0-based: White plays on 0, 2, 4… Black plays on 1, 3, 5…
        // If player is WHITE: opponent (Black) plays on odd indices.
        // If player is BLACK: opponent (White) plays on even indices.
        val latestIdx = newCount - 1
        val isOpponentMove = if (playerColor == ChessColor.WHITE) {
            latestIdx % 2 == 1
        } else {
            latestIdx % 2 == 0
        }

        // Full rebuild from the authoritative Lichess move list. This prevents any drift
        // from the race condition between the stream coroutine and the move-submission
        // coroutine, both of which run on Dispatchers.IO. synchronized(board) ensures
        // sanToUci (called from onPlayerMove) never reads a partially-updated board.
        val lastUci = uciList[newCount - 1]
        val lastSan: String
        // On resume we need all SANs to rebuild the move history; otherwise just the last.
        val allSans = if (needsHistoryRebuild) mutableListOf<String>() else null
        synchronized(board) {
            board.reset()
            for (i in 0 until newCount - 1) {
                allSans?.add(board.uciToSan(uciList[i]))
                board.applyUci(uciList[i])
            }
            lastSan = board.uciToSan(lastUci)
            allSans?.add(lastSan)
            board.applyUci(lastUci)
        }

        lastKnownMoveCount = newCount

        if (needsHistoryRebuild) {
            needsHistoryRebuild = false
            // Rebuild the full move history from the stream's authoritative UCI list.
            // Entry invariant: move=white half (even indices), response=black half (odd indices).
            val entries = mutableListOf<MoveEntry>()
            var i = 0
            while (i < allSans!!.size) {
                entries.add(MoveEntry(
                    moveNumber = entries.size + 1,
                    move = allSans[i],
                    response = allSans.getOrNull(i + 1),
                    status = MoveStatus.EXECUTED
                ))
                i += 2
            }
            MoveRepository.restoreHistory(entries)
            return
        }

        if (isOpponentMove) {
            onOpponentMoved(san = lastSan, uci = lastUci)
        }
    }

    private fun onOpponentMoved(san: String, uci: String) {
        Log.d("LichessProvider", "Opponent played: $uci (SAN: $san)")
        MoveRepository.recordOpponentMove(san)
        MoveRepository.updateLastMoveStatus(MoveStatus.EXECUTED)

        // Forward the opponent's move to the watch in SAN for display
        val ctx = appContext ?: return
        Wearable.getNodeClient(ctx).connectedNodes.addOnSuccessListener { nodes ->
            nodes.forEach { node ->
                Wearable.getMessageClient(ctx)
                    .sendMessage(node.id, "/chess/move", san.toByteArray(Charsets.UTF_8))
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

fun resolveColor(choice: ColorChoice): ChessColor = when (choice) {
    ColorChoice.WHITE -> ChessColor.WHITE
    ColorChoice.BLACK -> ChessColor.BLACK
    ColorChoice.RANDOM -> if (Random.nextBoolean()) ChessColor.WHITE else ChessColor.BLACK
}
