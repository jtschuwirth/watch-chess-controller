package com.example.chessmobile

import android.util.Log
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService

/**
 * Receives chess move messages from the Galaxy Watch via Wearable MessageClient.
 * Message path: /chess/move
 * Payload: UTF-8 encoded algebraic notation string (e.g. "Ne4", "O-O", "exd5")
 */
class WearMessageListenerService : WearableListenerService() {

    companion object {
        private const val TAG = "WearMsgListener"
        private const val PATH_CHESS_MOVE = "/chess/move"
        private const val PATH_CHESS_STATUS_REQUEST = "/chess/status"
        private const val PATH_CHESS_SYNC = "/chess/sync"
        private const val PATH_CHESS_SYNC_RESPONSE = "/chess/sync/response"
    }

    override fun onPeerConnected(peer: com.google.android.gms.wearable.Node) {
        MoveRepository.setWatchConnected(true)
    }

    override fun onPeerDisconnected(peer: com.google.android.gms.wearable.Node) {
        MoveRepository.setWatchConnected(false)
    }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        when (messageEvent.path) {
            PATH_CHESS_MOVE -> handleMove(messageEvent)
            PATH_CHESS_STATUS_REQUEST -> handleStatusRequest(messageEvent)
            PATH_CHESS_SYNC -> handleSyncRequest(messageEvent)
            else -> Log.w(TAG, "Unknown path: ${messageEvent.path}")
        }
    }

    private fun handleMove(event: MessageEvent) {
        val move = String(event.data, Charsets.UTF_8).trim()
        Log.d(TAG, "Received move from watch: '$move' (node: ${event.sourceNodeId})")

        if (!isValidNotation(move)) {
            Log.w(TAG, "Invalid notation rejected: '$move'")
            sendErrorToWatch(event.sourceNodeId, "Invalid: $move")
            return
        }

        MoveRepository.addMove(move)

        val provider = MoveRepository.activeProvider.value
        if (provider == null) {
            MoveRepository.updateLastMoveStatus(MoveStatus.FAILED, "No active game provider.")
            sendErrorToWatch(event.sourceNodeId, "No game")
            return
        }

        // Ask the provider to handle the move. For Lichess this is async (HTTP call);
        // for RandomMoves it resolves immediately.
        provider.onPlayerMove(move, this) { opponentMove, error ->
            if (error != null) {
                Log.w(TAG, "Provider rejected move '$move': $error")
                // Remove the optimistically-added entry so the move number is reused on retry
                MoveRepository.removeLastMove()
                MoveRepository.setLastStatus("Invalid move: $move")
                sendErrorToWatch(event.sourceNodeId, error.take(12))
                return@onPlayerMove
            }

            // For Lichess: move accepted, opponent reply comes via stream (opponentMove == null here).
            // For RandomMoves: opponentMove is the immediate random reply.
            if (opponentMove != null) {
                MoveRepository.setLastMoveResponse(opponentMove)
                sendMessage(event.sourceNodeId, "/chess/move", opponentMove)
            }

            if (provider is LichessProvider) {
                // Status is set to EXECUTED here; the stream will add the response when the bot plays.
                MoveRepository.updateLastMoveStatus(MoveStatus.EXECUTED)
            } else {
                // For non-Lichess providers, execute the move via accessibility service.
                ChessAccessibilityService.instance?.executeMove(move)
                    ?: MoveRepository.updateLastMoveStatus(
                        MoveStatus.FAILED,
                        "Accessibility service is not enabled. Enable it in Settings."
                    )
            }
        }
    }

    private fun handleStatusRequest(event: MessageEvent) {
        val isActive = ChessAccessibilityService.instance != null
        val payload = if (isActive) "active" else "inactive"
        sendMessage(event.sourceNodeId, "/chess/status/response", payload)
    }

    private fun handleSyncRequest(event: MessageEvent) {
        val gameInfo = MoveRepository.gameInfo.value
        val moves = MoveRepository.moves.value

        val colorChar = when (gameInfo?.playerColor) {
            ChessColor.WHITE -> "W"
            ChessColor.BLACK -> "B"
            null -> "?"
        }

        val moveParts = moves.joinToString("|") { entry ->
            "${entry.move}:${entry.response ?: ""}"
        }

        val payload = if (moveParts.isEmpty()) colorChar else "$colorChar|$moveParts"
        sendMessage(event.sourceNodeId, PATH_CHESS_SYNC_RESPONSE, payload)
        Log.d(TAG, "Sync response sent: $payload")
    }

    private fun sendErrorToWatch(nodeId: String, error: String) {
        sendMessage(nodeId, "/chess/error", error.take(12))
    }

    private fun sendMessage(nodeId: String, path: String, payload: String) {
        com.google.android.gms.wearable.Wearable.getMessageClient(this)
            .sendMessage(nodeId, path, payload.toByteArray(Charsets.UTF_8))
            .addOnFailureListener { e ->
                Log.e(TAG, "Failed to send message to watch ($path): ${e.message}")
            }
    }

    // ---------------------------------------------------------------------------
    // Algebraic notation validation
    // ---------------------------------------------------------------------------

    private val MOVE_REGEX = Regex(
        """^(""" +
        """O-O-O|O-O|""" +                          // castling
        """[KQRBN]?[a-h]?[1-8]?x?[a-h][1-8](=[QRBN])?|""" + // piece/pawn moves
        """[a-h][1-8](=[QRBN])?""" +                 // pawn push (simple)
        """)[+#]?$"""
    )

    private fun isValidNotation(move: String): Boolean = MOVE_REGEX.matches(move)
}
