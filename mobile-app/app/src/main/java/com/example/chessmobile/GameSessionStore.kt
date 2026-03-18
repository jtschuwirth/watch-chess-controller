package com.example.chessmobile

import android.content.Context

/**
 * Persistent storage for resumable game sessions across app restarts.
 * Uses plain (non-encrypted) SharedPreferences — game IDs are not secrets.
 */
object GameSessionStore {

    private const val PREFS_FILE = "game_sessions"
    private const val KEY_LICHESS_GAME_ID = "lichess_game_id"
    private const val KEY_LICHESS_PLAYER_COLOR = "lichess_player_color"

    private fun getPrefs(context: Context) =
        context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    fun saveLichessSession(context: Context, gameId: String, colorChar: String) {
        getPrefs(context).edit()
            .putString(KEY_LICHESS_GAME_ID, gameId)
            .putString(KEY_LICHESS_PLAYER_COLOR, colorChar)
            .apply()
    }

    fun loadLichessSession(context: Context): ResumableSession? {
        val prefs = getPrefs(context)
        val gameId = prefs.getString(KEY_LICHESS_GAME_ID, null) ?: return null
        val colorStr = prefs.getString(KEY_LICHESS_PLAYER_COLOR, null) ?: return null
        val color = if (colorStr == "W") ChessColor.WHITE else ChessColor.BLACK
        return ResumableSession(providerName = "Lichess", playerColor = color, gameId = gameId)
    }

    fun clearLichessSession(context: Context) {
        getPrefs(context).edit()
            .remove(KEY_LICHESS_GAME_ID)
            .remove(KEY_LICHESS_PLAYER_COLOR)
            .apply()
    }
}
