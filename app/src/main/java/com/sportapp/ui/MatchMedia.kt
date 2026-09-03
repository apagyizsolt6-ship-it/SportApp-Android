package com.sportapp.ui

import android.net.Uri
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import com.sportapp.models.HighlightVideo
import com.sportapp.models.MatchResponse
import org.json.JSONArray
import org.json.JSONObject

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
internal fun MultimediaLinkRow(
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
internal fun HighlightVideoRow(
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

internal fun loadRecentMedia(
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

internal fun saveRecentMedia(
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
internal fun MediaHubList(
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
internal fun RecentMediaCard(
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
internal fun MediaMatchCard(
    match: MatchResponse,
    cardBgColor: Color,
    textColor: Color,
    subTextColor: Color,
    primaryGreen: Color,
    onClick: () -> Unit
) {
    val isLive = isMatchLive(match.status, match.minute)
    val hasHighlight = !match.highlightMatchId.isNullOrBlank()
    val liveMin = rememberLiveMinute(match.id, match.minute, match.status, isLive, pulseMs = 0L)

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
                text = "${match.homeTeam.orEmpty()} vs ${match.awayTeam.orEmpty()}",
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
                        text = "ÉLŐ ${liveMin}'",
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
