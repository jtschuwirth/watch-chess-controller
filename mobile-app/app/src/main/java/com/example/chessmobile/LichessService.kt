package com.example.chessmobile

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * HTTP client for the Lichess Board API.
 *
 * Relevant endpoints:
 *   POST /api/challenge/ai        — start a game against a Stockfish bot
 *   POST /api/board/game/{id}/move/{uci}  — submit a move in UCI notation
 *   GET  /api/board/game/stream/{id}      — NDJSON stream of game events
 */
object LichessService {

    private const val TAG = "LichessService"
    private const val BASE = "https://lichess.org"

    // -------------------------------------------------------------------------
    // Challenge AI  (POST /api/challenge/ai)
    // -------------------------------------------------------------------------

    data class GameStartResult(val gameId: String, val playerColor: ChessColor)

    /**
     * Creates a game against the Lichess Stockfish bot.
     * @param level  Bot strength 1–8 (1 = easiest, 8 = hardest).
     * @param color  "white", "black", or "random".
     * @param clockLimitSeconds  Total time per side in seconds.
     * @throws Exception on HTTP error or network failure.
     */
    suspend fun challengeAi(
        apiKey: String,
        level: Int,
        color: String,
        clockLimitSeconds: Int? = 600,
        clockIncrement: Int = 0
    ): GameStartResult = withContext(Dispatchers.IO) {
        val url = URL("$BASE/api/challenge/ai")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            connectTimeout = 10_000
            readTimeout = 15_000
            doOutput = true
        }

        val body = if (clockLimitSeconds != null) {
            "level=$level&color=$color&clock.limit=$clockLimitSeconds&clock.increment=$clockIncrement"
        } else {
            "level=$level&color=$color"
        }
        conn.outputStream.use { it.write(body.toByteArray()) }

        val responseCode = conn.responseCode
        val responseBody = try {
            conn.inputStream.bufferedReader().readText()
        } catch (e: IOException) {
            conn.errorStream?.bufferedReader()?.readText() ?: ""
        } finally {
            conn.disconnect()
        }

        if (responseCode !in 200..201) {
            throw Exception("Challenge AI failed (HTTP $responseCode): $responseBody")
        }

        val json = JSONObject(responseBody)
        val gameId = json.getString("id")
        val colorStr = json.optString("color", "white")
        val playerColor = if (colorStr == "black") ChessColor.BLACK else ChessColor.WHITE

        Log.d(TAG, "Game started: id=$gameId color=$colorStr")
        GameStartResult(gameId, playerColor)
    }

    // -------------------------------------------------------------------------
    // Make a move  (POST /api/board/game/{id}/move/{uci})
    // -------------------------------------------------------------------------

    /**
     * Submits a move in UCI notation (e.g. "e2e4", "e7e8q").
     * @return true if Lichess accepted the move.
     */
    suspend fun makeMove(apiKey: String, gameId: String, uciMove: String): Boolean =
        withContext(Dispatchers.IO) {
            val url = URL("$BASE/api/board/game/$gameId/move/$uciMove")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Authorization", "Bearer $apiKey")
                connectTimeout = 10_000
                readTimeout = 10_000
            }
            return@withContext try {
                val code = conn.responseCode
                Log.d(TAG, "makeMove $uciMove → HTTP $code")
                code == 200
            } finally {
                conn.disconnect()
            }
        }

    // -------------------------------------------------------------------------
    // Stream game events  (GET /api/board/game/stream/{id})
    // -------------------------------------------------------------------------

    /**
     * Opens an NDJSON stream for the game and dispatches events on the calling scope.
     *
     * The stream runs until cancelled or the game ends.
     *
     * @param onConnected   Called with the raw [HttpURLConnection] once open — store it to
     *                      allow forceful cancellation via [HttpURLConnection.disconnect].
     * @param onGameFull    Called once with (moves, playerColor) from the first "gameFull" event.
     * @param onGameState   Called on each subsequent "gameState" with the full space-separated
     *                      UCI move string (e.g. "e2e4 e7e5 g1f3").
     * @param onGameEnd     Called when the game ends with the Lichess status string and optional
     *                      winner ("white", "black", or null for draws).
     * @param onError       Called if the stream terminates with an unexpected error.
     */
    fun streamGame(
        scope: CoroutineScope,
        apiKey: String,
        gameId: String,
        onConnected: (HttpURLConnection) -> Unit,
        onGameFull: (moves: String, color: String) -> Unit,
        onGameState: (moves: String) -> Unit,
        onGameEnd: (status: String, winner: String?) -> Unit = { _, _ -> },
        onError: (Exception) -> Unit
    ): Job = scope.launch(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            val url = URL("$BASE/api/board/game/stream/$gameId")
            conn = (url.openConnection() as HttpURLConnection).apply {
                setRequestProperty("Authorization", "Bearer $apiKey")
                setRequestProperty("Accept", "application/x-ndjson")
                connectTimeout = 10_000
                readTimeout = 0  // Stream indefinitely
            }
            onConnected(conn)

            val reader = BufferedReader(InputStreamReader(conn.inputStream))
            var line: String?
            while (isActive) {
                line = reader.readLine() ?: break
                if (line.isBlank()) continue
                try {
                    val json = JSONObject(line)
                    when (json.optString("type")) {
                        "gameFull" -> {
                            val state = json.optJSONObject("state")
                            val moves = state?.optString("moves") ?: ""
                            val color = json.optString("color", "white")
                            onGameFull(moves, color)
                        }
                        "gameState" -> {
                            val moves = json.optString("moves", "")
                            val status = json.optString("status", "started")
                            onGameState(moves)
                            if (status != "started" && status != "created") {
                                val winner = json.optString("winner", "").ifEmpty { null }
                                Log.d(TAG, "Game ended with status: $status, winner: $winner")
                                onGameEnd(status, winner)
                                break
                            }
                        }
                        "gameFinish" -> {
                            onGameEnd("finish", null)
                            break
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Skipping malformed stream line: ${e.message}")
                }
            }
        } catch (e: IOException) {
            // Expected when disconnect() is called to cancel the stream
            if (isActive) onError(e)
        } catch (e: Exception) {
            if (isActive) onError(e)
        } finally {
            conn?.disconnect()
        }
    }
}
