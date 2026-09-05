package com.sportapp.ui

import android.content.Context
import androidx.compose.ui.graphics.Color

/** Sötét / világos + üveg téma – tartós mentés. */
object ThemePrefs {
    private const val P = "theme_prefs"
    private const val KEY_DARK = "is_dark"
    private const val KEY_THEME = "glass_theme"

    fun isDark(ctx: Context): Boolean =
        ctx.getSharedPreferences(P, Context.MODE_PRIVATE).getBoolean(KEY_DARK, true)

    fun setDark(ctx: Context, dark: Boolean) {
        ctx.getSharedPreferences(P, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_DARK, dark)
            .apply()
    }

    fun glassTheme(ctx: Context): GlassTheme {
        val id = ctx.getSharedPreferences(P, Context.MODE_PRIVATE)
            .getString(KEY_THEME, GlassTheme.PURPLE.id) ?: GlassTheme.PURPLE.id
        return GlassTheme.fromId(id)
    }

    fun setGlassTheme(ctx: Context, theme: GlassTheme) {
        ctx.getSharedPreferences(P, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_THEME, theme.id)
            .apply()
    }
}

/**
 * Üveghatású háttér + akcentus csomagok.
 * A színek ARGB Int – Compose Color(value) kompatibilis.
 */
enum class GlassTheme(
    val id: String,
    val label: String,
    val emoji: String,
    // gradiens (sötét mód, 4 stop)
    val g1: Long, val g2: Long, val g3: Long, val g4: Long,
    // világos mód gradiens
    val lg1: Long, val lg2: Long, val lg3: Long,
    // kártya / header / liga (sötét, alpha beépítve ahol kell)
    val cardDark: Long, val headerDark: Long, val leagueDark: Long,
    val cardLight: Long, val headerLight: Long, val leagueLight: Long,
    val textDark: Long, val subDark: Long,
    val textLight: Long, val subLight: Long,
    val borderDark: Long, val borderLight: Long,
    val accent: Long,
    val primary: Long, // élő / gomb
    val glow: Long
) {
    PURPLE(
        "purple", "Sötét lila", "💜",
        0xFF0E0618, 0xFF1A0B2E, 0xFF24103F, 0xFF12081F,
        0xFFE9D5FF, 0xFFF5EEFF, 0xFFE0D0FF,
        0xB31A0B2E, 0xCC1C0F33, 0x9924123F,
        0xB3FFFFFF, 0xCCF8F0FF, 0x99E8D5FF,
        0xFFF5EEFF, 0xFFB9A3D4,
        0xFF1A0B2E, 0xFF6B5A8A,
        0x44C084FC, 0x55FFFFFF,
        0xFFA78BFA, 0xFF00E5A8, 0x55A78BFA
    ),
    DARK_BLUE(
        "dark_blue", "Sötét kék", "🔵",
        0xFF0A1628, 0xFF122445, 0xFF0D1B33, 0xFF0A1424,
        0xFFD6E8FF, 0xFFEEF5FF, 0xFFD0E4FF,
        0xCC152238, 0xD9111E33, 0x991A2D4D,
        0xB3FFFFFF, 0xCCF5F9FF, 0x99D6E6FF,
        0xFFF0F6FF, 0xFF9BB0C9,
        0xFF0D1B2A, 0xFF5A6F8A,
        0x33A0C4FF, 0x55FFFFFF,
        0xFF4DA3FF, 0xFF00E5A8, 0x334DA3FF
    ),
    DARK_GREEN(
        "dark_green", "Sötét zöld", "🟢",
        0xFF06140E, 0xFF0B2418, 0xFF0F3320, 0xFF081912,
        0xFFD5FFE9, 0xFFEEFFF5, 0xFFD0FFE0,
        0xB30B2418, 0xCC0F2E1C, 0x991A3D28,
        0xB3FFFFFF, 0xCCF0FFF6, 0x99D5FFE9,
        0xFFEEFFF5, 0xFFA3D4B9,
        0xFF0B2418, 0xFF5A8A6B,
        0x4434D399, 0x55FFFFFF,
        0xFF34D399, 0xFF00E5A8, 0x5534D399
    ),
    LIGHT_BLUE(
        "light_blue", "Világoskék", "🩵",
        0xFF0C1A2E, 0xFF153050, 0xFF1A3A5C, 0xFF0E2038,
        0xFFB8D9FF, 0xFFE8F4FF, 0xFFC8E4FF,
        0xB3153050, 0xCC1A3A5C, 0x99204870,
        0xCCFFFFFF, 0xEEF0F8FF, 0xAAD6E8FF,
        0xFFF0F8FF, 0xFFA8C4E0,
        0xFF0C2A4A, 0xFF4A6F8A,
        0x5560A5FA, 0x66FFFFFF,
        0xFF60A5FA, 0xFF22D3EE, 0x5560A5FA
    ),
    LIGHT_GREEN(
        "light_green", "Világoszöld", "🌿",
        0xFF0C1A12, 0xFF153525, 0xFF1A4030, 0xFF0E2418,
        0xFFB8FFD4, 0xFFE8FFF2, 0xFFC8FFE0,
        0xB3153525, 0xCC1A4030, 0x99205038,
        0xCCFFFFFF, 0xEEF0FFF6, 0xAAD6FFE8,
        0xFFF0FFF6, 0xFFA8E0C0,
        0xFF0C2A1A, 0xFF4A8A6A,
        0x554ADE80, 0x66FFFFFF,
        0xFF4ADE80, 0xFF00E5A8, 0x554ADE80
    ),
    MIDNIGHT(
        "midnight", "Éjfél", "🌑",
        0xFF050508, 0xFF0C0C12, 0xFF14141E, 0xFF08080C,
        0xFFE8E8F0, 0xFFF4F4F8, 0xFFDEDEE8,
        0xB312121A, 0xCC181822, 0x9920202C,
        0xB3FFFFFF, 0xCCF4F4F8, 0x99E0E0E8,
        0xFFF0F0F5, 0xFFA0A0B0,
        0xFF12121A, 0xFF606070,
        0x33FFFFFF, 0x55FFFFFF,
        0xFFE0E0E8, 0xFF00E5A8, 0x33FFFFFF
    ),
    CRIMSON(
        "crimson", "Karmazsin", "🔴",
        0xFF140608, 0xFF2A0C12, 0xFF3A1018, 0xFF18080C,
        0xFFFFD5DD, 0xFFFFEEF1, 0xFFFFD0D8,
        0xB32A0C12, 0xCC3A1018, 0x99481820,
        0xB3FFFFFF, 0xCCFFF5F7, 0x99FFD5DD,
        0xFFFFEEF1, 0xFFD4A3AB,
        0xFF2A0C12, 0xFF8A5A62,
        0x44F87171, 0x55FFFFFF,
        0xFFF87171, 0xFF00E5A8, 0x55F87171
    ),
    AMBER(
        "amber", "Borostyán", "🟠",
        0xFF141006, 0xFF2A1E0A, 0xFF3A2A10, 0xFF181208,
        0xFFFFE9C8, 0xFFFFF6E8, 0xFFFFE0B0,
        0xB32A1E0A, 0xCC3A2A10, 0x99483818,
        0xB3FFFFFF, 0xCCFFF8EE, 0x99FFE9C8,
        0xFFFFF6E8, 0xFFD4C0A3,
        0xFF2A1E0A, 0xFF8A705A,
        0x44FBBF24, 0x55FFFFFF,
        0xFFFBBF24, 0xFF00E5A8, 0x55FBBF24
    );

    fun c(v: Long) = Color(v)

    companion object {
        fun fromId(id: String): GlassTheme =
            entries.find { it.id == id } ?: PURPLE
    }
}
