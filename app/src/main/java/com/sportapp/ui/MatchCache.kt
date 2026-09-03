package com.sportapp.ui

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.sportapp.models.MatchResponse

/**
 * Egyszerű offline cache: utolsó sikeres meccslista JSON-ként.
 * Nethiba esetén a UI még mindig mutat adatot.
 */
object MatchCache {
    private const val P = "match_list_cache"
    private const val KEY_JSON = "matches_json"
    private const val KEY_DATE = "matches_date"
    private const val KEY_TS = "matches_ts"

    private val gson = Gson()
    private val type = object : TypeToken<List<MatchResponse>>() {}.type

    fun save(ctx: Context, dateIso: String, matches: List<MatchResponse>) {
        if (matches.isEmpty()) return
        try {
            val json = gson.toJson(matches, type)
            ctx.getSharedPreferences(P, Context.MODE_PRIVATE).edit()
                .putString(KEY_JSON, json)
                .putString(KEY_DATE, dateIso)
                .putLong(KEY_TS, System.currentTimeMillis())
                .apply()
        } catch (_: Exception) {
        }
    }

    fun load(ctx: Context, dateIso: String): List<MatchResponse> {
        return try {
            val prefs = ctx.getSharedPreferences(P, Context.MODE_PRIVATE)
            val storedDate = prefs.getString(KEY_DATE, null) ?: return emptyList()
            if (storedDate != dateIso) return emptyList()
            val json = prefs.getString(KEY_JSON, null) ?: return emptyList()
            gson.fromJson<List<MatchResponse>>(json, type).orEmpty()
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun lastUpdated(ctx: Context): Long =
        ctx.getSharedPreferences(P, Context.MODE_PRIVATE).getLong(KEY_TS, 0L)
}
