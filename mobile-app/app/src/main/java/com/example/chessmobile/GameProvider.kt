package com.example.chessmobile

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
 *   - (opponentMove, null)  — move accepted; opponentMove is non-null when the response is
 *                             immediate (e.g. RandomMovesProvider), null when async (Lichess).
 *   - (null, errorMsg)      — move rejected; errorMsg describes the problem.
 *
 * For async providers like Lichess, the opponent's reply arrives later via the internal stream
 * and is pushed directly to MoveRepository + the watch.
 */
interface GameProvider {
    val name: String
    fun onPlayerMove(san: String, context: Context, callback: (opponentMove: String?, error: String?) -> Unit)
    fun onGameStart(config: ProviderConfig, context: Context) {}
    fun onGameEnd() {}
    fun onResign(context: Context) {}
    fun onDrawOffer(context: Context) {}
}

// ---------------------------------------------------------------------------
// Random Moves Provider
// ---------------------------------------------------------------------------

object RandomMovesProvider : GameProvider {
    override val name = "Random Moves"

    override fun onPlayerMove(san: String, context: Context, callback: (String?, String?) -> Unit) {
        val file = ('a' + Random.nextInt(8))
        val rank = Random.nextInt(8) + 1
        callback("$file$rank", null)
    }

    override fun onGameStart(config: ProviderConfig, context: Context) {
        val cfg = config as? ProviderConfig.RandomMoves ?: return
        val playerColor = resolveColor(cfg.color)
        if (playerColor == ChessColor.BLACK) {
            MoveRepository.triggerRandomFirstMove(context)
        }
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
                playerColor = result.playerColor
                board.reset()
                lastKnownMoveCount = 0

                // If the API returned a different color than what was displayed (RANDOM case),
                // update the repository and notify the watch.
                val ctx = appContext ?: return@launch
                val currentInfo = MoveRepository.gameInfo.value
                if (currentInfo != null && currentInfo.playerColor != result.playerColor) {
                    MoveRepository.updateGameColor(result.playerColor, ctx)
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
        val uci = board.sanToUci(san) ?: run {
            callback(null, "Cannot convert move: $san")
            return
        }

        // Register callback — opponent's response will arrive via the stream
        pendingMoveCallback = callback

        scope.launch {
            try {
                val success = LichessService.makeMove(apiKey, gId, uci)
                if (success) {
                    // Apply our move to the local board so it stays in sync
                    board.applyUci(uci)
                    // callback(null, null) is intentionally NOT called here — the stream will
                    // deliver the status update and any opponent reply via handleGameState().
                    // We DO call it to signal move acceptance (no opponent move yet).
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

    override fun onGameEnd() {
        streamConnection?.disconnect()  // Unblocks the readLine() call in the stream coroutine
        streamJob?.cancel()
        scope.cancel()
        gameId = null
        pendingMoveCallback = null
        streamConnection = null
        appContext = null
    }

    // -------------------------------------------------------------------------
    // Stream event handling
    // -------------------------------------------------------------------------

    private fun handleGameState(moves: String) {
        if (moves.isBlank()) {
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

        // Apply all new moves except the last, then convert the last to SAN before applying it
        // (uciToSan needs the board state prior to the move being applied)
        for (i in lastKnownMoveCount until newCount - 1) {
            board.applyUci(uciList[i])
        }
        val lastUci = uciList[newCount - 1]
        val lastSan = board.uciToSan(lastUci)
        board.applyUci(lastUci)

        lastKnownMoveCount = newCount

        if (isOpponentMove) {
            onOpponentMoved(san = lastSan, uci = lastUci)
        }
    }

    private fun onOpponentMoved(san: String, uci: String) {
        Log.d("LichessProvider", "Opponent played: $uci (SAN: $san)")
        MoveRepository.setLastMoveResponse(san)
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
