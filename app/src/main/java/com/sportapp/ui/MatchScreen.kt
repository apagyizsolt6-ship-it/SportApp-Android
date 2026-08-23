package com.sportapp.ui

import android.view.ViewGroup
import android.widget.FrameLayout
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
    var selectedVideoUrl by remember { mutableStateOf<String?>(null) }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color(0xFF121212)
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Text(
                text = "⚡ ÉLŐ MATCH CENTER",
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF00E676)
                ),
                modifier = Modifier.padding(bottom = 16.dp)
            )

            if (isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Color(0xFF00E676))
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(matches) { match ->
                        MatchItemCard(match) { url ->
                            selectedVideoUrl = url
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
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${match.homeTeam} - ${match.awayTeam}",
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = if (match.homeScore != null) "${match.homeScore} : ${match.awayScore}" else "VS",
                    color = Color(0xFF00E676),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Black
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

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
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = { onVideoClick(url) },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2979FF)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Összefoglaló Lejátszása 🎥", color = Color.White)
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
