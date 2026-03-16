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
    }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        when (messageEvent.path) {
            PATH_CHESS_MOVE -> handleMove(messageEvent)
            PATH_CHESS_STATUS_REQUEST -> handleStatusRequest(messageEvent)
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

        // Delegate to the accessibility service for execution
        ChessAccessibilityService.instance?.executeMove(move)
            ?: run {
                Log.w(TAG, "Accessibility service not running")
                MoveRepository.updateLastMoveStatus(
                    MoveStatus.FAILED,
                    "Accessibility service is not enabled. Please enable it in Settings."
                )
                sendErrorToWatch(event.sourceNodeId, "Accessibility service not active")
            }
    }

    private fun handleStatusRequest(event: MessageEvent) {
        val isActive = ChessAccessibilityService.instance != null
        val payload = if (isActive) "active" else "inactive"
        sendMessage(event.sourceNodeId, "/chess/status/response", payload)
    }

    private fun sendErrorToWatch(nodeId: String, error: String) {
        sendMessage(nodeId, "/chess/error", error)
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
