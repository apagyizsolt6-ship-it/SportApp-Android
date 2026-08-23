package com.sportapp.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sportapp.models.MatchResponse

@Composable
fun MatchScreen(viewModel: MatchViewModel = viewModel()) {
    val matches by viewModel.matches.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text(
            text = "Élő & Közelgő Meccsek",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(matches) { match ->
                    MatchItemCard(match)
                }
            }
        }
    }
}

@Composable
fun MatchItemCard(match: MatchResponse) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(text = "${match.homeTeam} vs ${match.awayTeam}", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = if (match.homeScore != null) "${match.homeScore} - ${match.awayScore}" else "VS",
                    style = MaterialTheme.typography.titleLarge
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(text = "Perc: ${match.minute ?: 0}'", color = Color.Gray)
                if (match.isValueBet == true) {
                    Text(text = "🔥 Value Bet!", color = Color(0xFF2E7D32))
                }
            }

            match.highlightUrl?.let { url ->
                Spacer(modifier = Modifier.height(8.dp))
                Button(onClick = { /* Itt nyitjuk majd meg a videót */ }) {
                    Text("Összefoglaló Videó 🎥")
                }
            }
        }
    }
}
