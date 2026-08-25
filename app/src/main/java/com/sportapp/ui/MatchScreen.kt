package com.sportapp.ui

import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
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
import com.sportapp.models.MatchResponse
import com.sportapp.models.HighlightVideo
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

    var selectedVideo by remember { mutableStateOf<HighlightVideo?>(null) }
    var highlightVideos by remember { mutableStateOf<List<HighlightVideo>>(emptyList()) }
    var showHighlightPicker by remember { mutableStateOf(false) }
    var isHighlightLoading by remember { mutableStateOf(false) }
    var highlightError by remember { mutableStateOf<String?>(null) }
    var selectedLeaguePair by remember { mutableStateOf<Pair<String, String>?>(null) }

    val coroutineScope = rememberCoroutineScope()

    val bgColor by animateColorAsState(
        if (isDarkMode) Color(0xFF101214) else Color(0xFFF4F6F8),
        label = "bg"
    )

    val cardBgColor by animateColorAsState(
        if (isDarkMode) Color(0xFF1A1D21) else Color(0xFFFFFFFF),
        label = "card"
    )

    val headerBgColor by animateColorAsState(
        if (isDarkMode) Color(0xFF16181C) else Color(0xFFFFFFFF),
        label = "header"
    )

    val leagueBgColor by animateColorAsState(
        if (isDarkMode) Color(0xFF22262C) else Color(0xFFE9ECEF),
        label = "league"
    )

    val textColor by animateColorAsState(
        if (isDarkMode) Color(0xFFFFFFFF) else Color(0xFF101214),
        label = "text"
    )

    val subTextColor by animateColorAsState(
        if (isDarkMode) Color(0xFF8C939D) else Color(0xFF6C757D),
        label = "subtext"
    )

    val primaryGreen = Color(0xFF00E676)

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
    val topFiveLeagueNames = remember {
        setOf(
            "PREMIER LEAGUE",
            "BUNDESLIGA",
            "SERIE A",
            "LA LIGA",
            "LIGUE 1"
        )
    }

    val isTopFiveLeague: (String, String?) -> Boolean = { leagueName, countryCode ->

        val normalized = leagueName
            .trim()
            .uppercase()
            .replace("Á", "A")
            .replace("É", "E")

        // Ha a liga neve ország-előtaggal érkezik,
        // csak a liga tényleges részét nézzük.
        //
        // Például:
        // "ANGLIA: PREMIER LEAGUE"
        // -> "PREMIER LEAGUE"
        //
        // "ANGLIA: PREMIER LEAGUE 2"
        // -> "PREMIER LEAGUE 2"
        //
        // Így a Premier League 2 nem kerül bele.
        val leagueOnly = normalized
            .substringAfterLast(":")
            .trim()

        val country = countryCode
            ?.trim()
            ?.uppercase()
            .orEmpty()

        when (leagueOnly) {

            // A Premier League esetében az országot is ellenőrizzük,
            // hogy például az Örmény Premier League SOHA ne legyen TOP 5.
            "PREMIER LEAGUE" -> {
                country == "GB" ||
                        country == "UK" ||
                        country == "EN" ||
                        country == "ENG" ||
                        normalized.contains("ANGLIA") ||
                        normalized.contains("ENGLAND")
            }

            "BUNDESLIGA" -> true

            "SERIE A" -> true

            "LA LIGA" -> true

            "LIGUE 1" -> true

            else -> false
        }
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
        mutableStateOf(setOf<String>())
    }

    val filteredMatches = remember(
        matches,
        selectedTab,
        searchQuery,
        favoriteMatchIds,
        favoriteLeagueNames
    ) {
        matches.filter { match ->

            val leagueName = match.league ?: "EGYÉB BAJNOKSÁG"

            val isLeagueFav = favoriteLeagueNames.contains(leagueName)
            val isMatchFav = favoriteMatchIds.contains(match.id)

            val matchesSearch =
                searchQuery.isEmpty() ||
                        match.homeTeam.contains(
                            searchQuery,
                            ignoreCase = true
                        ) ||
                        match.awayTeam.contains(
                            searchQuery,
                            ignoreCase = true
                        ) ||
                        leagueName.contains(
                            searchQuery,
                            ignoreCase = true
                        )

            val matchesTab = when (selectedTab) {

                // ÉLŐ
                1 -> match.status != "FT" &&
                        (match.minute ?: 0) > 0

                // KEDVENC
                2 -> isMatchFav || isLeagueFav

                // ÖSSZES
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
    val groupedMatchesList = remember(
        filteredMatches
    ) {

        val groups = filteredMatches.groupBy {
            it.league ?: "EGYÉB BAJNOKSÁG"
        }

        // FONTOS: a kedvenc liga NEM kerül előre.
        // Csak a TOP 5 liga kap fix elsőbbséget, minden más liga
        // a magyar ábécé szerinti ORSZÁG sorrendjét követi, majd
        // azon belül a bajnokság neve szerint rendeződik.
        groups.entries.sortedWith(
            compareBy<Map.Entry<String, List<MatchResponse>>> { entry ->
                val first = entry.value.firstOrNull()
                topLeagueRank(
                    entry.key,
                    first?.country,
                    first?.countryCode
                )
            }.thenBy { entry ->
                val first = entry.value.firstOrNull()
                hungarianSortKey(
                    leagueCountryName(
                        entry.key,
                        first?.country
                    )
                )
            }.thenBy { entry ->
                hungarianSortKey(entry.key)
            }
        )
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = bgColor
    ) {

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
                            isDarkMode = !isDarkMode
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

            } else {

                LazyColumn(
                    modifier = Modifier.fillMaxSize()
                ) {

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

                                isTopFive && isDarkMode ->
                                    Color(0xFF5A4700)

                                isTopFive ->
                                    Color(0xFFFFE8A3)

                                isUserHighlighted && isDarkMode ->
                                    Color(0xFF3B3020)

                                isUserHighlighted ->
                                    Color(0xFFFFF2D2)

                                else ->
                                    leagueBgColor
                            }

                            val leagueHeaderTextColor =
                                if (isTopFive) {

                                    if (isDarkMode) {
                                        Color(0xFFFFD54F)
                                    } else {
                                        Color(0xFF6D4C00)
                                    }

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
                                            if (
                                                isTopFive ||
                                                isLeagueFav
                                            ) {
                                                "★"
                                            } else {
                                                "☆"
                                            },

                                        color = when {

                                            isTopFive ->
                                                Color(0xFFFFB300)

                                            isLeagueFav ->
                                                Color(0xFFFF9100)

                                            else ->
                                                subTextColor
                                        },

                                        fontSize = 15.sp,

                                        modifier = Modifier
                                            .then(

                                                // A TOP 5 csillaga fix.
                                                //
                                                // A többi liga csillaga
                                                // továbbra is kedvenccé
                                                // tehető.
                                                if (!isTopFive) {

                                                    Modifier.clickable {

                                                        favoriteLeagueNames =
                                                            if (isLeagueFav) {

                                                                favoriteLeagueNames
                                                                    .filter {
                                                                        it != leagueName
                                                                    }
                                                                    .toSet()

                                                            } else {

                                                                favoriteLeagueNames +
                                                                        leagueName
                                                            }
                                                    }

                                                } else {

                                                    Modifier
                                                }
                                            )
                                            .padding(end = 6.dp)
                                    )

                                    // ====================================================
                                    // ORSZÁG ZÁSZLÓ
                                    // ====================================================

                                    Text(
                                        text = countryFlag(
                                            countryCode = firstLeagueMatch?.countryCode,
                                            countryName = firstLeagueMatch?.country,
                                            leagueName = leagueName
                                        ),

                                        fontSize = 14.sp,

                                        modifier = Modifier.padding(
                                            end = 6.dp
                                        )
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

                            items(leagueMatches) { match ->

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

                                    onFavoriteToggle = {

                                        favoriteMatchIds =
                                            if (isFav) {

                                                favoriteMatchIds
                                                    .filter {
                                                        it != match.id
                                                    }
                                                    .toSet()

                                            } else {

                                                favoriteMatchIds +
                                                        match.id
                                            }
                                    },

                                    onVideoClick = {
                                        match ->
                                        val highlightMatchId =
                                            match.highlightMatchId?.trim().orEmpty()

                                        if (highlightMatchId.isBlank()) {
                                            highlightError =
                                                "Ehhez a mérkőzéshez jelenleg nincs elérhető Highlightly videó."
                                            showHighlightPicker = true
                                            highlightVideos = emptyList()
                                        } else {
                                            coroutineScope.launch {
                                                isHighlightLoading = true
                                                highlightError = null
                                                highlightVideos = emptyList()
                                                showHighlightPicker = true

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

                                                    if (highlightVideos.isEmpty()) {
                                                        highlightError =
                                                            "A Highlightly nem adott vissza lejátszható videót ehhez a mérkőzéshez."
                                                    }
                                                } catch (e: Exception) {
                                                    highlightError =
                                                        "A Highlightly videók betöltése sikertelen."
                                                } finally {
                                                    isHighlightLoading = false
                                                }
                                            }
                                        }
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

    if (showHighlightPicker) {
        HighlightVideoPickerDialog(
            videos = highlightVideos,
            isLoading = isHighlightLoading,
            errorMessage = highlightError,
            isDarkMode = isDarkMode,
            onVideoSelected = { video ->
                selectedVideo = video
                showHighlightPicker = false
            },
            onDismiss = {
                showHighlightPicker = false
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

    selectedLeaguePair?.let {
            (leagueId, leagueName) ->

        FullLeagueTableDialog(
            leagueId = leagueId,
            leagueName = leagueName,
            isDarkMode = isDarkMode
        ) {

            selectedLeaguePair = null
        }
    }
}

// ================================================================
// TOP 5 + MAGYAR ÁBÉCÉSORREND
// ================================================================

private fun normalizeCountry(value: String?): String {
    return value
        ?.trim()
        ?.uppercase()
        ?.replace("Á", "A")
        ?.replace("É", "E")
        ?.replace("Í", "I")
        ?.replace("Ó", "O")
        ?.replace("Ö", "O")
        ?.replace("Ő", "O")
        ?.replace("Ú", "U")
        ?.replace("Ü", "U")
        ?.replace("Ű", "U")
        ?.replace("-", " ")
        ?.replace("_", " ")
        ?.replace(Regex("\\s+"), " ")
        ?.trim()
        .orEmpty()
}

private fun leagueCountryName(
    leagueName: String?,
    countryName: String?
): String {
    val country = countryName?.trim().orEmpty()
    if (country.isNotBlank() &&
        country.uppercase() !in setOf("WORLD", "INTERNATIONAL", "NEMZETKOZI", "EUROPE", "EURÓPA")) {
        return country
    }

    val title = leagueName?.trim().orEmpty()
    return if (":" in title) title.substringBefore(":").trim() else title
}

private fun topLeagueRank(
    leagueName: String?,
    countryName: String?,
    countryCode: String?
): Int {
    val league = normalizeCountry(leagueName?.substringAfterLast(":") ?: "")
    val country = normalizeCountry(countryName)
    val code = countryCode?.trim()?.uppercase().orEmpty()

    return when {
        league == "PREMIER LEAGUE" && (code == "GB" || code == "UK" || country == "ANGLIA" || country == "ENGLAND") -> 0
        league == "SERIE A" && (code == "IT" || country == "OLASZORSZAG" || country == "ITALY") -> 1
        league == "LA LIGA" && (code == "ES" || country == "SPANYOLORSZAG" || country == "SPAIN") -> 2
        league == "BUNDESLIGA" && (code == "DE" || country == "NEMETORSZAG" || country == "GERMANY") -> 3
        league == "LIGUE 1" && (code == "FR" || country == "FRANCIAORSZAG" || country == "FRANCE") -> 4
        else -> 100
    }
}

private fun hungarianSortKey(value: String?): String {
    val text = value?.trim()?.lowercase().orEmpty()
    val alphabet = mapOf(
        'a' to 1, 'á' to 2, 'b' to 3, 'c' to 4, 'd' to 6,
        'e' to 9, 'é' to 10, 'f' to 11, 'g' to 12, 'h' to 14,
        'i' to 15, 'í' to 16, 'j' to 17, 'k' to 18, 'l' to 19,
        'm' to 21, 'n' to 22, 'o' to 24, 'ó' to 25, 'ö' to 26,
        'ő' to 27, 'p' to 28, 'q' to 29, 'r' to 30, 's' to 31,
        't' to 33, 'u' to 35, 'ú' to 36, 'ü' to 37, 'ű' to 38,
        'v' to 39, 'w' to 40, 'x' to 41, 'y' to 42, 'z' to 43
    )

    val digraphs = listOf("dzs" to 8, "cs" to 5, "dz" to 7, "gy" to 13,
        "ly" to 20, "ny" to 23, "sz" to 32, "ty" to 34, "zs" to 44)

    val result = StringBuilder()
    var i = 0
    while (i < text.length) {
        val match = digraphs.firstOrNull { (token, _) -> text.startsWith(token, i) }
        val rank: Int
        if (match != null) {
            rank = match.second
            i += match.first.length
        } else {
            rank = alphabet[text[i]] ?: (100 + text[i].code)
            i++
        }
        result.append(Char(0x1000 + rank))
    }
    return result.toString()
}

// ================================================================
// ORSZÁGZÁSZLÓ
// ================================================================

private fun countryFlag(
    countryCode: String?,
    countryName: String?,
    leagueName: String?
): String {
    val code = countryCode
        ?.trim()
        ?.lowercase()
        ?.takeIf { it.length == 2 }
        ?: countryToIso(countryName, leagueName)

    if (code.length != 2 || code.any { it !in 'a'..'z' }) return "🌐"

    return buildString {
        appendCodePoint(0x1F1E6 + (code[0] - 'a'))
        appendCodePoint(0x1F1E6 + (code[1] - 'a'))
    }
}

private fun countryToIso(countryName: String?, leagueName: String?): String {
    val raw = countryName?.trim().orEmpty().ifBlank {
        leagueName?.substringBefore(":")?.trim().orEmpty()
    }
    val key = normalizeCountry(raw)

    val map = mapOf(
        "ALBANIA" to "al", "ALGERIA" to "dz", "ARGENTINA" to "ar",
        "ARMENIA" to "am", "AUSTRALIA" to "au", "AUSTRIA" to "at", "AZERBAIJAN" to "az",
        "BELARUS" to "by", "BELGIUM" to "be", "BOLIVIA" to "bo", "BOSNIA AND HERZEGOVINA" to "ba",
        "BOSNIA HERZEGOVINA" to "ba", "BRAZIL" to "br", "BRAZILIA" to "br", "BULGARIA" to "bg",
        "CANADA" to "ca", "CHILE" to "cl", "CHINA" to "cn", "COLOMBIA" to "co",
        "COSTA RICA" to "cr", "CROATIA" to "hr", "CYPRUS" to "cy", "CZECHIA" to "cz",
        "CZECH REPUBLIC" to "cz", "DENMARK" to "dk", "DANIA" to "dk", "DOMINICAN REPUBLIC" to "do",
        "ECUADOR" to "ec", "EQUADOR" to "ec", "EGYPT" to "eg", "EL SALVADOR" to "sv",
        "ENGLAND" to "gb", "ANGLIA" to "gb", "ESTONIA" to "ee", "ESZTORSZAG" to "ee",
        "ETHIOPIA" to "et", "FAROE ISLANDS" to "fo", "FINLAND" to "fi", "FRANCE" to "fr",
        "FRANCIAORSZAG" to "fr", "GEORGIA" to "ge", "GERMANY" to "de", "NEMETORSZAG" to "de",
        "GHANA" to "gh", "GIBRALTAR" to "gi", "GREECE" to "gr", "GOROGORSZAG" to "gr",
        "GUATEMALA" to "gt", "HONDURAS" to "hn", "HONG KONG" to "hk", "HUNGARY" to "hu",
        "MAGYARORSZAG" to "hu", "ICELAND" to "is", "IZLAND" to "is", "INDIA" to "in",
        "INDONESIA" to "id", "IRAN" to "ir", "IRAQ" to "iq", "IRELAND" to "ie", "ISRAEL" to "il",
        "ITALY" to "it", "OLASZORSZAG" to "it", "IVORY COAST" to "ci", "JAMAICA" to "jm",
        "JAPAN" to "jp", "JORDAN" to "jo", "KAZAKHSTAN" to "kz", "KENYA" to "ke",
        "KOREA" to "kr", "SOUTH KOREA" to "kr", "KOSOVO" to "xk", "KYRGYZSTAN" to "kg",
        "KIRGIZISZTAN" to "kg", "KUWAIT" to "kw", "LATVIA" to "lv", "LETTORSZAG" to "lv",
        "LEBANON" to "lb", "LITHUANIA" to "lt", "LITVANIA" to "lt", "LUXEMBOURG" to "lu",
        "MALAYSIA" to "my", "MALTA" to "mt", "MEXICO" to "mx", "MEXIKO" to "mx",
        "MOLDOVA" to "md", "MONTENEGRO" to "me", "MOROCCO" to "ma", "NETHERLANDS" to "nl",
        "HOLLAND" to "nl", "HOLLANDIA" to "nl", "NEW ZEALAND" to "nz", "NICARAGUA" to "ni",
        "NIGERIA" to "ng", "NORTH MACEDONIA" to "mk", "ESZAK MACEDONIA" to "mk", "NORWAY" to "no",
        "OMAN" to "om", "PANAMA" to "pa", "PARAGUAY" to "py", "PERU" to "pe", "PHILIPPINES" to "ph",
        "POLAND" to "pl", "LENGYELORSZAG" to "pl", "PORTUGAL" to "pt", "PORTUGALIA" to "pt",
        "QATAR" to "qa", "ROMANIA" to "ro",
        "RUSSIA" to "ru", "OROSZORSZAG" to "ru", "SAUDI ARABIA" to "sa", "SAUDIARABIA" to "sa",
        "SERBIA" to "rs", "SINGAPORE" to "sg", "SLOVAKIA" to "sk", "SLOVENIA" to "si",
        "SOUTH AFRICA" to "za", "SPAIN" to "es", "SPANYOLORSZAG" to "es", "SRI LANKA" to "lk",
        "SWEDEN" to "se", "SVEDORSZAG" to "se", "SWITZERLAND" to "ch", "TAIWAN" to "tw",
        "TANZANIA" to "tz", "THAILAND" to "th", "TUNISIA" to "tn", "TURKEY" to "tr",
        "TOROKORSZAG" to "tr", "UGANDA" to "ug", "UKRAINE" to "ua", "UNITED ARAB EMIRATES" to "ae",
        "EGYESULT ARAB EMIRATEK" to "ae", "URUGUAY" to "uy", "USA" to "us", "UZBEKISTAN" to "uz",
        "UZBEGISZTAN" to "uz", "VENEZUELA" to "ve", "VIETNAM" to "vn", "WALES" to "gb",
        "ZAMBIA" to "zm", "ZIMBABWE" to "zw"
    )
    return map[key].orEmpty()
}

// ================================================================
// CSAPAT LOGÓ
// ================================================================

@Composable
private fun TeamLogo(
    url: String?,
    teamName: String,
    size: androidx.compose.ui.unit.Dp = 26.dp
) {

    val initials =
        teamName
            .trim()
            .split(Regex("\\s+"))
            .filter {
                it.isNotBlank()
            }
            .take(2)
            .joinToString("") {
                it.first().uppercase()
            }
            .ifBlank {
                "⚽"
            }

    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(
                Color(0xFF252A31)
            ),

        contentAlignment =
            Alignment.Center
    ) {

        if (!url.isNullOrBlank()) {

            SubcomposeAsyncImage(
                model = url,

                contentDescription =
                    "$teamName logó",

                modifier =
                    Modifier.fillMaxSize(),

                loading = {

                    Text(
                        text = initials,
                        color = Color(0xFF8C939D),
                        fontSize = 8.sp,
                        fontWeight =
                            FontWeight.Bold
                    )
                },

                error = {

                    Text(
                        text = initials,
                        color = Color(0xFF8C939D),
                        fontSize = 8.sp,
                        fontWeight =
                            FontWeight.Bold
                    )
                }
            )

        } else {

            Text(
                text = initials,
                color = Color(0xFF8C939D),
                fontSize = 8.sp,
                fontWeight =
                    FontWeight.Bold
            )
        }
    }
}

// ================================================================
// MECCS SOR
// ================================================================

@Composable
fun PremiumMatchRow(
    match: MatchResponse,
    isFavorite: Boolean,
    cardBgColor: Color,
    textColor: Color,
    subTextColor: Color,
    primaryGreen: Color,
    onFavoriteToggle: () -> Unit,
    onVideoClick: (MatchResponse) -> Unit
) {

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(cardBgColor)
            .padding(
                horizontal = 12.dp,
                vertical = 10.dp
            ),

        verticalAlignment =
            Alignment.CenterVertically
    ) {

        // ============================================================
        // MECCS KEDVENC
        // ============================================================

        Text(
            text =
                if (isFavorite) {
                    "★"
                } else {
                    "☆"
                },

            color =
                if (isFavorite) {
                    Color(0xFFFF9100)
                } else {
                    subTextColor
                },

            fontSize = 18.sp,

            modifier = Modifier
                .clickable {
                    onFavoriteToggle()
                }
                .padding(end = 8.dp)
        )

        // ============================================================
        // STÁTUSZ / IDŐ
        // ============================================================

        Column(
            modifier =
                Modifier.width(55.dp),

            horizontalAlignment =
                Alignment.Start
        ) {

            val isLive =
                match.status != "FT" &&
                        (match.minute ?: 0) > 0

            val statusText =
                when (match.status) {

                    "FT" ->
                        "Vége"

                    "HT" ->
                        "Félidő"

                    "1H" ->
                        "1. Félidő"

                    "2H" ->
                        "2. Félidő"

                    "NS" ->
                        "Kezdés"

                    else ->
                        match.status
                }

            if (isLive) {

                Row(
                    verticalAlignment =
                        Alignment.CenterVertically
                ) {

                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(
                                primaryGreen
                            )
                    )

                    Spacer(
                        modifier =
                            Modifier.width(4.dp)
                    )

                    Text(
                        text =
                            "${match.minute}'",

                        color =
                            primaryGreen,

                        fontSize = 11.sp,

                        fontWeight =
                            FontWeight.Bold
                    )
                }

            } else {

                Text(
                    text = statusText,

                    color =
                        subTextColor,

                    fontSize = 10.sp,

                    fontWeight =
                        FontWeight.Medium
                )
            }
        }

        // ============================================================
        // CSAPATOK
        // ============================================================

        Column(
            modifier =
                Modifier.weight(1f)
        ) {

            Row(
                verticalAlignment =
                    Alignment.CenterVertically
            ) {

                TeamLogo(
                    url =
                        match.homeLogoUrl,

                    teamName =
                        match.homeTeam
                )

                Spacer(
                    modifier =
                        Modifier.width(8.dp)
                )

                Text(
                    text =
                        match.homeTeam,

                    color =
                        textColor,

                    fontSize = 13.sp,

                    fontWeight =
                        FontWeight.SemiBold,

                    maxLines = 1
                )
            }

            Spacer(
                modifier =
                    Modifier.height(4.dp)
            )

            Row(
                verticalAlignment =
                    Alignment.CenterVertically
            ) {

                TeamLogo(
                    url =
                        match.awayLogoUrl,

                    teamName =
                        match.awayTeam
                )

                Spacer(
                    modifier =
                        Modifier.width(8.dp)
                )

                Text(
                    text =
                        match.awayTeam,

                    color =
                        textColor,

                    fontSize = 13.sp,

                    fontWeight =
                        FontWeight.SemiBold,

                    maxLines = 1
                )
            }
        }

        // ============================================================
        // EREDMÉNY
        // ============================================================

        Column(
            horizontalAlignment =
                Alignment.End
        ) {

            Text(
                text =
                    "${match.homeScore ?: 0}",

                color =
                    if (match.homeScore != null) {
                        primaryGreen
                    } else {
                        subTextColor
                    },

                fontSize = 13.sp,

                fontWeight =
                    FontWeight.Bold
            )

            Spacer(
                modifier =
                    Modifier.height(2.dp)
            )

            Text(
                text =
                    "${match.awayScore ?: 0}",

                color =
                    if (match.awayScore != null) {
                        primaryGreen
                    } else {
                        subTextColor
                    },

                fontSize = 13.sp,

                fontWeight =
                    FontWeight.Bold
            )
        }

        // ============================================================
        // VIDEÓ
        // ============================================================

        match.highlightMatchId?.let {

            Spacer(
                modifier =
                    Modifier.width(8.dp)
            )

            Box(
                modifier = Modifier
                    .clip(
                        RoundedCornerShape(6.dp)
                    )
                    .background(
                        Color(0xFF2979FF)
                    )
                    .clickable {
                        onVideoClick(match)
                    }
                    .padding(
                        horizontal = 6.dp,
                        vertical = 4.dp
                    )
            ) {

                Text(
                    text = "🎥",
                    color = Color.White,
                    fontSize = 11.sp
                )
            }
        }
    }
}

// ================================================================
// TELJES LIGA TABELLA
// ================================================================

@Composable
fun FullLeagueTableDialog(
    leagueId: String,
    leagueName: String,
    isDarkMode: Boolean,
    onDismiss: () -> Unit
) {

    val dialogBg =
        if (isDarkMode) {
            Color(0xFF1A1D21)
        } else {
            Color(0xFFFFFFFF)
        }

    val textColor =
        if (isDarkMode) {
            Color.White
        } else {
            Color.Black
        }

    val coroutineScope =
        rememberCoroutineScope()

    var standings by remember {
        mutableStateOf<List<StandingTeam>>(
            emptyList()
        )
    }

    var isLoading by remember {
        mutableStateOf(true)
    }

    LaunchedEffect(leagueId) {

        coroutineScope.launch {

            try {

                val api =
                    RetrofitInstance.api

                standings =
                    api.getStandings(
                        leagueId
                    )

            } catch (e: Exception) {

                standings =
                    emptyList()

            } finally {

                isLoading = false
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss
    ) {

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),

            shape =
                RoundedCornerShape(16.dp),

            colors =
                CardDefaults.cardColors(
                    containerColor =
                        dialogBg
                )
        ) {

            Column(
                modifier =
                    Modifier.padding(16.dp)
            ) {

                Text(
                    text =
                        "📊 $leagueName Tabella",

                    fontSize = 15.sp,

                    fontWeight =
                        FontWeight.Bold,

                    color =
                        Color(0xFF00E676)
                )

                Spacer(
                    modifier =
                        Modifier.height(10.dp)
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            vertical = 4.dp
                        ),

                    verticalAlignment =
                        Alignment.CenterVertically
                ) {

                    Text(
                        "#",
                        fontWeight =
                            FontWeight.Bold,
                        color = Color.Gray,
                        fontSize = 10.sp,
                        modifier =
                            Modifier.width(20.dp)
                    )

                    Text(
                        "CSAPAT",
                        fontWeight =
                            FontWeight.Bold,
                        color = Color.Gray,
                        fontSize = 10.sp,
                        modifier =
                            Modifier.weight(1f)
                    )

                    Text(
                        "M",
                        fontWeight =
                            FontWeight.Bold,
                        color = Color.Gray,
                        fontSize = 10.sp,
                        modifier =
                            Modifier.width(22.dp)
                    )

                    Text(
                        "GY",
                        fontWeight =
                            FontWeight.Bold,
                        color = Color.Gray,
                        fontSize = 10.sp,
                        modifier =
                            Modifier.width(22.dp)
                    )

                    Text(
                        "D",
                        fontWeight =
                            FontWeight.Bold,
                        color = Color.Gray,
                        fontSize = 10.sp,
                        modifier =
                            Modifier.width(22.dp)
                    )

                    Text(
                        "V",
                        fontWeight =
                            FontWeight.Bold,
                        color = Color.Gray,
                        fontSize = 10.sp,
                        modifier =
                            Modifier.width(22.dp)
                    )

                    Text(
                        "GÓL",
                        fontWeight =
                            FontWeight.Bold,
                        color = Color.Gray,
                        fontSize = 10.sp,
                        modifier =
                            Modifier.width(36.dp)
                    )

                    Text(
                        "P",
                        fontWeight =
                            FontWeight.Bold,
                        color =
                            Color(0xFF00E676),
                        fontSize = 10.sp,
                        modifier =
                            Modifier.width(24.dp)
                    )
                }

                Divider(
                    color = Color.Gray,
                    thickness = 1.dp
                )

                if (isLoading) {

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(150.dp),

                        contentAlignment =
                            Alignment.Center
                    ) {

                        CircularProgressIndicator(
                            color =
                                Color(0xFF00E676)
                        )
                    }

                } else if (standings.isEmpty()) {

                    Text(
                        "A tabella jelenleg nem érhető el ehhez a ligához.",

                        color =
                            Color.Gray,

                        fontSize = 11.sp,

                        modifier =
                            Modifier.padding(
                                vertical = 16.dp
                            )
                    )

                } else {

                    LazyColumn(
                        modifier =
                            Modifier.heightIn(
                                max = 350.dp
                            )
                    ) {

                        items(standings) { item ->

                            Row(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(
                                            vertical = 6.dp
                                        ),

                                verticalAlignment =
                                    Alignment.CenterVertically
                            ) {

                                Text(
                                    "${item.position}.",

                                    color =
                                        textColor,

                                    fontSize = 10.sp,

                                    fontWeight =
                                        FontWeight.Bold,

                                    modifier =
                                        Modifier.width(20.dp)
                                )

                                Text(
                                    item.team,

                                    color =
                                        textColor,

                                    fontSize = 11.sp,

                                    fontWeight =
                                        FontWeight.SemiBold,

                                    modifier =
                                        Modifier.weight(1f)
                                )

                                Text(
                                    "${item.played}",

                                    color =
                                        textColor,

                                    fontSize = 10.sp,

                                    modifier =
                                        Modifier.width(22.dp)
                                )

                                Text(
                                    "${item.wins}",

                                    color =
                                        textColor,

                                    fontSize = 10.sp,

                                    modifier =
                                        Modifier.width(22.dp)
                                )

                                Text(
                                    "${item.draws}",

                                    color =
                                        textColor,

                                    fontSize = 10.sp,

                                    modifier =
                                        Modifier.width(22.dp)
                                )

                                Text(
                                    "${item.losses}",

                                    color =
                                        textColor,

                                    fontSize = 10.sp,

                                    modifier =
                                        Modifier.width(22.dp)
                                )

                                Text(
                                    "${item.goalsScored}:${item.goalsAllowed}",

                                    color =
                                        textColor,

                                    fontSize = 10.sp,

                                    modifier =
                                        Modifier.width(36.dp)
                                )

                                Text(
                                    "${item.points}",

                                    color =
                                        Color(0xFF00E676),

                                    fontSize = 11.sp,

                                    fontWeight =
                                        FontWeight.Bold,

                                    modifier =
                                        Modifier.width(24.dp)
                                )
                            }

                            Divider(
                                color =
                                    Color(0xFF2B2B2B),

                                thickness = 0.5.dp
                            )
                        }
                    }
                }

                Spacer(
                    modifier =
                        Modifier.height(12.dp)
                )

                Button(
                    onClick =
                        onDismiss,

                    colors =
                        ButtonDefaults.buttonColors(
                            containerColor =
                                Color(0xFF00E676)
                        ),

                    modifier =
                        Modifier.align(
                            Alignment.End
                        )
                ) {

                    Text(
                        "Bezárás",
                        color = Color.Black,
                        fontSize = 12.sp
                    )
                }
            }
        }
    }
}

// ================================================================
// HIGHLIGHTLY VIDEÓ VÁLASZTÓ
// ================================================================

@Composable
fun HighlightVideoPickerDialog(
    videos: List<HighlightVideo>,
    isLoading: Boolean,
    errorMessage: String?,
    isDarkMode: Boolean,
    onVideoSelected: (HighlightVideo) -> Unit,
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
            it.category.equals(
                "goal-clip",
                ignoreCase = true
            )
        }

    val matchHighlights =
        videos.filter {
            it.category.equals(
                "match-highlights",
                ignoreCase = true
            )
        }

    Dialog(
        onDismissRequest = onDismiss
    ) {
        Card(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(10.dp),

            shape =
                RoundedCornerShape(16.dp),

            colors =
                CardDefaults.cardColors(
                    containerColor = dialogBg
                )
        ) {

            Column(
                modifier =
                    Modifier.padding(16.dp)
            ) {

                Text(
                    text = "🎥 Highlightly videók",
                    color = Color(0xFF00E676),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.ExtraBold
                )

                Spacer(
                    modifier =
                        Modifier.height(8.dp)
                )

                if (isLoading) {

                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .height(120.dp),

                        contentAlignment =
                            Alignment.Center
                    ) {

                        CircularProgressIndicator(
                            color = Color(0xFF00E676)
                        )
                    }

                } else if (errorMessage != null) {

                    Text(
                        text = errorMessage,
                        color = subTextColor,
                        fontSize = 12.sp
                    )

                } else {

                    if (goalClips.isNotEmpty()) {

                        Text(
                            text = "⚽ Gólvideók",
                            color = Color(0xFFFFD54F),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )

                        Spacer(
                            modifier =
                                Modifier.height(6.dp)
                        )

                        LazyColumn(
                            modifier =
                                Modifier.heightIn(
                                    max = 260.dp
                                )
                        ) {

                            items(goalClips) { video ->

                                HighlightVideoRow(
                                    video = video,
                                    textColor = textColor,
                                    subTextColor = subTextColor,
                                    onClick = {
                                        onVideoSelected(video)
                                    }
                                )

                                Divider(
                                    color =
                                        if (isDarkMode) {
                                            Color(0xFF2B3036)
                                        } else {
                                            Color(0xFFE5E7EB)
                                        },
                                    thickness = 0.5.dp
                                )
                            }
                        }

                    } else if (matchHighlights.isNotEmpty()) {

                        Text(
                            text = "🎬 Meccsösszefoglaló",
                            color = Color(0xFF64B5F6),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )

                        Spacer(
                            modifier =
                                Modifier.height(6.dp)
                        )

                        matchHighlights.forEach { video ->

                            HighlightVideoRow(
                                video = video,
                                textColor = textColor,
                                subTextColor = subTextColor,
                                onClick = {
                                    onVideoSelected(video)
                                }
                            )
                        }

                    } else {

                        Text(
                            text =
                                "Ehhez a mérkőzéshez nincs elérhető videó.",
                            color = subTextColor,
                            fontSize = 12.sp
                        )
                    }
                }

                Spacer(
                    modifier =
                        Modifier.height(12.dp)
                )

                Button(
                    onClick = onDismiss,
                    colors =
                        ButtonDefaults.buttonColors(
                            containerColor =
                                Color(0xFF00E676)
                        ),
                    modifier =
                        Modifier.align(
                            Alignment.End
                        )
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


// ================================================================
// HIGHLIGHTLY VIDEÓ SOR
// ================================================================

@Composable
private fun HighlightVideoRow(
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
