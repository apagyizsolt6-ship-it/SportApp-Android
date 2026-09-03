package com.sportapp.ui

import com.sportapp.models.AiAnalysisResponse
import com.sportapp.models.H2hResponse
import com.sportapp.models.FormResponse
import com.sportapp.models.H2hItem
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.sportapp.api.RetrofitInstance
import com.sportapp.fcm.FcmRegistrar
import android.net.Uri
import com.sportapp.api.StandingTeam
import com.sportapp.models.HighlightVideo
import com.sportapp.models.LineupPlayer
import com.sportapp.models.LineupsResponse
import com.sportapp.models.MatchEvent
import com.sportapp.models.MatchResponse
import com.sportapp.models.StatItem
import com.sportapp.models.StatisticsResponse
import coil.compose.SubcomposeAsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MatchDetailDialog(
    match: MatchResponse,
    isDarkMode: Boolean,
    isFavorite: Boolean,
    onFavoriteToggle: () -> Unit,
    onDismiss: () -> Unit,
    onVideoClick: (HighlightVideo) -> Unit,
    onAddOddsToTicket: (market: String, pick: String, odds: Double?) -> Unit = { _, _, _ -> }
) {
    // Kék üveg paletta – egyezik a MatchScreen-nel
    val bg = if (isDarkMode) Color(0xFF0B1426) else Color(0xFFE8F1FF)
    val card = if (isDarkMode) Color(0xCC152238) else Color(0xB3FFFFFF)
    val text = if (isDarkMode) Color(0xFFF0F6FF) else Color(0xFF0D1B2A)
    val sub = if (isDarkMode) Color(0xFF9BB0C9) else Color(0xFF5A6F8A)
    val green = Color(0xFF00E5A8)
    val accent = Color(0xFF4DA3FF)
    val glassBorder = if (isDarkMode) Color(0x33A0C4FF) else Color(0x55FFFFFF)

    var selectedTab by remember { mutableIntStateOf(0) }
    val ctx = LocalContext.current
    var isFollowing by remember {
        mutableStateOf(FcmRegistrar.isFollowing(ctx, match.id))
    }
    var selectedPlayer by remember { mutableStateOf<com.sportapp.models.LineupPlayer?>(null) }
    var selectedPlayerTeam by remember { mutableStateOf("") }
    var teamDialogName by remember { mutableStateOf<String?>(null) }
    var previousTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Összefoglaló", "Események", "Statisztika", "Összeállítás", "Videók", "Tabella")

    var detail by remember { mutableStateOf(match) }
    var events by remember { mutableStateOf(match.events.orEmpty()) }
    var stats by remember { mutableStateOf<List<StatItem>>(emptyList()) }
    var lineups by remember { mutableStateOf<LineupsResponse?>(null) }
    var videos by remember { mutableStateOf<List<HighlightVideo>>(emptyList()) }
    var oddsHomeUi by remember { mutableStateOf(match.oddsHome) }
    var oddsDrawUi by remember { mutableStateOf(match.oddsDraw) }
    var oddsAwayUi by remember { mutableStateOf(match.oddsAway) }
    var oddsSource by remember { mutableStateOf<String?>(null) }
    var oddsMarkets by remember { mutableStateOf<List<Map<String, Any?>>>(emptyList()) }
    var standings by remember { mutableStateOf<List<StandingTeam>>(emptyList()) }
    var loadingTab by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    val scope = rememberCoroutineScope()
    val hlId = (detail.highlightMatchId ?: match.highlightMatchId)?.trim().orEmpty()
    var h2h by remember { mutableStateOf<H2hResponse?>(null) }
    var form by remember { mutableStateOf<FormResponse?>(null) }
    var aiText by remember { mutableStateOf<String?>(null) }
    var showAi by remember { mutableStateOf(false) }
    val context = LocalContext.current

    // Élő frissítés: score + events 20 mp-enként, amíg a részlet nyitva van.
    // A lista ViewModel TTL-jével összhangban (backend cache 20 mp).
    var isRefreshing by remember { mutableStateOf(false) }
    var lastRefreshedAt by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(match.id) {
        // Első betöltés azonnal, utána 20 mp ciklus
        while (true) {
            try {
                isRefreshing = true
                val d = RetrofitInstance.api.getMatchDetail(match.id)
                detail = d
                if (!d.events.isNullOrEmpty()) {
                    events = d.events.orEmpty()
                } else if (events.isEmpty() && !match.events.isNullOrEmpty()) {
                    events = match.events.orEmpty()
                }
                lastRefreshedAt = System.currentTimeMillis()
                // H2H egyszer (vagy ha üres)
                if (h2h == null) {
                    try {
                        h2h = RetrofitInstance.api.getMatchH2h(match.id)
                    } catch (_: Exception) {
                    }
                }
                if (form == null) {
                    try {
                        form = RetrofitInstance.api.getMatchForm(match.id)
                    } catch (_: Exception) {
                    }
                }
            } catch (_: Exception) {
                // hálózati hiba: megtartjuk az utolsó ismert adatot
            } finally {
                isRefreshing = false
            }

            // Ha a meccs már vége, ne spammeljük feleslegesen az API-t
            val finished = detail.status == "FT" || detail.status == "info" || detail.status == "error"
            if (finished) break

            delay(30_000L)
        }
    }

    // Élő meccs + Statisztika tab: stats is 20 mp-enként
    LaunchedEffect(match.id) {
        try {
            val o = RetrofitInstance.api.getMatchOdds(match.id)
            fun num(key: String): Double? {
                val v = o[key] ?: return null
                return when (v) {
                    is Number -> v.toDouble()
                    is String -> v.toDoubleOrNull()
                    else -> null
                }
            }
            oddsHomeUi = num("odds_home")
            oddsDrawUi = num("odds_draw")
            oddsAwayUi = num("odds_away")
            oddsSource = o["source"]?.toString()
            val rawMarkets = o["markets"]
            if (rawMarkets is List<*>) {
                oddsMarkets = rawMarkets.mapNotNull { item ->
                    if (item is Map<*, *>) {
                        item.entries.associate { (k, v) -> k.toString() to v }
                    } else null
                }
            }
        } catch (_: Exception) {
        }
    }

    LaunchedEffect(match.id, selectedTab, detail.status) {
        val live = detail.status != "FT"
                && detail.status != "NS"
                && detail.status != "info"
                && detail.status != "error"
        if (selectedTab != 2 || !live || hlId.isBlank()) return@LaunchedEffect
        while (true) {
            try {
                val r = RetrofitInstance.api.getMatchStatistics(hlId)
                if (!r.items.isNullOrEmpty()) {
                    stats = r.items.orEmpty()
                    errorMsg = null
                }
            } catch (_: Exception) {
            }
            delay(30_000L)
            if (detail.status == "FT") break
        }
    }

    fun loadTab(index: Int) {
        scope.launch {
            loadingTab = true
            errorMsg = null
            try {
                when (index) {
                    2 -> { // stats
                        if (stats.isEmpty()) {
                            val r = try {
                                if (hlId.isNotBlank()) {
                                    RetrofitInstance.api.getMatchStatistics(hlId)
                                } else {
                                    RetrofitInstance.api.getMatchStatisticsByMatchId(match.id)
                                }
                            } catch (_: Exception) {
                                RetrofitInstance.api.getMatchStatisticsByMatchId(match.id)
                            }
                            stats = r.items.orEmpty()
                            if (stats.isEmpty()) {
                                errorMsg = "Ehhez a meccshez még nincs statisztika (alsóbb ligáknál gyakran hiányzik)."
                            }
                        }
                    }
                    3 -> { // lineups
                        if (lineups == null || lineups?.available != true) {
                            lineups = try {
                                if (hlId.isNotBlank()) {
                                    RetrofitInstance.api.getMatchLineups(hlId)
                                } else {
                                    RetrofitInstance.api.getMatchLineupsByMatchId(match.id)
                                }
                            } catch (_: Exception) {
                                RetrofitInstance.api.getMatchLineupsByMatchId(match.id)
                            }
                            if (lineups?.available != true) {
                                errorMsg = "Az összeállítás ehhez a meccshez nem érhető el."
                            }
                        }
                    }
                    4 -> { // videos
                        if (hlId.isNotBlank() && videos.isEmpty()) {
                            videos = RetrofitInstance.api.getMatchHighlights(hlId)
                                .filter { !it.embedUrl.isNullOrBlank() || !it.url.isNullOrBlank() }
                                .sortedWith(
                                    compareByDescending<HighlightVideo> {
                                        it.category.equals("goal-clip", ignoreCase = true)
                                    }.thenBy { it.title.orEmpty() }
                                )
                            if (videos.isEmpty()) errorMsg = "Nincs elérhető videó."
                        } else if (hlId.isBlank()) {
                            errorMsg = "Nincs Highlightly videó ehhez a meccshez."
                        }
                    }
                    5 -> { // standings
                        val lid = match.leagueId.orEmpty().trim()
                        if (lid.isNotBlank() && standings.isEmpty()) {
                            standings = RetrofitInstance.api.getStandings(lid)
                            if (standings.isEmpty()) errorMsg = "Tabella nem elérhető."
                        } else if (lid.isBlank()) {
                            errorMsg = "Nincs liga azonosító."
                        }
                    }
                }
            } catch (e: Exception) {
                errorMsg = "Betöltési hiba: ${e.message?.take(40) ?: "ismeretlen"}"
            } finally {
                loadingTab = false
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color.Transparent
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                // Gradiens háttér
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            brush = if (isDarkMode) {
                                Brush.verticalGradient(
                                    listOf(
                                        Color(0xFF0A1628),
                                        Color(0xFF122445),
                                        Color(0xFF0D1B33)
                                    )
                                )
                            } else {
                                Brush.verticalGradient(
                                    listOf(
                                        Color(0xFFD6E8FF),
                                        Color(0xFFEEF5FF),
                                        Color(0xFFD0E4FF)
                                    )
                                )
                            }
                        )
                )
                Column(modifier = Modifier.fillMaxSize()) {
                // Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(card)
                        .border(0.5.dp, glassBorder)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "←",
                        color = text,
                        fontSize = 22.sp,
                        modifier = Modifier
                            .clickable { onDismiss() }
                            .padding(end = 12.dp)
                    )
                    Text(
                        text = match.league.orEmpty(),
                        color = sub,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = "🔄",
                        fontSize = 16.sp,
                        modifier = Modifier
                            .clickable {
                                scope.launch {
                                    try {
                                        isRefreshing = true
                                        val d = RetrofitInstance.api.getMatchDetail(match.id)
                                        detail = d
                                        if (!d.events.isNullOrEmpty()) events = d.events.orEmpty()
                                        h2h = RetrofitInstance.api.getMatchH2h(match.id)
                                        lastRefreshedAt = System.currentTimeMillis()
                                    } catch (_: Exception) {
                                    } finally {
                                        isRefreshing = false
                                    }
                                }
                            }
                            .padding(horizontal = 6.dp)
                    )
                    Text(
                        text = "🤖",
                        fontSize = 18.sp,
                        modifier = Modifier
                            .clickable {
                                scope.launch {
                                    showAi = true
                                    aiText = null
                                    try {
                                        val r = RetrofitInstance.api.getAiAnalysis(match.id)
                                        aiText = extractAiText(r)
                                    } catch (e: Exception) {
                                        aiText = "AI nem elérhető."
                                    }
                                }
                            }
                            .padding(horizontal = 6.dp)
                    )
                    Text(
                        text = "↗",
                        color = accent,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clickable {
                                val score = "${detail.homeScore ?: 0}–${detail.awayScore ?: 0}"
                                val min = detail.minute?.takeIf { it > 0 }?.let { " ($it')" } ?: ""
                                val body = "${detail.homeTeam.orEmpty()} $score ${detail.awayTeam.orEmpty()}$min\n${detail.league.orEmpty()}"
                                val intent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, body)
                                }
                                context.startActivity(Intent.createChooser(intent, "Megosztás"))
                            }
                            .padding(horizontal = 6.dp)
                    )
                    Text(
                        text = if (isFavorite) "★" else "☆",
                        color = if (isFavorite) Color(0xFFFF9100) else sub,
                        fontSize = 22.sp,
                        modifier = Modifier.clickable { onFavoriteToggle() }
                    )
                }

                // Scoreboard
                MatchDetailScoreboard(
                    match = detail,
                    card = card,
                    text = text,
                    sub = sub,
                    green = green
                )

                // Élő frissítés jelző
                val liveNow = detail.status != "FT"
                        && detail.status != "NS"
                        && detail.status != "info"
                        && detail.status != "error"
                        && (detail.minute ?: 0) > 0
                if (liveNow || isRefreshing) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(card)
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (isRefreshing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(10.dp),
                                strokeWidth = 1.5.dp,
                                color = green
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Frissítés…",
                                color = sub,
                                fontSize = 11.sp
                            )
                        } else if (liveNow) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(green)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Élő · automatikus frissítés 20 mp-enként",
                                color = sub,
                                fontSize = 11.sp
                            )
                        }
                    }
                }

                // Tabs
                ScrollableTabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = card,
                    contentColor = accent,
                    edgePadding = 8.dp
                ) {
                    tabs.forEachIndexed { index, title ->
                        Tab(
                            selected = selectedTab == index,
                            onClick = {
                                previousTab = selectedTab
                                selectedTab = index
                                loadTab(index)
                            },
                            text = {
                                Text(
                                    text = title,
                                    fontSize = 13.sp,
                                    color = if (selectedTab == index) accent else sub
                                )
                            }
                        )
                    }
                }


                // FCM követés – gól / lap / kezdés / félidő / vége
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(card)
                        .clickable {
                            val next = !isFollowing
                            isFollowing = next
                            FcmRegistrar.setFollowing(ctx, match.id, next)
                            Toast.makeText(
                                ctx,
                                if (next) "Értesítések BE – gól / lap / státusz"
                                else "Értesítések KI",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (isFollowing) "🔔" else "🔕",
                        fontSize = 18.sp
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (isFollowing) {
                                "Értesítések bekapcsolva"
                            } else {
                                "Értesítések a meccsről"
                            },
                            color = text,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Gól, lap, kezdés, félidő, vége",
                            color = sub,
                            fontSize = 11.sp
                        )
                    }
                    Text(
                        text = if (isFollowing) "BE" else "KI",
                        color = if (isFollowing) green else sub,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                // Csapat követés (összes meccs + push)
                val ctxTeams = LocalContext.current
                var followedTeams by remember {
                    mutableStateOf(TeamFollowPrefs.teams(ctxTeams))
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(card)
                        .padding(10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOfNotNull(detail.homeTeam?.takeIf { it.isNotBlank() }, detail.awayTeam?.takeIf { it.isNotBlank() }).forEach { teamName ->
                        val on = followedTeams.any { it.equals(teamName, true) }
                        Text(
                            text = if (on) "★ $teamName" else "☆ $teamName",
                            color = if (on) green else text,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0x221A2D4D))
                                .clickable {
                                    followedTeams = TeamFollowPrefs.toggle(ctxTeams, teamName)
                                    // ha bekapcsolva, kövesd ezt a meccset is
                                    if (followedTeams.any { it.equals(teamName, true) }) {
                                        FcmRegistrar.setFollowing(ctxTeams, detail.id, true)
                                    }
                                    Toast.makeText(
                                        ctxTeams,
                                        if (followedTeams.any { it.equals(teamName, true) })
                                            "$teamName követve – jövőbeli meccsek + push"
                                        else
                                            "$teamName követés kikapcsolva",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                                .padding(8.dp)
                        )
                    }
                }

                if (selectedPlayer != null) {
                    PlayerCardDialogWithOpen(
                        player = selectedPlayer!!,
                        teamHint = selectedPlayerTeam,
                        onDismiss = { selectedPlayer = null },
                        onYoutube = { q ->
                            try {
                                context.startActivity(
                                    Intent(
                                        Intent.ACTION_VIEW,
                                        Uri.parse(
                                            "https://www.youtube.com/results?search_query=" +
                                                Uri.encode(q)
                                        )
                                    )
                                )
                            } catch (_: Exception) {
                            }
                        }
                    )
                }
                if (teamDialogName != null) {
                    TeamQuickDialog(
                        teamName = teamDialogName!!,
                        formLine = null,
                        onDismiss = { teamDialogName = null },
                        onYoutube = {
                            try {
                                context.startActivity(
                                    Intent(
                                        Intent.ACTION_VIEW,
                                        Uri.parse(
                                            "https://www.youtube.com/results?search_query=" +
                                                Uri.encode(teamDialogName + " football")
                                        )
                                    )
                                )
                            } catch (_: Exception) {
                            }
                        }
                    )
                }
                WhoWinsVote(
                    match = detail,
                    ctx = ctx,
                    card = card,
                    text = text,
                    sub = sub,
                    green = green
                )
                Spacer(modifier = Modifier.height(6.dp))
                if (loadingTab) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = accent, strokeWidth = 2.dp)
                    }
                }

                errorMsg?.let { msg ->
                    Text(
                        text = msg,
                        color = sub,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(16.dp)
                    )
                }

                AnimatedContent(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    targetState = selectedTab,
                    transitionSpec = {
                        val forward = targetState >= initialState
                        val slideIn = if (forward) {
                            slideInHorizontally(
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioNoBouncy,
                                    stiffness = Spring.StiffnessMediumLow
                                ),
                                initialOffsetX = { it / 6 }
                            )
                        } else {
                            slideInHorizontally(
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioNoBouncy,
                                    stiffness = Spring.StiffnessMediumLow
                                ),
                                initialOffsetX = { -it / 6 }
                            )
                        }
                        val slideOut = if (forward) {
                            slideOutHorizontally(
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioNoBouncy,
                                    stiffness = Spring.StiffnessMediumLow
                                ),
                                targetOffsetX = { -it / 6 }
                            )
                        } else {
                            slideOutHorizontally(
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioNoBouncy,
                                    stiffness = Spring.StiffnessMediumLow
                                ),
                                targetOffsetX = { it / 6 }
                            )
                        }
                        (slideIn + fadeIn(animationSpec = tween(200))) togetherWith
                            (slideOut + fadeOut(animationSpec = tween(160)))
                    },
                    label = "detailTab"
                ) { tab ->
                when (tab) {
                    0 -> SummaryTab(
                        match = detail,
                        events = events,
                        h2h = h2h,
                        form = form,
                        text = text,
                        sub = sub,
                        card = card,
                        green = green,
                        accent = accent,
                        oddsHome = oddsHomeUi,
                        oddsDraw = oddsDrawUi,
                        oddsAway = oddsAwayUi,
                        oddsSource = oddsSource,
                        oddsMarkets = oddsMarkets,
                        onAddOddsToTicket = onAddOddsToTicket
                    )
                    1 -> EventsTab(
                        events = events,
                        text = text,
                        sub = sub,
                        card = card
                    )
                    2 -> StatsTab(stats = stats, text = text, sub = sub, card = card, accent = accent)
                    3 -> LineupsTab(
                        lineups = lineups, text = text, sub = sub, card = card,
                        onPlayerClick = { pl, team ->
                            selectedPlayer = pl
                            selectedPlayerTeam = team
                        }
                    )
                    4 -> VideosTab(
                        videos = videos,
                        text = text,
                        sub = sub,
                        card = card,
                        onVideoClick = onVideoClick
                    )
                    5 -> StandingsTab(
                        standings = standings,
                        text = text,
                        sub = sub,
                        card = card,
                        homeTeam = match.homeTeam.orEmpty(),
                        awayTeam = match.awayTeam.orEmpty(),
                        accent = accent
                    )
                }
                }
            }
            } // Box (gradiens + tartalom)
        } // Surface
    } // Dialog

        if (showAi) {
            AlertDialog(
                onDismissRequest = { showAi = false },
                title = {
                    Text("🤖 AI elemzés", fontWeight = FontWeight.Bold, color = green)
                },
                text = {
                    if (aiText == null) {
                        CircularProgressIndicator(color = green, modifier = Modifier.size(28.dp))
                    } else {
                        Text(aiText ?: "", color = text, fontSize = 13.sp)
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showAi = false }) {
                        Text("Bezárás", color = green)
                    }
                }
            )
        }
    }


/** AiAnalysisResponse mezői repónként eltérhetnek – első nem üres String property. */


private fun formatStatValue(name: String?, raw: Any?): String {
    if (raw == null) return "—"
    val n = huStatName(name).lowercase()
    val s = raw.toString().trim()
    val f = s.replace("%", "").toFloatOrNull()
    if (f != null) {
        // 0–1 arány → százalék
        if (f in 0.0..1.0 && ("birtoklás" in n || "pontosság" in n || "accuracy" in (name ?: "").lowercase())) {
            return "${(f * 100).toInt()}%"
        }
        // már százalékos érték 0-100
        if ("birtoklás" in n && f > 1f && f <= 100f) {
            return "${f.toInt()}%"
        }
        // egész számok
        if (f == f.toLong().toFloat()) return f.toLong().toString()
        return "%.1f".format(f)
    }
    return s
}

private fun huStatName(raw: String?): String {
    if (raw.isNullOrBlank()) return "—"
    val key = raw.trim().lowercase().replace(Regex("""\s+"""), " ")
    val map = linkedMapOf(
        "shots accuracy" to "Lövéspontosság",
        "shot accuracy" to "Lövéspontosság",
        "shots on target" to "Kapura lövés",
        "shots off target" to "Kapu mellé",
        "blocked shots" to "Blokkolt lövés",
        "shots blocked" to "Blokkolt lövés",
        "total shots" to "Összes lövés",
        "shots total" to "Összes lövés",
        "shots" to "Lövések",
        "fouls" to "Szabálytalanság",
        "corners" to "Szöglet",
        "corner kicks" to "Szöglet",
        "offsides" to "Les",
        "possession" to "Labdabirtoklás",
        "ball possession" to "Labdabirtoklás",
        "yellow cards" to "Sárga lap",
        "red cards" to "Piros lap",
        "goalkeeper saves" to "Kapus védés",
        "saves" to "Védések",
        "total passes" to "Összes passz",
        "passes total" to "Összes passz",
        "accurate passes" to "Pontos passz",
        "passes accurate" to "Pontos passz",
        "pass accuracy" to "Passzpontosság",
        "key passes" to "Kulcspassz",
        "passes" to "Passzok",
        "expected goals" to "Várható gól (xG)",
        "expected goals (xg)" to "Várható gól (xG)",
        "xg" to "Várható gól (xG)",
        "expected assists" to "Várható gólpassz (xA)",
        "expected assists (xa)" to "Várható gólpassz (xA)",
        "xa" to "Várható gólpassz (xA)",
        "attacks" to "Támadás",
        "dangerous attacks" to "Veszélyes támadás",
        "throw-ins" to "Bedobás",
        "throw ins" to "Bedobás",
        "free kicks" to "Szabadrúgás",
        "goal kicks" to "Kapusrúgás",
        "total tackles" to "Összes szerelés",
        "tackles won" to "Nyert szerelés",
        "tackles" to "Szerelés",
        "interceptions" to "Labdaszerzés",
        "clearances" to "Kiszabadítás",
        "accurate crosses" to "Pontos beadás",
        "crosses accurate" to "Pontos beadás",
        "total crosses" to "Összes beadás",
        "crosses" to "Beadás",
        "counter attacks" to "Kontratámadás",
        "hits woodwork" to "Kapufák",
        "big chances missed" to "Elpuskázott nagy helyzet",
        "big chances created" to "Kialakított nagy helyzet",
        "big chances" to "Nagy helyzet",
        "duels won" to "Nyert párharc",
        "duels" to "Párharc",
        "aerials won" to "Fejpárbaj",
        "aerials" to "Fejpárbaj",
        "successful dribbles" to "Sikeres csel",
        "dribbles successful" to "Sikeres csel",
        "dribbles succeeded" to "Sikeres csel",
        "dribbles attempted" to "Próbált csel",
        "dribbles" to "Cselt",
        "substitutions" to "Csere",
        "passes in final third" to "Passz a 16-osban",
        "long balls" to "Hosszú labda",
        "accurate long balls" to "Pontos hosszú labda"
    )
    map[key]?.let { return it }
    // csak hosszú (>=8) kulcs részleges egyezése, hossz szerint csökkenő
    map.keys.sortedByDescending { it.length }
        .firstOrNull { it.length >= 8 && key.contains(it) }
        ?.let { return map[it]!! }
    return raw.trim()
}

private fun extractAiText(r: Any): String {
    val preferred = listOf(
        "summary", "analysis", "text", "message", "content",
        "result", "prediction", "output", "answer"
    )
    val clazz = r.javaClass
    for (name in preferred) {
        try {
            val field = clazz.declaredFields.find { it.name == name }
                ?: clazz.fields.find { it.name == name }
            if (field != null) {
                field.isAccessible = true
                val v = field.get(r)?.toString()?.trim()
                if (!v.isNullOrBlank() && v != "null") return v
            }
        } catch (_: Exception) {
        }
        try {
            val getter = "get" + name.replaceFirstChar { it.uppercase() }
            val m = clazz.methods.find { it.name == getter && it.parameterCount == 0 }
            val v = m?.invoke(r)?.toString()?.trim()
            if (!v.isNullOrBlank() && v != "null") return v
        } catch (_: Exception) {
        }
    }
    // bármilyen nem üres string mező
    try {
        for (field in clazz.declaredFields) {
            field.isAccessible = true
            val v = field.get(r)?.toString()?.trim()
            if (!v.isNullOrBlank() && v != "null" && field.type == String::class.java) {
                return v
            }
        }
    } catch (_: Exception) {
    }
    return "AI válasz érkezett (részletek a lista 🤖 gombjánál)."
}

@Composable
private fun DetailTeamLogo(
    url: String?,
    teamName: String?,
    size: androidx.compose.ui.unit.Dp = 48.dp
) {
    val safeName = teamName?.trim().orEmpty()
    val initials = safeName.split(Regex("""\s+"""))
        .filter { it.isNotBlank() }.take(2)
        .joinToString("") { it.first().uppercase() }
        .ifBlank { "⚽" }
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(Color(0xFF252A31)),
        contentAlignment = Alignment.Center
    ) {
        if (!url.isNullOrBlank()) {
            SubcomposeAsyncImage(
                model = url,
                contentDescription = "$safeName logo",
                modifier = Modifier.fillMaxSize(),
                loading = {
                    Text(initials, color = Color(0xFF8C939D), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                },
                error = {
                    Text(initials, color = Color(0xFF8C939D), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            )
        } else {
            Text(initials, color = Color(0xFF8C939D), fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun MatchDetailScoreboard(
    match: MatchResponse,
    card: Color,
    text: Color,
    sub: Color,
    green: Color
) {
    val statusU = (match.status ?: "").trim().uppercase().replace(".", "")
    val isLive = statusU in setOf("1H", "2H", "HT", "LIVE", "ET", "INPLAY") ||
        ((match.minute ?: 0) > 0 && statusU !in setOf(
            "FT", "AET", "PEN", "PENS", "NS", "TBD", "PST", "CANC", "FINISHED"
        ) && !statusU.contains(":"))
    var detailPulse by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(isLive) {
        if (!isLive) return@LaunchedEffect
        while (true) {
            kotlinx.coroutines.delay(15_000L)
            detailPulse = System.currentTimeMillis()
        }
    }
    val liveMinute = rememberLiveMinute(
        matchId = match.id ?: match.matchId ?: "unknown",
        serverMinute = match.minute,
        status = match.status.orEmpty(),
        isLive = isLive,
        pulseMs = detailPulse
    )
    Column(modifier = Modifier
            .fillMaxWidth()
            .background(card)
            .padding(vertical = 16.dp, horizontal = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                DetailTeamLogo(url = match.homeLogoUrl, teamName = match.homeTeam, size = 48.dp)
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = match.homeTeam.orEmpty(),
                    color = text,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "${match.homeScore ?: "-"}  :  ${match.awayScore ?: "-"}",
                    color = text,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                if (isLive) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(green)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (liveMinute > 0) "${liveMinute}'" else "ÉLŐ",
                            color = green,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                } else {
                    Text(
                        text = statusLabel(match.status),
                        color = sub,
                        fontSize = 12.sp
                    )
                }
                if (match.isValueBet == true) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "ÉRTÉKES",
                        color = Color(0xFFFFB300),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Column(modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                DetailTeamLogo(url = match.awayLogoUrl, teamName = match.awayTeam, size = 48.dp)
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = match.awayTeam.orEmpty(),
                    color = text,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

private fun statusLabel(status: String?): String {
    val s = status?.trim().orEmpty()
    if (s.isEmpty()) return "—"
    val key = s.uppercase().replace(".", "").replace(" ", "")
    return when (key) {
        "FT", "AET", "PEN", "PENS", "PSO", "FINISHED", "FULLTIME", "ENDED" -> "Vége"
        "HT" -> "Félidő"
        "1H" -> "1. Félidő"
        "2H" -> "2. Félidő"
        "NS", "TBD", "SCHEDULED" -> "Kezdés előtt"
        "LIVE", "INPLAY", "ET" -> "ÉLŐ"
        "PST", "POSTP", "POSTPONED" -> "Elhalasztva"
        "CANC", "CANCELLED", "ABD", "ABANDONED" -> "Törölve"
        else -> s
    }
}

private fun eventIcon(type: String?): String {
    val t = type?.lowercase().orEmpty()
    return when {
        t.contains("own") -> "⚽"
        t.contains("goal") || t == "penalty" -> "⚽"
        t.contains("yellow") -> "🟨"
        t.contains("red") -> "🟥"
        t.contains("sub") -> "🔄"
        t.contains("var") -> "📺"
        t.contains("miss") -> "❌"
        else -> "•"
    }
}

@Composable
private fun SummaryTab(
    match: MatchResponse,
    events: List<MatchEvent>,
    h2h: H2hResponse?,
    form: FormResponse?,
    text: Color,
    sub: Color,
    card: Color,
    green: Color,
    accent: Color,
    oddsHome: Double? = null,
    oddsDraw: Double? = null,
    oddsAway: Double? = null,
    oddsSource: String? = null,
    oddsMarkets: List<Map<String, Any?>> = emptyList(),
    onAddOddsToTicket: (String, String, Double?) -> Unit = { _, _, _ -> }
) {
    val goals = events.filter {
        val ty = it.type?.lowercase().orEmpty()
        ty.contains("goal") || ty == "penalty"
    }
    val scheduled = isScheduledStatus(match.status, match.minute)
    val finished = (match.status ?: "").equals("FT", true) || (match.status ?: "").equals("AET", true)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Státusz üzenet
        item {
            val msg = when {
                scheduled -> "A meccs még nem kezdődött (${statusLabel(match.status)}). Az események és a statisztikák kezdés után jelennek meg."
                finished -> "A mérkőzés véget ért."
                else -> null
            }
            if (msg != null) {
                Text(
                    text = msg,
                    color = sub,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(card)
                        .padding(12.dp)
                )
            }
        }

        // Odds
        item {
            OddsRow(
                match, text, sub, card, green, oddsHome, oddsDraw, oddsAway, oddsSource, oddsMarkets,
                onPick = onAddOddsToTicket
            )
            Spacer(modifier = Modifier.height(10.dp))
            AiPrematchTipCard(match, text, sub, card, green)
        }

        // Forma (W-D-L)
        if (form?.available == true || !form?.home.isNullOrEmpty() || !form?.away.isNullOrEmpty()) {
            item {
                Text("Forma (utolsó 5)", color = sub, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                Spacer(modifier = Modifier.height(6.dp))
                FormRow(
                    homeName = match.homeTeam.orEmpty(),
                    awayName = match.awayTeam.orEmpty(),
                    homeForm = form?.home.orEmpty(),
                    awayForm = form?.away.orEmpty(),
                    text = text,
                    sub = sub,
                    card = card,
                    green = green
                )
            }
        }

        // Venue / referee
        if (!match.venue.isNullOrBlank() || !match.referee.isNullOrBlank()) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(card)
                        .padding(12.dp)
                ) {
                    if (!match.venue.isNullOrBlank()) {
                        Text("📍 ${match.venue}", color = text, fontSize = 13.sp)
                    }
                    if (!match.referee.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("🧑‍⚖️ ${match.referee}", color = sub, fontSize = 12.sp)
                    }
                }
            }
        }

        // Momentum
        item {
            Text("Momentum", color = sub, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            Spacer(modifier = Modifier.height(6.dp))
            MomentumChart(events = events, green = green, accent = accent, card = card)
        }

        // Góllövők
        item {
            Text("Góllövők", color = sub, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }
        if (goals.isEmpty()) {
            item {
                Text(
                    text = if (scheduled) "Kezdés után jelennek meg a gólok." else "Még nincs gól.",
                    color = sub,
                    fontSize = 13.sp
                )
            }
        } else {
            items(goals) { ev ->
                EventRow(ev, text, sub, card, isHome = ev.team == "home")
            }
        }

        // H2H
        item {
            Spacer(modifier = Modifier.height(4.dp))
            Text("Egymás ellen (H2H)", color = sub, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }
        val h2hItems = h2h?.items.orEmpty()
        if (h2hItems.isEmpty()) {
            item {
                Text(
                    text = h2h?.message ?: "Nincs elérhető H2H adat.",
                    color = sub,
                    fontSize = 13.sp
                )
            }
        } else {
            items(h2hItems) { item ->
                H2hRow(item, text, sub, card)
            }
        }

        item {
            Text(
                text = "Liga: ${match.league.orEmpty()}",
                color = sub,
                fontSize = 12.sp
            )
        }
    }
}

private fun isScheduledStatus(status: String?, minute: Int?): Boolean {
    val s = status?.trim()?.uppercase()?.replace(".", "") ?: return true
    if (s in setOf("NS", "TBD", "SCHEDULED", "POSTP", "PST")) return true
    if (s.contains(":")) return true // 20:30
    if ((minute ?: 0) <= 0 && s !in setOf("1H", "2H", "HT", "LIVE", "ET", "FT", "AET", "PEN")) {
        if (s.toIntOrNull() == null) return true
    }
    return false
}


@Composable
private fun FormRow(
    homeName: String,
    awayName: String,
    homeForm: List<String>,
    awayForm: List<String>,
    text: Color,
    sub: Color,
    card: Color,
    green: Color
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(card)
            .padding(12.dp)
    ) {
        FormSide(homeName, homeForm, text, sub, green)
        Spacer(modifier = Modifier.height(8.dp))
        FormSide(awayName, awayForm, text, sub, green)
    }
}

@Composable
private fun FormSide(name: String, form: List<String>, text: Color, sub: Color, green: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = name,
            color = text,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (form.isEmpty()) {
            Text("—", color = sub, fontSize = 11.sp)
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                form.forEach { r ->
                    val bg = when (r.uppercase()) {
                        "W" -> green
                        "L" -> Color(0xFFE53935)
                        else -> Color(0xFF9E9E9E)
                    }
                    Box(
                        modifier = Modifier
                            .size(18.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(bg),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(r.uppercase().take(1), color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}


@Composable
private fun AiPrematchTipCard(
    match: MatchResponse,
    text: Color,
    sub: Color,
    card: Color,
    green: Color
) {
    var tip by remember(match.id) { mutableStateOf<String?>(null) }
    var loading by remember(match.id) { mutableStateOf(false) }
    var requested by remember(match.id) { mutableStateOf(false) }

    LaunchedEffect(requested) {
        if (!requested || tip != null) return@LaunchedEffect
        loading = true
        try {
            val r = RetrofitInstance.api.getAiAnalysis(match.id)
            val raw = try { r.analysis } catch (_: Exception) { null }
            val textFull = raw?.takeIf { !it.isNullOrBlank() }
                ?: "A ${match.homeTeam} – ${match.awayTeam} meccsen a forma és a hazai pálya lehet döntő. Érdemes figyelni a gólokat mindkét oldalon. A végeredmény nyitott."
            val parts = textFull.split(Regex("(?<=[.!?])\\s+")).filter { it.isNotBlank() }.take(3)
            tip = parts.joinToString(" ")
        } catch (_: Exception) {
            tip = "${match.homeTeam.orEmpty()} hazai előnye és a legutóbbi forma számít. A ${match.awayTeam.orEmpty()} kontrákkal veszélyes lehet. Ez tájékoztató, nem fogadási javaslat."
        } finally {
            loading = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(card)
            .border(1.dp, Color(0x334DA3FF), RoundedCornerShape(10.dp))
            .padding(12.dp)
    ) {
        Text("AI előzetes (3 mondat)", color = text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Spacer(modifier = Modifier.height(4.dp))
        Text("Nem fogadási tanács – csak tájékoztató elemzés.", color = sub, fontSize = 10.sp)
        Spacer(modifier = Modifier.height(8.dp))
        when {
            loading -> Text("Elemzés…", color = sub, fontSize = 12.sp)
            tip != null -> Text(tip!!, color = text, fontSize = 12.sp, lineHeight = 16.sp)
            else -> TextButton(onClick = { requested = true }) { Text("Előzetes kérése", color = green) }
        }
    }
}

@Composable
private fun OddsRow(
    match: MatchResponse,
    text: Color,
    sub: Color,
    card: Color,
    green: Color,
    oddsHome: Double? = null,
    oddsDraw: Double? = null,
    oddsAway: Double? = null,
    oddsSource: String? = null,
    oddsMarkets: List<Map<String, Any?>> = emptyList(),
    onPick: (market: String, pick: String, odds: Double?) -> Unit = { _, _, _ -> }
) {
    val h = oddsHome ?: match.oddsHome
    val d = oddsDraw ?: match.oddsDraw
    val a = oddsAway ?: match.oddsAway

    // Odds változás nyíl (előző értékhez képest)
    var prevH by remember { mutableStateOf<Double?>(null) }
    var prevD by remember { mutableStateOf<Double?>(null) }
    var prevA by remember { mutableStateOf<Double?>(null) }
    LaunchedEffect(h, d, a) {
        // első betöltéskor csak elmentjük, nem mutatunk nyilat
        if (prevH == null && prevD == null && prevA == null) {
            prevH = h; prevD = d; prevA = a
        } else {
            // frissítés után megtartjuk a régi prev-et a nyílhoz, majd delay után frissítjük
            kotlinx.coroutines.delay(4000)
            prevH = h; prevD = d; prevA = a
        }
    }
    fun deltaArrow(cur: Double?, prev: Double?): String {
        if (cur == null || prev == null) return ""
        val diff = cur - prev
        return when {
            diff > 0.01 -> " ↑"
            diff < -0.01 -> " ↓"
            else -> ""
        }
    }
    fun deltaColor(cur: Double?, prev: Double?): Color {
        if (cur == null || prev == null) return green
        val diff = cur - prev
        return when {
            diff > 0.01 -> Color(0xFF00E676) // magasabb odd jobb a fogadónak
            diff < -0.01 -> Color(0xFFFF5252)
            else -> green
        }
    }
    if (h == null && d == null && a == null && oddsMarkets.isEmpty()) return

    data class MarketLine(
        val marketKey: String,
        val marketTitle: String,
        val bookmaker: String,
        val values: List<Pair<String, Double>>
    )

    fun marketCategory(name: String): Pair<Int, String> {
        val n = name.lowercase()
        return when {
            listOf("full time", "1x2", "match winner", "match result", "match odds", "ft result")
                .any { it in n } -> 0 to "1X2 – Végeredmény"
            "both teams" in n || "btts" in n || "gg" in n -> 1 to "Mindkét csapat gól (BTTS)"
            "double chance" in n -> 2 to "Kettős esély"
            "asian handicap" in n || n.startsWith("handicap") -> 3 to "Ázsiai handicap"
            "corner" in n -> 4 to "Szögletek"
            listOf("card", "booking", "yellow", "red card", "total cards").any { it in n } ->
                5 to "Lapok (sárga/piros)"
            "total goals" in n || "over/under" in n || ("total" in n && ("goal" in n || "over" in n || "under" in n) && "corner" !in n && "card" !in n) ->
                6 to "Gólszám (Over/Under)"
            "correct score" in n || "exact score" in n -> 7 to "Pontos eredmény"
            "draw no bet" in n -> 8 to "Döntetlennél visszajár"
            "first half" in n || "1st half" in n -> 9 to "1. félidő"
            "second half" in n || "2nd half" in n -> 10 to "2. félidő"
            else -> 11 to "Egyéb piacok"
        }
    }

    fun parseValues(valuesRaw: Any?): List<Pair<String, Double>> {
        if (valuesRaw !is List<*>) return emptyList()
        return valuesRaw.mapNotNull { v ->
            if (v !is Map<*, *>) return@mapNotNull null
            val lab = (v["label"] ?: v["value"] ?: v["name"])?.toString() ?: return@mapNotNull null
            val odd = when (val o = v["odd"] ?: v["price"]) {
                is Number -> o.toDouble()
                is String -> o.toDoubleOrNull()
                else -> null
            } ?: return@mapNotNull null
            lab to odd
        }
    }

    fun huLabel(lab: String): String {
        val l = lab.trim().lowercase()
        return when (l) {
            "home", "1" -> "1"
            "draw", "x" -> "X"
            "away", "2" -> "2"
            "over" -> "Over"
            "under" -> "Under"
            "yes" -> "Igen"
            "no" -> "Nem"
            else -> lab.take(20)
        }
    }

    val lines = oddsMarkets.mapNotNull { market ->
        val marketName = market["market"]?.toString().orEmpty()
        if (marketName.isBlank()) return@mapNotNull null
        val bookie = market["bookmaker"]?.toString().orEmpty()
        val values = parseValues(market["values"])
        if (values.isEmpty()) return@mapNotNull null
        val (cat, title) = marketCategory(marketName)
        // kulcs: kategória + eredeti market név (pl. Total Goals 2.5)
        MarketLine(
            marketKey = "$cat|$marketName",
            marketTitle = marketName,
            bookmaker = bookie,
            values = values
        )
    }

    // Csoport: kategória cím -> (marketTitle -> legjobb values + bookmaker lista)
    val grouped = lines.groupBy { marketCategory(it.marketTitle).second }

    var expandedCategory by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(card)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Szorzók (prematch)", color = sub, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            if (match.isValueBet == true) {
                Text("ÉRTÉKES", color = Color(0xFFFFD54F), fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }

        // Kiemelt 1X2
        if (h != null || d != null || a != null) {
            Text("1X2 – Végeredmény", color = text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OddsChip("1" + deltaArrow(h, prevH), h, text, deltaColor(h, prevH), Modifier.weight(1f))
                OddsChip("X" + deltaArrow(d, prevD), d, text, deltaColor(d, prevD), Modifier.weight(1f))
                OddsChip("2" + deltaArrow(a, prevA), a, text, deltaColor(a, prevA), Modifier.weight(1f))
            }

            // Best-of 1X2 a markets-ból
            run {
                fun bestOdd(labels: List<String>): Pair<Double?, String?> {
                    var best: Double? = null
                    var book: String? = null
                    oddsMarkets.forEach { mkt ->
                        val vals = (mkt["values"] as? List<*>) ?: return@forEach
                        vals.forEach { v ->
                            if (v !is Map<*, *>) return@forEach
                            val lab = v["label"]?.toString()?.lowercase().orEmpty()
                            if (labels.none { lab.contains(it) || lab == it }) return@forEach
                            val odd = (v["odd"] as? Number)?.toDouble() ?: return@forEach
                            if (best == null || odd > best!!) {
                                best = odd
                                book = mkt["bookmaker"]?.toString()
                            }
                        }
                    }
                    return best to book
                }
                val (bh, hb) = bestOdd(listOf("home", "1", "hazai"))
                val (bd, db) = bestOdd(listOf("draw", "x", "döntetlen"))
                val (ba, ab) = bestOdd(listOf("away", "2", "vendég"))
                if (bh != null || bd != null || ba != null) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text("Legjobb 1/X/2", color = sub, fontSize = 11.sp)
                    Text(
                        buildString {
                            if (bh != null) append("1 ${"%.2f".format(bh)}${hb?.let { " ($it)" } ?: ""}  ")
                            if (bd != null) append("X ${"%.2f".format(bd)}${db?.let { " ($it)" } ?: ""}  ")
                            if (ba != null) append("2 ${"%.2f".format(ba)}${ab?.let { " ($it)" } ?: ""}")
                        },
                        color = text,
                        fontSize = 11.sp
                    )
                }
            }
            // Top 3 iroda összehasonlítás (ha a markets listában van)
            val ftr = oddsMarkets.filter {
                val n = it["market"]?.toString()?.lowercase().orEmpty()
                listOf("full time", "1x2", "match winner", "match result").any { k -> k in n }
            }.take(3)
            if (ftr.size >= 2) {
                Spacer(modifier = Modifier.height(6.dp))
                Text("Top irodák", color = sub, fontSize = 11.sp)
                ftr.forEach { mkt ->
                    val bookie = mkt["bookmaker"]?.toString().orEmpty()
                    val vals = (mkt["values"] as? List<*>)?.mapNotNull { v ->
                        if (v is Map<*, *>) {
                            val lab = v["label"]?.toString() ?: return@mapNotNull null
                            val odd = (v["odd"] as? Number)?.toDouble() ?: return@mapNotNull null
                            lab to odd
                        } else null
                    }.orEmpty()
                    if (vals.isEmpty()) return@forEach
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(bookie.take(14), color = sub, fontSize = 10.sp, modifier = Modifier.width(72.dp))
                        vals.take(3).forEach { (lab, odd) ->
                            Text(
                                "${lab.take(1)} ${String.format("%.2f", odd)}",
                                color = text,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(horizontal = 4.dp)
                            )
                        }
                    }
                }
            }
        }

        val categoryOrder = listOf(
            "1X2 – Végeredmény",
            "Mindkét csapat gól (BTTS)",
            "Kettős esély",
            "Gólszám (Over/Under)",
            "Ázsiai handicap",
            "Szögletek",
            "Lapok (sárga/piros)",
            "Döntetlennél visszajár",
            "1. félidő",
            "2. félidő",
            "Pontos eredmény",
            "Egyéb piacok"
        )

        categoryOrder.forEach { catTitle ->
            val catLines = grouped[catTitle] ?: return@forEach
            // 1X2 kategóriát ne ismételjük, ha már van kiemelt
            if (catTitle.startsWith("1X2") && (h != null || d != null || a != null)) {
                // csak „további irodák” összecsukva
            }

            // marketTitle szerint alcsoport (pl. Total Goals 2.5)
            val byMarket = catLines.groupBy { it.marketTitle }

            val isExpanded = expandedCategory == catTitle
            val showFull = catTitle != "Pontos eredmény" // pontos eredmény mindig összecsukva alapból

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFF152238))
                    .clickable {
                        expandedCategory = if (isExpanded) null else catTitle
                    }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "$catTitle (${byMarket.size})",
                    color = text,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = if (isExpanded) "▲" else "▼",
                    color = sub,
                    fontSize = 11.sp
                )
            }

            if (isExpanded || (showFull && catTitle != "Pontos eredmény" && expandedCategory == null && catTitle in listOf("Gólszám (Over/Under)", "Mindkét csapat gól (BTTS)", "Kettős esély"))) {
                // Alapból mutassuk a legfontosabbakat kinyitva, ha semmi nincs expandálva
                val autoOpen = expandedCategory == null && catTitle in listOf(
                    "Gólszám (Over/Under)",
                    "Mindkét csapat gól (BTTS)",
                    "Kettős esély"
                )
                if (!isExpanded && !autoOpen) return@forEach

                byMarket.entries
                    .sortedBy { it.key }
                    .take(if (catTitle == "Pontos eredmény") 12 else 30)
                    .forEach { (marketTitle, bookLines) ->
                        // Legjobb odd / kimenet: max odd per label (value bet style) – vagy min odd?
                        // Fogadásnál a magasabb odd jobb a játékosnak
                        val bestByLabel = linkedMapOf<String, Pair<Double, String>>()
                        bookLines.forEach { line ->
                            line.values.forEach { (lab, odd) ->
                                val key = huLabel(lab)
                                val prev = bestByLabel[key]
                                if (prev == null || odd > prev.first) {
                                    bestByLabel[key] = odd to line.bookmaker
                                }
                            }
                        }
                        if (bestByLabel.isEmpty()) return@forEach

                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = marketTitle,
                                color = sub,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium
                            )
                            val entries = bestByLabel.entries.toList()
                            entries.chunked(3).forEach { row ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    row.forEach { (lab, pair) ->
                                        val (odd, bookie) = pair
                                        Column(
                                            modifier = Modifier
                                                .weight(1f)
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(Color(0xFF1A2D4D))
                                                .clickable {
                                                    onPick(marketTitle, lab, odd)
                                                }
                                                .padding(vertical = 6.dp, horizontal = 4.dp),
                                            horizontalAlignment = Alignment.CenterHorizontally
                                        ) {
                                            Text(lab, color = green, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                            Text(
                                                String.format("%.2f", odd),
                                                color = text,
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                            if (bookie.isNotBlank()) {
                                                Text(
                                                    bookie.take(12),
                                                    color = sub,
                                                    fontSize = 9.sp,
                                                    maxLines = 1
                                                )
                                            }
                                        }
                                    }
                                    repeat(3 - row.size) {
                                        Spacer(modifier = Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                    }
            }
        }
    }
}

@Composable
private fun OddsChip(label: String, value: Double?, text: Color, green: Color, modifier: Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF1A2D4D))
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(label, color = green, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Text(
            text = value?.let { String.format("%.2f", it) } ?: "—",
            color = text,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun MomentumChart(
    events: List<MatchEvent>,
    green: Color,
    accent: Color,
    card: Color
) {
    val points = events.mapNotNull { ev ->
        val m = ev.minute ?: return@mapNotNull null
        val ty = ev.type?.lowercase().orEmpty()
        val weight = when {
            ty.contains("goal") || ty == "penalty" -> 3f
            ty.contains("card") -> 1f
            ty.contains("sub") -> 0.5f
            else -> 1f
        }
        val sign = if (ev.team == "away") -1f else 1f
        m to (sign * weight)
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(card)
            .padding(8.dp)
    ) {
        if (points.isEmpty()) {
            Text("Kezdés után jelenik meg a momentum.", color = Color(0xFF9AA0A6), fontSize = 11.sp)
        } else {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                val mid = h / 2
                drawLine(Color(0x33FFFFFF), Offset(0f, mid), Offset(w, mid), strokeWidth = 1f)
                // cumulative
                var cum = 0f
                val cumPoints = mutableListOf<Offset>()
                val maxMin = (points.maxOfOrNull { it.first } ?: 90).coerceAtLeast(90).toFloat()
                cumPoints.add(Offset(0f, mid))
                for ((minute, wgt) in points.sortedBy { it.first }) {
                    cum += wgt
                    val x = (minute / maxMin) * w
                    val y = mid - (cum * 6f).coerceIn(-mid + 4, mid - 4)
                    cumPoints.add(Offset(x, y))
                }
                for (i in 0 until cumPoints.size - 1) {
                    drawLine(
                        color = if (cumPoints[i + 1].y <= mid) green else accent,
                        start = cumPoints[i],
                        end = cumPoints[i + 1],
                        strokeWidth = 3f,
                        cap = StrokeCap.Round
                    )
                }
            }
        }
    }
}

@Composable
private fun H2hRow(item: H2hItem, text: Color, sub: Color, card: Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(card)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "${item.homeTeam.orEmpty()} vs ${item.awayTeam.orEmpty()}",
                color = text,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val meta = listOfNotNull(item.date, item.competition).joinToString(" · ")
            if (meta.isNotBlank()) {
                Text(meta, color = sub, fontSize = 10.sp)
            }
        }
        Text(
            text = "${item.homeScore ?: "-"} : ${item.awayScore ?: "-"}",
            color = text,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp
        )
    }
}

@Composable
private fun EventsTab(
    events: List<MatchEvent>,
    text: Color,
    sub: Color,
    card: Color
) {
    var filter by remember { mutableStateOf("ALL") } // ALL, GOAL, CARD, SUB
    if (events.isEmpty()) {
        Box(
            Modifier
                .fillMaxSize()
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Text("Nincs esemény", color = sub, fontSize = 13.sp)
        }
        return
    }
    fun matchesFilter(ev: MatchEvent): Boolean {
        val typeRaw = (ev.type ?: "").lowercase()
        return when (filter) {
            "GOAL" -> "goal" in typeRaw || "gól" in typeRaw || typeRaw == "g" || "pen" in typeRaw
            "CARD" -> "yellow" in typeRaw || "red" in typeRaw || "card" in typeRaw
            "SUB" -> "subst" in typeRaw || "sub" in typeRaw || "csere" in typeRaw
            else -> true
        }
    }
    val sorted = events.filter { matchesFilter(it) }.sortedBy { it.minute ?: 0 }
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            listOf("ALL" to "Mind", "GOAL" to "Gól", "CARD" to "Lap", "SUB" to "Csere").forEach { (key, label) ->
                val sel = filter == key
                Text(
                    text = label,
                    color = if (sel) Color.White else text,
                    fontSize = 11.sp,
                    fontWeight = if (sel) FontWeight.Bold else FontWeight.Medium,
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(if (sel) Color(0xFF00C853) else card)
                        .clickable { filter = key }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
        }
        if (sorted.isEmpty()) {
            Text(
                "Nincs ilyen típusú esemény",
                color = sub,
                fontSize = 12.sp,
                modifier = Modifier.padding(16.dp)
            )
        }
    LazyColumn(
        modifier = Modifier
            .weight(1f)
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        contentPadding = PaddingValues(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        items(sorted.size) { index ->
            val ev = sorted[index]
            val typeRaw = (ev.type ?: "").lowercase()
            val (badge, color) = when {
                "goal" in typeRaw || "gól" in typeRaw || typeRaw == "g" -> "⚽ GÓL" to Color(0xFF00E676)
                "red" in typeRaw -> "🟥 PIROS" to Color(0xFFE53935)
                "yellow" in typeRaw || "card" in typeRaw -> "🟨 SÁRGA" to Color(0xFFFFB300)
                "subst" in typeRaw || "sub" in typeRaw || "csere" in typeRaw -> "🔄 CSERE" to Color(0xFF42A5F5)
                "var" in typeRaw -> "VAR" to Color(0xFFAB47BC)
                "pen" in typeRaw -> "11-es" to Color(0xFFFF7043)
                else -> (ev.type ?: "Esemény").uppercase().take(12) to sub
            }
            val minute = ev.minuteDisplay?.takeIf { it.isNotBlank() }
                ?: (ev.minute?.let { "$it'" } ?: "–")
            val player = listOfNotNull(ev.player, ev.assist, ev.substituted)
                .filter { it.isNotBlank() }
                .joinToString(" → ")
            val teamRaw = (ev.team ?: ev.teamName).orEmpty()
            val team = when (teamRaw.lowercase()) {
                "home", "hazai" -> "Hazai"
                "away", "vendég" -> "Vendég"
                else -> teamRaw
            }
            val isLast = index == sorted.lastIndex

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.width(36.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(color)
                    )
                    if (!isLast) {
                        Box(
                            modifier = Modifier
                                .width(2.dp)
                                .height(52.dp)
                                .background(Color(0x334DA3FF))
                        )
                    }
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 8.dp, bottom = if (isLast) 8.dp else 8.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(card)
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(badge, color = color, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Text(minute, color = sub, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                    if (player.isNotBlank()) {
                        Text(player, color = text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    }
                    if (team.isNotBlank()) {
                        Text(team, color = sub, fontSize = 11.sp)
                    }
                }
            }
        }
    }
}

}

@Composable
private fun EventRow(
    ev: MatchEvent,
    text: Color,
    sub: Color,
    card: Color,
    isHome: Boolean
) {
    val minute = ev.minuteDisplay?.takeIf { it.isNotBlank() }
        ?: ev.minute?.let { "$it'" }
        ?: ""
    val label = buildString {
        append(eventIcon(ev.type))
        append(" ")
        if (!ev.player.isNullOrBlank()) append(ev.player)
        if (!ev.assist.isNullOrBlank()) append(" (${ev.assist})")
        if (!ev.substituted.isNullOrBlank()) append(" → ${ev.substituted}")
    }
    val badge = eventBadge(ev)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(card)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (badge != null) {
            Text(
                text = badge.first,
                color = Color.White,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(badge.second)
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
        }
        if (isHome) {
            Text(label, color = text, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Text(minute, color = sub, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.weight(1f))
        } else {
            Spacer(modifier = Modifier.weight(1f))
            Text(minute, color = sub, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Text(label, color = text, fontSize = 13.sp, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
        }
    }
}

@Composable
private fun StatsTab(
    stats: List<StatItem>,
    text: Color,
    sub: Color,
    card: Color,
    accent: Color
) {
    LazyColumn(modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item { XgMomentumCard(stats, "Hazai", "Vendég", card, text, sub) }
        if (stats.isEmpty()) {
            item { Text("Nincs megjeleníthető statisztika ehhez a meccshez.", color = sub, fontSize = 13.sp) }
        } else {
            items(stats) { s ->
                val homeVal = formatStatValue(s.name, s.home)
                val awayVal = formatStatValue(s.name, s.away)
                Column(modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(card)
                        .padding(12.dp)
                ) {
                    Text(huStatName(s.name), color = sub, fontSize = 11.sp)
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(homeVal, color = text, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text(awayVal, color = text, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }
                    // egyszerű progress ha % szám
                    val h = homeVal.replace("%", "").toFloatOrNull()
                    val a = awayVal.replace("%", "").toFloatOrNull()
                    if (h != null && a != null && h + a > 0) {
                        Spacer(modifier = Modifier.height(6.dp))
                        val ratio = (h / (h + a)).coerceIn(0f, 1f)
                        val animatedRatio by animateFloatAsState(
                            targetValue = ratio,
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioNoBouncy,
                                stiffness = Spring.StiffnessLow
                            ),
                            label = "statBar"
                        )
                        LinearProgressIndicator(
                            progress = animatedRatio,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp)),
                            color = accent,
                            trackColor = Color(0xFF44474A)
                        )
                    }
                }
            }
        }
    }
}


/** Mezszám: 1.0 → "1", "23" → "23" */
private fun jerseyNumber(n: Any?): String {
    if (n == null) return "·"
    return when (n) {
        is Int -> n.toString()
        is Long -> n.toString()
        is Double -> {
            val i = n.toInt()
            if (n == i.toDouble()) i.toString() else n.toInt().toString()
        }
        is Float -> n.toInt().toString()
        is String -> {
            val d = n.toDoubleOrNull()
            if (d != null) d.toInt().toString() else n.filter { it.isDigit() }.ifBlank { "·" }
        }
        is Number -> n.toInt().toString()
        else -> n.toString().substringBefore(".").ifBlank { "·" }
    }
}

@Composable
private fun LineupsTab(
    lineups: LineupsResponse?,
    text: Color,
    sub: Color,
    card: Color,
    onPlayerClick: (com.sportapp.models.LineupPlayer, String) -> Unit = { _, _ -> }
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        if (lineups == null || lineups.available != true) {
            item { Text("Az API nem adott kezdőcsapatot ehhez a meccshez (gyakori alsóbb ligáknál).", color = sub, fontSize = 13.sp) }
        } else {
            item {
                PitchLineupCard(
                    title = "Hazai",
                    side = lineups.home,
                    jerseyColor = Color(0xFF1E88E5),
                    text = text,
                    sub = sub,
                    onPlayerClick = { onPlayerClick(it, lineups.home?.teamName.orEmpty()) }
                )
            }
            item {
                PitchLineupCard(
                    title = "Vendég",
                    side = lineups.away,
                    jerseyColor = Color(0xFFE53935),
                    text = text,
                    sub = sub,
                    onPlayerClick = { onPlayerClick(it, lineups.away?.teamName.orEmpty()) }
                )
            }
        }
    }
}

/** Modern, látványos pálya + kezdő 11. */
@Composable
private fun PitchLineupCard(
    title: String,
    side: com.sportapp.models.LineupSide?,
    jerseyColor: Color,
    text: Color,
    sub: Color,
    onPlayerClick: (com.sportapp.models.LineupPlayer) -> Unit = {}
) {
    val starters = side?.players?.filter { it.isBench != true }.orEmpty()
    val bench = side?.players?.filter { it.isBench == true }.orEmpty()
    val formation = side?.formation?.trim().orEmpty()
    val positions = remember(formation, starters.size) {
        formationPitchPositions(formation, starters.size.coerceAtLeast(1))
    }
    // Formáció animáció kulcs – új lineupnál újraindul
    val animKey = remember(side?.teamName, formation, starters.size) {
        "${side?.teamName}|$formation|${starters.size}"
    }
    val grassDark = Color(0xFF0D3B1E)
    val grassMid = Color(0xFF1B7A3A)
    val grassLight = Color(0xFF249B4A)

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = side?.teamName ?: title,
                color = text,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (formation.isNotBlank()) {
                val badgeAnim = remember(animKey) { Animatable(0f) }
                LaunchedEffect(animKey) {
                    badgeAnim.snapTo(0f)
                    badgeAnim.animateTo(
                        1f,
                        spring(dampingRatio = 0.65f, stiffness = 320f)
                    )
                }
                Text(
                    text = formation,
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .graphicsLayer {
                            alpha = badgeAnim.value
                            scaleX = 0.7f + 0.3f * badgeAnim.value
                            scaleY = 0.7f + 0.3f * badgeAnim.value
                        }
                        .clip(RoundedCornerShape(8.dp))
                        .background(jerseyColor.copy(alpha = 0.9f))
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(320.dp)
                .clip(RoundedCornerShape(18.dp))
                .border(1.5.dp, Color(0x55FFFFFF), RoundedCornerShape(18.dp))
        ) {
            // Pálya háttér – csíkos fű + ragyogás
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                // Alap zöld gradiens
                drawRect(
                    brush = Brush.verticalGradient(
                        listOf(grassDark, grassMid, grassLight, grassMid, grassDark)
                    )
                )
                // Függőleges fűcsíkok
                val stripeCount = 10
                val stripeW = w / stripeCount
                for (i in 0 until stripeCount) {
                    if (i % 2 == 0) {
                        drawRect(
                            color = Color(0x14000000),
                            topLeft = Offset(i * stripeW, 0f),
                            size = Size(stripeW, h)
                        )
                    }
                }
                // Felső „stadion fény”
                drawRect(
                    brush = Brush.verticalGradient(
                        listOf(Color(0x33FFFFFF), Color.Transparent),
                        startY = 0f,
                        endY = h * 0.35f
                    )
                )
                val line = Color(0xEEFFFFFF)
                val stroke = 2.5.dp.toPx()
                // Keret
                drawRoundRect(
                    color = line,
                    topLeft = Offset(stroke, stroke),
                    size = Size(w - 2 * stroke, h - 2 * stroke),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(12.dp.toPx()),
                    style = Stroke(width = stroke)
                )
                // Félpálya
                drawLine(line, Offset(stroke, h / 2), Offset(w - stroke, h / 2), strokeWidth = stroke)
                // Középkör
                drawCircle(line, radius = w * 0.12f, center = Offset(w / 2, h / 2), style = Stroke(width = stroke))
                drawCircle(line, radius = 4.dp.toPx(), center = Offset(w / 2, h / 2))
                // Büntetőterületek
                val boxW = w * 0.55f
                val boxH = h * 0.16f
                val boxX = (w - boxW) / 2
                drawRect(line, Offset(boxX, stroke), Size(boxW, boxH), style = Stroke(width = stroke))
                drawRect(line, Offset(boxX, h - stroke - boxH), Size(boxW, boxH), style = Stroke(width = stroke))
                // Kapu területek
                val sixW = w * 0.28f
                val sixH = h * 0.07f
                val sixX = (w - sixW) / 2
                drawRect(line, Offset(sixX, stroke), Size(sixW, sixH), style = Stroke(width = stroke))
                drawRect(line, Offset(sixX, h - stroke - sixH), Size(sixW, sixH), style = Stroke(width = stroke))
                // Büntetőpontok
                drawCircle(line, radius = 3.dp.toPx(), center = Offset(w / 2, boxH * 0.7f))
                drawCircle(line, radius = 3.dp.toPx(), center = Offset(w / 2, h - boxH * 0.7f))
            }

            // Játékosok
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val pitchW = maxWidth
                val pitchH = maxHeight
                starters.forEachIndexed { index, player ->
                    val (xf, yf) = positions.getOrElse(index) { 0.5f to 0.5f }
                    val chipW = 64.dp
                    val chipH = 54.dp
                    val x = (pitchW * xf) - chipW / 2
                    val y = (pitchH * yf) - chipH / 2
                    // Kapustól előre: soronként késleltetett fade + scale
                    val appear = remember(animKey, index) { Animatable(0f) }
                    LaunchedEffect(animKey, index) {
                        appear.snapTo(0f)
                        // Kapus → csatár hullám; enyhe késleltetés
                        kotlinx.coroutines.delay(index * 16L)
                        appear.animateTo(
                            1f,
                            animationSpec = spring(
                                dampingRatio = 0.72f, // kis overshoot
                                stiffness = 380f
                            )
                        )
                    }
                    val a = appear.value
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .offset(
                                x = x.coerceIn(2.dp, (pitchW - chipW).coerceAtLeast(2.dp)),
                                y = y.coerceIn(2.dp, (pitchH - chipH).coerceAtLeast(2.dp))
                            )
                            .width(chipW)
                            .graphicsLayer {
                                alpha = a.coerceIn(0f, 1f)
                                val s = 0.72f + 0.28f * a
                                scaleX = s
                                scaleY = s
                                translationY = (1f - a) * 22f
                            }
                    ) {
                        // Mez + árnyék
                        Box(contentAlignment = Alignment.Center) {
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .offset(y = 2.dp)
                                    .clip(CircleShape)
                                    .background(Color(0x66000000))
                            )
                            Box(
                                modifier = Modifier
                                    .size(30.dp)
                                    .clip(CircleShape)
                                    .background(
                                        brush = Brush.verticalGradient(
                                            listOf(
                                                jerseyColor.copy(alpha = 1f),
                                                jerseyColor.copy(alpha = 0.75f)
                                            )
                                        )
                                    )
                                    .border(2.dp, Color.White, CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = jerseyNumber(player.number),
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.ExtraBold
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = shortName(player.name),
                            color = Color.White,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color(0xCC0A1628))
                                .clickable { onPlayerClick(player) }
                                .padding(horizontal = 4.dp, vertical = 1.dp)
                                .fillMaxWidth()
                        )
                    }
                }
            }
        }

        if (bench.isNotEmpty()) {
            Spacer(modifier = Modifier.height(10.dp))
            Text("Pad", color = sub, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            Spacer(modifier = Modifier.height(6.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0x331A2D4D))
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                bench.chunked(2).forEach { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        row.forEach { p ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color(0x221A2D4D))
                                    .clickable { onPlayerClick(p) }
                                    .padding(horizontal = 8.dp, vertical = 6.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(22.dp)
                                        .clip(CircleShape)
                                        .background(jerseyColor.copy(alpha = 0.7f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        jerseyNumber(p.number),
                                        color = Color.White,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    buildString {
                                        append(jerseyNumber(p.number))
                                        val pos = (p.position ?: "").uppercase().trim()
                                        if (pos.isNotEmpty()) append(" · $pos")
                                        append(" · ")
                                        append(shortName(p.name))
                                    },
                                    color = text,
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        if (row.size == 1) Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

/** Játékosnév rövidítése a pályára (vezetéknév / utolsó tag). */
private fun shortName(name: String?): String {
    if (name.isNullOrBlank()) return "—"
    val parts = name.trim().split(Regex("""\s+""")).filter { it.isNotBlank() }
    if (parts.isEmpty()) return "—"
    val last = parts.last()
    return if (last.length <= 10) last else last.take(9) + "…"
}

private fun formationPitchPositions(formation: String, count: Int): List<Pair<Float, Float>> {
    val rows = formation
        .split("-", "–", "—")
        .mapNotNull { it.trim().toIntOrNull() }
        .filter { it > 0 }

    // Alap 4-3-3 ha nincs formáció
    val structure = if (rows.isEmpty()) listOf(1, 4, 3, 3) else listOf(1) + rows
    // structure[0] = GK

    val totalSlots = structure.sum().coerceAtLeast(count)
    val result = mutableListOf<Pair<Float, Float>>()

    // y sávok: kapus közel a felső vonalhoz, támadók lejjebb (egy félpálya)
    val bandCount = structure.size
    structure.forEachIndexed { rowIdx, nInRow ->
        val y = 0.08f + (rowIdx.toFloat() / (bandCount - 1).coerceAtLeast(1)) * 0.82f
        for (i in 0 until nInRow) {
            val x = if (nInRow == 1) 0.5f
            else 0.12f + (i.toFloat() / (nInRow - 1)) * 0.76f
            result.add(x to y)
        }
    }

    // Ha több játékos van mint slot, középre pakoljuk
    while (result.size < count) {
        result.add(0.5f to 0.5f)
    }
    return result.take(count)
}

@Composable
private fun VideosTab(
    videos: List<HighlightVideo>,
    text: Color,
    sub: Color,
    card: Color,
    onVideoClick: (HighlightVideo) -> Unit
) {
    LazyColumn(modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (videos.isEmpty()) {
            item { Text("Nincs videó.", color = sub, fontSize = 13.sp) }
        } else {
            items(videos) { v ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(card)
                        .clickable { onVideoClick(v) }
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("🎥", fontSize = 18.sp)
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = v.title ?: "Videó",
                            color = text,
                            fontSize = 13.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        v.category?.let {
                            Text(it, color = sub, fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StandingsTab(
    standings: List<StandingTeam>,
    text: Color,
    sub: Color,
    card: Color,
    homeTeam: String,
    awayTeam: String,
    accent: Color
) {
    LazyColumn(modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp)
    ) {
        if (standings.isEmpty()) {
            item { Text("Nincs tabella.", color = sub, fontSize = 13.sp) }
        } else {
            item {
                Text(
                    "Zöld sáv: top 4  ·  Piros sáv: kieső zóna (utolsó 3)  ·  W-D-L szezonforma",
                    color = sub,
                    fontSize = 10.sp,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text("#", color = sub, fontSize = 11.sp, modifier = Modifier.width(28.dp))
                    Text("Csapat", color = sub, fontSize = 11.sp, modifier = Modifier.weight(1f))
                    Text("M", color = sub, fontSize = 11.sp, modifier = Modifier.width(28.dp), textAlign = TextAlign.End)
                    Text("P", color = sub, fontSize = 11.sp, modifier = Modifier.width(32.dp), textAlign = TextAlign.End)
                }
            }
            items(standings) { row ->
                val highlight = row.team.contains(homeTeam, true) || row.team.contains(awayTeam, true)
                    || homeTeam.contains(row.team, true) || awayTeam.contains(row.team, true)
                val n = standings.size
                val zoneColor = when {
                    row.position in 1..4 -> Color(0xFF00C853).copy(alpha = 0.18f) // top / EL
                    n >= 18 && row.position > n - 3 -> Color(0xFFE53935).copy(alpha = 0.18f) // kieső
                    else -> if (highlight) accent.copy(alpha = 0.15f) else card
                }
                val posColor = when {
                    row.position in 1..4 -> Color(0xFF00E676)
                    n >= 18 && row.position > n - 3 -> Color(0xFFFF5252)
                    else -> text
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(zoneColor)
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("${row.position}", color = posColor, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(28.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(row.team, color = text, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "Forma: ${row.wins}W-${row.draws}D-${row.losses}L · GK: ${row.goalDifference}",
                            color = sub,
                            fontSize = 10.sp,
                            maxLines = 1
                        )
                    }
                    Text("${row.played}", color = sub, fontSize = 12.sp, modifier = Modifier.width(28.dp), textAlign = TextAlign.End)
                    Text("${row.points}", color = text, fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.width(32.dp), textAlign = TextAlign.End)
                }
            }
        }
    }
}
