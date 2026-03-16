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
            sendErrorToWatch(event.sourceNodeId, "Invalid notation: $move")
            return
        }

        MoveRepository.addMove(move)

        // Ask the active provider if it wants to generate an opponent move
        val opponentMove = MoveRepository.activeProvider.value?.generateOpponentMove()
        val opponentMoveSent = opponentMove != null
        if (opponentMove != null) {
            MoveRepository.setLastMoveResponse(opponentMove)
            sendMessage(event.sourceNodeId, "/chess/move", opponentMove)
            Log.d(TAG, "Provider opponent move sent: $opponentMove")
        }

        // Delegate to the accessibility service for execution
        ChessAccessibilityService.instance?.executeMove(move)
            ?: run {
                Log.w(TAG, "Accessibility service not running")
                MoveRepository.updateLastMoveStatus(
                    MoveStatus.FAILED,
                    "Accessibility service is not enabled. Please enable it in Settings."
                )
                // Only notify the watch if no response was already sent
                if (!opponentMoveSent) {
                    sendErrorToWatch(event.sourceNodeId, "Svc off")
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
