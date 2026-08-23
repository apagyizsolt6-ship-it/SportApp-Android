package com.sportapp.ui

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.compose.SubcomposeAsyncImage
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.sportapp.models.MatchResponse
import com.sportapp.api.RetrofitInstance
import com.sportapp.api.StandingTeam
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MatchScreen(viewModel: MatchViewModel = viewModel()) {
    val matches by viewModel.matches.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    var isDarkMode by remember { mutableStateOf(true) }
    var selectedTab by remember { mutableIntStateOf(0) }
    var searchQuery by remember { mutableStateOf("") }

    val context = LocalContext.current
    val favoritePrefs = remember(context) {
        context.getSharedPreferences(
            "match_screen_preferences",
            android.content.Context.MODE_PRIVATE
        )
    }

    var favoriteMatchIds by remember { mutableStateOf(setOf<String>()) }

    // Korlátlan számú kedvenc liga. A kiválasztás tartósan elmentésre kerül.
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

    var selectedVideoUrl by remember { mutableStateOf<String?>(null) }
    var selectedLeaguePair by remember { mutableStateOf<Pair<String, String>?>(null) }

    val bgColor by animateColorAsState(if (isDarkMode) Color(0xFF101214) else Color(0xFFF4F6F8), label = "bg")
    val cardBgColor by animateColorAsState(if (isDarkMode) Color(0xFF1A1D21) else Color(0xFFFFFFFF), label = "card")
    val headerBgColor by animateColorAsState(if (isDarkMode) Color(0xFF16181C) else Color(0xFFFFFFFF), label = "header")
    val leagueBgColor by animateColorAsState(if (isDarkMode) Color(0xFF22262C) else Color(0xFFE9ECEF), label = "league")
    val textColor by animateColorAsState(if (isDarkMode) Color(0xFFFFFFFF) else Color(0xFF101214), label = "text")
    val subTextColor by animateColorAsState(if (isDarkMode) Color(0xFF8C939D) else Color(0xFF6C757D), label = "subtext")
    val primaryGreen = Color(0xFF00E676)

    // Automatikusan kiemelt TOP 5 európai bajnokság.
    // Ezek mindig arany fejléccel jelennek meg.
    val topFiveLeagueNames = remember {
        setOf(
            "PREMIER LEAGUE",
            "LA LIGA",
            "SERIE A",
            "BUNDESLIGA",
            "LIGUE 1"
        )
    }

    val isTopFiveLeague: (String) -> Boolean = { leagueName ->
        val normalized = leagueName
            .trim()
            .uppercase()
            .replace("Á", "A")
            .replace("É", "E")
        topFiveLeagueNames.any { normalized.contains(it) }
    }

    val filteredMatches = remember(matches, selectedTab, searchQuery, favoriteMatchIds, favoriteLeagueNames) {
        matches.filter { match ->
            val leagueName = match.league ?: "EGYÉB BAJNOKSÁG"
            val isLeagueFav = favoriteLeagueNames.contains(leagueName)
            val isMatchFav = favoriteMatchIds.contains(match.id)

            val matchesSearch = searchQuery.isEmpty() ||
                    match.homeTeam.contains(searchQuery, ignoreCase = true) ||
                    match.awayTeam.contains(searchQuery, ignoreCase = true) ||
                    leagueName.contains(searchQuery, ignoreCase = true)

            val matchesTab = when (selectedTab) {
                1 -> match.status != "FT" && (match.minute ?: 0) > 0
                2 -> isMatchFav || isLeagueFav
                else -> true
            }

            matchesSearch && matchesTab
        }
    }

    val groupedMatchesList = remember(filteredMatches, favoriteLeagueNames) {
        val groups = filteredMatches.groupBy { it.league ?: "EGYÉB BAJNOKSÁG" }
        groups.entries.sortedWith(
            compareByDescending<Map.Entry<String, List<MatchResponse>>> { (leagueName, _) ->
                isTopFiveLeague(leagueName)
            }.thenByDescending { (leagueName, _) ->
                favoriteLeagueNames.contains(leagueName)
            }.thenBy { it.key.uppercase() }
        )
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = bgColor
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(headerBgColor)
                    .padding(horizontal = 20.dp, vertical = 14.dp)
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
                        onClick = { isDarkMode = !isDarkMode },
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(leagueBgColor)
                            .size(36.dp)
                    ) {
                        Text(text = if (isDarkMode) "☀️" else "🌙", fontSize = 16.sp)
                    }
                }
            }

            TextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Keresés csapatra vagy bajnokságra...", color = subTextColor, fontSize = 12.sp) },
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
                    .padding(horizontal = 12.dp, vertical = 4.dp)
                    .clip(RoundedCornerShape(8.dp))
            )

            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = headerBgColor,
                contentColor = primaryGreen,
                divider = { Divider(color = leagueBgColor, thickness = 1.dp) }
            ) {
                Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }) {
                    Text(
                        "ÖSSZES",
                        modifier = Modifier.padding(vertical = 10.dp),
                        color = if (selectedTab == 0) primaryGreen else subTextColor,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }) {
                    Text(
                        "🔴 ÉLŐ",
                        modifier = Modifier.padding(vertical = 10.dp),
                        color = if (selectedTab == 1) primaryGreen else subTextColor,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Tab(selected = selectedTab == 2, onClick = { selectedTab = 2 }) {
                    Text(
                        "⭐ KEDVENC",
                        modifier = Modifier.padding(vertical = 10.dp),
                        color = if (selectedTab == 2) Color(0xFFFF9100) else subTextColor,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            if (isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = primaryGreen)
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    groupedMatchesList.forEach { (leagueName, leagueMatches) ->
                        val isLeagueFav = favoriteLeagueNames.contains(leagueName)

                        item {
                            val isTopFive = isTopFiveLeague(leagueName)
                            val isUserHighlighted = favoriteLeagueNames.contains(leagueName)

                            val leagueHeaderColor = when {
                                isTopFive && isDarkMode -> Color(0xFF5A4700)
                                isTopFive -> Color(0xFFFFE8A3)
                                isUserHighlighted && isDarkMode -> Color(0xFF3B3020)
                                isUserHighlighted -> Color(0xFFFFF2D2)
                                else -> leagueBgColor
                            }

                            val leagueHeaderTextColor = if (isTopFive) {
                                if (isDarkMode) Color(0xFFFFD54F) else Color(0xFF6D4C00)
                            } else {
                                textColor
                            }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(leagueHeaderColor)
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    // TOP 5 automatikusan kiemelt.
                                    // Minden más liga szabadon kiemelhető és visszavonható.
                                    Text(
                                        text = if (isTopFive || isLeagueFav) "★" else "☆",
                                        color = when {
                                            isTopFive -> Color(0xFFFFB300)
                                            isLeagueFav -> Color(0xFFFF9100)
                                            else -> subTextColor
                                        },
                                        fontSize = 15.sp,
                                        modifier = Modifier
                                            .then(
                                                if (!isTopFive) {
                                                    Modifier.clickable {
                                                        favoriteLeagueNames = if (isLeagueFav) {
                                                            favoriteLeagueNames.filter { it != leagueName }.toSet()
                                                        } else {
                                                            favoriteLeagueNames + leagueName
                                                        }
                                                    }
                                                } else {
                                                    Modifier
                                                }
                                            )
                                            .padding(end = 6.dp)
                                    )

                                    val firstLeagueMatch = leagueMatches.firstOrNull()

                                    Text(
                                        text = countryFlag(firstLeagueMatch?.countryCode),
                                        fontSize = 14.sp,
                                        modifier = Modifier.padding(end = 6.dp)
                                    )

                                    firstLeagueMatch?.let { leagueMatch ->
                                        if (!leagueMatch.leagueLogoUrl.isNullOrBlank()) {
                                            AsyncImage(
                                                model = leagueMatch.leagueLogoUrl,
                                                contentDescription = "$leagueName logó",
                                                modifier = Modifier
                                                    .size(18.dp)
                                                    .clip(RoundedCornerShape(4.dp))
                                            )
                                            Spacer(modifier = Modifier.width(5.dp))
                                        }
                                    }

                                    Text(
                                        text = leagueName.uppercase(),
                                        color = leagueHeaderTextColor,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        letterSpacing = 0.5.sp
                                    )
                                }

                                Text(
                                    text = "📊 TABELLA ➔",
                                    color = primaryGreen,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.clickable {
                                        val realLeagueId = leagueMatches
                                            .firstOrNull()
                                            ?.leagueId
                                            ?.takeIf { it.isNotBlank() }
                                            ?: leagueName

                                        selectedLeaguePair = Pair(realLeagueId, leagueName)
                                    }
                                )
                            }
                        }

                        items(leagueMatches) { match ->
                            val isFav = favoriteMatchIds.contains(match.id)
                            PremiumMatchRow(
                                match = match,
                                isFavorite = isFav,
                                cardBgColor = cardBgColor,
                                textColor = textColor,
                                subTextColor = subTextColor,
                                primaryGreen = primaryGreen,
                                onFavoriteToggle = {
                                    favoriteMatchIds = if (isFav) {
                                        favoriteMatchIds.filter { it != match.id }.toSet()
                                    } else {
                                        favoriteMatchIds + match.id
                                    }
                                },
                                onVideoClick = { url -> selectedVideoUrl = url }
                            )
                            Divider(color = leagueBgColor, thickness = 1.dp)
                        }
                    }
                }
            }
        }
    }

    selectedVideoUrl?.let { url ->
        VideoPlayerDialog(url = url) { selectedVideoUrl = null }
    }

    selectedLeaguePair?.let { (leagueId, leagueName) ->
        FullLeagueTableDialog(leagueId = leagueId, leagueName = leagueName, isDarkMode = isDarkMode) {
            selectedLeaguePair = null
        }
    }
}

private fun countryFlag(countryCode: String?): String {
    val code = countryCode?.trim()?.lowercase().orEmpty()
    if (code.length != 2) return "🌐"

    val first = code[0]
    val second = code[1]
    if (first !in 'a'..'z' || second !in 'a'..'z') return "🌐"

    return buildString {
        appendCodePoint(0x1F1E6 + (first - 'a'))
        appendCodePoint(0x1F1E6 + (second - 'a'))
    }
}

@Composable
private fun TeamLogo(
    url: String?,
    teamName: String,
    size: androidx.compose.ui.unit.Dp = 26.dp
) {
    val initials = teamName
        .trim()
        .split(Regex("\\s+"))
        .filter { it.isNotBlank() }
        .take(2)
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
                contentDescription = "$teamName logó",
                modifier = Modifier.fillMaxSize(),
                loading = {
                    Text(
                        text = initials,
                        color = Color(0xFF8C939D),
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold
                    )
                },
                error = {
                    Text(
                        text = initials,
                        color = Color(0xFF8C939D),
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            )
        } else {
            Text(
                text = initials,
                color = Color(0xFF8C939D),
                fontSize = 8.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
fun PremiumMatchRow(
    match: MatchResponse,
    isFavorite: Boolean,
    cardBgColor: Color,
    textColor: Color,
    subTextColor: Color,
    primaryGreen: Color,
    onFavoriteToggle: () -> Unit,
    onVideoClick: (String) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(cardBgColor)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = if (isFavorite) "★" else "☆",
            color = if (isFavorite) Color(0xFFFF9100) else subTextColor,
            fontSize = 18.sp,
            modifier = Modifier
                .clickable { onFavoriteToggle() }
                .padding(end = 8.dp)
        )

        Column(
            modifier = Modifier.width(55.dp),
            horizontalAlignment = Alignment.Start
        ) {
            val isLive = match.status != "FT" && (match.minute ?: 0) > 0
            val statusText = when (match.status) {
                "FT" -> "Vége"
                "HT" -> "Félidő"
                "1H" -> "1. Félidő"
                "2H" -> "2. Félidő"
                "NS" -> "Kezdés"
                else -> match.status
            }

            if (isLive) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(primaryGreen)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(text = "${match.minute}'", color = primaryGreen, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            } else {
                Text(text = statusText, color = subTextColor, fontSize = 10.sp, fontWeight = FontWeight.Medium)
            }

        }

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TeamLogo(
                    url = match.homeLogoUrl,
                    teamName = match.homeTeam
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = match.homeTeam,
                    color = textColor,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                TeamLogo(
                    url = match.awayLogoUrl,
                    teamName = match.awayTeam
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = match.awayTeam,
                    color = textColor,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                )
            }
        }

        Column(horizontalAlignment = Alignment.End) {
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

        match.highlightUrl?.let { url ->
            Spacer(modifier = Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFF2979FF))
                    .clickable { onVideoClick(url) }
                    .padding(horizontal = 6.dp, vertical = 4.dp)
            ) {
                Text(text = "🎥", color = Color.White, fontSize = 11.sp)
            }
        }
    }
}

@Composable
fun FullLeagueTableDialog(leagueId: String, leagueName: String, isDarkMode: Boolean, onDismiss: () -> Unit) {
    val dialogBg = if (isDarkMode) Color(0xFF1A1D21) else Color(0xFFFFFFFF)
    val textColor = if (isDarkMode) Color.White else Color.Black
    val coroutineScope = rememberCoroutineScope()
    var standings by remember { mutableStateOf<List<StandingTeam>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(leagueId) {
        coroutineScope.launch {
            try {
                val api = RetrofitInstance.api
                standings = api.getStandings(leagueId)
            } catch (e: Exception) {
                standings = emptyList()
            } finally {
                isLoading = false
            }
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = dialogBg)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "📊 $leagueName Tabella",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF00E676)
                )
                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("#", fontWeight = FontWeight.Bold, color = Color.Gray, fontSize = 10.sp, modifier = Modifier.width(20.dp))
                    Text("CSAPAT", fontWeight = FontWeight.Bold, color = Color.Gray, fontSize = 10.sp, modifier = Modifier.weight(1f))
                    Text("M", fontWeight = FontWeight.Bold, color = Color.Gray, fontSize = 10.sp, modifier = Modifier.width(22.dp))
                    Text("GY", fontWeight = FontWeight.Bold, color = Color.Gray, fontSize = 10.sp, modifier = Modifier.width(22.dp))
                    Text("D", fontWeight = FontWeight.Bold, color = Color.Gray, fontSize = 10.sp, modifier = Modifier.width(22.dp))
                    Text("V", fontWeight = FontWeight.Bold, color = Color.Gray, fontSize = 10.sp, modifier = Modifier.width(22.dp))
                    Text("GÓL", fontWeight = FontWeight.Bold, color = Color.Gray, fontSize = 10.sp, modifier = Modifier.width(36.dp))
                    Text("P", fontWeight = FontWeight.Bold, color = Color(0xFF00E676), fontSize = 10.sp, modifier = Modifier.width(24.dp))
                }
                Divider(color = Color.Gray, thickness = 1.dp)

                if (isLoading) {
                    Box(modifier = Modifier.fillMaxWidth().height(150.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Color(0xFF00E676))
                    }
                } else if (standings.isEmpty()) {
                    Text("A tabella jelenleg nem érhető el ehhez a ligához.", color = Color.Gray, fontSize = 11.sp, modifier = Modifier.padding(vertical = 16.dp))
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 350.dp)) {
                        items(standings) { item ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("${item.position}.", color = textColor, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(20.dp))
                                Text(item.team, color = textColor, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                Text("${item.played}", color = textColor, fontSize = 10.sp, modifier = Modifier.width(22.dp))
                                Text("${item.wins}", color = textColor, fontSize = 10.sp, modifier = Modifier.width(22.dp))
                                Text("${item.draws}", color = textColor, fontSize = 10.sp, modifier = Modifier.width(22.dp))
                                Text("${item.losses}", color = textColor, fontSize = 10.sp, modifier = Modifier.width(22.dp))
                                Text("${item.goalsScored}:${item.goalsAllowed}", color = textColor, fontSize = 10.sp, modifier = Modifier.width(36.dp))
                                Text("${item.points}", color = Color(0xFF00E676), fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(24.dp))
                            }
                            Divider(color = Color(0xFF2B2B2B), thickness = 0.5.dp)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E676)),
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text("Bezárás", color = Color.Black, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
fun VideoPlayerDialog(url: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(url))
            prepare()
            playWhenReady = true
        }
    }

    DisposableEffect(Unit) {
        onDispose { exoPlayer.release() }
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(260.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        player = exoPlayer
                        layoutParams = FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}
