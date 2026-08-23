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
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.sportapp.models.MatchResponse

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MatchScreen(viewModel: MatchViewModel = viewModel()) {
    val matches by viewModel.matches.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    var isDarkMode by remember { mutableStateOf(true) }
    var selectedTab by remember { mutableIntStateOf(0) } // 0: ÖSSZES, 1: ÉLŐ, 2: ÉRTÉKES, 3: KEDVENCEK
    var searchQuery by remember { mutableStateOf("") }
    var favoriteIds by remember { mutableStateOf(setOf<String>()) }
    var selectedVideoUrl by remember { mutableStateOf<String?>(null) }

    val bgColor by animateColorAsState(if (isDarkMode) Color(0xFF101214) else Color(0xFFF4F6F8), label = "bg")
    val cardBgColor by animateColorAsState(if (isDarkMode) Color(0xFF1A1D21) else Color(0xFFFFFFFF), label = "card")
    val headerBgColor by animateColorAsState(if (isDarkMode) Color(0xFF16181C) else Color(0xFFFFFFFF), label = "header")
    val leagueBgColor by animateColorAsState(if (isDarkMode) Color(0xFF22262C) else Color(0xFFE9ECEF), label = "league")
    val textColor by animateColorAsState(if (isDarkMode) Color(0xFFFFFFFF) else Color(0xFF101214), label = "text")
    val subTextColor by animateColorAsState(if (isDarkMode) Color(0xFF8C939D) else Color(0xFF6C757D), label = "subtext")
    val primaryGreen = Color(0xFF00E676)

    val filteredMatches = remember(matches, selectedTab, searchQuery, favoriteIds) {
        matches.filter { match ->
            val matchesSearch = searchQuery.isEmpty() ||
                    match.homeTeam.contains(searchQuery, ignoreCase = true) ||
                    match.awayTeam.contains(searchQuery, ignoreCase = true) ||
                    (match.league ?: "").contains(searchQuery, ignoreCase = true)

            val matchesTab = when (selectedTab) {
                1 -> match.status != "FT" && (match.minute ?: 0) > 0
                2 -> match.isValueBet == true
                3 -> favoriteIds.contains(match.matchId)
                else -> true
            }

            matchesSearch && matchesTab
        }
    }

    val groupedMatches = remember(filteredMatches) {
        filteredMatches.groupBy { it.league ?: "EGYÉB BAJNOKSÁG" }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = bgColor
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Fejléc
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

            // Keresősáv (kompatibilis színbeállításokkal)
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

            // Szűrő Fülek
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = headerBgColor,
                contentColor = primaryGreen,
                divider = { Divider(color = leagueBgColor, thickness = 1.dp) }
            ) {
                Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }) {
                    Text("ÖSSZES", modifier = Modifier.padding(vertical = 10.dp), color = if(selectedTab == 0) primaryGreen else subTextColor, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
                Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }) {
                    Text("🔴 ÉLŐ", modifier = Modifier.padding(vertical = 10.dp), color = if(selectedTab == 1) primaryGreen else subTextColor, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
                Tab(selected = selectedTab == 2, onClick = { selectedTab = 2 }) {
                    Text("🔥 ÉRTÉKES", modifier = Modifier.padding(vertical = 10.dp), color = if(selectedTab == 2) Color(0xFFFFD600) else subTextColor, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
                Tab(selected = selectedTab == 3, onClick = { selectedTab = 3 }) {
                    Text("⭐ KEDVENC", modifier = Modifier.padding(vertical = 10.dp), color = if(selectedTab == 3) Color(0xFFFF9100) else subTextColor, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }

            if (isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = primaryGreen)
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    groupedMatches.forEach { (leagueName, leagueMatches) ->
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(leagueBgColor)
                                    .padding(horizontal = 16.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = leagueName.uppercase(),
                                    color = textColor,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    letterSpacing = 0.5.sp
                                )
                            }
                        }

                        items(leagueMatches) { match ->
                            val isFav = favoriteIds.contains(match.matchId)
                            PremiumMatchRow(
                                match = match,
                                isFavorite = isFav,
                                cardBgColor = cardBgColor,
                                textColor = textColor,
                                subTextColor = subTextColor,
                                primaryGreen = primaryGreen,
                                onFavoriteToggle = {
                                    favoriteIds = if (isFav) favoriteIds - match.matchId else favoriteIds + match.matchId
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
        // Kedvencek csillag
        Text(
            text = if (isFavorite) "★" else "☆",
            color = if (isFavorite) Color(0xFFFF9100) else subTextColor,
            fontSize = 18.sp,
            modifier = Modifier
                .clickable { onFavoriteToggle() }
                .padding(end = 8.dp)
        )

        // Perc & Státusz
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

            if (match.isValueBet == true) {
                Text(text = "🔥 ÉRTÉKES", color = Color(0xFFFFD600), fontSize = 7.sp, fontWeight = FontWeight.Black)
            }
        }

        // Csapatnevek
        Column(modifier = Modifier.weight(1f)) {
            Text(text = match.homeTeam, color = textColor, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(2.dp))
            Text(text = match.awayTeam, color = textColor, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }

        // Gólok
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

        // Videó gomb
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
