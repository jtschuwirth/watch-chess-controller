package com.example.chessmobile

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Accessibility service that executes chess moves inside Chess.com or Lichess
 * by interacting with the app's UI nodes.
 *
 * NOTE: This is a demo scaffold. Real execution logic must be adapted to the
 * specific UI hierarchy of the target app version.
 */
class ChessAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "ChessA11y"

        // Singleton reference so WearMessageListenerService can reach us
        var instance: ChessAccessibilityService? = null
            private set
    }

    override fun onServiceConnected() {
        instance = this
        Log.d(TAG, "Accessibility service connected")
        MoveRepository.updateLastMoveStatus(
            MoveStatus.PENDING,
            "Accessibility service connected and ready."
        )
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility service interrupted")
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        // Future: detect opponent moves from screen content changes
        // and relay them to the watch / TTS
    }

    // ---------------------------------------------------------------------------
    // Move execution
    // ---------------------------------------------------------------------------

    /**
     * Entry point called by [WearMessageListenerService] when a move arrives.
     * Parses the algebraic notation and taps the appropriate board squares.
     */
    fun executeMove(move: String) {
        Log.d(TAG, "Executing move: $move")

        val boardNode = findChessBoardNode()
        if (boardNode == null) {
            Log.w(TAG, "Chess board not found in current screen")
            MoveRepository.updateLastMoveStatus(MoveStatus.FAILED, "Chess board not visible on screen.")
            return
        }

        val squares = parseMovesToSquares(move) ?: run {
            Log.w(TAG, "Could not parse squares from move: $move")
            MoveRepository.updateLastMoveStatus(MoveStatus.FAILED, "Could not parse move: $move")
            return
        }

        tapSquares(boardNode, squares)
    }

    /** Walks the window hierarchy looking for the chess board view. */
    private fun findChessBoardNode(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        // Chess.com uses a WebView-based board; Lichess uses a custom View.
        // Look for common content descriptions or class names.
        return findNodeByDescription(root, listOf("chess board", "chessboard", "board"))
    }

    private fun findNodeByDescription(
        node: AccessibilityNodeInfo,
        keywords: List<String>
    ): AccessibilityNodeInfo? {
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""
        if (keywords.any { it in desc }) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findNodeByDescription(child, keywords)?.let { return it }
        }
        return null
    }

    /**
     * Converts algebraic notation to a list of (file, rank) square pairs.
     * For a standard move we need: source square + destination square.
     * This demo resolves only the destination from simple notation.
     * Full disambiguation requires tracking board state.
     */
    private fun parseMovesToSquares(move: String): List<Pair<Int, Int>>? {
        // Strip check/checkmate suffixes
        val clean = move.trimEnd('+', '#')

        // Castling
        if (clean == "O-O") return listOf(Pair(4, 0), Pair(6, 0))   // e1 → g1 (white kingside)
        if (clean == "O-O-O") return listOf(Pair(4, 0), Pair(2, 0)) // e1 → c1 (white queenside)

        // Extract destination square (last two chars are always the target)
        val dest = clean.takeLast(2)
        if (dest.length < 2) return null
        val file = dest[0] - 'a'  // 0–7
        val rank = dest[1] - '1'  // 0–7
        if (file !in 0..7 || rank !in 0..7) return null

        // For a full implementation we'd also resolve the source square.
        // Here we return only the destination for demonstration.
        return listOf(Pair(file, rank))
    }

    /** Taps each square in sequence on the board node's bounding rect. */
    private fun tapSquares(boardNode: AccessibilityNodeInfo, squares: List<Pair<Int, Int>>) {
        val bounds = Rect()
        boardNode.getBoundsInScreen(bounds)

        val squareW = bounds.width() / 8f
        val squareH = bounds.height() / 8f

        squares.forEach { (file, rank) ->
            // Rank 0 = rank 1 visually at the bottom (white's perspective)
            val x = bounds.left + squareW * file + squareW / 2f
            val y = bounds.bottom - squareH * rank - squareH / 2f
            performTap(x, y)
        }

        MoveRepository.updateLastMoveStatus(MoveStatus.EXECUTED)
    }

    private fun performTap(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 50L)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription) {
                Log.d(TAG, "Tap at ($x, $y) completed")
            }
            override fun onCancelled(gestureDescription: GestureDescription) {
                Log.w(TAG, "Tap at ($x, $y) cancelled")
            }
        }, null)
    }
}
