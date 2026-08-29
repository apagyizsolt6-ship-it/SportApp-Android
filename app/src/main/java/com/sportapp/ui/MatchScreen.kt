package com.sportapp.ui

import android.content.Intent
import java.util.Calendar
import java.time.format.DateTimeFormatter
import java.time.LocalDate
import com.sportapp.MatchReminderReceiver
import android.widget.Toast
import android.os.Build
import android.app.PendingIntent
import android.app.AlarmManager
import android.net.Uri

import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.compose.SubcomposeAsyncImage
import com.sportapp.models.MatchResponse
import com.sportapp.models.HighlightVideo
import com.sportapp.api.RetrofitInstance
import com.sportapp.fcm.FcmRegistrar
import com.sportapp.api.StandingTeam
import kotlinx.coroutines.launch
import java.text.Collator
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

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
        ?: match.status.trim().takeIf { it.contains(":") && it.length <= 5 }
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
    val home = match.homeTeam.lowercase()
    val away = match.awayTeam.lowercase()
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
private fun scheduleMatchReminder(context: android.content.Context, match: MatchResponse): Boolean {
    val timeStr = match.kickoffTime?.trim()?.takeIf { it.contains(":") }
        ?: match.status.trim().takeIf { it.contains(":") && it.length <= 5 }
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
            "${match.homeTeam} vs ${match.awayTeam} · ${match.kickoffTime ?: timeStr}"
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

private fun todayIso(): String {
    return try {
        LocalDate.now().toString()
    } catch (_: Exception) {
        java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            .format(java.util.Date())
    }
}

private fun dateIsoWithOffset(offsetDays: Int): String {
    return try {
        LocalDate.now().plusDays(offsetDays.toLong()).toString()
    } catch (_: Exception) {
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, offsetDays)
        java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(cal.time)
    }
}

private fun matchKickoffDate(match: MatchResponse): String {
    val d = match.kickoffDate?.trim().orEmpty()
    if (d.length >= 10) return d.take(10)
    // Ütemezett / élő ma a feedben → ma
    return todayIso()
}

private fun dayLabel(offset: Int): String {
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MatchScreen(viewModel: MatchViewModel = viewModel()) {
    val aiAnalysis by viewModel.aiAnalysis.collectAsState()
    val isLoadingAi by viewModel.isLoadingAi.collectAsState()
    var selectedMatchForAi by remember { mutableStateOf<MatchResponse?>(null) }
    // Meccs részlet (events/stats/lineups) – NEM cseréli az AI / videó / média funkciókat
    var selectedMatchForDetail by remember { mutableStateOf<MatchResponse?>(null) }

    val matches by viewModel.matches.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    var isDarkMode by remember { mutableStateOf(true) }
    var selectedTab by remember { mutableIntStateOf(0) }
    var searchQuery by remember { mutableStateOf("") }
    // Naptár: 0 = ma, -1 = tegnap, +1 = holnap...
    var selectedDayOffset by remember { mutableIntStateOf(0) }
    LaunchedEffect(selectedDayOffset) {
        viewModel.setDayOffset(selectedDayOffset)
    }

    val context = LocalContext.current
    val favoritePrefs = remember(context) {
        context.getSharedPreferences(
            "match_screen_preferences",
            android.content.Context.MODE_PRIVATE
        )
    }
    val reminderPrefs = remember(context) {
        context.getSharedPreferences(
            "match_reminders",
            android.content.Context.MODE_PRIVATE
        )
    }
    var reminderMatchIds by remember {
        mutableStateOf(
            reminderPrefs.getStringSet("ids", emptySet())?.toSet() ?: emptySet()
        )
    }

    var favoriteMatchIds by remember { mutableStateOf(setOf<String>()) }

    // Korlátlan számú kedvenc liga.
    // A kiválasztás tartósan elmentésre kerül.
    var favoriteLeagueNames by remember {
        mutableStateOf(
            favoritePrefs
                .getStringSet("favorite_leagues", emptySet())
                ?.toSet()
                ?: emptySet()
        )
    }

    // Minden módosítás után elmentjük a kedvenc ligák aktuális listáját.
    LaunchedEffect(favoriteLeagueNames) {
        favoritePrefs.edit()
            .putStringSet("favorite_leagues", favoriteLeagueNames)
            .apply()
    }

    // Első indítás: TOP 5 kiemelésbe, utána szabadon törölhető
    LaunchedEffect(matches) {
        if (matches.isEmpty()) return@LaunchedEffect
        if (favoritePrefs.getBoolean("leagues_seeded_v2", false)) return@LaunchedEffect
        val seeds = matches.mapNotNull { m ->
            val name = m.league?.trim().orEmpty()
            if (name.isEmpty()) return@mapNotNull null
            if (topFiveRank(name, m.countryCode) != null) name else null
        }.distinct()
        if (seeds.isNotEmpty()) {
            favoriteLeagueNames = favoriteLeagueNames + seeds
        }
        favoritePrefs.edit().putBoolean("leagues_seeded_v2", true).apply()
    }

    var followedMatchIds by remember {
        mutableStateOf(FcmRegistrar.followedMatches(context))
    }
    var showNotifHistory by remember { mutableStateOf(false) }
    var showQuietHours by remember { mutableStateOf(false) }
    var prevScores by remember { mutableStateOf(mapOf<String, Pair<Int?, Int?>>()) }
    var flashMatchIds by remember { mutableStateOf(setOf<String>()) }
    LaunchedEffect(matches) {
        val nextFlash = mutableSetOf<String>()
        val nextPrev = prevScores.toMutableMap()
        matches.forEach { m ->
            val old = prevScores[m.id]
            if (old != null) {
                val nh = m.homeScore
                val na = m.awayScore
                if ((nh != null && old.first != null && nh > old.first!!) ||
                    (na != null && old.second != null && na > old.second!!)
                ) nextFlash.add(m.id)
            }
            nextPrev[m.id] = m.homeScore to m.awayScore
        }
        prevScores = nextPrev
        if (nextFlash.isNotEmpty()) {
            flashMatchIds = flashMatchIds + nextFlash
            kotlinx.coroutines.delay(2500)
            flashMatchIds = flashMatchIds - nextFlash
        }
    }
    var onlyPinnedLeagues by remember { mutableStateOf(false) }
    var compactMode by remember {
        mutableStateOf(favoritePrefs.getBoolean("compact_mode", false))
    }
    var sortMode by remember {
        mutableStateOf(
            try {
                MatchSortMode.valueOf(
                    favoritePrefs.getString("sort_mode", "LEAGUE") ?: "LEAGUE"
                )
            } catch (_: Exception) {
                MatchSortMode.LEAGUE
            }
        )
    }
    LaunchedEffect(compactMode) {
        favoritePrefs.edit().putBoolean("compact_mode", compactMode).apply()
    }
    LaunchedEffect(sortMode) {
        favoritePrefs.edit().putString("sort_mode", sortMode.name).apply()
    }

    var selectedVideo by remember { mutableStateOf<HighlightVideo?>(null) }
    var selectedMatchForMedia by remember { mutableStateOf<MatchResponse?>(null) }

    val mediaPrefs = remember(context) {
        context.getSharedPreferences(
            "match_media_preferences",
            android.content.Context.MODE_PRIVATE
        )
    }

    var recentMediaItems by remember {
        mutableStateOf(loadRecentMedia(mediaPrefs))
    }

    fun persistRecentMedia(items: List<RecentMediaItem>) {
        recentMediaItems = items
        saveRecentMedia(mediaPrefs, items)
    }

    fun pushRecentMedia(item: RecentMediaItem) {
        val next = listOf(item) + recentMediaItems.filter { it.id != item.id }
        persistRecentMedia(next.take(20))
    }
    var highlightVideos by remember { mutableStateOf<List<HighlightVideo>>(emptyList()) }
    var showHighlightPicker by remember { mutableStateOf(false) }
    var isHighlightLoading by remember { mutableStateOf(false) }
    var highlightError by remember { mutableStateOf<String?>(null) }
    var selectedLeaguePair by remember { mutableStateOf<Pair<String, String>?>(null) }

    val coroutineScope = rememberCoroutineScope()

    // --- Kék üveg (glassmorphism) paletta ---
    val bgColor by animateColorAsState(
        if (isDarkMode) Color(0xFF0B1426) else Color(0xFFE8F1FF),
        label = "bg"
    )

    // Kártya: áttetsző „üveg”
    val cardBgColor by animateColorAsState(
        if (isDarkMode) Color(0xCC152238) else Color(0xB3FFFFFF),
        label = "card"
    )

    val headerBgColor by animateColorAsState(
        if (isDarkMode) Color(0xD9111E33) else Color(0xCCF5F9FF),
        label = "header"
    )

    val leagueBgColor by animateColorAsState(
        if (isDarkMode) Color(0x991A2D4D) else Color(0x99D6E6FF),
        label = "league"
    )

    val textColor by animateColorAsState(
        if (isDarkMode) Color(0xFFF0F6FF) else Color(0xFF0D1B2A),
        label = "text"
    )

    val subTextColor by animateColorAsState(
        if (isDarkMode) Color(0xFF9BB0C9) else Color(0xFF5A6F8A),
        label = "subtext"
    )

    // Akcent: élénk aqua / zöld a sötét kéken
    val primaryGreen = Color(0xFF00E5A8)
    val glassBorder = if (isDarkMode) Color(0x33A0C4FF) else Color(0x55FFFFFF)
    val accentBlue = Color(0xFF4DA3FF)

    // ============================================================
    // ELŐRE KIEMELT LIGÁK
    // ============================================================
    //
    // KIZÁRÓLAG ez az 5 liga kap arany fejlécet és kerül előre:
    //
    // 1. Anglia – Premier League
    // 2. Németország – Bundesliga
    // 3. Olaszország – Serie A
    // 4. Spanyolország – La Liga
    // 5. Franciaország – Ligue 1
    //
    // Fontos:
    // Nem használunk contains() alapú ellenőrzést,
    // mert az például a "Premier League 2" ligát
    // tévesen kiemelné.
    //
    // TOP 5 = csak az 5 nagy európai első osztály (ország-kötött).
    // Algír / örmény / brazil stb. "Ligue 1" / "Serie A" NEM kerül bele.
    val isTopFiveLeague: (String, String?) -> Boolean = { leagueName, countryCode ->
        topFiveRank(leagueName, countryCode) != null
    }



    // ============================================================
    // LIGA NYITÁS / ZÁRÁS
    // ============================================================
    //
    // Minden liga külön nyitható/zárható.
    //
    // Alapállapot:
    // minden liga NYITVA.
    //
    // Ha egy liga neve bekerül ebbe a set-be,
    // akkor az adott liga mérkőzései elrejtésre kerülnek.
    //
    var collapsedLeagueNames by remember {
        mutableStateOf(
            favoritePrefs.getStringSet("collapsed_leagues", emptySet())?.toSet()
                ?: emptySet()
        )
    }
    LaunchedEffect(collapsedLeagueNames) {
        favoritePrefs.edit()
            .putStringSet("collapsed_leagues", collapsedLeagueNames)
            .apply()
    }

    val selectedDateIso = remember(selectedDayOffset) { dateIsoWithOffset(selectedDayOffset) }

    val filteredMatches = remember(
        matches,
        selectedTab,
        searchQuery,
        favoriteMatchIds,
        favoriteLeagueNames,
        selectedDateIso,
        selectedDayOffset,
        onlyPinnedLeagues
    ) {
        matches.filter { match ->
            // Naptár: más napokon kickoff_date; MA = teljes feed (ne essen ki timezone / null miatt)
            if (selectedDayOffset != 0) {
                val onDay = matchKickoffDate(match) == selectedDateIso
                if (!onDay) return@filter false
            }

            val leagueName = match.league ?: "EGYÉB BAJNOKSÁG"

            val isLeagueFav = favoriteLeagueNames.contains(leagueName)
            val isMatchFav = favoriteMatchIds.contains(match.id)

            if (onlyPinnedLeagues && !isLeagueFav) return@filter false
            val matchesSearch = matchesSmartSearch(match, searchQuery)

            val matchesTab = when (selectedTab) {
                1 -> isMatchLive(match.status, match.minute)
                2 -> isMatchFav || isLeagueFav
                3 -> {
                    val hasHighlight = !match.highlightMatchId.isNullOrBlank()
                    val live = isMatchLive(match.status, match.minute)
                    val finished = isMatchFinished(match.status)
                    hasHighlight || live || finished
                }
                4 -> isMatchFav || isLeagueFav || followedMatchIds.contains(match.id)
                else -> true
            }

            matchesSearch && matchesTab
        }
    }

    // ============================================================
    // LIGÁK CSOPORTOSÍTÁSA ÉS SORRENDJE
    // ============================================================
    //
    // Sorrend:
    //
    // 1. TOP 5 kiemelt liga
    // 2. felhasználói kedvenc ligák
    // 3. minden egyéb liga ABC sorrendben
    //
    // Magyar ábécés rendező. A java.text.Collator magyar Locale-lal
    // kezeli az ékezetes és többjegyű magyar betűket is.
    val hungarianCollator = remember {
        Collator.getInstance(Locale("hu", "HU")).apply {
            strength = Collator.TERTIARY
        }
    }

    // A TOP ligák fix sorrendben maradnak legelöl. Minden más liga
    // magyar ABC szerint követi őket. A kedvenc státusz nem írhatja
    // felül ezt a sorrendet.
    val liveMatches = remember(filteredMatches) {
        filteredMatches.filter { isMatchLive(it.status, it.minute) }
    }
    val soonMatches = remember(filteredMatches) {
        filteredMatches.filter { isStartingSoon(it, 90) }
            .sortedBy { matchKickoffMillis(it) ?: Long.MAX_VALUE }
            .take(12)
    }
    val worthWatchMatches = remember(matches, favoriteLeagueNames) {
        matches.filter { !isMatchFinished(it.status) }
            .map { it to worthWatchScore(it, favoriteLeagueNames) }
            .filter { it.second >= 30 }
            .sortedByDescending { it.second }
            .take(5)
            .map { it.first }
    }
    val spotlightMatches = remember(filteredMatches, favoriteLeagueNames) {
        filteredMatches
            .filter {
                favoriteLeagueNames.contains(it.league ?: "") ||
                    topFiveRank(it.league, it.countryCode) != null
            }
            .sortedWith(
                compareByDescending<MatchResponse> { isMatchLive(it.status, it.minute) }
                    .thenBy { matchKickoffMillis(it) ?: Long.MAX_VALUE }
            )
            .take(3)
    }

    val groupedMatchesList = remember(
        filteredMatches,
        favoriteLeagueNames,
        hungarianCollator,
        sortMode
    ) {
        when (sortMode) {
            MatchSortMode.TIME -> {
                val sorted = filteredMatches.sortedBy {
                    matchKickoffMillis(it) ?: Long.MAX_VALUE
                }
                listOf("IDŐREND" to sorted)
            }
            MatchSortMode.LIVE_FIRST -> {
                val sorted = filteredMatches.sortedWith(
                    compareByDescending<MatchResponse> { isMatchLive(it.status, it.minute) }
                        .thenBy { matchKickoffMillis(it) ?: Long.MAX_VALUE }
                )
                listOf("ÉLŐ / IDŐREND" to sorted)
            }
            MatchSortMode.LEAGUE -> {
                val groups = filteredMatches.groupBy {
                    it.league ?: "EGYÉB BAJNOKSÁG"
                }
                groups.entries.sortedWith(Comparator { a, b ->
                    val aName = a.key.trim()
                    val bName = b.key.trim()
                    val aFav = favoriteLeagueNames.contains(aName)
                    val bFav = favoriteLeagueNames.contains(bName)
                    when {
                        aFav && !bFav -> return@Comparator -1
                        !aFav && bFav -> return@Comparator 1
                    }
                    val aCountry = a.value.firstOrNull()?.countryCode
                    val bCountry = b.value.firstOrNull()?.countryCode
                    val aRank = topFiveRank(aName, aCountry)
                    val bRank = topFiveRank(bName, bCountry)
                    when {
                        aFav && bFav && aRank != null && bRank != null -> aRank.compareTo(bRank)
                        aFav && bFav && aRank != null -> -1
                        aFav && bFav && bRank != null -> 1
                        aFav && bFav -> hungarianCollator.compare(aName, bName)
                        else -> hungarianCollator.compare(aName, bName)
                    }
                }).map { it.toPair() }
            }
        }
    }

    // ============================================================
    // TELJES MECCSLISTA NYITÁS / ZÁRÁS
    // ============================================================
    // A ligák továbbra is külön-külön is nyithatók/zárhatók.
    // Ez a kapcsoló csak az összes jelenleg látható ligára hat.

    val daySummary = remember(filteredMatches, favoriteMatchIds, favoriteLeagueNames) {
        val total = filteredMatches.size
        val live = filteredMatches.count { isMatchLive(it.status, it.minute) }
        val fav = filteredMatches.count {
            favoriteMatchIds.contains(it.id) ||
                favoriteLeagueNames.contains(it.league ?: "")
        }
        Triple(total, live, fav)
    }

    val allLeaguesCollapsed = groupedMatchesList.isNotEmpty() &&
            groupedMatchesList.all {
                collapsedLeagueNames.contains(it.first)
            }

    Box(modifier = Modifier.fillMaxSize()) {
        // Mély kék gradiens háttér (üveg alatt)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    brush = if (isDarkMode) {
                        Brush.verticalGradient(
                            colors = listOf(
                                Color(0xFF0A1628),
                                Color(0xFF122445),
                                Color(0xFF0D1B33),
                                Color(0xFF0A1424)
                            )
                        )
                    } else {
                        Brush.verticalGradient(
                            colors = listOf(
                                Color(0xFFD6E8FF),
                                Color(0xFFEEF5FF),
                                Color(0xFFE3EFFF),
                                Color(0xFFD0E4FF)
                            )
                        )
                    }
                )
        )
        // Finom „fényfolt” fent
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp)
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            if (isDarkMode) Color(0x334DA3FF) else Color(0x664DA3FF),
                            Color.Transparent
                        )
                    )
                )
        )

        Column(
            modifier = Modifier.fillMaxSize()
        ) {

            // ====================================================
            // FEJLÉC
            // ====================================================

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(headerBgColor)
                    .border(width = 0.5.dp, color = glassBorder)
                    .padding(
                        horizontal = 20.dp,
                        vertical = 14.dp
                    )
            ) {

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {

                    Text(
                        text = "⚡ Élő Meccsközpont",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Black,
                        color = primaryGreen
                    )

                    IconButton(
                        onClick = {
                            viewModel.fetchMatches(showLoading = true)
                        },
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(leagueBgColor)
                            .size(36.dp)
                    ) {
                        Text(text = "🔄", fontSize = 16.sp)
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    IconButton(
                        onClick = {
                            isDarkMode = !isDarkMode
                        },
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(leagueBgColor)
                            .size(36.dp)
                    ) {

                        Text(
                            text = if (isDarkMode) "☀️" else "🌙",
                            fontSize = 16.sp
                        )
                    }
                }
            }

            // ====================================================
            // KERESÉS
            // ====================================================

            TextField(
                value = searchQuery,

                onValueChange = {
                    searchQuery = it
                },

                placeholder = {
                    Text(
                        "Keresés csapatra vagy bajnokságra...",
                        color = subTextColor,
                        fontSize = 12.sp
                    )
                },

                singleLine = true,

                colors = TextFieldDefaults.textFieldColors(
                    containerColor = headerBgColor,
                    focusedIndicatorColor = primaryGreen,
                    unfocusedIndicatorColor = Color.Transparent,
                    focusedTextColor = textColor,
                    unfocusedTextColor = textColor
                ),

                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = 12.dp,
                        vertical = 4.dp
                    )
                    .clip(RoundedCornerShape(8.dp))
            )

            // ====================================================
            // NAPI ÖSSZEFOGLALÓ + NAPTÁR
            // ====================================================
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val (total, live, fav) = daySummary
                Text(
                    text = "${dayLabel(selectedDayOffset)} · $total meccs" +
                        (if (live > 0) " · $live élő" else "") +
                        (if (fav > 0) " · $fav kedvenc" else ""),
                    color = subTextColor,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(20.dp))
                        .background(cardBgColor)
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }

            // Napválasztó sáv
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                for (off in -1..3) {
                    val selected = selectedDayOffset == off
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .clickable { selectedDayOffset = off },
                        shape = RoundedCornerShape(12.dp),
                        color = if (selected) primaryGreen.copy(alpha = 0.25f) else cardBgColor,
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (selected) primaryGreen else Color(0x33A0C4FF)
                        )
                    ) {
                        Text(
                            text = dayLabel(off),
                            color = if (selected) primaryGreen else textColor,
                            fontSize = 11.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp)
                        )
                    }
                }
            }

            // Eszközsáv
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilterChip(
                    selected = onlyPinnedLeagues,
                    onClick = { onlyPinnedLeagues = !onlyPinnedLeagues },
                    label = { Text("★ Kiemelt", fontSize = 11.sp) }
                )
                FilterChip(
                    selected = sortMode != MatchSortMode.LEAGUE,
                    onClick = {
                        sortMode = when (sortMode) {
                            MatchSortMode.LEAGUE -> MatchSortMode.LIVE_FIRST
                            MatchSortMode.LIVE_FIRST -> MatchSortMode.TIME
                            MatchSortMode.TIME -> MatchSortMode.LEAGUE
                        }
                    },
                    label = {
                        Text(
                            when (sortMode) {
                                MatchSortMode.LEAGUE -> "Sorrend: liga"
                                MatchSortMode.LIVE_FIRST -> "Sorrend: élő"
                                MatchSortMode.TIME -> "Sorrend: idő"
                            },
                            fontSize = 11.sp
                        )
                    }
                )
                FilterChip(
                    selected = compactMode,
                    onClick = { compactMode = !compactMode },
                    label = { Text(if (compactMode) "Kompakt" else "Normál", fontSize = 11.sp) }
                )
                FilterChip(
                    selected = false,
                    onClick = { showNotifHistory = true },
                    label = { Text("🔔 Előzmény", fontSize = 11.sp) }
                )
                FilterChip(
                    selected = false,
                    onClick = { showQuietHours = true },
                    label = { Text("🌙 Csend", fontSize = 11.sp) }
                )
            }

            // Élő ticker
            if (liveMatches.isNotEmpty() && selectedTab != 3) {
                LazyRow(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(liveMatches.size) { i ->
                        val lm = liveMatches[i]
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(20.dp))
                                .background(Color(0x3300E5A8))
                                .border(1.dp, primaryGreen.copy(alpha = 0.45f), RoundedCornerShape(20.dp))
                                .clickable { selectedMatchForDetail = lm }
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("● ", color = primaryGreen, fontSize = 10.sp)
                            Text(
                                text = "${lm.homeTeam.take(12)} ${lm.homeScore ?: 0}–${lm.awayScore ?: 0} ${lm.awayTeam.take(12)}",
                                color = textColor,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1
                            )
                        }
                    }
                }
            }


            // ====================================================
            // SZŰRŐ FÜLEK
            // ====================================================

            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = headerBgColor,
                contentColor = primaryGreen,
                divider = {
                    Divider(
                        color = leagueBgColor,
                        thickness = 1.dp
                    )
                }
            ) {

                Tab(
                    selected = selectedTab == 0,
                    onClick = {
                        selectedTab = 0
                    }
                ) {

                    Text(
                        "ÖSSZES",
                        modifier = Modifier.padding(
                            vertical = 10.dp
                        ),
                        color = if (selectedTab == 0) {
                            primaryGreen
                        } else {
                            subTextColor
                        },
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Tab(
                    selected = selectedTab == 1,
                    onClick = {
                        selectedTab = 1
                    }
                ) {

                    Text(
                        "🔴 ÉLŐ",
                        modifier = Modifier.padding(
                            vertical = 10.dp
                        ),
                        color = if (selectedTab == 1) {
                            primaryGreen
                        } else {
                            subTextColor
                        },
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Tab(
                    selected = selectedTab == 2,
                    onClick = {
                        selectedTab = 2
                    }
                ) {

                    Text(
                        "⭐ KEDVENC",
                        modifier = Modifier.padding(
                            vertical = 10.dp
                        ),
                        color = if (selectedTab == 2) {
                            Color(0xFFFF9100)
                        } else {
                            subTextColor
                        },
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Tab(
                    selected = selectedTab == 3,
                    onClick = {
                        selectedTab = 3
                    }
                ) {

                    Text(
                        "🎬 MÉDIA",
                        modifier = Modifier.padding(
                            vertical = 10.dp
                        ),
                        color = if (selectedTab == 3) {
                            Color(0xFF40C4FF)
                        } else {
                            subTextColor
                        },
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Tab(
                    selected = selectedTab == 4,
                    onClick = {
                        selectedTab = 4
                        followedMatchIds = FcmRegistrar.followedMatches(context)
                    }
                ) {
                    Text(
                        "🔔 KÖVETETT",
                        modifier = Modifier.padding(vertical = 10.dp),
                        color = if (selectedTab == 4) Color(0xFFE040FB) else subTextColor,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // ====================================================
            // TELJES MECCSLISTA – NYITÁS / ZÁRÁS
            // ====================================================
            // Független az egyes ligák saját nyitás/zárás gombjától.
            //
            // Ha minden liga nyitva van -> minden liga bezárása.
            // Ha akár csak egy liga nyitva van -> minden liga megnyitása.
            if (!isLoading && groupedMatchesList.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        modifier = Modifier.clickable {
                            collapsedLeagueNames = if (allLeaguesCollapsed) {
                                emptySet()
                            } else {
                                groupedMatchesList
                                    .map { it.first }
                                    .toSet()
                            }
                        },
                        shape = RoundedCornerShape(20.dp),
                        color = if (isDarkMode) {
                            Color(0xFF20252B)
                        } else {
                            Color(0xFFE7EBEF)
                        }
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp, 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (allLeaguesCollapsed) "▲" else "▼",
                                color = primaryGreen,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(Modifier.width(5.dp))
                            Text(
                                text = if (allLeaguesCollapsed) {
                                    "ÖSSZES NYITÁSA"
                                } else {
                                    "ÖSSZES ZÁRÁSA"
                                },
                                color = primaryGreen,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            // ====================================================
            // LOADING / MECCSLISTA
            // ====================================================

            if (isLoading) {

                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {

                    CircularProgressIndicator(
                        color = primaryGreen
                    )
                }

            } else if (selectedTab == 3) {

                // ====================================================
                // MÉDIA HUB
                // ====================================================
                MediaHubList(
                    matches = filteredMatches,
                    recentItems = recentMediaItems,
                    isDarkMode = isDarkMode,
                    cardBgColor = cardBgColor,
                    textColor = textColor,
                    subTextColor = subTextColor,
                    primaryGreen = primaryGreen,
                    onOpenMatchMedia = { match ->
                        selectedMatchForMedia = match
                        highlightVideos = emptyList()
                        highlightError = null
                        showHighlightPicker = true
                        val highlightMatchId =
                            match.highlightMatchId?.trim().orEmpty()
                        if (highlightMatchId.isNotBlank()) {
                            coroutineScope.launch {
                                isHighlightLoading = true
                                try {
                                    val videos =
                                        RetrofitInstance.api.getMatchHighlights(
                                            highlightMatchId
                                        )
                                    highlightVideos =
                                        videos.filter {
                                            !it.embedUrl.isNullOrBlank() ||
                                                    !it.url.isNullOrBlank()
                                        }
                                } catch (_: Exception) {
                                } finally {
                                    isHighlightLoading = false
                                }
                            }
                        }
                    },
                    onOpenRecent = { item ->
                        try {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(item.url))
                            context.startActivity(intent)
                        } catch (_: Exception) {
                        }
                    },
                    onClearRecent = {
                        persistRecentMedia(emptyList())
                    }
                )

            } else if (filteredMatches.isEmpty() && !isLoading) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text("Nincs megjeleníthető meccs", color = textColor, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Próbáld másik napot, töröld a szűrőt, vagy húzd le a frissítést.",
                        color = subTextColor,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = { viewModel.fetchMatches(showLoading = true) },
                        colors = ButtonDefaults.buttonColors(containerColor = primaryGreen)
                    ) {
                        Text("Frissítés", color = Color.Black)
                    }
                }
            } else {

                LazyColumn(
                    modifier = Modifier.fillMaxSize()
                ) {
                    // Spotlight
                                        if (worthWatchMatches.isNotEmpty() && selectedTab == 0 && searchQuery.isEmpty()) {
                        item {
                            Text(
                                "🔥 Ma este érdemes nézni",
                                color = Color(0xFFFF6E40),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                            )
                        }
                        items(worthWatchMatches, key = { "ww-${it.id}" }) { match ->
                            PremiumMatchRow(
                                match = match,
                                isFavorite = favoriteMatchIds.contains(match.id),
                                isReminderSet = reminderMatchIds.contains(match.id),
                                cardBgColor = cardBgColor,
                                textColor = textColor,
                                subTextColor = subTextColor,
                                primaryGreen = primaryGreen,
                                compact = compactMode,
                                scoreFlash = flashMatchIds.contains(match.id),
                                onFavoriteToggle = {
                                    favoriteMatchIds =
                                        if (favoriteMatchIds.contains(match.id))
                                            favoriteMatchIds - match.id
                                        else favoriteMatchIds + match.id
                                },
                                onVideoClick = { m ->
                                    selectedMatchForMedia = m
                                    showHighlightPicker = true
                                },
                                onAiClick = { m ->
                                    selectedMatchForAi = m
                                    viewModel.fetchAiAnalysis(m.id)
                                },
                                onMatchClick = { selectedMatchForDetail = it },
                                onReminderClick = { },
                                onShareClick = { }
                            )
                        }
                    }
if (spotlightMatches.isNotEmpty() && selectedTab == 0 && searchQuery.isEmpty() && !onlyPinnedLeagues) {
                        item {
                            Text(
                                "⭐ Mai spotlight",
                                color = primaryGreen,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                            )
                        }
                        items(spotlightMatches, key = { "sp-${it.id}" }) { match ->
                            PremiumMatchRow(
                                match = match,
                                isFavorite = favoriteMatchIds.contains(match.id),
                                isReminderSet = reminderMatchIds.contains(match.id),
                                cardBgColor = cardBgColor,
                                textColor = textColor,
                                subTextColor = subTextColor,
                                primaryGreen = primaryGreen,
                                compact = compactMode,
                                scoreFlash = flashMatchIds.contains(match.id),
                                onFavoriteToggle = {
                                    favoriteMatchIds =
                                        if (favoriteMatchIds.contains(match.id))
                                            favoriteMatchIds - match.id
                                        else
                                            favoriteMatchIds + match.id
                                },
                                onVideoClick = { m ->
                                    selectedMatchForMedia = m
                                    highlightVideos = emptyList()
                                    highlightError = null
                                    showHighlightPicker = true
                                    val highlightMatchId = m.highlightMatchId?.trim().orEmpty()
                                    if (highlightMatchId.isNotBlank()) {
                                        coroutineScope.launch {
                                            isHighlightLoading = true
                                            try {
                                                val videos = RetrofitInstance.api.getMatchHighlights(highlightMatchId)
                                                highlightVideos = videos.filter {
                                                    !it.embedUrl.isNullOrBlank() || !it.url.isNullOrBlank()
                                                }
                                            } catch (e: Exception) {
                                                highlightError = e.message
                                            } finally {
                                                isHighlightLoading = false
                                            }
                                        }
                                    }
                                },
                                onAiClick = { m ->
                                    selectedMatchForAi = m
                                    viewModel.fetchAiAnalysis(m.id)
                                },
                                onMatchClick = { m ->
                                    selectedMatchForDetail = m
                                },
                                onReminderClick = { m ->
                                    val ok = scheduleMatchReminder(context, m)
                                    if (ok) {
                                        val next = reminderMatchIds + m.id
                                        reminderMatchIds = next
                                        reminderPrefs.edit().putStringSet("ids", next).apply()
                                        Toast.makeText(
                                            context,
                                            "Emlékeztető beállítva (15 perccel kezdés előtt)",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    } else {
                                        Toast.makeText(
                                            context,
                                            "Nem sikerült (nincs kezdési idő vagy már elmúlt)",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                },
                                onShareClick = {
                                    val score = "${match.homeScore ?: 0}–${match.awayScore ?: 0}"
                                    val body = "${match.homeTeam} $score ${match.awayTeam}\n${match.league.orEmpty()}"
                                    val intent = Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_TEXT, body)
                                    }
                                    context.startActivity(Intent.createChooser(intent, "Megosztás"))
                                }
                            )
                        }
                    }
                    // Hamarosan
                    if (soonMatches.isNotEmpty() && selectedTab == 0) {
                        item {
                            Text(
                                "⏰ Hamarosan kezdődik",
                                color = Color(0xFFFFD54F),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                            )
                        }
                        items(soonMatches.take(6), key = { "soon-${it.id}" }) { match ->
                            PremiumMatchRow(
                                match = match,
                                isFavorite = favoriteMatchIds.contains(match.id),
                                isReminderSet = reminderMatchIds.contains(match.id),
                                cardBgColor = cardBgColor,
                                textColor = textColor,
                                subTextColor = subTextColor,
                                primaryGreen = primaryGreen,
                                compact = compactMode,
                                scoreFlash = flashMatchIds.contains(match.id),
                                onFavoriteToggle = {
                                    favoriteMatchIds =
                                        if (favoriteMatchIds.contains(match.id))
                                            favoriteMatchIds - match.id
                                        else
                                            favoriteMatchIds + match.id
                                },
                                onVideoClick = { m ->
                                    selectedMatchForMedia = m
                                    highlightVideos = emptyList()
                                    highlightError = null
                                    showHighlightPicker = true
                                    val highlightMatchId = m.highlightMatchId?.trim().orEmpty()
                                    if (highlightMatchId.isNotBlank()) {
                                        coroutineScope.launch {
                                            isHighlightLoading = true
                                            try {
                                                val videos = RetrofitInstance.api.getMatchHighlights(highlightMatchId)
                                                highlightVideos = videos.filter {
                                                    !it.embedUrl.isNullOrBlank() || !it.url.isNullOrBlank()
                                                }
                                            } catch (e: Exception) {
                                                highlightError = e.message
                                            } finally {
                                                isHighlightLoading = false
                                            }
                                        }
                                    }
                                },
                                onAiClick = { m ->
                                    selectedMatchForAi = m
                                    viewModel.fetchAiAnalysis(m.id)
                                },
                                onMatchClick = { m ->
                                    selectedMatchForDetail = m
                                },
                                onReminderClick = { m ->
                                    val ok = scheduleMatchReminder(context, m)
                                    if (ok) {
                                        val next = reminderMatchIds + m.id
                                        reminderMatchIds = next
                                        reminderPrefs.edit().putStringSet("ids", next).apply()
                                        Toast.makeText(
                                            context,
                                            "Emlékeztető beállítva (15 perccel kezdés előtt)",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    } else {
                                        Toast.makeText(
                                            context,
                                            "Nem sikerült (nincs kezdési idő vagy már elmúlt)",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                },
                                onShareClick = {
                                    val score = "${match.homeScore ?: 0}–${match.awayScore ?: 0}"
                                    val body = "${match.homeTeam} $score ${match.awayTeam}\n${match.league.orEmpty()}"
                                    val intent = Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_TEXT, body)
                                    }
                                    context.startActivity(Intent.createChooser(intent, "Megosztás"))
                                }
                            )
                        }
                    }

                    groupedMatchesList.forEach {
                            (leagueName, leagueMatches) ->

                        val isLeagueFav =
                            favoriteLeagueNames.contains(leagueName)

                        val firstLeagueMatch =
                            leagueMatches.firstOrNull()

                        val isTopFive =
                            isTopFiveLeague(
                                leagueName,
                                firstLeagueMatch?.countryCode
                            )

                        val isUserHighlighted =
                            favoriteLeagueNames.contains(leagueName)

                        val isCollapsed =
                            collapsedLeagueNames.contains(leagueName)

                        // ====================================================
                        // LIGA FEJLÉC
                        // ====================================================

                        item {

                            val leagueHeaderColor = when {
                                isLeagueFav && isDarkMode ->
                                    Color(0xFF2A3F6A)
                                isLeagueFav ->
                                    Color(0xFFB8D4FF)
                                else ->
                                    leagueBgColor
                            }

                            val leagueHeaderTextColor =
                                if (isLeagueFav) {
                                    if (isDarkMode) Color(0xFF9EC9FF) else Color(0xFF1A4A8A)
                                } else {
                                    textColor
                                }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(
                                        leagueHeaderColor
                                    )
                                    .clickable {

                                        collapsedLeagueNames =
                                            if (isCollapsed) {

                                                // NYITÁS
                                                collapsedLeagueNames -
                                                        leagueName

                                            } else {

                                                // ZÁRÁS
                                                collapsedLeagueNames +
                                                        leagueName
                                            }
                                    }
                                    .padding(
                                        horizontal = 12.dp,
                                        vertical = 8.dp
                                    ),

                                horizontalArrangement =
                                    Arrangement.SpaceBetween,

                                verticalAlignment =
                                    Alignment.CenterVertically
                            ) {

                                // ====================================================
                                // BAL OLDAL
                                // ====================================================

                                Row(
                                    verticalAlignment =
                                        Alignment.CenterVertically,

                                    modifier =
                                        Modifier.weight(1f)
                                ) {

                                    // ====================================================
                                    // KEDVENC / KIEMELT CSILLAG
                                    // ====================================================

                                    Text(
                                        text =
                                            if (isLeagueFav) {
                                                "★"
                                            } else {
                                                "☆"
                                            },

                                        color = when {
                                            isLeagueFav ->
                                                Color(0xFFFFB300)
                                            else ->
                                                subTextColor
                                        },

                                        fontSize = 15.sp,

                                        modifier = Modifier
                                            .then(

                                                // Bármely liga kiemelhető / törölhető
                                                Modifier.clickable {
                                                    favoriteLeagueNames =
                                                        if (isLeagueFav) {
                                                            favoriteLeagueNames
                                                                .filter { it != leagueName }
                                                                .toSet()
                                                        } else {
                                                            favoriteLeagueNames + leagueName
                                                        }
                                                }
                                            )
                                            .padding(end = 6.dp)
                                    )

                                    // ====================================================
                                    // ORSZÁG ZÁSZLÓ
                                    // ====================================================

                                    LeagueFlagIcon(
                                        countryCode = firstLeagueMatch?.countryCode,
                                        leagueName = leagueName,
                                        modifier = Modifier.padding(end = 6.dp)
                                    )

                                    // ====================================================
                                    // LIGA LOGÓ
                                    // ====================================================

                                    firstLeagueMatch?.let {
                                        leagueMatch ->

                                        if (
                                            !leagueMatch
                                                .leagueLogoUrl
                                                .isNullOrBlank()
                                        ) {

                                            AsyncImage(
                                                model =
                                                    leagueMatch.leagueLogoUrl,

                                                contentDescription =
                                                    "$leagueName logó",

                                                modifier = Modifier
                                                    .size(18.dp)
                                                    .clip(
                                                        RoundedCornerShape(4.dp)
                                                    )
                                            )

                                            Spacer(
                                                modifier =
                                                    Modifier.width(5.dp)
                                            )
                                        }
                                    }

                                    // ====================================================
                                    // LIGA NEVE
                                    // ====================================================

                                    Text(
                                        text =
                                            leagueName.uppercase(),

                                        color =
                                            leagueHeaderTextColor,

                                        fontSize = 11.sp,

                                        fontWeight =
                                            FontWeight.ExtraBold,

                                        letterSpacing = 0.5.sp
                                    )
                                }

                                // ====================================================
                                // JOBB OLDAL
                                // TABELLA + NYITÁS/ZÁRÁS
                                // ====================================================

                                Row(
                                    verticalAlignment =
                                        Alignment.CenterVertically,

                                    modifier =
                                        Modifier.padding(start = 8.dp)
                                ) {

                                    // TABELLA
                                    Text(
                                        text =
                                            "📊 TABELLA ➔",

                                        color =
                                            primaryGreen,

                                        fontSize = 10.sp,

                                        fontWeight =
                                            FontWeight.Bold,

                                        modifier =
                                            Modifier.clickable {

                                                val realLeagueId =
                                                    leagueMatches
                                                        .firstOrNull()
                                                        ?.leagueId
                                                        ?.takeIf {
                                                            it.isNotBlank()
                                                        }
                                                        ?: leagueName

                                                selectedLeaguePair =
                                                    Pair(
                                                        realLeagueId,
                                                        leagueName
                                                    )
                                            }
                                    )

                                    Spacer(
                                        modifier =
                                            Modifier.width(8.dp)
                                    )

                                    // NYITVA / ZÁRVA JELZÉS
                                    Text(
                                        text =
                                            if (isCollapsed) {
                                                "▼"
                                            } else {
                                                "▲"
                                            },

                                        color =
                                            if (isTopFive) {
                                                Color(0xFFFFD54F)
                                            } else {
                                                subTextColor
                                            },

                                        fontSize = 12.sp,

                                        fontWeight =
                                            FontWeight.Bold
                                    )
                                }
                            }
                        }

                        // ====================================================
                        // MECCSEK
                        // ====================================================
                        //
                        // Ha a liga zárva van, a meccsek nem jelennek meg.
                        //
                        if (!isCollapsed) {

                            items(leagueMatches) { match ->

                                val isFav =
                                    favoriteMatchIds.contains(
                                        match.id
                                    )

                                PremiumMatchRow(
                                    match = match,

                                    isFavorite = isFav,

                                    cardBgColor =
                                        cardBgColor,

                                    textColor =
                                        textColor,

                                    subTextColor =
                                        subTextColor,

                                    primaryGreen =
                                        primaryGreen,

                                    onFavoriteToggle = {

                                        favoriteMatchIds =
                                            if (isFav) {

                                                favoriteMatchIds
                                                    .filter {
                                                        it != match.id
                                                    }
                                                    .toSet()

                                            } else {

                                                favoriteMatchIds +
                                                        match.id
                                            }
                                    },

                                    onVideoClick = { match ->
                                        selectedMatchForMedia = match
                                        highlightVideos = emptyList()
                                        highlightError = null
                                        showHighlightPicker = true

                                        val highlightMatchId =
                                            match.highlightMatchId?.trim().orEmpty()

                                        if (highlightMatchId.isNotBlank()) {
                                            coroutineScope.launch {
                                                isHighlightLoading = true
                                                try {
                                                    val videos =
                                                        RetrofitInstance.api.getMatchHighlights(
                                                            highlightMatchId
                                                        )
                                                    highlightVideos =
                                                        videos
                                                            .filter {
                                                                !it.embedUrl.isNullOrBlank() ||
                                                                        !it.url.isNullOrBlank()
                                                            }
                                                            .sortedWith(
                                                                compareByDescending<HighlightVideo> {
                                                                    it.category.equals(
                                                                        "goal-clip",
                                                                        ignoreCase = true
                                                                    )
                                                                }.thenBy {
                                                                    it.title.orEmpty()
                                                                }
                                                            )
                                                } catch (e: Exception) {
                                                    highlightError = null
                                                } finally {
                                                    isHighlightLoading = false
                                                }
                                            }
                                        }
                                    },
                                    onAiClick = { match ->
                                        selectedMatchForAi = match
                                        viewModel.fetchAiAnalysis(match.id)
                                    },
                                    onMatchClick = { match ->
                                        selectedMatchForDetail = match
                                    },
                                    isReminderSet = reminderMatchIds.contains(match.id),
                                    onReminderClick = { m ->
                                        val ok = scheduleMatchReminder(context, m)
                                        if (ok) {
                                            val next = reminderMatchIds + m.id
                                            reminderMatchIds = next
                                            reminderPrefs.edit()
                                                .putStringSet("ids", next)
                                                .apply()
                                            Toast.makeText(
                                                context,
                                                "Emlékeztető beállítva (15 perccel kezdés előtt)",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        } else {
                                            Toast.makeText(
                                                context,
                                                "Nem sikerült (nincs kezdési idő vagy már elmúlt)",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    },
                                    onShareClick = {
                                        val score = "${match.homeScore ?: 0}–${match.awayScore ?: 0}"
                                        val body = "${match.homeTeam} $score ${match.awayTeam}\n${match.league.orEmpty()}"
                                        val intent = Intent(Intent.ACTION_SEND).apply {
                                            type = "text/plain"
                                            putExtra(Intent.EXTRA_TEXT, body)
                                        }
                                        context.startActivity(Intent.createChooser(intent, "Megosztás"))
                                    }
                                )

                                Divider(
                                    color = leagueBgColor,
                                    thickness = 1.dp
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // ============================================================
    // HIGHLIGHTLY VIDEÓK
    // ============================================================

    if (showNotifHistory) {
        AlertDialog(
            onDismissRequest = { showNotifHistory = false },
            title = { Text("Értesítés előzmények") },
            text = {
                val items = NotifPrefs.history(context)
                Column {
                    if (items.isEmpty()) Text("Még nincs értesítés.")
                    else items.take(15).forEach { n ->
                        Text(n.title, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        Text(n.body, fontSize = 11.sp, color = subTextColor)
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showNotifHistory = false }) { Text("OK") }
            }
        )
    }
    if (showQuietHours) {
        val (qs, qe) = NotifPrefs.quietHours(context)
        var startH by remember { mutableIntStateOf(qs) }
        var endH by remember { mutableIntStateOf(qe) }
        AlertDialog(
            onDismissRequest = { showQuietHours = false },
            title = { Text("Csendes órák") },
            text = {
                Column {
                    Text("Sárga lap push kikapcsolva (pl. 23→7).", fontSize = 12.sp)
                    Text("Kezdet: $startH:00")
                    Slider(value = startH.toFloat(), onValueChange = { startH = it.toInt() }, valueRange = 0f..23f, steps = 22)
                    Text("Vég: $endH:00")
                    Slider(value = endH.toFloat(), onValueChange = { endH = it.toInt() }, valueRange = 0f..23f, steps = 22)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    NotifPrefs.setQuietHours(context, startH, endH)
                    showQuietHours = false
                }) { Text("Mentés") }
            },
            dismissButton = {
                TextButton(onClick = { showQuietHours = false }) { Text("Mégse") }
            }
        )
    }
    if (showHighlightPicker) {
        HighlightVideoPickerDialog(
            match = selectedMatchForMedia,
            videos = highlightVideos,
            isLoading = isHighlightLoading,
            errorMessage = highlightError,
            isDarkMode = isDarkMode,
            onVideoSelected = { video ->
                val m = selectedMatchForMedia
                val url = video.embedUrl ?: video.url
                if (m != null && !url.isNullOrBlank()) {
                    pushRecentMedia(
                        RecentMediaItem(
                            id = "hl-${video.id}",
                            title = video.title?.takeIf { it.isNotBlank() }
                                ?: "${m.homeTeam} vs ${m.awayTeam}",
                            subtitle = video.category ?: (m.league ?: "Highlightly"),
                            url = url,
                            thumbUrl = video.imgUrl ?: m.homeLogoUrl,
                            timestamp = System.currentTimeMillis()
                        )
                    )
                }
                selectedVideo = video
                showHighlightPicker = false
            },
            onOpenExternalUrl = { url ->
                try {
                    val m = selectedMatchForMedia
                    if (m != null) {
                        pushRecentMedia(
                            RecentMediaItem(
                                id = "yt-${m.id}-${url.hashCode()}",
                                title = "${m.homeTeam} vs ${m.awayTeam}",
                                subtitle = m.league ?: "YouTube",
                                url = url,
                                thumbUrl = m.homeLogoUrl,
                                timestamp = System.currentTimeMillis()
                            )
                        )
                    }
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    context.startActivity(intent)
                } catch (e: Exception) {
                    // ignore
                }
            },
            onDismiss = {
                showHighlightPicker = false
                selectedMatchForMedia = null
                highlightVideos = emptyList()
                highlightError = null
            }
        )
    }

    selectedVideo?.let { video ->
        val videoUrl = video.embedUrl ?: video.url

        if (!videoUrl.isNullOrBlank()) {
            HighlightlyVideoDialog(
                video = video,
                url = videoUrl
            ) {
                selectedVideo = null
            }
        }
    }

    // ============================================================
    // TABELLA
    // ============================================================

    selectedLeaguePair?.let { (leagueId, leagueName) ->
        FullLeagueTableDialog(
            leagueId = leagueId,
            leagueName = leagueName,
            isDarkMode = isDarkMode
        ) {
            selectedLeaguePair = null
        }
    }

    // ============================================================
    // MECCS RÉSZLET (events / stats / lineups / videók tab)
    // ============================================================
    selectedMatchForDetail?.let { m ->
        val isFav = favoriteMatchIds.contains(m.id)
        MatchDetailDialog(
            match = m,
            isDarkMode = isDarkMode,
            isFavorite = isFav,
            onFavoriteToggle = {
                favoriteMatchIds =
                    if (isFav) favoriteMatchIds - m.id
                    else favoriteMatchIds + m.id
            },
            onDismiss = { selectedMatchForDetail = null },
            onVideoClick = { video ->
                selectedVideo = video
            }
        )
    }

    // ============================================================
    // AI ELEMZÉS DIALOG
    // ============================================================

    selectedMatchForAi?.let { match ->
        val aiScroll = rememberScrollState()
        AlertDialog(
            onDismissRequest = {
                selectedMatchForAi = null
                viewModel.clearAiAnalysis()
            },
            title = {
                Text(
                    text = "🤖 AI Szimuláció & Elemzés",
                    fontWeight = FontWeight.Bold,
                    color = primaryGreen
                )
            },
            text = {
                if (isLoadingAi) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator(color = primaryGreen)
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "Szimuláció futtatása…",
                            color = subTextColor,
                            fontSize = 12.sp
                        )
                    }
                } else {
                    Text(
                        text = aiAnalysis ?: "Nincs elérhető elemzés.",
                        color = textColor,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        modifier = Modifier
                            .heightIn(max = 360.dp)
                            .verticalScroll(aiScroll)
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        selectedMatchForAi = null
                        viewModel.clearAiAnalysis()
                    }
                ) {
                    Text(
                        text = "Bezárás",
                        color = primaryGreen,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            containerColor = if (isDarkMode) Color(0xFF1E293B) else Color.White
        )
    }
}

// ================================================================
// ORSZÁG / RÉGIÓ ZÁSZLÓ
// ================================================================

private enum class FlagKind {
    COUNTRY,
    EUROPE,
    SOUTH_AMERICA,
    NORTH_AMERICA,
    CENTRAL_AMERICA,
    AFRICA,
    ASIA,
    OCEANIA,
    WORLD,
    GENERIC
}

private data class FlagResult(
    val kind: FlagKind,
    val emoji: String? = null
)

@Composable
private fun LeagueFlagIcon(
    countryCode: String?,
    leagueName: String?,
    modifier: Modifier = Modifier
) {
    val result = countryFlagResult(countryCode, leagueName)

    when (result.kind) {
        FlagKind.COUNTRY -> {
            Text(
                text = result.emoji ?: "🏳️",
                fontSize = 14.sp,
                modifier = modifier
            )
        }

        FlagKind.EUROPE -> {
            RegionFlagIcon(
                kind = FlagKind.EUROPE,
                modifier = modifier
            )
        }

        FlagKind.SOUTH_AMERICA,
        FlagKind.NORTH_AMERICA,
        FlagKind.CENTRAL_AMERICA,
        FlagKind.AFRICA,
        FlagKind.ASIA,
        FlagKind.OCEANIA,
        FlagKind.WORLD,
        FlagKind.GENERIC -> {
            RegionFlagIcon(
                kind = result.kind,
                modifier = modifier
            )
        }
    }
}

/**
 * Kis, egységes zászló-jellegű régióikonok azokhoz a sorozatokhoz,
 * amelyek nem egyetlen országhoz tartoznak.
 *
 * Így többé nem jelenik meg 🌐 a régióknál sem.
 */
@Composable
private fun RegionFlagIcon(
    kind: FlagKind,
    modifier: Modifier = Modifier
) {
    Canvas(
        modifier = modifier
            .width(22.dp)
            .height(15.dp)
    ) {
        val w = size.width
        val h = size.height
        val radius = 2.5f

        when (kind) {
            FlagKind.EUROPE -> {
                // EU: kék zászló + sárga csillagpontok.
                drawRoundRect(
                    color = Color(0xFF174EA6),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius, radius)
                )
                val cx = w / 2f
                val cy = h / 2f
                val r = h * 0.30f
                for (i in 0 until 8) {
                    val a = Math.toRadians((i * 45.0) - 90.0)
                    drawCircle(
                        color = Color(0xFFFFD700),
                        radius = 0.85f,
                        center = androidx.compose.ui.geometry.Offset(
                            cx + kotlin.math.cos(a).toFloat() * r,
                            cy + kotlin.math.sin(a).toFloat() * r
                        )
                    )
                }
            }

            FlagKind.SOUTH_AMERICA -> {
                drawRoundRect(Color(0xFF1B5E20), cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius, radius))
                drawCircle(Color(0xFFFFD54F), radius = h * 0.34f, center = androidx.compose.ui.geometry.Offset(w * 0.50f, h * 0.50f))
                drawCircle(Color(0xFF1565C0), radius = h * 0.19f, center = androidx.compose.ui.geometry.Offset(w * 0.50f, h * 0.50f))
            }

            FlagKind.NORTH_AMERICA -> {
                drawRoundRect(Color(0xFF1565C0), cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius, radius))
                drawRect(Color.White, androidx.compose.ui.geometry.Offset(w * 0.10f, h * 0.28f), androidx.compose.ui.geometry.Size(w * 0.80f, h * 0.14f))
                drawRect(Color.White, androidx.compose.ui.geometry.Offset(w * 0.10f, h * 0.58f), androidx.compose.ui.geometry.Size(w * 0.80f, h * 0.14f))
                drawCircle(Color(0xFFE53935), radius = h * 0.16f, center = androidx.compose.ui.geometry.Offset(w * 0.22f, h * 0.50f))
            }

            FlagKind.CENTRAL_AMERICA -> {
                drawRoundRect(Color(0xFF0277BD), cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius, radius))
                drawRect(Color.White, androidx.compose.ui.geometry.Offset(0f, h * 0.28f), androidx.compose.ui.geometry.Size(w, h * 0.44f))
                drawCircle(Color(0xFF2E7D32), radius = h * 0.17f, center = androidx.compose.ui.geometry.Offset(w * 0.50f, h * 0.50f))
            }

            FlagKind.AFRICA -> {
                drawRoundRect(Color(0xFF2E7D32), cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius, radius))
                drawLine(Color(0xFFFFD600), androidx.compose.ui.geometry.Offset(w * 0.10f, h * 0.78f), androidx.compose.ui.geometry.Offset(w * 0.90f, h * 0.22f), strokeWidth = h * 0.16f)
                drawCircle(Color(0xFFD32F2F), radius = h * 0.18f, center = androidx.compose.ui.geometry.Offset(w * 0.72f, h * 0.35f))
            }

            FlagKind.ASIA -> {
                drawRoundRect(Color(0xFFD32F2F), cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius, radius))
                drawCircle(Color(0xFFFFD54F), radius = h * 0.28f, center = androidx.compose.ui.geometry.Offset(w * 0.50f, h * 0.50f))
                drawCircle(Color(0xFFD32F2F), radius = h * 0.20f, center = androidx.compose.ui.geometry.Offset(w * 0.56f, h * 0.44f))
            }

            FlagKind.OCEANIA -> {
                drawRoundRect(Color(0xFF1565C0), cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius, radius))
                drawCircle(Color(0xFFFFD54F), radius = h * 0.14f, center = androidx.compose.ui.geometry.Offset(w * 0.70f, h * 0.34f))
                drawCircle(Color.White, radius = h * 0.10f, center = androidx.compose.ui.geometry.Offset(w * 0.35f, h * 0.65f))
                drawCircle(Color.White, radius = h * 0.07f, center = androidx.compose.ui.geometry.Offset(w * 0.55f, h * 0.68f))
            }

            FlagKind.WORLD -> {
                drawRoundRect(Color(0xFF1565C0), cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius, radius))
                drawCircle(
                    color = Color(0xFF66BB6A),
                    radius = h * 0.36f,
                    center = androidx.compose.ui.geometry.Offset(w * 0.50f, h * 0.50f)
                )
                drawCircle(
                    color = Color(0xFF1565C0),
                    radius = h * 0.36f,
                    center = androidx.compose.ui.geometry.Offset(w * 0.55f, h * 0.45f)
                )
            }

            FlagKind.GENERIC -> {
                drawRoundRect(Color(0xFF455A64), cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius, radius))
                drawRect(Color.White, androidx.compose.ui.geometry.Offset(0f, h * 0.33f), androidx.compose.ui.geometry.Size(w, h * 0.34f))
            }

            FlagKind.COUNTRY -> Unit
        }
    }
}

private fun countryFlagResult(
    countryCode: String?,
    leagueName: String? = null
): FlagResult {
    val directCode = countryCode
        ?.trim()
        ?.uppercase()
        .orEmpty()

    if (directCode.length == 2 &&
        directCode[0] in 'A'..'Z' &&
        directCode[1] in 'A'..'Z'
    ) {
        return FlagResult(FlagKind.COUNTRY, isoFlag(directCode))
    }

    val name = leagueName
        ?.trim()
        ?.uppercase()
        .orEmpty()

    return when {
        startsWithRegion(name, "EURÓPA", "EUROPE", "UEFA") -> FlagResult(FlagKind.EUROPE)
        startsWithRegion(name, "DÉL-AMERIKA", "SOUTH AMERICA", "CONMEBOL", "SOUTHAMERICA") -> FlagResult(FlagKind.SOUTH_AMERICA)
        startsWithRegion(name, "KÖZÉP-AMERIKA", "CENTRAL AMERICA") -> FlagResult(FlagKind.CENTRAL_AMERICA)
        startsWithRegion(name, "ÉSZAK-AMERIKA", "NORTH AMERICA", "CONCACAF") -> FlagResult(FlagKind.NORTH_AMERICA)
        startsWithRegion(name, "AFRIKA", "AFRICA", "CAF") -> FlagResult(FlagKind.AFRICA)
        startsWithRegion(name, "ÁZSIA", "ASIA", "AFC") -> FlagResult(FlagKind.ASIA)
        startsWithRegion(name, "ÓCEÁNIA", "OCEANIA", "OFC") -> FlagResult(FlagKind.OCEANIA)
        startsWithRegion(name, "VILÁG", "WORLD", "NEMZETKÖZI", "INTERNATIONAL") -> FlagResult(FlagKind.WORLD)
        else -> countryCodeFromLeagueName(name)?.let { FlagResult(FlagKind.COUNTRY, isoFlag(it)) }
            ?: FlagResult(FlagKind.GENERIC)
    }
}

private fun startsWithRegion(name: String, vararg prefixes: String): Boolean =
    prefixes.any { prefix ->
        name == prefix ||
            name.startsWith("$prefix:") ||
            name.startsWith("$prefix ")
    }

private fun isoFlag(code: String): String {
    if (code == "EU") return "🇪🇺"
    if (code == "UN") return "🇺🇳"
    if (code == "XK") return "🇽🇰"
    if (code.length != 2) return "🏳️"

    val first = code[0]
    val second = code[1]

    if (first !in 'A'..'Z' || second !in 'A'..'Z') {
        return "🏳️"
    }

    return buildString {
        appendCodePoint(0x1F1E6 + (first - 'A'))
        appendCodePoint(0x1F1E6 + (second - 'A'))
    }
}

/**
 * Ország meghatározása a bajnokság nevéből.
 *
 * Ez a tartalék megoldás azért kell, mert több API-rekordnál a
 * countryCode üres / hiányzik, miközben a bajnokság neve egyértelműen
 * tartalmazza az országot.
 */
private fun countryCodeFromLeagueName(name: String): String? {
    val countries = linkedMapOf(
        "EGYESÜLT ARAB EMÍRSÉGEK" to "AE", "UNITED ARAB EMIRATES" to "AE",
        "DÉL-KOREA" to "KR", "SOUTH KOREA" to "KR",
        "ÉSZAK-MACEDÓNIA" to "MK", "NORTH MACEDONIA" to "MK",
        "CSEHORSZÁG" to "CZ", "CZECHIA" to "CZ", "CZECH REPUBLIC" to "CZ",
        "FEHÉROROSZORSZÁG" to "BY", "BELARUS" to "BY",
        "HORVÁTORSZÁG" to "HR", "CROATIA" to "HR",
        "SZERBIA" to "RS", "SERBIA" to "RS",
        "SZLOVÁKIA" to "SK", "SLOVAKIA" to "SK",
        "SZLOVÉNIA" to "SI", "SLOVENIA" to "SI",
        "LENGYELORSZÁG" to "PL", "POLAND" to "PL",
        "ROMÁNIA" to "RO", "ROMANIA" to "RO",
        "BULGÁRIA" to "BG", "BULGARIA" to "BG",
        "DÁNIA" to "DK", "DENMARK" to "DK",
        "ANGLIA" to "GB", "ENGLAND" to "GB", "SKÓCIA" to "GB", "SCOTLAND" to "GB",
        "WALES" to "GB", "ÉSZAK-ÍRORSZÁG" to "GB", "NORTHERN IRELAND" to "GB",
        "FRANCIAORSZÁG" to "FR", "FRANCE" to "FR",
        "NÉMETORSZÁG" to "DE", "GERMANY" to "DE",
        "OLASZORSZÁG" to "IT", "ITALY" to "IT",
        "SPANYOLORSZÁG" to "ES", "SPAIN" to "ES",
        "PORTUGÁLIA" to "PT", "PORTUGAL" to "PT",
        "HOLLANDIA" to "NL", "NETHERLANDS" to "NL",
        "BELGIUM" to "BE", "SVÁJC" to "CH", "SWITZERLAND" to "CH",
        "AUSZTRIA" to "AT", "AUSTRIA" to "AT",
        "TÖRÖKORSZÁG" to "TR", "TURKEY" to "TR",
        "GÖRÖGORSZÁG" to "GR", "GREECE" to "GR",
        "IZLAND" to "IS", "ICELAND" to "IS",
        "ÍRORSZÁG" to "IE", "IRELAND" to "IE",
        "NORVÉGIA" to "NO", "NORWAY" to "NO",
        "SVÉDORSZÁG" to "SE", "SWEDEN" to "SE",
        "FINNORSZÁG" to "FI", "FINLAND" to "FI",
        "UKRAJNA" to "UA", "UKRAINE" to "UA",
        "OROSZORSZÁG" to "RU", "RUSSIA" to "RU",
        "BOSZNIA-HERCEGOVINA" to "BA", "BOSNIA" to "BA", "MONTENEGRO" to "ME",
        "ÉSZTORSZÁG" to "EE", "ESTONIA" to "EE", "LETTORSZÁG" to "LV", "LATVIA" to "LV",
        "LITVÁNIA" to "LT", "LITHUANIA" to "LT", "MOLDOVA" to "MD",
        "KOSZOVÓ" to "XK", "KOSOVO" to "XK", "ÖRMÉNYORSZÁG" to "AM", "ARMENIA" to "AM",
        "AZERBAJDZSÁN" to "AZ", "AZERBAIJAN" to "AZ", "GRÚZIA" to "GE", "GEORGIA" to "GE",
        "KAZAHSZTÁN" to "KZ", "KAZAKHSTAN" to "KZ", "ÜZBEGISZTÁN" to "UZ", "UZBEKISTAN" to "UZ",
        "KIRGIZISZTÁN" to "KG", "KYRGYZSTAN" to "KG", "TÁDZSIKISZTÁN" to "TJ", "TAJIKISTAN" to "TJ",
        "TURKMENISZTÁN" to "TM", "TURKMENISTAN" to "TM", "IRÁN" to "IR", "IRAN" to "IR",
        "IRAK" to "IQ", "IRAQ" to "IQ", "IZRAEL" to "IL", "ISRAEL" to "IL",
        "KATAR" to "QA", "QATAR" to "QA", "SZAÚD-ARÁBIA" to "SA", "SAUDI ARABIA" to "SA",
        "INDIA" to "IN", "PAKISZTÁN" to "PK", "PAKISTAN" to "PK", "BANGLADESH" to "BD",
        "JAPÁN" to "JP", "JAPAN" to "JP", "KÍNA" to "CN", "CHINA" to "CN",
        "DÉL-AFRIKA" to "ZA", "SOUTH AFRICA" to "ZA", "EGYIPTOM" to "EG", "EGYPT" to "EG",
        "MAROKKÓ" to "MA", "MOROCCO" to "MA", "ALGÉRIA" to "DZ", "ALGERIA" to "DZ",
        "TUNÉZIA" to "TN", "TUNISIA" to "TN", "TANZÁNIA" to "TZ", "TANZANIA" to "TZ",
        "GHÁNA" to "GH", "GHANA" to "GH", "NIGÉRIA" to "NG", "NIGERIA" to "NG",
        "KENYA" to "KE", "UGANDA" to "UG", "ETIÓPIA" to "ET", "ETHIOPIA" to "ET",
        "USA" to "US", "EGYESÜLT ÁLLAMOK" to "US", "UNITED STATES" to "US", "KANADA" to "CA", "CANADA" to "CA",
        "MEXIKÓ" to "MX", "MEXICO" to "MX", "KOSTA RIKA" to "CR", "COSTA RICA" to "CR",
        "PANAMA" to "PA", "GUATEMALA" to "GT", "HONDURAS" to "HN", "NICARAGUA" to "NI", "EL SALVADOR" to "SV",
        "KOLUMBIA" to "CO", "COLOMBIA" to "CO", "ECUADOR" to "EC", "PERU" to "PE", "BOLÍVIA" to "BO", "BOLIVIA" to "BO",
        "CHILE" to "CL", "ARGENTÍNA" to "AR", "ARGENTINA" to "AR", "BRAZÍLIA" to "BR", "BRAZIL" to "BR",
        "PARAGUAY" to "PY", "URUGUAY" to "UY", "VENEZUELA" to "VE", "KUBA" to "CU", "CUBA" to "CU", "JAMAICA" to "JM",
        "DOMINIKAI KÖZTÁRSASÁG" to "DO", "DOMINICAN REPUBLIC" to "DO", "TRINIDAD ÉS TOBAGO" to "TT", "TRINIDAD AND TOBAGO" to "TT",
        "AUSTRÁLIA" to "AU", "AUSTRALIA" to "AU", "ÚJ-ZÉLAND" to "NZ", "NEW ZEALAND" to "NZ",
        "MALAJZIA" to "MY", "MALAYSIA" to "MY", "SZINGAPÚR" to "SG", "SINGAPORE" to "SG", "THAIFÖLD" to "TH", "THAILAND" to "TH",
        "VIETNÁM" to "VN", "VIETNAM" to "VN", "INDONÉZIA" to "ID", "INDONESIA" to "ID", "FÜLÖP-SZIGETEK" to "PH", "PHILIPPINES" to "PH",
        "MYANMAR" to "MM", "SRÍ LANKA" to "LK", "SRI LANKA" to "LK", "NEPÁL" to "NP", "NEPAL" to "NP",
        "CIPRUS" to "CY", "CYPRUS" to "CY", "MÁLTA" to "MT", "MALTA" to "MT", "LUXEMBURG" to "LU", "LUXEMBOURG" to "LU",
        "ANDORRA" to "AD", "SAN MARINO" to "SM", "GIBRALTÁR" to "GI", "GIBRALTAR" to "GI", "FERÖER-SZIGETEK" to "FO", "FAROE ISLANDS" to "FO",
        "LIECHTENSTEIN" to "LI"
    )

    for ((country, code) in countries) {
        if (name == country || name.startsWith("$country:") || name.startsWith("$country ")) {
            return code
        }
    }

    val containsCountry = listOf(
        "COPA PARAGUAY" to "PY",
        "COPA URUGUAY" to "UY",
        "BETANO POKALEN" to "DK",
        "CROATIAN CUP" to "HR",
        "ARMENIAN CUP" to "AM",
        "RUSSIAN CUP" to "RU",
        "SLOVAK CUP" to "SK",
        "CALCUTTA PREMIER" to "IN",
        "LIGA DE ASCENSO" to "CR"
    )

    for ((part, code) in containsCountry) {
        if (name.contains(part)) return code
    }

    return null
}

// ================================================================
// CSAPAT LOGÓ
// ================================================================

@Composable
private fun TeamLogo(
    url: String?,
    teamName: String,
    size: androidx.compose.ui.unit.Dp = 26.dp
) {

    val initials =
        teamName
            .trim()
            .split(Regex("\\s+"))
            .filter {
                it.isNotBlank()
            }
            .take(2)
            .joinToString("") {
                it.first().uppercase()
            }
            .ifBlank {
                "⚽"
            }

    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(
                Color(0xFF252A31)
            ),

        contentAlignment =
            Alignment.Center
    ) {

        if (!url.isNullOrBlank()) {

            SubcomposeAsyncImage(
                model = url,

                contentDescription =
                    "$teamName logó",

                modifier =
                    Modifier.fillMaxSize(),

                loading = {

                    Text(
                        text = initials,
                        color = Color(0xFF8C939D),
                        fontSize = 8.sp,
                        fontWeight =
                            FontWeight.Bold
                    )
                },

                error = {

                    Text(
                        text = initials,
                        color = Color(0xFF8C939D),
                        fontSize = 8.sp,
                        fontWeight =
                            FontWeight.Bold
                    )
                }
            )

        } else {

            Text(
                text = initials,
                color = Color(0xFF8C939D),
                fontSize = 8.sp,
                fontWeight =
                    FontWeight.Bold
            )
        }
    }
}

// ================================================================
// MECCS SOR
// ================================================================

@Composable
fun PremiumMatchRow(
    match: MatchResponse,
    isFavorite: Boolean,
    isReminderSet: Boolean = false,
    cardBgColor: Color,
    textColor: Color,
    subTextColor: Color,
    primaryGreen: Color,
    compact: Boolean = false,
    scoreFlash: Boolean = false,
    onFavoriteToggle: () -> Unit,
    onVideoClick: (MatchResponse) -> Unit,
    onAiClick: (MatchResponse) -> Unit,
    onMatchClick: (MatchResponse) -> Unit = {},
    onReminderClick: (MatchResponse) -> Unit = {},
    onShareClick: () -> Unit = {}
) {
    val isLive = isMatchLive(match.status, match.minute)
    val isFinished = isMatchFinished(match.status)
    val statusBarColor = when {
        isLive -> primaryGreen
        match.status == "HT" -> Color(0xFFFFD54F)
        isFinished -> Color(0xFF6B7C8F)
        else -> Color(0xFF4DA3FF)
    }
    val vPad = if (compact) 6.dp else 10.dp

    Column(modifier = Modifier.fillMaxWidth()) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = if (compact) 2.dp else 3.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (scoreFlash) Color(0x4400E5A8) else cardBgColor)
            .border(1.dp, if (scoreFlash) primaryGreen else Color(0x28A0C4FF), RoundedCornerShape(14.dp))
            .clickable { onMatchClick(match) }
            .padding(
                horizontal = 12.dp,
                vertical = vPad
            ),

        verticalAlignment =
            Alignment.CenterVertically
    ) {
        // Státusz sáv
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(if (compact) 28.dp else 36.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(statusBarColor)
        )
        Spacer(Modifier.width(8.dp))


        // ============================================================
        // MECCS KEDVENC
        // ============================================================

        Text(
            text =
                if (isFavorite) {
                    "★"
                } else {
                    "☆"
                },

            color =
                if (isFavorite) {
                    Color(0xFFFF9100)
                } else {
                    subTextColor
                },

            fontSize = 18.sp,

            modifier = Modifier
                .clickable {
                    onFavoriteToggle()
                }
                .padding(end = 8.dp)
        )

        // ============================================================
        // STÁTUSZ / IDŐ
        // ============================================================

        Column(
            modifier =
                Modifier.width(55.dp),

            horizontalAlignment =
                Alignment.Start
        ) {

            val isLive = isMatchLive(match.status, match.minute)

            val statusText =
                when {
                    match.status == "FT" -> "Vége"
                    match.status == "AET" -> "Hossz. után"
                    match.status == "PEN" || match.status == "Pen." -> "11-esek"
                    match.status == "HT" -> "Félidő"
                    match.status == "1H" -> "1. Félidő"
                    match.status == "2H" -> "2. Félidő"
                    match.status == "ET" -> "Hosszabbítás"
                    match.status == "NS" -> "Kezdés"
                    else -> match.status
                }

            if (isLive) {

                Row(
                    verticalAlignment =
                        Alignment.CenterVertically
                ) {

                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(
                                primaryGreen
                            )
                    )

                    Spacer(
                        modifier =
                            Modifier.width(4.dp)
                    )

                    Text(
                        text =
                            "${match.minute}'",

                        color =
                            primaryGreen,

                        fontSize = 11.sp,

                        fontWeight =
                            FontWeight.Bold
                    )
                }

            } else {

                Text(
                    text = statusText,

                    color =
                        subTextColor,

                    fontSize = 10.sp,

                    fontWeight =
                        FontWeight.Medium
                )
            }
        }

        // ============================================================
        // CSAPATOK
        // ============================================================

        Column(
            modifier =
                Modifier.weight(1f)
        ) {

            Row(
                verticalAlignment =
                    Alignment.CenterVertically
            ) {

                TeamLogo(
                    url =
                        match.homeLogoUrl,

                    teamName =
                        match.homeTeam
                )

                Spacer(
                    modifier =
                        Modifier.width(8.dp)
                )

                Text(
                    text = match.homeTeam,
                    color = textColor,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
            }

            Spacer(
                modifier =
                    Modifier.height(4.dp)
            )

            Row(
                verticalAlignment =
                    Alignment.CenterVertically
            ) {

                TeamLogo(
                    url =
                        match.awayLogoUrl,

                    teamName =
                        match.awayTeam
                )

                Spacer(
                    modifier =
                        Modifier.width(8.dp)
                )

                Text(
                    text = match.awayTeam,
                    color = textColor,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
            }
        }

        // ============================================================
        // EREDMÉNY + AKCIÓ GOMBOK (nem zsugorodhatnak el)
        // ============================================================

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.wrapContentWidth()
        ) {
            Column(
                horizontalAlignment = Alignment.End,
                modifier = Modifier.widthIn(min = 20.dp)
            ) {
                Text(
                    text = "${match.homeScore ?: 0}",
                    color = if (match.homeScore != null) primaryGreen else subTextColor,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "${match.awayScore ?: 0}",
                    color = if (match.awayScore != null) primaryGreen else subTextColor,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Emlékeztető
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(
                        if (isReminderSet) Color(0xFFFF9100) else Color(0xFF455A64)
                    )
                    .clickable { onReminderClick(match) }
                    .padding(horizontal = 7.dp, vertical = 5.dp)
            ) {
                Text(
                    text = if (isReminderSet) "🔔" else "⏰",
                    color = Color.White,
                    fontSize = 12.sp
                )
            }

            Spacer(modifier = Modifier.width(6.dp))

            // AI gomb – mindig látszik
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFF0284C7))
                    .clickable { onAiClick(match) }
                    .padding(horizontal = 7.dp, vertical = 5.dp)
            ) {
                Text(
                    text = "🤖",
                    color = Color.White,
                    fontSize = 12.sp
                )
            }

            // Videó / multimédia gomb – mindig látszik
            Spacer(modifier = Modifier.width(6.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(
                        if (!match.highlightMatchId.isNullOrBlank()) {
                            Color(0xFF2979FF)
                        } else {
                            Color(0xFF455A64)
                        }
                    )
                    .clickable { onVideoClick(match) }
                    .padding(horizontal = 7.dp, vertical = 5.dp)
            ) {
                Text(
                    text = "🎥",
                    color = Color.White,
                    fontSize = 12.sp
                )
            }

            // Megosztás
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF546E7A))
                    .clickable { onShareClick() },
                contentAlignment = Alignment.Center
            ) {
                Text(text = "↗", color = Color.White, fontSize = 12.sp)
            }
        }
    }

    // Odds sor (ha van)
    if (match.oddsHome != null || match.oddsDraw != null || match.oddsAway != null) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 48.dp, end = 12.dp, bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf(
                "1" to match.oddsHome,
                "X" to match.oddsDraw,
                "2" to match.oddsAway
            ).forEach { (label, v) ->
                if (v != null) {
                    Text(
                        text = "$label ${"%.2f".format(v)}",
                        color = subTextColor,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
            if (match.isValueBet == true) {
                Text("ÉRTÉKES", color = Color(0xFFFFD54F), fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
    } // Column
}

// ================================================================
// TELJES LIGA TABELLA
// ================================================================

@Composable
fun FullLeagueTableDialog(
    leagueId: String,
    leagueName: String,
    isDarkMode: Boolean,
    onDismiss: () -> Unit
) {

    val dialogBg =
        if (isDarkMode) {
            Color(0xFF1A1D21)
        } else {
            Color(0xFFFFFFFF)
        }

    val textColor =
        if (isDarkMode) {
            Color.White
        } else {
            Color.Black
        }

    val coroutineScope =
        rememberCoroutineScope()

    var standings by remember {
        mutableStateOf<List<StandingTeam>>(
            emptyList()
        )
    }

    var isLoading by remember {
        mutableStateOf(true)
    }

    LaunchedEffect(leagueId) {

        coroutineScope.launch {

            try {

                val api =
                    RetrofitInstance.api

                standings =
                    api.getStandings(
                        leagueId
                    )

            } catch (e: Exception) {

                standings =
                    emptyList()

            } finally {

                isLoading = false
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss
    ) {

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),

            shape =
                RoundedCornerShape(16.dp),

            colors =
                CardDefaults.cardColors(
                    containerColor =
                        dialogBg
                )
        ) {

            Column(
                modifier =
                    Modifier.padding(16.dp)
            ) {

                Text(
                    text =
                        "📊 $leagueName Tabella",

                    fontSize = 15.sp,

                    fontWeight =
                        FontWeight.Bold,

                    color =
                        Color(0xFF00E676)
                )

                Spacer(
                    modifier =
                        Modifier.height(10.dp)
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            vertical = 4.dp
                        ),

                    verticalAlignment =
                        Alignment.CenterVertically
                ) {

                    Text(
                        "#",
                        fontWeight =
                            FontWeight.Bold,
                        color = Color.Gray,
                        fontSize = 10.sp,
                        modifier =
                            Modifier.width(20.dp)
                    )

                    Text(
                        "CSAPAT",
                        fontWeight =
                            FontWeight.Bold,
                        color = Color.Gray,
                        fontSize = 10.sp,
                        modifier =
                            Modifier.weight(1f)
                    )

                    Text(
                        "M",
                        fontWeight =
                            FontWeight.Bold,
                        color = Color.Gray,
                        fontSize = 10.sp,
                        modifier =
                            Modifier.width(22.dp)
                    )

                    Text(
                        "GY",
                        fontWeight =
                            FontWeight.Bold,
                        color = Color.Gray,
                        fontSize = 10.sp,
                        modifier =
                            Modifier.width(22.dp)
                    )

                    Text(
                        "D",
                        fontWeight =
                            FontWeight.Bold,
                        color = Color.Gray,
                        fontSize = 10.sp,
                        modifier =
                            Modifier.width(22.dp)
                    )

                    Text(
                        "V",
                        fontWeight =
                            FontWeight.Bold,
                        color = Color.Gray,
                        fontSize = 10.sp,
                        modifier =
                            Modifier.width(22.dp)
                    )

                    Text(
                        "GÓL",
                        fontWeight =
                            FontWeight.Bold,
                        color = Color.Gray,
                        fontSize = 10.sp,
                        modifier =
                            Modifier.width(36.dp)
                    )

                    Text(
                        "P",
                        fontWeight =
                            FontWeight.Bold,
                        color =
                            Color(0xFF00E676),
                        fontSize = 10.sp,
                        modifier =
                            Modifier.width(24.dp)
                    )
                }

                Divider(
                    color = Color.Gray,
                    thickness = 1.dp
                )

                if (isLoading) {

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(150.dp),

                        contentAlignment =
                            Alignment.Center
                    ) {

                        CircularProgressIndicator(
                            color =
                                Color(0xFF00E676)
                        )
                    }

                } else if (standings.isEmpty()) {

                    Text(
                        "A tabella jelenleg nem érhető el ehhez a ligához.",

                        color =
                            Color.Gray,

                        fontSize = 11.sp,

                        modifier =
                            Modifier.padding(
                                vertical = 16.dp
                            )
                    )

                } else {

                    LazyColumn(
                        modifier =
                            Modifier.heightIn(
                                max = 350.dp
                            )
                    ) {

                        items(standings) { item ->

                            Row(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(
                                            vertical = 6.dp
                                        ),

                                verticalAlignment =
                                    Alignment.CenterVertically
                            ) {

                                Text(
                                    "${item.position}.",

                                    color =
                                        textColor,

                                    fontSize = 10.sp,

                                    fontWeight =
                                        FontWeight.Bold,

                                    modifier =
                                        Modifier.width(20.dp)
                                )

                                Text(
                                    item.team,

                                    color =
                                        textColor,

                                    fontSize = 11.sp,

                                    fontWeight =
                                        FontWeight.SemiBold,

                                    modifier =
                                        Modifier.weight(1f)
                                )

                                Text(
                                    "${item.played}",

                                    color =
                                        textColor,

                                    fontSize = 10.sp,

                                    modifier =
                                        Modifier.width(22.dp)
                                )

                                Text(
                                    "${item.wins}",

                                    color =
                                        textColor,

                                    fontSize = 10.sp,

                                    modifier =
                                        Modifier.width(22.dp)
                                )

                                Text(
                                    "${item.draws}",

                                    color =
                                        textColor,

                                    fontSize = 10.sp,

                                    modifier =
                                        Modifier.width(22.dp)
                                )

                                Text(
                                    "${item.losses}",

                                    color =
                                        textColor,

                                    fontSize = 10.sp,

                                    modifier =
                                        Modifier.width(22.dp)
                                )

                                Text(
                                    "${item.goalsScored}:${item.goalsAllowed}",

                                    color =
                                        textColor,

                                    fontSize = 10.sp,

                                    modifier =
                                        Modifier.width(36.dp)
                                )

                                Text(
                                    "${item.points}",

                                    color =
                                        Color(0xFF00E676),

                                    fontSize = 11.sp,

                                    fontWeight =
                                        FontWeight.Bold,

                                    modifier =
                                        Modifier.width(24.dp)
                                )
                            }

                            Divider(
                                color =
                                    Color(0xFF2B2B2B),

                                thickness = 0.5.dp
                            )
                        }
                    }
                }

                Spacer(
                    modifier =
                        Modifier.height(12.dp)
                )

                Button(
                    onClick =
                        onDismiss,

                    colors =
                        ButtonDefaults.buttonColors(
                            containerColor =
                                Color(0xFF00E676)
                        ),

                    modifier =
                        Modifier.align(
                            Alignment.End
                        )
                ) {

                    Text(
                        "Bezárás",
                        color = Color.Black,
                        fontSize = 12.sp
                    )
                }
            }
        }
    }
}

// ================================================================
// HIGHLIGHTLY VIDEÓ VÁLASZTÓ
// ================================================================

@Composable
fun HighlightVideoPickerDialog(
    match: MatchResponse?,
    videos: List<HighlightVideo>,
    isLoading: Boolean,
    errorMessage: String?,
    isDarkMode: Boolean,
    onVideoSelected: (HighlightVideo) -> Unit,
    onOpenExternalUrl: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val dialogBg =
        if (isDarkMode) Color(0xFF1A1D21) else Color.White

    val textColor =
        if (isDarkMode) Color.White else Color(0xFF101214)

    val subTextColor =
        if (isDarkMode) Color(0xFF8C939D) else Color(0xFF6C757D)

    val goalClips =
        videos.filter {
            it.category.equals("goal-clip", ignoreCase = true)
        }

    val matchHighlights =
        videos.filter {
            it.category.equals("match-highlights", ignoreCase = true)
        }

    val otherVideos =
        videos.filter {
            !it.category.equals("goal-clip", ignoreCase = true) &&
                !it.category.equals("match-highlights", ignoreCase = true)
        }

    val home = match?.homeTeam.orEmpty()
    val away = match?.awayTeam.orEmpty()
    val queryBase = listOf(home, "vs", away).filter { it.isNotBlank() }.joinToString(" ")

    fun ytSearchUrl(extra: String): String {
        val q = "$queryBase $extra".trim().ifBlank { "football highlights" }
        return "https://www.youtube.com/results?search_query=" + Uri.encode(q)
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = dialogBg)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {

                Text(
                    text = "🎬 Multimédia központ",
                    color = Color(0xFF00E676),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.ExtraBold
                )

                if (!home.isNullOrBlank() || !away.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "$home vs $away",
                        color = textColor,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    match?.league?.let { league ->
                        Text(
                            text = league,
                            color = subTextColor,
                            fontSize = 11.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // ---- YouTube / külső források (mindig) ----
                Text(
                    text = "🌍 Nemzetközi források",
                    color = Color(0xFF40C4FF),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(6.dp))

                MultimediaLinkRow(
                    emoji = "▶️",
                    title = "YouTube – meccsösszefoglaló",
                    subtitle = "Keresés: highlights",
                    textColor = textColor,
                    subTextColor = subTextColor,
                    onClick = { onOpenExternalUrl(ytSearchUrl("highlights")) }
                )
                MultimediaLinkRow(
                    emoji = "⚽",
                    title = "YouTube – gólok",
                    subtitle = "Keresés: goals",
                    textColor = textColor,
                    subTextColor = subTextColor,
                    onClick = { onOpenExternalUrl(ytSearchUrl("goals")) }
                )
                MultimediaLinkRow(
                    emoji = "📡",
                    title = "YouTube – élő / preview",
                    subtitle = "Keresés: live OR preview",
                    textColor = textColor,
                    subTextColor = subTextColor,
                    onClick = { onOpenExternalUrl(ytSearchUrl("live OR preview")) }
                )

                Spacer(modifier = Modifier.height(12.dp))
                Divider(
                    color = if (isDarkMode) Color(0xFF2B3036) else Color(0xFFE5E7EB),
                    thickness = 1.dp
                )
                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = "🎥 Highlightly",
                    color = Color(0xFFFFD54F),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(6.dp))

                if (isLoading) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(100.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = Color(0xFF00E676))
                    }
                } else if (goalClips.isNotEmpty() || matchHighlights.isNotEmpty() || otherVideos.isNotEmpty()) {
                    LazyColumn(modifier = Modifier.heightIn(max = 220.dp)) {
                        if (goalClips.isNotEmpty()) {
                            item {
                                Text(
                                    text = "⚽ Gólvideók",
                                    color = Color(0xFFFFD54F),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(vertical = 4.dp)
                                )
                            }
                            items(goalClips) { video ->
                                HighlightVideoRow(
                                    video = video,
                                    textColor = textColor,
                                    subTextColor = subTextColor,
                                    onClick = { onVideoSelected(video) }
                                )
                            }
                        }
                        if (matchHighlights.isNotEmpty()) {
                            item {
                                Text(
                                    text = "🎬 Meccsösszefoglaló",
                                    color = Color(0xFF64B5F6),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(vertical = 4.dp)
                                )
                            }
                            items(matchHighlights) { video ->
                                HighlightVideoRow(
                                    video = video,
                                    textColor = textColor,
                                    subTextColor = subTextColor,
                                    onClick = { onVideoSelected(video) }
                                )
                            }
                        }
                        if (otherVideos.isNotEmpty()) {
                            item {
                                Text(
                                    text = "📹 Egyéb",
                                    color = subTextColor,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(vertical = 4.dp)
                                )
                            }
                            items(otherVideos) { video ->
                                HighlightVideoRow(
                                    video = video,
                                    textColor = textColor,
                                    subTextColor = subTextColor,
                                    onClick = { onVideoSelected(video) }
                                )
                            }
                        }
                    }
                } else {
                    Text(
                        text = errorMessage
                            ?: "Jelenleg nincs Highlightly-videó ehhez a meccshez. Használd a YouTube forrásokat fent.",
                        color = subTextColor,
                        fontSize = 12.sp
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF00E676)
                    ),
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text(
                        text = "Bezárás",
                        color = Color.Black,
                        fontSize = 12.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun MultimediaLinkRow(
    emoji: String,
    title: String,
    subtitle: String,
    textColor: Color,
    subTextColor: Color,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = emoji, fontSize = 18.sp)
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = textColor,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = subtitle,
                color = subTextColor,
                fontSize = 10.sp
            )
        }
        Text(
            text = "↗",
            color = Color(0xFF40C4FF),
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold
        )
    }
}


// ================================================================
// HIGHLIGHTLY VIDEÓ SOR
// ================================================================

@Composable
private fun HighlightVideoRow(
    video: HighlightVideo,
    textColor: Color,
    subTextColor: Color,
    onClick: () -> Unit
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(
                    vertical = 10.dp
                ),

        verticalAlignment =
            Alignment.CenterVertically
    ) {

        Text(
            text =
                if (
                    video.category.equals(
                        "goal-clip",
                        ignoreCase = true
                    )
                ) {
                    "⚽"
                } else {
                    "🎬"
                },

            fontSize = 18.sp
        )

        Spacer(
            modifier =
                Modifier.width(10.dp)
        )

        Column(
            modifier =
                Modifier.weight(1f)
        ) {

            Text(
                text =
                    video.title
                        ?.takeIf { it.isNotBlank() }
                        ?: "Highlightly videó",

                color = textColor,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2
            )

            if (!video.category.isNullOrBlank()) {

                Text(
                    text = video.category,
                    color = subTextColor,
                    fontSize = 9.sp
                )
            }
        }

        Text(
            text = "▶",
            color = Color(0xFF00E676),
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold
        )
    }
}


// ================================================================
// HIGHLIGHTLY WEBVIEW VIDEÓLEJÁTSZÓ
// ================================================================

@Composable
fun HighlightlyVideoDialog(
    video: HighlightVideo,
    url: String,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss
    ) {

        Card(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(280.dp)
                    .padding(6.dp),

            shape =
                RoundedCornerShape(16.dp),

            colors =
                CardDefaults.cardColors(
                    containerColor =
                        Color.Black
                )
        ) {

            Box(
                modifier =
                    Modifier.fillMaxSize()
            ) {

                AndroidView(
                    modifier =
                        Modifier.fillMaxSize(),

                    factory = { ctx ->

                        WebView(ctx).apply {

                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.mediaPlaybackRequiresUserGesture = false
                            settings.allowContentAccess = true
                            settings.allowFileAccess = true
                            settings.loadsImagesAutomatically = true

                            WebView.setWebContentsDebuggingEnabled(false)

                            webViewClient =
                                object : WebViewClient() {}

                            webChromeClient =
                                WebChromeClient()

                            loadUrl(url)
                        }
                    }
                )

                Box(
                    modifier =
                        Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp)
                            .clip(CircleShape)
                            .background(
                                Color(0xCC000000)
                            )
                            .clickable(
                                onClick = onDismiss
                            )
                            .padding(
                                horizontal = 10.dp,
                                vertical = 6.dp
                            )
                ) {

                    Text(
                        text = "✕",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}


// ================================================================
// MÉDIA HUB – legutóbb nézett + thumbnail kártyák
// ================================================================

data class RecentMediaItem(
    val id: String,
    val title: String,
    val subtitle: String,
    val url: String,
    val thumbUrl: String?,
    val timestamp: Long
)

private fun loadRecentMedia(
    prefs: android.content.SharedPreferences
): List<RecentMediaItem> {
    return try {
        val raw = prefs.getString("recent_media_json", null) ?: return emptyList()
        val arr = JSONArray(raw)
        buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                add(
                    RecentMediaItem(
                        id = o.optString("id"),
                        title = o.optString("title"),
                        subtitle = o.optString("subtitle"),
                        url = o.optString("url"),
                        thumbUrl = o.optString("thumbUrl").takeIf { it.isNotBlank() },
                        timestamp = o.optLong("timestamp")
                    )
                )
            }
        }
    } catch (_: Exception) {
        emptyList()
    }
}

private fun saveRecentMedia(
    prefs: android.content.SharedPreferences,
    items: List<RecentMediaItem>
) {
    val arr = JSONArray()
    items.forEach { item ->
        arr.put(
            JSONObject()
                .put("id", item.id)
                .put("title", item.title)
                .put("subtitle", item.subtitle)
                .put("url", item.url)
                .put("thumbUrl", item.thumbUrl ?: "")
                .put("timestamp", item.timestamp)
        )
    }
    prefs.edit().putString("recent_media_json", arr.toString()).apply()
}

@Composable
private fun MediaHubList(
    matches: List<MatchResponse>,
    recentItems: List<RecentMediaItem>,
    isDarkMode: Boolean,
    cardBgColor: Color,
    textColor: Color,
    subTextColor: Color,
    primaryGreen: Color,
    onOpenMatchMedia: (MatchResponse) -> Unit,
    onOpenRecent: (RecentMediaItem) -> Unit,
    onClearRecent: () -> Unit
) {
    val mediaMatches = remember(matches) {
        matches.sortedWith(
            compareByDescending<MatchResponse> {
                !it.highlightMatchId.isNullOrBlank()
            }.thenByDescending {
                (it.minute ?: 0) > 0 && it.status != "FT"
            }.thenBy {
                it.league.orEmpty()
            }
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Text(
                text = "🎬 Média központ",
                color = primaryGreen,
                fontSize = 16.sp,
                fontWeight = FontWeight.ExtraBold
            )
            Text(
                text = "Összefoglalók, gólok, nemzetközi források",
                color = subTextColor,
                fontSize = 11.sp
            )
        }

        if (recentItems.isNotEmpty()) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "🕐 Legutóbb nézett",
                        color = Color(0xFF40C4FF),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Törlés",
                        color = subTextColor,
                        fontSize = 11.sp,
                        modifier = Modifier.clickable { onClearRecent() }
                    )
                }
            }

            items(recentItems.take(8), key = { it.id }) { item ->
                RecentMediaCard(
                    item = item,
                    cardBgColor = cardBgColor,
                    textColor = textColor,
                    subTextColor = subTextColor,
                    onClick = { onOpenRecent(item) }
                )
            }
        }

        item {
            Text(
                text = "📺 Ajánlott meccsek",
                color = Color(0xFFFFD54F),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 6.dp)
            )
        }

        if (mediaMatches.isEmpty()) {
            item {
                Text(
                    text = "Most nincs megjeleníthető média-tartalom. Nézz vissza élő vagy befejezett meccseknél.",
                    color = subTextColor,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(vertical = 16.dp)
                )
            }
        } else {
            items(mediaMatches, key = { it.id }) { match ->
                MediaMatchCard(
                    match = match,
                    cardBgColor = cardBgColor,
                    textColor = textColor,
                    subTextColor = subTextColor,
                    primaryGreen = primaryGreen,
                    onClick = { onOpenMatchMedia(match) }
                )
            }
        }
    }
}

@Composable
private fun RecentMediaCard(
    item: RecentMediaItem,
    cardBgColor: Color,
    textColor: Color,
    subTextColor: Color,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(cardBgColor)
            .clickable(onClick = onClick)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF263238)),
            contentAlignment = Alignment.Center
        ) {
            if (!item.thumbUrl.isNullOrBlank()) {
                AsyncImage(
                    model = item.thumbUrl,
                    contentDescription = null,
                    modifier = Modifier.size(52.dp)
                )
            } else {
                Text("▶️", fontSize = 20.sp)
            }
        }
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.title,
                color = textColor,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = item.subtitle,
                color = subTextColor,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(text = "↗", color = Color(0xFF40C4FF), fontSize = 16.sp)
    }
}

@Composable
private fun MediaMatchCard(
    match: MatchResponse,
    cardBgColor: Color,
    textColor: Color,
    subTextColor: Color,
    primaryGreen: Color,
    onClick: () -> Unit
) {
    val isLive = isMatchLive(match.status, match.minute)
    val hasHighlight = !match.highlightMatchId.isNullOrBlank()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(cardBgColor)
            .clickable(onClick = onClick)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Thumbnail: hazai + vendég logó
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF1B2228))
        ) {
            AsyncImage(
                model = match.homeLogoUrl,
                contentDescription = null,
                modifier = Modifier
                    .size(30.dp)
                    .align(Alignment.TopStart)
                    .padding(4.dp)
            )
            AsyncImage(
                model = match.awayLogoUrl,
                contentDescription = null,
                modifier = Modifier
                    .size(30.dp)
                    .align(Alignment.BottomEnd)
                    .padding(4.dp)
            )
        }

        Spacer(modifier = Modifier.width(10.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "${match.homeTeam} vs ${match.awayTeam}",
                color = textColor,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = match.league ?: "",
                color = subTextColor,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isLive) {
                    Text(
                        text = "ÉLŐ ${match.minute}'",
                        color = primaryGreen,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                } else if (isMatchFinished(match.status)) {
                    Text(
                        text = "VÉGE",
                        color = subTextColor,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(
                    text = "${match.homeScore ?: 0} - ${match.awayScore ?: 0}",
                    color = textColor,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
                if (hasHighlight) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Highlightly",
                        color = Color(0xFFFFD54F),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF2979FF))
                .padding(horizontal = 10.dp, vertical = 8.dp)
        ) {
            Text(text = "🎥", fontSize = 14.sp)
        }
    }
}
