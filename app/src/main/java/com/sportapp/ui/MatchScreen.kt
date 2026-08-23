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

@Composable
fun MatchScreen(viewModel: MatchViewModel = viewModel()) {
    val matches by viewModel.matches.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    var isDarkMode by remember { mutableStateOf(true) }
    var selectedTab by remember { mutableIntStateOf(0) }
    var selectedVideoUrl by remember { mutableStateOf<String?>(null) }

    // Prémium Színpaletta (Sötét / Világos)
    val bgColor by animateColorAsState(if (isDarkMode) Color(0xFF101214) else Color(0xFFF4F6F8), label = "bg")
    val cardBgColor by animateColorAsState(if (isDarkMode) Color(0xFF1A1D21) else Color(0xFFFFFFFF), label = "card")
    val headerBgColor by animateColorAsState(if (isDarkMode) Color(0xFF16181C) else Color(0xFFFFFFFF), label = "header")
    val leagueBgColor by animateColorAsState(if (isDarkMode) Color(0xFF22262C) else Color(0xFFE9ECEF), label = "league")
    val textColor by animateColorAsState(if (isDarkMode) Color(0xFFFFFFFF) else Color(0xFF101214), label = "text")
    val subTextColor by animateColorAsState(if (isDarkMode) Color(0xFF8C939D) else Color(0xFF6C757D), label = "subtext")
    val primaryGreen = Color(0xFF00E676)

    val filteredMatches = remember(matches, selectedTab) {
        when (selectedTab) {
            1 -> matches.filter { it.status != "FT" && (it.minute ?: 0) > 0 }
            2 -> matches.filter { it.isValueBet == true }
            else -> matches
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
            // Címsor & Témaváltó Gomb
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(headerBgColor)
                    .padding(horizontal = 20.dp, vertical = 16.dp)
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

            // Szűrő Fülek (Tabs)
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = headerBgColor,
                contentColor = primaryGreen,
                divider = { Divider(color = leagueBgColor, thickness = 1.dp) }
            ) {
                Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }) {
                    Text("ÖSSZES (${matches.size})", modifier = Modifier.padding(vertical = 12.dp), color = if(selectedTab == 0) primaryGreen else subTextColor, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
                Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }) {
                    Text("🔴 ÉLŐ", modifier = Modifier.padding(vertical = 12.dp), color = if(selectedTab == 1) primaryGreen else subTextColor, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
                Tab(selected = selectedTab == 2, onClick = { selectedTab = 2 }) {
                    Text("🔥 ÉRTÉKES", modifier = Modifier.padding(vertical = 12.dp), color = if(selectedTab == 2) Color(0xFFFFD600) else subTextColor, fontSize = 11.sp, fontWeight = FontWeight.Bold)
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
                            PremiumMatchRow(
                                match = match,
                                cardBgColor = cardBgColor,
                                textColor = textColor,
                                subTextColor = subTextColor,
                                primaryGreen = primaryGreen,
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
    cardBgColor: Color,
    textColor: Color,
    subTextColor: Color,
    primaryGreen: Color,
    onVideoClick: (String) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(cardBgColor)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Perc & Státusz oszlop magyarítva
        Column(
            modifier = Modifier.width(65.dp),
            horizontalAlignment = Alignment.Start
        ) {
            val isLive = match.status != "FT" && (match.minute ?: 0) > 0
            val statusText = when (match.status) {
                "FT" -> "Vége"
                "HT" -> "Félidő"
                "1H" -> "1. Félidő"
                "2H" -> "2. Félidő"
                "NS" -> "Hamarosan"
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
                    Text(text = "${match.minute}'", color = primaryGreen, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            } else {
                Text(text = statusText, color = subTextColor, fontSize = 11.sp, fontWeight = FontWeight.Medium)
            }

            if (match.isValueBet == true) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(text = "🔥 ÉRTÉKES", color = Color(0xFFFFD600), fontSize = 8.sp, fontWeight = FontWeight.Black)
            }
        }

        // Csapatnevek
        Column(modifier = Modifier.weight(1f)) {
            Text(text = match.homeTeam, color = textColor, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(3.dp))
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
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text = "${match.awayScore ?: 0}",
                color = if (match.awayScore != null) primaryGreen else subTextColor,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )
        }

        // Videó gomb
        match.highlightUrl?.let { url ->
            Spacer(modifier = Modifier.width(12.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF2979FF))
                    .clickable { onVideoClick(url) }
                    .padding(horizontal = 8.dp, vertical = 6.dp)
            ) {
                Text(text = "🎥 Videó", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
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
            modifier = Modifier.fillMaxWidth().height(260.dp),
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
