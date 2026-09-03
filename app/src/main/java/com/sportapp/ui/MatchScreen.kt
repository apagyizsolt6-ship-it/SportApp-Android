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
import androidx.compose.foundation.horizontalScroll
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
import com.sportapp.VideoPipActivity
import com.sportapp.fcm.FcmRegistrar
import com.sportapp.api.StandingTeam
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import java.text.Collator
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MatchScreen(viewModel: MatchViewModel = viewModel()) {
    val aiAnalysis by viewModel.aiAnalysis.collectAsState()
    val isLoadingAi by viewModel.isLoadingAi.collectAsState()
    var selectedMatchForAi by remember { mutableStateOf<MatchResponse?>(null) }
    var showDailyTips by remember { mutableStateOf(false) }
    var dailyTipsLoading by remember { mutableStateOf(false) }
    var dailyTipsError by remember { mutableStateOf<String?>(null) }
    var dailyTipsList by remember { mutableStateOf<List<Map<String, Any?>>>(emptyList()) }
    var dailyTipsDisclaimer by remember { mutableStateOf("") }
    var dailyTipsTab by remember { mutableIntStateOf(0) } // 0=ma, 1=tegnap
    var yesterdayTipsList by remember { mutableStateOf<List<Map<String, Any?>>>(emptyList()) }
    var yesterdaySummary by remember { mutableStateOf("") }


    // Meccs részlet (events/stats/lineups) – NEM cseréli az AI / videó / média funkciókat
    var selectedMatchForDetail by remember { mutableStateOf<MatchResponse?>(null) }

    val matches by viewModel.matches.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    var minutePulseMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(15_000L)
            minutePulseMs = System.currentTimeMillis()
        }
    }

    val loadError by viewModel.loadError.collectAsState()
    val fromCache by viewModel.fromCache.collectAsState()
    val contextForTheme = LocalContext.current
    var isDarkMode by remember {
        mutableStateOf(ThemePrefs.isDark(contextForTheme))
    }
    fun setDarkMode(dark: Boolean) {
        isDarkMode = dark
        ThemePrefs.setDark(contextForTheme, dark)
    }
    var selectedTab by remember { mutableIntStateOf(0) }
    var matchForTicket by remember { mutableStateOf<MatchResponse?>(null) }

    var ticketPrefillMarket by remember { mutableStateOf<String?>(null) }
    var ticketPrefillPick by remember { mutableStateOf<String?>(null) }
    var ticketPrefillOdds by remember { mutableStateOf<Double?>(null) }


    var searchQuery by remember { mutableStateOf("") }
    // Naptár: 0 = ma, -1 = tegnap, +1 = holnap...
    var selectedDayOffset by remember { mutableIntStateOf(0) }
    var showSeasonCalendar by remember { mutableStateOf(false) }
    var tipStreakText by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(selectedDayOffset) {
        viewModel.setDayOffset(selectedDayOffset)
    }

    val context = LocalContext.current
    var ticketMatchIds by remember { mutableStateOf(TicketPrefs.activeMatchIds(context)) }
    fun shareMatch(m: MatchResponse) {
        val status = when {
            m.status == "FT" -> "Vége"
            m.status in listOf("1H", "2H", "HT", "LIVE") -> "Élő ${m.minute ?: ""}'"
            else -> m.kickoffTime ?: m.status
        }
        val score = "${m.homeScore ?: "-"} : ${m.awayScore ?: "-"}"
        val text = buildString {
            append("⚽ ${m.homeTeam.orEmpty()} $score ${m.awayTeam.orEmpty()}\n")
            append("🏆 ${m.league ?: ""}\n")
            append("⏱ $status\n")
            if (m.oddsHome != null) {
                append("📊 1X2: ${m.oddsHome} / ${m.oddsDraw ?: "-"} / ${m.oddsAway ?: "-"}\n")
            }
            append("📱 SportApp")
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        context.startActivity(Intent.createChooser(intent, "Meccs megosztása"))
    }


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

    var favoriteMatchIds by remember {
        mutableStateOf(
            favoritePrefs.getStringSet("favorite_matches", emptySet())?.toSet() ?: emptySet()
        )
    }
    LaunchedEffect(favoriteMatchIds) {
        favoritePrefs.edit()
            .putStringSet("favorite_matches", favoriteMatchIds)
            .apply()
    }

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
    var followedTeamNames by remember {
        mutableStateOf(TeamFollowPrefs.teams(context))
    }
    // Követett csapatok meccseire auto FCM
    LaunchedEffect(matches, followedTeamNames) {
        if (followedTeamNames.isEmpty() || matches.isEmpty()) return@LaunchedEffect
        matches.forEach { m ->
            val hit = followedTeamNames.any { team ->
                m.homeTeam.equals(team, true) || m.awayTeam.equals(team, true) ||
                    m.homeTeam.contains(team, true) || m.awayTeam.contains(team, true) ||
                    team.contains(m.homeTeam, true) || team.contains(m.awayTeam, true)
            }
            if (hit && !FcmRegistrar.followedMatches(context).contains(m.id)) {
                FcmRegistrar.setFollowing(context, m.id, true)
            }
        }
        followedMatchIds = FcmRegistrar.followedMatches(context)
    }


    fun toggleFavorite(matchId: String) {
        val nowFav = !favoriteMatchIds.contains(matchId)
        favoriteMatchIds = if (nowFav) favoriteMatchIds + matchId else favoriteMatchIds - matchId
        // Kedvenc → automatikus push-követés (és fordítva)
        FcmRegistrar.setFollowing(context, matchId, nowFav)
        followedMatchIds = FcmRegistrar.followedMatches(context)
        if (nowFav) {
            android.widget.Toast.makeText(
                context,
                "Kedvenc + értesítések bekapcsolva",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }

    var showNotifHistory by remember { mutableStateOf(false) }
    var showQuietHours by remember { mutableStateOf(false) }
    var prevScores by remember { mutableStateOf(mapOf<String, Pair<Int?, Int?>>()) }
    var flashMatchIds by remember { mutableStateOf(setOf<String>()) }
    // Ugyanarra a gólra (ugyanaz az állás) ne flash/push újra
    var lastGoalSig by remember { mutableStateOf(mapOf<String, String>()) }
    var ticketLegPrevStatus by remember { mutableStateOf(mapOf<String, String>()) }

    LaunchedEffect(matches) {
        val nextFlash = mutableSetOf<String>()
        val nextPrev = prevScores.toMutableMap()
        val goalEvents = mutableListOf<MatchResponse>()
        matches.forEach { m ->
            val old = prevScores[m.id]
            if (old != null) {
                val nh = m.homeScore
                val na = m.awayScore
                val goalUp = (nh != null && old.first != null && nh > old.first!!) ||
                    (na != null && old.second != null && na > old.second!!)
                if (goalUp) {
                    val sig = "${nh ?: 0}-${na ?: 0}"
                    // Ha már kezelve volt ez az állás, kihagyjuk (dupla poll / FCM echo)
                    if (lastGoalSig[m.id] != sig) {
                        nextFlash.add(m.id)
                        goalEvents.add(m)
                    }
                }
            }
            nextPrev[m.id] = m.homeScore to m.awayScore
        }
        prevScores = nextPrev
        if (nextFlash.isNotEmpty()) {
            val sigMap = lastGoalSig.toMutableMap()
            goalEvents.forEach { m ->
                sigMap[m.id] = "${m.homeScore ?: 0}-${m.awayScore ?: 0}"
            }
            lastGoalSig = sigMap
            flashMatchIds = flashMatchIds + nextFlash
            HapticPrefs.goalVibrate(context)
            SoundPrefs.playGoalBeep(context)
            goalEvents.forEach { m ->
                if (!followedMatchIds.contains(m.id) && !favoriteMatchIds.contains(m.id)) return@forEach
                try {
                    val nm = context.getSystemService(android.content.Context.NOTIFICATION_SERVICE)
                        as android.app.NotificationManager
                    if (android.os.Build.VERSION.SDK_INT >= 26) {
                        nm.createNotificationChannel(
                            android.app.NotificationChannel(
                                "sportapp_goals",
                                "Gólok",
                                android.app.NotificationManager.IMPORTANCE_HIGH
                            )
                        )
                    }
                    val score = "${m.homeScore ?: 0}–${m.awayScore ?: 0}"
                    val n = androidx.core.app.NotificationCompat.Builder(context, "sportapp_goals")
                        .setSmallIcon(android.R.drawable.ic_menu_compass)
                        .setContentTitle("⚽ GÓL")
                        .setContentText("${m.homeTeam.orEmpty()} $score ${m.awayTeam.orEmpty()}")
                        .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
                        .setAutoCancel(true)
                        .setOnlyAlertOnce(true)
                        .build()
                    // Ugyanaz a meccs+állás → ugyanaz az ID (nem új push)
                    val nid = ("goal-${m.id}-$score").hashCode()
                    nm.notify(nid, n)
                } catch (_: Exception) {
                }
            }
            kotlinx.coroutines.delay(25_000L)
            flashMatchIds = flashMatchIds - nextFlash
        }
    }
    // Szelvény sor státusz változás → helyi push
    LaunchedEffect(matches) {
        val active = TicketPrefs.getActive(context) ?: return@LaunchedEffect
        val map = matches.associateBy { it.id }
        val nextPrev = ticketLegPrevStatus.toMutableMap()
        active.legs.forEach { leg ->
            val stt = evaluateTicketLeg(leg, map[leg.matchId])
            val key = leg.id
            val prev = ticketLegPrevStatus[key]
            val now = stt.name
            if (prev != null && prev != now) {
                if (stt == LegStatus.LOST || stt == LegStatus.WON) {
                    try {
                        val nm = context.getSystemService(android.content.Context.NOTIFICATION_SERVICE)
                            as android.app.NotificationManager
                        if (android.os.Build.VERSION.SDK_INT >= 26) {
                            nm.createNotificationChannel(
                                android.app.NotificationChannel(
                                    "sportapp_ticket",
                                    "Szelvény",
                                    android.app.NotificationManager.IMPORTANCE_DEFAULT
                                )
                            )
                        }
                        val title = if (stt == LegStatus.WON) "✅ Szelvény: BEJÖTT" else "❌ Szelvény: ELBUKOTT"
                        val body = "${leg.homeTeam} – ${leg.awayTeam}: ${leg.market} ${leg.pick}"
                        val n = androidx.core.app.NotificationCompat.Builder(context, "sportapp_ticket")
                            .setSmallIcon(android.R.drawable.ic_menu_compass)
                            .setContentTitle(title)
                            .setContentText(body)
                            .setAutoCancel(true)
                            .build()
                        nm.notify(("ticket-$key-$now").hashCode(), n)
                    } catch (_: Exception) {
                    }
                }
            }
            nextPrev[key] = now
        }
        ticketLegPrevStatus = nextPrev
    }
    var onlyPinnedLeagues by remember { mutableStateOf(false) }
    var onlyFavorites by remember { mutableStateOf(false) }
    var onlyTopLeagues by remember { mutableStateOf(false) }
    var onlyNext60 by remember { mutableStateOf(false) }

    var showNotifTypes by remember { mutableStateOf(false) }
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
            if (onlyFavorites) {
                val teamHit = followedTeamNames.any { team ->
                    match.homeTeam.equals(team, true) || match.awayTeam.equals(team, true)
                }
                if (!isMatchFav && !isLeagueFav && !teamHit && !followedMatchIds.contains(match.id))
                    return@filter false
            }
            val matchesSearch = matchesSmartSearch(match, searchQuery)

            if (onlyTopLeagues) {
                if (!isTopFiveLeague(leagueName, match.countryCode ?: match.country)) return@filter false
            }
            if (onlyNext60) {
                val live = isMatchLive(match.status, match.minute)
                if (!live) {
                    val ko = matchKickoffMillis(match)
                    val nowMs = System.currentTimeMillis()
                    if (ko == null || ko < nowMs || ko > nowMs + 60L * 60_000L) return@filter false
                }
            }
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
            .distinctBy { it.id }
            .sortedBy { matchKickoffMillis(it) ?: Long.MAX_VALUE }
            .take(12)
    }
    val worthWatchMatches = remember(matches, favoriteLeagueNames) {
        matches.filter { !isMatchFinished(it.status) }
            .distinctBy { it.id }
            .map { it to worthWatchScore(it, favoriteLeagueNames) }
            .filter { it.second >= 30 }
            .sortedByDescending { it.second }
            .take(5)
            .map { it.first }
    }
    val derbyMatches = remember(filteredMatches) {
        filteredMatches.filter { DerbyPrefs.isDerby(it.homeTeam, it.awayTeam) }
            .distinctBy { it.id }
            .take(8)
    }
    val tonightTips = remember(filteredMatches) {
        filteredMatches.filter { m ->
            val kt = m.kickoffTime ?: return@filter false
            val hour = kt.take(2).toIntOrNull() ?: return@filter false
            hour in 18..23 && !isMatchFinished(m.status)
        }.distinctBy { it.id }
            .sortedByDescending { worthWatchScore(it, favoriteLeagueNames) }.take(3)
    }
    val spotlightMatches = remember(filteredMatches, favoriteLeagueNames) {
        filteredMatches
            .filter {
                favoriteLeagueNames.contains(it.league ?: "") ||
                    topFiveRank(it.league, it.countryCode) != null
            }
            .distinctBy { it.id }
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
                            setDarkMode(!isDarkMode)
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

                    Spacer(modifier = Modifier.width(8.dp))
                    IconButton(
                        onClick = {
                            showDailyTips = true
                            dailyTipsLoading = true
                            dailyTipsError = null
                            tipStreakText = null
                            coroutineScope.launch {
                                try {
                                    val streak = RetrofitInstance.api.getTipsStreak(days = 7)
                                    tipStreakText = streak["summary"]?.toString()
                                        ?: streak["hit_rate_pct"]?.let { "Heti találat: $it%" }
                                } catch (_: Exception) {
                                    tipStreakText = null
                                }
                                try {
                                    val r = RetrofitInstance.api.getDailyTips(
                                        date = selectedDateIso,
                                        offset = selectedDayOffset,
                                        refresh = 1
                                    )
                                    dailyTipsDisclaimer = r["disclaimer"]?.toString().orEmpty()
                                    val raw = r["tips"]
                                    val list = mutableListOf<Map<String, Any?>>()
                                    if (raw is List<*>) {
                                        raw.forEach { item ->
                                            if (item is Map<*, *>) {
                                                val map = item.entries.associate { (k, v) -> k.toString() to v }
                                                val blob = listOf(
                                                    map["match"], map["market"], map["pick"], map["reason"], map["raw"]
                                                ).joinToString(" ").lowercase()
                                                val junk = listOf(
                                                    "nem minősül fogadási",
                                                    "nem fogadási tanács",
                                                    "kizárólag tájékoztató",
                                                    "ez az elemzés nem"
                                                ).any { it in blob }
                                                val hasContent = !map["match"]?.toString().isNullOrBlank() ||
                                                    !map["pick"]?.toString().isNullOrBlank()
                                                if (!junk && hasContent) list.add(map)
                                            }
                                        }
                                    }
                                    dailyTipsList = list.take(3)
                                    if (list.isEmpty()) {
                                        dailyTipsError = r["message"]?.toString()
                                            ?: "Ma nincs elég adat a 3 tipphez."
                                    }
                                } catch (e: Exception) {
                                    dailyTipsError = e.message ?: "Hiba a tippek betöltésekor"
                                    dailyTipsList = emptyList()
                                } finally {
                                    dailyTipsLoading = false
                                }
                            }
                        },
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(leagueBgColor)
                            .size(36.dp)
                    ) {
                        Text(text = "💡", fontSize = 16.sp)
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

            // Napválasztó sáv + naptár
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "📅",
                    fontSize = 16.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(cardBgColor)
                        .clickable { showSeasonCalendar = true }
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                )
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

            // Eszközsáv – görgethető, kevesebb zaj
            var showMoreTools by remember { mutableStateOf(false) }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilterChip(
                    selected = onlyPinnedLeagues,
                    onClick = { onlyPinnedLeagues = !onlyPinnedLeagues },
                    label = { Text("Kiemelt", fontSize = 11.sp) }
                )
                FilterChip(
                    selected = onlyFavorites,
                    onClick = { onlyFavorites = !onlyFavorites },
                    label = { Text("Kedvencek", fontSize = 11.sp) }
                )
                FilterChip(
                    selected = onlyTopLeagues,
                    onClick = { onlyTopLeagues = !onlyTopLeagues },
                    label = { Text("TOP", fontSize = 11.sp) }
                )
                FilterChip(
                    selected = onlyNext60,
                    onClick = { onlyNext60 = !onlyNext60 },
                    label = { Text("60 perc", fontSize = 11.sp) }
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
                                MatchSortMode.LEAGUE -> "Liga"
                                MatchSortMode.LIVE_FIRST -> "Élő"
                                MatchSortMode.TIME -> "Idő"
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
                Box {
                    FilterChip(
                        selected = false,
                        onClick = { showMoreTools = true },
                        label = { Text("⋯", fontSize = 14.sp) }
                    )
                    DropdownMenu(
                        expanded = showMoreTools,
                        onDismissRequest = { showMoreTools = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("🔔 Értesítés előzmény") },
                            onClick = {
                                showMoreTools = false
                                showNotifHistory = true
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("🌙 Csendes órák") },
                            onClick = {
                                showMoreTools = false
                                showQuietHours = true
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("⚙️ Push beállítások") },
                            onClick = {
                                showMoreTools = false
                                showNotifTypes = true
                            }
                        )
                    }
                }
            }

            
                if (fromCache && matches.isNotEmpty()) {
                    Text(
                        text = "📦 Offline cache – frissítés folyamatban…",
                        color = subTextColor,
                        fontSize = 11.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp)
                    )
                }
                if (loadError != null && matches.isEmpty() && !isLoading) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("Nem sikerült betölteni", color = textColor, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Text(loadError ?: "", color = subTextColor, fontSize = 12.sp)
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(onClick = { viewModel.retry() }) { Text("Újra betöltés") }
                }
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
                Tab(
                    selected = selectedTab == 5,
                    onClick = { selectedTab = 5
                        ticketMatchIds = TicketPrefs.activeMatchIds(context) }
                ) {
                    Text(
                        "🎫 SZELVÉNY",
                        modifier = Modifier.padding(vertical = 10.dp),
                        color = if (selectedTab == 5) primaryGreen else subTextColor,
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

            } else if (selectedTab == 5) {
                TicketAssistantPanel(
                    matches = matches,
                    isDarkMode = isDarkMode,
                    primaryGreen = primaryGreen,
                    cardBg = cardBgColor,
                    textColor = textColor,
                    subTextColor = subTextColor,
                    onOpenMatch = { selectedMatchForDetail = it },
                    onRequestAddFromList = { selectedTab = 0 }
                )
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
                        items(worthWatchMatches, key = { "ww-${it.id}-${it.homeTeam}-${it.awayTeam}" }) { match ->
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
                                minutePulseMs = minutePulseMs,
                                onFavoriteToggle = { toggleFavorite(match.id) },
                                onVideoClick = { m ->
                                    selectedMatchForMedia = m
                                    showHighlightPicker = true
                                },
                                onAiClick = { m ->
                                    selectedMatchForAi = m
                                    viewModel.fetchAiAnalysis(m.id)
                                },
                                onMatchClick = { selectedMatchForDetail = it },
                                isOnTicket = ticketMatchIds.contains(match.id),
                                    onTicketClick = { matchForTicket = match },
                                onReminderClick = { },
                                onShareClick = { shareMatch(match) }
                            )
                        }
                    }
if (derbyMatches.isNotEmpty() && selectedTab == 0 && searchQuery.isEmpty()) {
                        item {
                            Text(
                                "🔥 Derby mód",
                                color = Color(0xFFFF6D00),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                            )
                        }
                        items(derbyMatches, key = { "derby-${it.id}-${it.homeTeam}" }) { match ->
                            PremiumMatchRow(
                                match = match,
                                isFavorite = favoriteMatchIds.contains(match.id),
                                cardBgColor = cardBgColor,
                                textColor = textColor,
                                subTextColor = subTextColor,
                                primaryGreen = primaryGreen,
                                compact = compactMode,
                                scoreFlash = flashMatchIds.contains(match.id),
                                minutePulseMs = minutePulseMs,
                                onFavoriteToggle = { toggleFavorite(match.id) },
                                onVideoClick = { },
                                onAiClick = { m ->
                                    selectedMatchForAi = m
                                    viewModel.fetchAiAnalysis(m.id)
                                },
                                onMatchClick = { selectedMatchForDetail = it },
                                isOnTicket = ticketMatchIds.contains(match.id),
                                    onTicketClick = { matchForTicket = match },
                                onReminderClick = { },
                                onShareClick = { shareMatch(match) }
                            )
                        }
                    }
                    if (tonightTips.isNotEmpty() && selectedTab == 0 && searchQuery.isEmpty()) {
                        item {
                            Text(
                                "🌙 Ma este 3 tipp",
                                color = primaryGreen,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                            )
                        }
                        items(tonightTips, key = { "tip-${it.id}-${it.kickoffTime}" }) { match ->
                            PremiumMatchRow(
                                match = match,
                                isFavorite = favoriteMatchIds.contains(match.id),
                                cardBgColor = cardBgColor,
                                textColor = textColor,
                                subTextColor = subTextColor,
                                primaryGreen = primaryGreen,
                                compact = compactMode,
                                scoreFlash = flashMatchIds.contains(match.id),
                                minutePulseMs = minutePulseMs,
                                onFavoriteToggle = { toggleFavorite(match.id) },
                                onVideoClick = { },
                                onAiClick = { m ->
                                    selectedMatchForAi = m
                                    viewModel.fetchAiAnalysis(m.id)
                                },
                                onMatchClick = { selectedMatchForDetail = it },
                                isOnTicket = ticketMatchIds.contains(match.id),
                                    onTicketClick = { matchForTicket = match },
                                onReminderClick = { },
                                onShareClick = { shareMatch(match) }
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
                        items(spotlightMatches, key = { "sp-${it.id}-${it.status}" }) { match ->
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
                                minutePulseMs = minutePulseMs,
                                onFavoriteToggle = { toggleFavorite(match.id) },
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
                        items(soonMatches.take(6), key = { "soon-${it.id}-${it.kickoffTime}" }) { match ->
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
                                minutePulseMs = minutePulseMs,
                                onFavoriteToggle = { toggleFavorite(match.id) },
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

                            items(leagueMatches.distinctBy { it.id }, key = { "lg-${leagueName}-${it.id}" }) { match ->

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
                                    compact = compactMode,
                                    scoreFlash = flashMatchIds.contains(match.id),
                                    minutePulseMs = minutePulseMs,

onFavoriteToggle = { toggleFavorite(match.id) },

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
                                    isOnTicket = ticketMatchIds.contains(match.id),
                                    onTicketClick = { matchForTicket = match },
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
    if (showNotifTypes) {
        AlertDialog(
            onDismissRequest = { showNotifTypes = false },
            title = { Text("Értesítés típusok") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Melyik eseményekről kérsz push-t?", fontSize = 13.sp)
                    var hapticOn by remember {
                        mutableStateOf(HapticPrefs.enabled(context))
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Gól vibráció", fontSize = 14.sp)
                        Switch(
                            checked = hapticOn,
                            onCheckedChange = {
                                hapticOn = it
                                HapticPrefs.setEnabled(context, it)
                            }
                        )
                    }
                    var soundOn by remember { mutableStateOf(SoundPrefs.goalSoundEnabled(context)) }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Gól hang", fontSize = 14.sp)
                        Switch(
                            checked = soundOn,
                            onCheckedChange = {
                                soundOn = it
                                SoundPrefs.setGoalSoundEnabled(context, it)
                            }
                        )
                    }
                    var quietFav by remember {
                        mutableStateOf(NotifPrefs.allowFavoriteDuringQuiet(context))
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Kedvenc gól csendben is", fontSize = 14.sp)
                        Switch(
                            checked = quietFav,
                            onCheckedChange = {
                                quietFav = it
                                NotifPrefs.setAllowFavoriteDuringQuiet(context, it)
                            }
                        )
                    }
                    var glassA by remember { mutableStateOf(GlassPrefs.alpha(context)) }
                    Text("Üveg átlátszóság: ${(glassA * 100).toInt()}%", fontSize = 13.sp)
                    Slider(
                        value = glassA,
                        onValueChange = {
                            glassA = it
                            GlassPrefs.setAlpha(context, it)
                        },
                        valueRange = 0.4f..1f
                    )
                    NotifPrefs.allTypeKeys().forEach { (key, label) ->
                        var en by remember(key) {
                            mutableStateOf(NotifPrefs.isTypeEnabled(context, key))
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(label, fontSize = 14.sp)
                            Switch(
                                checked = en,
                                onCheckedChange = {
                                    en = it
                                    NotifPrefs.setTypeEnabled(context, key, it)
                                }
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = {
                        FcmRegistrar.requestTestPush(context)
                        android.widget.Toast.makeText(
                            context,
                            "Teszt értesítés küldve…",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }) { Text("🔔 Teszt push") }
                    TextButton(onClick = { showNotifTypes = false }) { Text("Kész") }
                }
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
                    // PiP-képes lejátszó, ha van URL
                    try {
                        val pip = Intent(context, VideoPipActivity::class.java).apply {
                            putExtra(VideoPipActivity.EXTRA_URL, url)
                        }
                        context.startActivity(pip)
                    } catch (_: Exception) {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    }
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
            onFavoriteToggle = { toggleFavorite(m.id) },
            onDismiss = { selectedMatchForDetail = null },
            onVideoClick = { video ->
                selectedVideo = video
            },
            onAddOddsToTicket = { market, pick, odds ->
                selectedMatchForDetail?.let { m ->
                    // Map market title -> internal key
                    val mk = when {
                        "1x2" in market.lowercase() || "végeredmény" in market.lowercase() || "vereger" in market.lowercase() -> "1X2"
                        "btts" in market.lowercase() || "mindkét" in market.lowercase() || "both" in market.lowercase() -> "BTTS"
                        "double" in market.lowercase() || "kettős" in market.lowercase() -> {
                            if ("1x" in pick.lowercase() || "1x" in market.lowercase()) "DC_1X" else "DC_X2"
                        }
                        "over" in market.lowercase() || "under" in market.lowercase() || "gól" in market.lowercase() -> "OU25"
                        "handicap" in market.lowercase() || "hendik" in market.lowercase() -> "AH"
                        else -> "1X2"
                    }
                    ticketPrefillMarket = mk
                    ticketPrefillPick = pick
                    ticketPrefillOdds = odds
                    matchForTicket = m
                }
            }
        )
    }

    // ============================================================
    // AI ELEMZÉS DIALOG
    // ============================================================



    if (showSeasonCalendar) {
        val today = remember { try { LocalDate.now() } catch (_: Exception) { null } }
        var monthCursor by remember {
            mutableStateOf(today ?: try { LocalDate.of(2026, 9, 1) } catch (_: Exception) { null })
        }
        AlertDialog(
            onDismissRequest = { showSeasonCalendar = false },
            title = { Text("📅 Szezon naptár", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val mc = monthCursor
                    if (mc != null) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TextButton(onClick = {
                                monthCursor = mc.minusMonths(1)
                            }) { Text("◀") }
                            Text(
                                try {
                                    mc.format(DateTimeFormatter.ofPattern("yyyy. MMMM", java.util.Locale("hu"))
                                    )
                                } catch (_: Exception) {
                                    "${mc.year}-${mc.monthValue}"
                                },
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 14.sp
                            )
                            TextButton(onClick = {
                                monthCursor = mc.plusMonths(1)
                            }) { Text("▶") }
                        }
                        // Hét napjai
                        Row(modifier = Modifier.fillMaxWidth()) {
                            listOf("H", "K", "Sz", "Cs", "P", "Szo", "V").forEach { d ->
                                Text(
                                    d,
                                    modifier = Modifier.weight(1f),
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                    fontSize = 11.sp,
                                    color = subTextColor
                                )
                            }
                        }
                        val first = mc.withDayOfMonth(1)
                        val startOffset = (first.dayOfWeek.value - 1) // Monday=0
                        val daysInMonth = mc.lengthOfMonth()
                        val cells = startOffset + daysInMonth
                        val rows = (cells + 6) / 7
                        var dayNum = 1
                        repeat(rows) { row ->
                            Row(modifier = Modifier.fillMaxWidth()) {
                                repeat(7) { col ->
                                    val cellIndex = row * 7 + col
                                    if (cellIndex < startOffset || dayNum > daysInMonth) {
                                        Spacer(modifier = Modifier.weight(1f))
                                    } else {
                                        val d = dayNum
                                        dayNum++
                                        val date = mc.withDayOfMonth(d)
                                        val offset = try {
                                            java.time.temporal.ChronoUnit.DAYS.between(LocalDate.now(), date).toInt()
                                        } catch (_: Exception) {
                                            0
                                        }
                                        val selected = offset == selectedDayOffset
                                        val isToday = offset == 0
                                        Text(
                                            text = d.toString(),
                                            modifier = Modifier
                                                .weight(1f)
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(
                                                    when {
                                                        selected -> primaryGreen.copy(alpha = 0.35f)
                                                        isToday -> cardBgColor
                                                        else -> Color.Transparent
                                                    }
                                                )
                                                .clickable {
                                                    selectedDayOffset = offset.coerceIn(-30, 60)
                                                    showSeasonCalendar = false
                                                }
                                                .padding(vertical = 8.dp),
                                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                            color = if (selected) primaryGreen else textColor,
                                            fontWeight = if (isToday || selected) FontWeight.Bold else FontWeight.Normal,
                                            fontSize = 13.sp
                                        )
                                    }
                                }
                            }
                        }
                        Text(
                            "Koppints egy napra a meccsek betöltéséhez (−30…+60 nap).",
                            fontSize = 11.sp,
                            color = subTextColor
                        )
                    } else {
                        Text("Naptár nem elérhető ezen a készüléken.", color = subTextColor)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSeasonCalendar = false }) { Text("Bezár") }
            }
        )
    }

    matchForTicket?.let { m ->
        AddToTicketDialog(
            match = m,
            onDismiss = {
                matchForTicket = null
                ticketPrefillMarket = null
                ticketPrefillPick = null
                ticketPrefillOdds = null
            },
            onAdded = {
                android.widget.Toast.makeText(context, "Mentve az aktív szelvényre", android.widget.Toast.LENGTH_SHORT).show()
                ticketMatchIds = TicketPrefs.activeMatchIds(context)
            },
            prefillMarket = ticketPrefillMarket,
            prefillPick = ticketPrefillPick,
            prefillOdds = ticketPrefillOdds
        )
    }

    if (showDailyTips) {
        AlertDialog(
            onDismissRequest = { showDailyTips = false },
            title = {
                Text("💡 Napi 3 AI tipp", fontWeight = FontWeight.Bold)
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 460.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        dailyTipsDisclaimer.ifBlank {
                            "Tájékoztató jellegű – nem fogadási tanács."
                        },
                        fontSize = 11.sp,
                        color = subTextColor
                    )
                    tipStreakText?.let { streak ->
                        Text(
                            "📊 $streak",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = primaryGreen
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = dailyTipsTab == 0,
                            onClick = { dailyTipsTab = 0 },
                            label = { Text("Ma", fontSize = 12.sp) }
                        )
                        FilterChip(
                            selected = dailyTipsTab == 1,
                            onClick = {
                                dailyTipsTab = 1
                                if (yesterdayTipsList.isEmpty()) {
                                    dailyTipsLoading = true
                                    coroutineScope.launch {
                                        try {
                                            val r = RetrofitInstance.api.getTipsResults(date = null)
                                            yesterdaySummary = buildString {
                                                val h = (r["hits"] as? Number)?.toInt() ?: 0
                                                val m = (r["misses"] as? Number)?.toInt() ?: 0
                                                append("Bejött: $h · Nem: $m")
                                            }
                                            val raw = r["tips"]
                                            val list = mutableListOf<Map<String, Any?>>()
                                            if (raw is List<*>) {
                                                raw.forEach { item ->
                                                    if (item is Map<*, *>) {
                                                        list.add(item.entries.associate { (k, v) -> k.toString() to v })
                                                    }
                                                }
                                            }
                                            yesterdayTipsList = list
                                        } catch (e: Exception) {
                                            dailyTipsError = e.message
                                        } finally {
                                            dailyTipsLoading = false
                                        }
                                    }
                                }
                            },
                            label = { Text("Tegnap", fontSize = 12.sp) }
                        )
                    }
                    when {
                        dailyTipsLoading -> {
                            CircularProgressIndicator(
                                modifier = Modifier
                                    .size(28.dp)
                                    .align(Alignment.CenterHorizontally),
                                strokeWidth = 2.dp,
                                color = primaryGreen
                            )
                            Text("Betöltés…", fontSize = 12.sp, color = subTextColor)
                        }
                        dailyTipsTab == 0 && dailyTipsError != null && dailyTipsList.isEmpty() -> {
                            Text(dailyTipsError ?: "", fontSize = 13.sp, color = textColor)
                        }
                        dailyTipsTab == 0 -> {
                            dailyTipsList.forEachIndexed { idx, tip ->
                                val match = tip["match"]?.toString().orEmpty()
                                val market = tip["market"]?.toString().orEmpty()
                                val pick = tip["pick"]?.toString().orEmpty()
                                val reason = tip["reason"]?.toString().orEmpty()
                                val strength = tip["strength"]?.toString().orEmpty()
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(cardBgColor)
                                        .padding(10.dp)
                                ) {
                                    Text(
                                        "${idx + 1}. ${market.ifBlank { "Tipp" }}",
                                        color = primaryGreen,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    if (match.isNotBlank()) {
                                        Text(match, color = textColor, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                    }
                                    if (pick.isNotBlank()) {
                                        Text("→ $pick", color = textColor, fontSize = 13.sp)
                                    }
                                    if (reason.isNotBlank()) {
                                        Text(reason, color = subTextColor, fontSize = 11.sp)
                                    }
                                    if (strength.isNotBlank()) {
                                        Text("Erő: $strength", color = subTextColor, fontSize = 11.sp)
                                    }
                                }
                            }
                        }
                        else -> {
                            if (yesterdaySummary.isNotBlank()) {
                                Text(yesterdaySummary, color = textColor, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            }
                            if (yesterdayTipsList.isEmpty()) {
                                Text("Nincs tegnapi tipp / eredmény.", color = subTextColor, fontSize = 12.sp)
                            }
                            yesterdayTipsList.forEachIndexed { idx, tip ->
                                val result = tip["result"]?.toString().orEmpty()
                                val badgeColor = when (result) {
                                    "hit" -> Color(0xFF00C853)
                                    "miss" -> Color(0xFFE53935)
                                    "pending" -> Color(0xFFFFB300)
                                    else -> subTextColor
                                }
                                val badge = when (result) {
                                    "hit" -> "BEJÖTT"
                                    "miss" -> "NEM JÖTT"
                                    "pending" -> "VÁR"
                                    else -> "?"
                                }
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(cardBgColor)
                                        .border(1.dp, badgeColor.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                                        .padding(10.dp)
                                ) {
                                    Text(
                                        "${idx + 1}. ${tip["market"] ?: "Tipp"} · $badge",
                                        color = badgeColor,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(tip["match"]?.toString().orEmpty(), color = textColor, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                    Text("→ ${tip["pick"] ?: ""}", color = textColor, fontSize = 13.sp)
                                    Text(tip["result_detail"]?.toString().orEmpty(), color = subTextColor, fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Row {
                    TextButton(onClick = {
                        val lines = if (dailyTipsTab == 0) dailyTipsList else yesterdayTipsList
                        val body = buildString {
                            append("⚽ SportApp – Napi 3 tipp\n")
                            lines.forEachIndexed { i, tip ->
                                append("${i + 1}. ${tip["match"] ?: ""}\n")
                                append("   ${tip["market"] ?: ""}: ${tip["pick"] ?: ""}\n")
                                tip["result"]?.let {
                                    append("   Eredmény: $it ${tip["result_detail"] ?: ""}\n")
                                }
                            }
                            append("\nTájékoztató jellegű – nem fogadási tanács.")
                        }
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, body)
                        }
                        context.startActivity(Intent.createChooser(intent, "Tippek megosztása"))
                    }) { Text("Megosztás") }
                    TextButton(onClick = { showDailyTips = false }) { Text("Bezár") }
                }
            }
        )
    }

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
                        lineHeight = 20.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 120.dp, max = 520.dp)
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
