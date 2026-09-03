package com.sportapp.ui

/** Compose Text() soha ne kapjon null-t. */
fun safeText(s: String?): String = s?.trim().orEmpty()

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import com.sportapp.MatchReminderReceiver
import com.sportapp.models.MatchResponse
import java.text.Collator
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Calendar
import java.util.Locale

internal fun isMatchFinished(status: String?): Boolean {
    val s = status?.trim()?.uppercase()?.replace(".", "")?.replace(" ", "") ?: return false
    return s in setOf(
        "FT", "AET", "PEN", "PENS", "PSO", "FINISHED", "FULLTIME", "FULL-TIME",
        "ENDED", "AFTEREXTRATIME"
    )
}

internal fun isMatchLive(status: String?, minute: Int?): Boolean {
    if (isMatchFinished(status)) return false
    val s = status?.trim()?.uppercase()?.replace(".", "") ?: ""
    if (s in setOf("1H", "2H", "HT", "LIVE", "ET", "INPLAY")) return true
    // státusz maga a perc (régi feed)
    if (s.toIntOrNull() != null) return true
    val min = minute ?: 0
    if (min <= 0) return false
    // kezdési idő (20:45) ne legyen élő
    if (s.contains(":")) return false
    if (s in setOf("NS", "TBD", "SCHEDULED")) return false
    return true
}

/** 0..4 = TOP sorrend, null = nem TOP. */



/** Kickoff millis, vagy null. */
internal fun matchKickoffMillis(match: MatchResponse): Long? {
    val timeStr = match.kickoffTime?.trim()?.takeIf { it.contains(":") }
        ?: (match.status ?: "").trim().takeIf { it.contains(":") && it.length <= 5 }
        ?: return null
    val parts = timeStr.split(":")
    if (parts.size < 2) return null
    val hour = parts[0].toIntOrNull() ?: return null
    val minute = parts[1].toIntOrNull() ?: return null
    return try {
        val cal = Calendar.getInstance()
        val d = match.kickoffDate?.take(10)
        if (d != null && d.length >= 10) {
            cal.set(Calendar.YEAR, d.substring(0, 4).toInt())
            cal.set(Calendar.MONTH, d.substring(5, 7).toInt() - 1)
            cal.set(Calendar.DAY_OF_MONTH, d.substring(8, 10).toInt())
        }
        cal.set(Calendar.HOUR_OF_DAY, hour)
        cal.set(Calendar.MINUTE, minute)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        cal.timeInMillis
    } catch (_: Exception) {
        null
    }
}

internal fun isStartingSoon(match: MatchResponse, withinMinutes: Int = 90): Boolean {
    if (isMatchLive(match.status, match.minute) || isMatchFinished(match.status)) return false
    val ko = matchKickoffMillis(match) ?: return false
    val diff = ko - System.currentTimeMillis()
    return diff in 0..(withinMinutes * 60_000L)
}

internal fun matchesSmartSearch(match: MatchResponse, query: String): Boolean {
    if (query.isBlank()) return true
    val q = query.trim().lowercase()
    val league = match.league.orEmpty().lowercase()
    val home = match.homeTeam.orEmpty().lowercase()
    val away = match.awayTeam.orEmpty().lowercase()
    if (home.contains(q) || away.contains(q) || league.contains(q)) return true
    val aliases = mapOf(
        "pl" to listOf("premier league"),
        "epl" to listOf("premier league"),
        "laliga" to listOf("la liga", "laliga"),
        "seriea" to listOf("serie a"),
        "bundes" to listOf("bundesliga"),
        "ligue1" to listOf("ligue 1"),
        "fradi" to listOf("ferencvaros", "ferencváros", "ftc"),
        "liv" to listOf("liverpool"),
        "barca" to listOf("barcelona"),
        "ucl" to listOf("champions league", "bajnokok"),
        "uel" to listOf("europa league"),
        "ecl" to listOf("conference league")
    )
    aliases[q]?.forEach { a ->
        if (league.contains(a) || home.contains(a) || away.contains(a)) return true
    }
    if (q in listOf("elo", "élő", "live") && isMatchLive(match.status, match.minute)) return true
    return false
}

enum class MatchSortMode { LEAGUE, TIME, LIVE_FIRST }

/** 15 perccel a kickoff előtt értesítés. Siker: true. */
internal fun scheduleMatchReminder(context: android.content.Context, match: MatchResponse): Boolean {
    val timeStr = match.kickoffTime?.trim()?.takeIf { it.contains(":") }
        ?: (match.status ?: "").trim().takeIf { it.contains(":") && it.length <= 5 }
        ?: return false
    val parts = timeStr.split(":")
    if (parts.size < 2) return false
    val hour = parts[0].toIntOrNull() ?: return false
    val minute = parts[1].toIntOrNull() ?: return false

    val cal = Calendar.getInstance().apply {
        // kickoff dátum
        val d = match.kickoffDate?.take(10)
        if (d != null && d.length >= 10) {
            try {
                val y = d.substring(0, 4).toInt()
                val mo = d.substring(5, 7).toInt()
                val day = d.substring(8, 10).toInt()
                set(Calendar.YEAR, y)
                set(Calendar.MONTH, mo - 1)
                set(Calendar.DAY_OF_MONTH, day)
            } catch (_: Exception) {
            }
        }
        set(Calendar.HOUR_OF_DAY, hour)
        set(Calendar.MINUTE, minute)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
        add(Calendar.MINUTE, -15)
    }
    if (cal.timeInMillis <= System.currentTimeMillis()) return false

    val intent = Intent(context, MatchReminderReceiver::class.java).apply {
        putExtra(MatchReminderReceiver.EXTRA_TITLE, "⚽ Hamarosan kezdődik")
        putExtra(
            MatchReminderReceiver.EXTRA_BODY,
            "${match.homeTeam.orEmpty()} vs ${match.awayTeam.orEmpty()} · ${match.kickoffTime ?: timeStr}"
        )
        putExtra(MatchReminderReceiver.EXTRA_MATCH_ID, match.id)
    }
    val pi = PendingIntent.getBroadcast(
        context,
        match.id.hashCode(),
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
    val am = context.getSystemService(android.content.Context.ALARM_SERVICE) as AlarmManager
    return try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pi)
        } else {
            am.setExact(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pi)
        }
        true
    } catch (_: Exception) {
        try {
            am.set(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pi)
            true
        } catch (_: Exception) {
            false
        }
    }
}

internal fun todayIso(): String {
    return try {
        LocalDate.now().toString()
    } catch (_: Exception) {
        java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            .format(java.util.Date())
    }
}

internal fun dateIsoWithOffset(offsetDays: Int): String {
    return try {
        LocalDate.now().plusDays(offsetDays.toLong()).toString()
    } catch (_: Exception) {
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, offsetDays)
        java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(cal.time)
    }
}

internal fun matchKickoffDate(match: MatchResponse): String {
    val d = match.kickoffDate?.trim().orEmpty()
    if (d.length >= 10) return d.take(10)
    // Ütemezett / élő ma a feedben → ma
    return todayIso()
}

internal fun dayLabel(offset: Int): String {
    return when (offset) {
        0 -> "Ma"
        -1 -> "Tegnap"
        1 -> "Holnap"
        else -> {
            try {
                val d = LocalDate.now().plusDays(offset.toLong())
                d.format(DateTimeFormatter.ofPattern("MM.dd"))
            } catch (_: Exception) {
                if (offset > 0) "+$offset" else "$offset"
            }
        }
    }
}

internal fun topFiveRank(leagueName: String?, countryCode: String?): Int? {
    if (leagueName.isNullOrBlank()) return null

    val normalized = leagueName
        .trim()
        .uppercase()
        .replace('Á', 'A')
        .replace('É', 'E')
        .replace('Ó', 'O')
        .replace('Ö', 'O')
        .replace('Ő', 'O')
        .replace('Ü', 'U')
        .replace('Ű', 'U')
        .replace('Í', 'I')

    val leagueOnly = normalized
        .substringAfterLast(":")
        .trim()
        // "LA LIGA EA SPORTS" / "LALIGA" stb.
        .replace(Regex("""\s+"""), " ")

    val country = countryCode?.trim()?.uppercase().orEmpty()

    fun isCountry(codes: Set<String>, nameHints: Set<String>): Boolean {
        if (country in codes) return true
        return nameHints.any { normalized.contains(it) }
    }

    // Másodosztály / ifi / női – soha ne legyen TOP
    val secondTier = listOf(
        "LIGUE 2", "SERIE B", "SEGUNDA", "CHAMPIONSHIP",
        "PREMIER LEAGUE 2", "2. BUNDESLIGA", "BUNDESLIGA 2",
        "BUNDESLIGA II", "LA LIGA 2", "LALIGA2", "PRIMERA FEDERACION",
        "U16", "U17", "U18", "U19", "U20", "U21", "U23",
        "YOUTH", "JUNIOR", "RESERVE", "RESERVES",
        "WOMEN", "FEMININE", "NŐI", "FEMENINA", "FEMMINILE"
    )
    if (secondTier.any { leagueOnly.contains(it) || normalized.contains(it) }) {
        return null
    }

    // 0 Premier League – csak Anglia
    val isPremier = leagueOnly == "PREMIER LEAGUE" || leagueOnly == "ENGLISH PREMIER LEAGUE"
    if (isPremier && isCountry(
            setOf("GB", "UK", "EN", "ENG", "GB-ENG"),
            setOf("ANGLIA", "ENGLAND")
        )
    ) return 0

    // 1 La Liga – csak Spanyolország (több névváltozat)
    val isLaLiga = leagueOnly == "LA LIGA" ||
            leagueOnly == "LALIGA" ||
            leagueOnly.startsWith("LA LIGA ") ||
            leagueOnly.startsWith("LALIGA ") ||
            leagueOnly == "PRIMERA DIVISION" ||
            leagueOnly == "PRIMERA DIVISIÓN" ||
            leagueOnly.contains("LA LIGA") && !leagueOnly.contains("2") && !leagueOnly.contains("SEGUNDA")
    if (isLaLiga && isCountry(
            setOf("ES", "ESP", "SP"),
            setOf("SPANYOL", "SPAIN", "ESPANA", "ESPAÑA")
        )
    ) return 1

    // 2 Serie A – csak Olaszország (ne brazil)
    val isSerieA = leagueOnly == "SERIE A" ||
            (leagueOnly.startsWith("SERIE A") && !leagueOnly.contains("B"))
    if (isSerieA && isCountry(
            setOf("IT", "ITA"),
            setOf("OLASZ", "ITALY", "ITALIA")
        )
    ) return 2

    // 3 Bundesliga – csak Németország (ne osztrák)
    val isBundes = leagueOnly == "BUNDESLIGA" ||
            (leagueOnly.startsWith("BUNDESLIGA") && !leagueOnly.contains("2") && !leagueOnly.contains("II"))
    if (isBundes && isCountry(
            setOf("DE", "DEU", "GER"),
            setOf("NEMET", "NÉMET", "GERMANY", "DEUTSCH")
        )
    ) return 3
    // normalized already stripped accents so NEMET
    if (isBundes && (normalized.contains("NEMET") || normalized.contains("GERMANY") || country in setOf("DE", "DEU", "GER"))) {
        return 3
    }

    // 4 Ligue 1 – csak Franciaország (NE Algír, Marokkó, stb.)
    val isLigue1 = leagueOnly == "LIGUE 1" ||
            leagueOnly == "LIGUE1" ||
            (leagueOnly.startsWith("LIGUE 1") && !leagueOnly.contains("2"))
    if (isLigue1 && isCountry(
            setOf("FR", "FRA"),
            setOf("FRANCIA", "FRANCE")
        )
    ) return 4

    return null
}

