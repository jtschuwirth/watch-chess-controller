package com.example.chessmobile

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class SavedPosition(val name: String, val fen: String)

object SavedPositionsStore {

    private const val PREFS_FILE = "saved_positions"
    private const val KEY_POSITIONS = "positions"

    private fun getPrefs(context: Context) =
        context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    fun getAll(context: Context): List<SavedPosition> {
        val json = getPrefs(context).getString(KEY_POSITIONS, "[]") ?: "[]"
        return try {
            val array = JSONArray(json)
            (0 until array.length()).map { i ->
                val obj = array.getJSONObject(i)
                SavedPosition(obj.getString("name"), obj.getString("fen"))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun save(context: Context, position: SavedPosition) {
        val current = getAll(context).toMutableList()
        current.add(position)
        persist(context, current)
    }

    fun delete(context: Context, name: String) {
        val updated = getAll(context).filter { it.name != name }
        persist(context, updated)
    }

    fun nameExists(context: Context, name: String): Boolean =
        getAll(context).any { it.name.equals(name, ignoreCase = true) }

    private fun persist(context: Context, positions: List<SavedPosition>) {
        val array = JSONArray()
        positions.forEach { pos ->
            array.put(JSONObject().apply {
                put("name", pos.name)
                put("fen", pos.fen)
            })
        }
        getPrefs(context).edit().putString(KEY_POSITIONS, array.toString()).apply()
    }
}
