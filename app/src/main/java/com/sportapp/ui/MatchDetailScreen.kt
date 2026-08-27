package com.sportapp.ui

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.sportapp.api.RetrofitInstance
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
    onVideoClick: (HighlightVideo) -> Unit
) {
    val bg = if (isDarkMode) Color(0xFF101214) else Color(0xFFF4F6F8)
    val card = if (isDarkMode) Color(0xFF1A1D21) else Color.White
    val text = if (isDarkMode) Color(0xFFE8EAED) else Color(0xFF1A1D21)
    val sub = if (isDarkMode) Color(0xFF9AA0A6) else Color(0xFF5F6368)
    val green = Color(0xFF00E676)
    val accent = Color(0xFF2979FF)

    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Összefoglaló", "Események", "Statisztika", "Összeállítás", "Videók", "Tabella")

    var detail by remember { mutableStateOf(match) }
    var events by remember { mutableStateOf(match.events.orEmpty()) }
    var stats by remember { mutableStateOf<List<StatItem>>(emptyList()) }
    var lineups by remember { mutableStateOf<LineupsResponse?>(null) }
    var videos by remember { mutableStateOf<List<HighlightVideo>>(emptyList()) }
    var standings by remember { mutableStateOf<List<StandingTeam>>(emptyList()) }
    var loadingTab by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    val scope = rememberCoroutineScope()
    val hlId = match.highlightMatchId?.trim().orEmpty()

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
            } catch (_: Exception) {
                // hálózati hiba: megtartjuk az utolsó ismert adatot
            } finally {
                isRefreshing = false
            }

            // Ha a meccs már vége, ne spammeljük feleslegesen az API-t
            val finished = detail.status == "FT" || detail.status == "info" || detail.status == "error"
            if (finished) break

            delay(20_000L)
        }
    }

    // Élő meccs + Statisztika tab: stats is 20 mp-enként
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
            delay(20_000L)
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
                        if (hlId.isNotBlank()) {
                            val r = RetrofitInstance.api.getMatchStatistics(hlId)
                            stats = r.items.orEmpty()
                            if (stats.isEmpty()) errorMsg = "Nincs elérhető statisztika."
                        } else {
                            errorMsg = "Ehhez a meccshez nincs Highlightly azonosító."
                        }
                    }
                    3 -> { // lineups
                        if (hlId.isNotBlank() && lineups == null) {
                            lineups = RetrofitInstance.api.getMatchLineups(hlId)
                            if (lineups?.available != true) {
                                errorMsg = "Az összeállítás még nem elérhető."
                            }
                        } else if (hlId.isBlank()) {
                            errorMsg = "Ehhez a meccshez nincs Highlightly azonosító."
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
                        val lid = match.leagueId.trim()
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
            modifier = Modifier
                .fillMaxSize()
                .background(bg),
            color = bg
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(card)
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
                            Spacer(Modifier.width(6.dp))
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
                            Spacer(Modifier.width(6.dp))
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

                when (selectedTab) {
                    0 -> SummaryTab(
                        match = detail,
                        events = events,
                        text = text,
                        sub = sub,
                        card = card,
                        green = green
                    )
                    1 -> EventsTab(
                        events = events,
                        homeTeam = match.homeTeam,
                        awayTeam = match.awayTeam,
                        text = text,
                        sub = sub,
                        card = card,
                        green = green
                    )
                    2 -> StatsTab(stats = stats, text = text, sub = sub, card = card, accent = accent)
                    3 -> LineupsTab(lineups = lineups, text = text, sub = sub, card = card)
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
                        homeTeam = match.homeTeam,
                        awayTeam = match.awayTeam,
                        accent = accent
                    )
                }
            }
        }
    }
}


@Composable
private fun DetailTeamLogo(
    url: String?,
    teamName: String,
    size: androidx.compose.ui.unit.Dp = 48.dp
) {
    val initials = teamName.trim().split(Regex("\s+"))
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
                contentDescription = "$teamName logo",
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
    val isLive = match.status != "FT" && (match.minute ?: 0) > 0
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
                Spacer(Modifier.height(6.dp))
                Text(
                    text = match.homeTeam,
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
                Spacer(Modifier.height(4.dp))
                if (isLive) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(green)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = "${match.minute}'",
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
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "VALUE BET",
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
                Spacer(Modifier.height(6.dp))
                Text(
                    text = match.awayTeam,
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

private fun statusLabel(status: String): String = when (status) {
    "FT" -> "Vége"
    "HT" -> "Félidő"
    "1H" -> "1. Félidő"
    "2H" -> "2. Félidő"
    "NS" -> "Kezdés előtt"
    else -> status
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
    text: Color,
    sub: Color,
    card: Color,
    green: Color
) {
    val goals = events.filter {
        val t = it.type?.lowercase().orEmpty()
        t.contains("goal") || t == "penalty"
    }
    LazyColumn(modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Text("Góllövők", color = sub, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }
        if (goals.isEmpty()) {
            item {
                Text("Még nincs gól.", color = sub, fontSize = 13.sp)
            }
        } else {
            items(goals) { ev ->
                EventRow(ev, text, sub, card, isHome = ev.team == "home")
            }
        }
        item {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Liga: ${match.league.orEmpty()}",
                color = sub,
                fontSize = 12.sp
            )
        }
    }
}

@Composable
private fun EventsTab(
    events: List<MatchEvent>,
    homeTeam: String,
    awayTeam: String,
    text: Color,
    sub: Color,
    card: Color,
    green: Color
) {
    LazyColumn(modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        item {
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(homeTeam, color = sub, fontSize = 11.sp, modifier = Modifier.weight(1f), maxLines = 1)
                Text(awayTeam, color = sub, fontSize = 11.sp, modifier = Modifier.weight(1f), textAlign = TextAlign.End, maxLines = 1)
            }
            Spacer(Modifier.height(8.dp))
        }
        if (events.isEmpty()) {
            item {
                Text("Nincs esemény adat.", color = sub, fontSize = 13.sp)
            }
        } else {
            items(events) { ev ->
                EventRow(ev, text, sub, card, isHome = ev.team == "home")
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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(card)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isHome) {
            Text(label, color = text, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Text(minute, color = sub, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
        } else {
            Spacer(Modifier.weight(1f))
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
        if (stats.isEmpty()) {
            item { Text("Nincs statisztika.", color = sub, fontSize = 13.sp) }
        } else {
            items(stats) { s ->
                val homeVal = s.home?.toString() ?: "-"
                val awayVal = s.away?.toString() ?: "-"
                Column(modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(card)
                        .padding(12.dp)
                ) {
                    Text(s.name.orEmpty(), color = sub, fontSize = 11.sp)
                    Spacer(Modifier.height(4.dp))
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
                        Spacer(Modifier.height(6.dp))
                        val ratio = (h / (h + a)).coerceIn(0f, 1f)
                        LinearProgressIndicator(
                            progress = ratio,
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

@Composable
private fun LineupsTab(
    lineups: LineupsResponse?,
    text: Color,
    sub: Color,
    card: Color
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        if (lineups == null || lineups.available != true) {
            item { Text("Összeállítás nem elérhető.", color = sub, fontSize = 13.sp) }
        } else {
            item {
                PitchLineupCard(
                    title = "Hazai",
                    side = lineups.home,
                    jerseyColor = Color(0xFF1E88E5),
                    text = text,
                    sub = sub
                )
            }
            item {
                PitchLineupCard(
                    title = "Vendég",
                    side = lineups.away,
                    jerseyColor = Color(0xFFE53935),
                    text = text,
                    sub = sub
                )
            }
        }
    }
}

/** Modern pálya + kezdő 11 elrendezés formáció alapján. */
@Composable
private fun PitchLineupCard(
    title: String,
    side: com.sportapp.models.LineupSide?,
    jerseyColor: Color,
    text: Color,
    sub: Color
) {
    val starters = side?.players?.filter { it.isBench != true }.orEmpty()
    val bench = side?.players?.filter { it.isBench == true }.orEmpty()
    val formation = side?.formation?.trim().orEmpty()
    val positions = remember(formation, starters.size) {
        formationPitchPositions(formation, starters.size.coerceAtLeast(1))
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "${side?.teamName ?: title}${if (formation.isNotBlank()) " · $formation" else ""}",
            color = text,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        // --- Modern pálya ---
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(340.dp)
                .clip(RoundedCornerShape(14.dp))
                .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(14.dp))
        ) {
            // Csíkos zöld pálya + fehér vonalak (Canvas)
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height

                // Alap gradiens zöld
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFF1B5E20),
                            Color(0xFF2E7D32),
                            Color(0xFF1B5E20)
                        )
                    )
                )

                // Fűcsíkok (modern stadion hatás)
                val stripeCount = 10
                val stripeH = h / stripeCount
                for (i in 0 until stripeCount) {
                    if (i % 2 == 0) {
                        drawRect(
                            color = Color(0x14000000),
                            topLeft = Offset(0f, i * stripeH),
                            size = Size(w, stripeH)
                        )
                    }
                }

                val line = Color(0xE6FFFFFF)
                val stroke = 2.5f

                // Külső keret
                drawRect(
                    color = line,
                    style = Stroke(width = stroke)
                )

                // Félpálya vonal
                drawLine(
                    color = line,
                    start = Offset(0f, h / 2),
                    end = Offset(w, h / 2),
                    strokeWidth = stroke
                )

                // Középkör
                val midR = w * 0.14f
                drawCircle(
                    color = line,
                    radius = midR,
                    center = Offset(w / 2, h / 2),
                    style = Stroke(width = stroke)
                )
                drawCircle(
                    color = line,
                    radius = 4f,
                    center = Offset(w / 2, h / 2)
                )

                // Büntetőterületek (felső / alsó)
                fun penaltyBox(top: Boolean) {
                    val boxH = h * 0.18f
                    val boxW = w * 0.62f
                    val left = (w - boxW) / 2
                    val topY = if (top) 0f else h - boxH
                    drawRect(
                        color = line,
                        topLeft = Offset(left, topY),
                        size = Size(boxW, boxH),
                        style = Stroke(width = stroke)
                    )
                    // kapu előtti kisebb terület
                    val smallH = h * 0.08f
                    val smallW = w * 0.32f
                    val sLeft = (w - smallW) / 2
                    val sTop = if (top) 0f else h - smallH
                    drawRect(
                        color = line,
                        topLeft = Offset(sLeft, sTop),
                        size = Size(smallW, smallH),
                        style = Stroke(width = stroke)
                    )
                    // büntetőpont
                    val spotY = if (top) boxH * 0.65f else h - boxH * 0.65f
                    drawCircle(color = line, radius = 3.5f, center = Offset(w / 2, spotY))
                }
                penaltyBox(top = true)
                penaltyBox(top = false)

                // Sarokívek
                val cornerR = 18f
                // top-left
                drawArc(
                    color = line,
                    startAngle = 0f,
                    sweepAngle = 90f,
                    useCenter = false,
                    topLeft = Offset(-cornerR, -cornerR),
                    size = Size(cornerR * 2, cornerR * 2),
                    style = Stroke(width = stroke)
                )
                // top-right
                drawArc(
                    color = line,
                    startAngle = 90f,
                    sweepAngle = 90f,
                    useCenter = false,
                    topLeft = Offset(w - cornerR, -cornerR),
                    size = Size(cornerR * 2, cornerR * 2),
                    style = Stroke(width = stroke)
                )
                // bottom-left
                drawArc(
                    color = line,
                    startAngle = 270f,
                    sweepAngle = 90f,
                    useCenter = false,
                    topLeft = Offset(-cornerR, h - cornerR),
                    size = Size(cornerR * 2, cornerR * 2),
                    style = Stroke(width = stroke)
                )
                // bottom-right
                drawArc(
                    color = line,
                    startAngle = 180f,
                    sweepAngle = 90f,
                    useCenter = false,
                    topLeft = Offset(w - cornerR, h - cornerR),
                    size = Size(cornerR * 2, cornerR * 2),
                    style = Stroke(width = stroke)
                )
            }

            // Játékosok a pályán
            starters.forEachIndexed { index, player ->
                val pos = positions.getOrElse(index) { 0.5f to 0.5f }
                val xFrac = pos.first
                val yFrac = pos.second
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                    contentAlignment = Alignment.TopStart
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .fillMaxWidth(0.22f)
                            .align(Alignment.TopStart)
                            .offset(
                                x = ((xFrac * 1000).toInt() * 0.001f).let {
                                    // relative placement via BoxWithConstraints would be better
                                    0.dp
                                },
                                y = 0.dp
                            )
                    ) { }
                }
            }

            // Pozíciók BoxWithConstraints-szel
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val pitchW = maxWidth
                val pitchH = maxHeight
                starters.forEachIndexed { index, player ->
                    val (xf, yf) = positions.getOrElse(index) { 0.5f to 0.5f }
                    val chipW = 56.dp
                    val chipH = 48.dp
                    val x = (pitchW * xf) - chipW / 2
                    val y = (pitchH * yf) - chipH / 2
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .offset(
                                x = x.coerceIn(0.dp, (pitchW - chipW).coerceAtLeast(0.dp)),
                                y = y.coerceIn(0.dp, (pitchH - chipH).coerceAtLeast(0.dp))
                            )
                            .width(chipW)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(jerseyColor)
                                .border(1.5.dp, Color.White, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = player.number?.toString() ?: "·",
                                color = Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Text(
                            text = shortName(player.name),
                            color = Color.White,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .padding(top = 2.dp)
                                .background(
                                    Color(0x99000000),
                                    RoundedCornerShape(3.dp)
                                )
                                .padding(horizontal = 3.dp, vertical = 1.dp)
                        )
                    }
                }
            }
        }

        // Pad lista
        if (bench.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text("Pad", color = sub, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            bench.forEach { p ->
                PlayerLine(p, text, sub)
            }
        }
    }
}

@Composable
private fun PlayerLine(p: LineupPlayer, text: Color, sub: Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = p.number?.toString() ?: "-",
            color = sub,
            fontSize = 12.sp,
            modifier = Modifier.width(28.dp)
        )
        Text(
            text = p.name.orEmpty(),
            color = text,
            fontSize = 13.sp,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = p.position.orEmpty(),
            color = sub,
            fontSize = 11.sp
        )
    }
}

/** Rövid név a pályára (vezetéknév). */
private fun shortName(name: String?): String {
    if (name.isNullOrBlank()) return "—"
    val parts = name.trim().split(Regex("\s+"))
    return parts.last().take(10)
}

/**
 * Formáció → (x, y) arányok a pályán.
 * y: 0 = kapu (felső), 1 = támadók (alsó) — egy csapat a pálya alsó felén.
 * A kezdő 11: [0]=kapus, utána védők → középpálya → támadók.
 */
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
                    Spacer(Modifier.width(10.dp))
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
            items(standings) { t ->
                val highlight = t.team.contains(homeTeam, true) || t.team.contains(awayTeam, true)
                    || homeTeam.contains(t.team, true) || awayTeam.contains(t.team, true)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (highlight) accent.copy(alpha = 0.15f) else card)
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("${t.position}", color = text, fontSize = 13.sp, modifier = Modifier.width(28.dp))
                    Text(t.team, color = text, fontSize = 13.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${t.played}", color = sub, fontSize = 12.sp, modifier = Modifier.width(28.dp), textAlign = TextAlign.End)
                    Text("${t.points}", color = text, fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.width(32.dp), textAlign = TextAlign.End)
                }
            }
        }
    }
}
