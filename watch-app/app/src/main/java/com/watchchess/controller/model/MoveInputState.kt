package com.watchchess.controller.model

/**
 * A single full-move pair: the user's move (white) and the phone's response (black).
 */
data class MovePair(
    val number: Int,
    val white: String,
    val black: String? = null
)

/**
 * Represents the full UI state for the chess move input screen.
 *
 * @param currentInput The move string currently being built by the user (e.g., "Ne").
 * @param moveHistory  The ordered list of move pairs that have been sent/responded to.
 * @param playerColor  The player's piece color synced from the phone: "W", "B", or null if unknown.
 * @param gameResult   Non-null when the game has ended; contains the human-readable result message.
 */
data class MoveInputState(
    val currentInput: String = "",
    val moveHistory: List<MovePair> = emptyList(),
    val playerColor: String? = null,
    val gameResult: String? = null
) {
    val hasInput: Boolean get() = currentInput.isNotEmpty()
    val gameActive: Boolean get() = (playerColor == "W" || playerColor == "B") && gameResult == null
    val gameOver: Boolean get() = gameResult != null

    /** The most recent move played (by either side), or null if no moves yet. */
    val lastMove: String? get() {
        val last = moveHistory.lastOrNull() ?: return null
        return last.black ?: last.white.ifEmpty { null }
    }

    /**
     * True when it is the local player's turn to move.
     *
     * - White: turn when history is empty (opening) or the last pair already has a black response.
     * - Black: turn when the last pair has a white move but no black reply yet.
     */
    val isMyTurn: Boolean get() = when (playerColor) {
        "W" -> moveHistory.isEmpty() || moveHistory.last().black != null
        "B" -> moveHistory.isNotEmpty() && moveHistory.last().black == null
        else -> false
    }

    fun append(char: String): MoveInputState = copy(currentInput = currentInput + char)

    fun backspace(): MoveInputState = if (currentInput.isEmpty()) this
    else copy(currentInput = currentInput.dropLast(1))

    fun clear(): MoveInputState = copy(currentInput = "")

    /**
     * Sends the current input. When playing white (or unknown), creates a new MovePair in the
     * white column. When playing black, fills the black column of the last pending pair
     * (the opponent's white move that is waiting for a response).
     */
    fun send(): MoveInputState = if (currentInput.isBlank()) this
    else {
        val move = currentInput.trim()
        if (playerColor == "B") {
            val lastPendingIdx = moveHistory.indexOfLast { it.black == null }
            if (lastPendingIdx >= 0) {
                val updated = moveHistory.toMutableList()
                updated[lastPendingIdx] = updated[lastPendingIdx].copy(black = move)
                copy(moveHistory = updated, currentInput = "")
            } else {
                // No pending opponent move yet (edge case), record anyway
                copy(
                    moveHistory = moveHistory + MovePair(
                        number = moveHistory.size + 1,
                        white = "",
                        black = move
                    ),
                    currentInput = ""
                )
            }
        } else {
            copy(
                moveHistory = moveHistory + MovePair(
                    number = moveHistory.size + 1,
                    white = move
                ),
                currentInput = ""
            )
        }
    }

    /**
     * Handles an incoming phone message:
     * - Playing white: it's the opponent's black response → fill black column of last pair.
     * - Playing black, non-error: it's the opponent's white move → create a new pair.
     * - Error (any color): fill black column of last pair with the error text.
     */
    fun receiveResponse(response: String, isError: Boolean = false): MoveInputState {
        return when {
            isError -> {
                // The move was rejected — undo the optimistic add so the move number is
                // reused when the user retries with a corrected move.
                if (moveHistory.isEmpty()) this
                else copy(moveHistory = moveHistory.dropLast(1))
            }
            playerColor == "B" -> {
                // Opponent's white move arrives → new row, black column pending user's reply
                copy(
                    moveHistory = moveHistory + MovePair(
                        number = moveHistory.size + 1,
                        white = response
                    )
                )
            }
            else -> {
                // White player's flow: fill black column of last pair with opponent response
                if (moveHistory.isEmpty()) this
                else {
                    val updated = moveHistory.last().copy(black = response)
                    copy(moveHistory = moveHistory.dropLast(1) + updated)
                }
            }
        }
    }

    /**
     * Replaces the move history and player color from a sync payload sent by the phone.
     *
     * Payload format: "{colorChar}|{move1}:{response1}|{move2}:{response2}|..."
     * - colorChar: "W" (player=White), "B" (player=Black), "?" (no game active)
     *
     * For WHITE: each entry = (yourWhiteMove : opponentBlackResponse)
     * For BLACK: entry[0] = (opponentWhiteMove1 : ""), entry[k≥1] = (yourBlackMove : nextOpponentWhiteMove)
     *   → reconstructed as standard pairs: (white, black) per chess move number.
     */
    fun applySync(payload: String): MoveInputState {
        if (payload.isBlank()) return this
        val parts = payload.split("|")
        val colorChar = parts[0]
        if (colorChar == "?") return copy(playerColor = null)
        val newColor = if (colorChar == "W" || colorChar == "B") colorChar else null

        val entries = if (parts.size <= 1) emptyList() else {
            parts.drop(1).mapNotNull { part ->
                val colonIdx = part.indexOf(':')
                if (colonIdx < 0) return@mapNotNull null
                val move = part.substring(0, colonIdx)
                val response = part.substring(colonIdx + 1).ifEmpty { null }
                if (move.isBlank()) null else Pair(move, response)
            }
        }

        val newHistory: List<MovePair> = if (newColor == "B") {
            // entry[0]  = (whiteMove1, null)           ← phone's opening move
            // entry[k≥1] = (userBlackMove, whiteNextMove or null)
            // Reconstruct: pair[n] = { white = whiteMove[n], black = userBlackMove[n] }
            val result = mutableListOf<MovePair>()
            if (entries.isNotEmpty()) {
                val firstWhite = entries[0].first
                val firstBlack = if (entries.size > 1) entries[1].first else null
                result.add(MovePair(number = 1, white = firstWhite, black = firstBlack))
                for (k in 1 until entries.size) {
                    val nextWhite = entries[k].second ?: break
                    val nextBlack = if (k + 1 < entries.size) entries[k + 1].first else null
                    result.add(MovePair(number = result.size + 1, white = nextWhite, black = nextBlack))
                }
            }
            result
        } else {
            entries.mapIndexed { index, (move, response) ->
                MovePair(number = index + 1, white = move, black = response)
            }
        }

        return copy(moveHistory = newHistory, playerColor = newColor, currentInput = "")
    }
}
