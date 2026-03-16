package com.example.chessmobile

import android.util.Log
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Receives chess move messages from the Galaxy Watch via Wearable MessageClient.
 * Message path: /chess/move
 * Payload: UTF-8 encoded algebraic notation string (e.g. "Ne4", "O-O", "exd5")
 */
class WearMessageListenerService : WearableListenerService() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    companion object {
        private const val TAG = "WearMsgListener"
        private const val PATH_CHESS_MOVE = "/chess/move"
        private const val PATH_CHESS_STATUS_REQUEST = "/chess/status"
        private const val PATH_CHESS_SYNC = "/chess/sync"
        private const val PATH_CHESS_SYNC_RESPONSE = "/chess/sync/response"
        private const val PATH_CHESS_RESIGN = "/chess/resign"
        private const val PATH_CHESS_DRAW_OFFER = "/chess/draw/offer"
        private const val PATH_CHESS_HOME = "/chess/home"
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
            PATH_CHESS_RESIGN -> handleResign()
            PATH_CHESS_DRAW_OFFER -> handleDrawOffer()
            PATH_CHESS_HOME -> {
                Log.d(TAG, "Home requested by watch")
                MoveRepository.requestNavigateHome()
            }
            else -> Log.w(TAG, "Unknown path: ${messageEvent.path}")
        }
    }

    private fun handleMove(event: MessageEvent) {
        val move = String(event.data, Charsets.UTF_8).trim()
        Log.d(TAG, "Received move from watch: '$move' (node: ${event.sourceNodeId})")
        serviceScope.launch {
            val error = MoveRepository.processPlayerMove(move, this@WearMessageListenerService)
            if (error != null) {
                Log.w(TAG, "Move rejected: '$move' — $error")
                sendErrorToWatch(event.sourceNodeId, error)
            } else {
                // For RandomMoves the opponent response is set synchronously; forward it to the watch.
                val response = MoveRepository.moves.value.lastOrNull()?.response
                if (response != null) {
                    sendMessage(event.sourceNodeId, "/chess/move", response)
                }
            }
        }
    }

    private fun handleResign() {
        val provider = MoveRepository.activeProvider.value
        if (provider == null) {
            Log.w(TAG, "Resign received but no active provider")
            return
        }
        Log.d(TAG, "Player resigned")
        provider.onResign(this)
    }

    private fun handleDrawOffer() {
        val provider = MoveRepository.activeProvider.value
        if (provider == null) {
            Log.w(TAG, "Draw offer received but no active provider")
            return
        }
        Log.d(TAG, "Player offered/accepted draw")
        provider.onDrawOffer(this)
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

}
