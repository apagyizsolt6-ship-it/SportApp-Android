package com.sportapp.ui

import android.content.Context

/** Sötét / világos téma – tartós mentés. */
object ThemePrefs {
    private const val P = "theme_prefs"
    private const val KEY_DARK = "is_dark"

    fun isDark(ctx: Context): Boolean =
        ctx.getSharedPreferences(P, Context.MODE_PRIVATE).getBoolean(KEY_DARK, true)

    fun setDark(ctx: Context, dark: Boolean) {
        ctx.getSharedPreferences(P, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_DARK, dark)
            .apply()
    }
}
