package com.sportapp.ui

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
    var selectedTab by remember { mutableIntStateOf(0) } // 0: ÖSSZES, 1: ÉLŐ, 2: VALUE BET
    var selectedVideoUrl by remember { mutableStateOf<String?>(null) }

    val filteredMatches = remember(matches, selectedTab) {
        when (selectedTab) {
            1 -> matches.filter { it.status != "FT" && (it.minute ?: 0) > 0 }
            2 -> matches.filter { it.isValueBet == true }
            else -> matches
        }
    }

    val groupedMatches = remember(filteredMatches) {
        filteredMatches.groupBy { it.league ?: "Egyéb Bajnokság" }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color(0xFF121212)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Fejléc
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF1E1E1E))
                    .padding(16.dp)
            ) {
                Text(
                    text = "⚡ FlashScore MatchCenter",
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF00E676)
                    )
                )
            }

            // Flashscore-stílusú fülek
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = Color(0xFF1E1E1E),
                contentColor = Color(0xFF00E676)
            ) {
                Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }) {
                    Text("ÖSSZES (${matches.size})", modifier = Modifier.padding(12.dp), color = if(selectedTab == 0) Color(0xFF00E676) else Color.Gray, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
                Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }) {
                    Text("🔴 ÉLŐ", modifier = Modifier.padding(12.dp), color = if(selectedTab == 1) Color(0xFF00E676) else Color.Gray, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
                Tab(selected = selectedTab == 2, onClick = { selectedTab = 2 }) {
                    Text("🔥 VALUE BETS", modifier = Modifier.padding(12.dp), color = if(selectedTab == 2) Color(0xFFFFD600) else Color.Gray, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }

            if (isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Color(0xFF00E676))
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    groupedMatches.forEach { (leagueName, leagueMatches) ->
                        // Liga Sáv
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color(0xFF2A2A2A))
                                    .padding(horizontal = 16.dp, vertical = 8.dp)
                            ) {
                                Text(
                                    text = leagueName.uppercase(),
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        // Meccs Kártyák a ligán belül
                        items(leagueMatches) { match ->
                            MatchItemCard(match) { url ->
                                selectedVideoUrl = url
                            }
                            HorizontalDivider(color = Color(0xFF222222), thickness = 1.dp)
                        }
                    }
                }
            }
        }
    }

    selectedVideoUrl?.let { url ->
        VideoPlayerDialog(url = url) {
            selectedVideoUrl = null
        }
    }
}

@Composable
fun MatchItemCard(match: MatchResponse, onVideoClick: (String) -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${match.homeTeam} - ${match.awayTeam}",
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = if (match.homeScore != null) "${match.homeScore} : ${match.awayScore}" else "VS",
                    color = Color(0xFF00E676),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Black
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(text = "Perc: ${match.minute ?: 0}'", color = Color.Gray, fontSize = 12.sp)
                if (match.isValueBet == true) {
                    Text(text = "🔥 VALUE BET", color = Color(0xFFFFD600), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            }

            match.highlightUrl?.let { url ->
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = { onVideoClick(url) },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2979FF)),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text("Összefoglaló Lejátszása 🎥", color = Color.White, fontSize = 12.sp)
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
        onDispose {
            exoPlayer.release()
        }
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
