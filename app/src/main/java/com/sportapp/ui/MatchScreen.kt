package com.sportapp.ui

import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawCircle
import androidx.compose.ui.graphics.drawscope.drawLine
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
import java.text.Collator
import java.util.Locale

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
    // Magyar ábécés rendező. A java.text.Collator magyar Locale-lal
    // kezeli az ékezetes és többjegyű magyar betűket is.
    val hungarianCollator = remember {
        Collator.getInstance(Locale("hu", "HU")).apply {
            strength = Collator.TERTIARY
        }
    }

    // A TOP ligák fix sorrendben maradnak legelöl. Minden más liga
    // magyar ABC szerint követi őket. A kedvenc státusz nem írhatja
    // felül ezt a sorrendet.
    val topLeagueRanks = remember {
        mapOf(
            "ANGLIA: PREMIER LEAGUE" to 0,
            "SPANYOLORSZÁG: LA LIGA" to 1,
            "OLASZORSZÁG: SERIE A" to 2,
            "NÉMETORSZÁG: BUNDESLIGA" to 3,
            "FRANCIAORSZÁG: LIGUE 1" to 4
        )
    }

    val groupedMatchesList = remember(
        filteredMatches,
        favoriteLeagueNames,
        hungarianCollator
    ) {
        val groups = filteredMatches.groupBy {
            it.league ?: "EGYÉB BAJNOKSÁG"
        }

        groups.entries.sortedWith(Comparator { a, b ->
            val aName = a.key.trim()
            val bName = b.key.trim()

            val aRank = topLeagueRanks[aName.uppercase()]
            val bRank = topLeagueRanks[bName.uppercase()]

            when {
                aRank != null && bRank != null ->
                    aRank.compareTo(bRank)

                aRank != null -> -1
                bRank != null -> 1

                else -> hungarianCollator.compare(aName, bName)
            }
        })
    }

    // ============================================================
    // TELJES MECCSLISTA NYITÁS / ZÁRÁS
    // ============================================================
    // A ligák továbbra is külön-külön is nyithatók/zárhatók.
    // Ez a kapcsoló csak az összes jelenleg látható ligára hat.
    val allLeaguesCollapsed = groupedMatchesList.isNotEmpty() &&
            groupedMatchesList.all {
                collapsedLeagueNames.contains(it.key)
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
            // TELJES MECCSLISTA – NYITÁS / ZÁRÁS
            // ====================================================
            // Független az egyes ligák saját nyitás/zárás gombjától.
            //
            // Ha minden liga nyitva van -> minden liga bezárása.
            // Ha akár csak egy liga nyitva van -> minden liga megnyitása.
            if (!isLoading && groupedMatchesList.isNotEmpty) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        modifier = Modifier.clickable {
                            collapsedLeagueNames = if (allLeaguesCollapsed) {
                                emptySet()
                            } else {
                                groupedMatchesList
                                    .map { it.key }
                                    .toSet()
                            }
                        },
                        shape = RoundedCornerShape(20.dp),
                        color = if (isDarkMode) {
                            Color(0xFF20252B)
                        } else {
                            Color(0xFFE7EBEF)
                        }
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp, 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (allLeaguesCollapsed) "▲" else "▼",
                                color = primaryGreen,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(Modifier.width(5.dp))
                            Text(
                                text = if (allLeaguesCollapsed) {
                                    "ÖSSZES NYITÁSA"
                                } else {
                                    "ÖSSZES ZÁRÁSA"
                                },
                                color = primaryGreen,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
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

                                    LeagueFlagIcon(
                                        countryCode = firstLeagueMatch?.countryCode,
                                        leagueName = leagueName,
                                        modifier = Modifier.padding(end = 6.dp)
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
// ORSZÁG / RÉGIÓ ZÁSZLÓ
// ================================================================

private enum class FlagKind {
    COUNTRY,
    EUROPE,
    SOUTH_AMERICA,
    NORTH_AMERICA,
    CENTRAL_AMERICA,
    AFRICA,
    ASIA,
    OCEANIA,
    WORLD,
    GENERIC
}

private data class FlagResult(
    val kind: FlagKind,
    val emoji: String? = null
)

@Composable
private fun LeagueFlagIcon(
    countryCode: String?,
    leagueName: String?,
    modifier: Modifier = Modifier
) {
    val result = countryFlagResult(countryCode, leagueName)

    when (result.kind) {
        FlagKind.COUNTRY -> {
            Text(
                text = result.emoji ?: "🏳️",
                fontSize = 14.sp,
                modifier = modifier
            )
        }

        FlagKind.EUROPE -> {
            RegionFlagIcon(
                kind = FlagKind.EUROPE,
                modifier = modifier
            )
        }

        FlagKind.SOUTH_AMERICA,
        FlagKind.NORTH_AMERICA,
        FlagKind.CENTRAL_AMERICA,
        FlagKind.AFRICA,
        FlagKind.ASIA,
        FlagKind.OCEANIA,
        FlagKind.WORLD,
        FlagKind.GENERIC -> {
            RegionFlagIcon(
                kind = result.kind,
                modifier = modifier
            )
        }
    }
}

/**
 * Kis, egységes zászló-jellegű régióikonok azokhoz a sorozatokhoz,
 * amelyek nem egyetlen országhoz tartoznak.
 *
 * Így többé nem jelenik meg 🌐 a régióknál sem.
 */
@Composable
private fun RegionFlagIcon(
    kind: FlagKind,
    modifier: Modifier = Modifier
) {
    Canvas(
        modifier = modifier
            .width(22.dp)
            .height(15.dp)
    ) {
        val w = size.width
        val h = size.height
        val radius = 2.5f

        when (kind) {
            FlagKind.EUROPE -> {
                // EU: kék zászló + sárga csillagpontok.
                drawRoundRect(
                    color = Color(0xFF174EA6),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius, radius)
                )
                val cx = w / 2f
                val cy = h / 2f
                val r = h * 0.30f
                for (i in 0 until 8) {
                    val a = Math.toRadians((i * 45.0) - 90.0)
                    drawCircle(
                        color = Color(0xFFFFD700),
                        radius = 0.85f,
                        center = androidx.compose.ui.geometry.Offset(
                            cx + kotlin.math.cos(a).toFloat() * r,
                            cy + kotlin.math.sin(a).toFloat() * r
                        )
                    )
                }
            }

            FlagKind.SOUTH_AMERICA -> {
                drawRoundRect(Color(0xFF1B5E20), cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius, radius))
                drawCircle(Color(0xFFFFD54F), radius = h * 0.34f, center = androidx.compose.ui.geometry.Offset(w * 0.50f, h * 0.50f))
                drawCircle(Color(0xFF1565C0), radius = h * 0.19f, center = androidx.compose.ui.geometry.Offset(w * 0.50f, h * 0.50f))
            }

            FlagKind.NORTH_AMERICA -> {
                drawRoundRect(Color(0xFF1565C0), cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius, radius))
                drawRect(Color.White, androidx.compose.ui.geometry.Offset(w * 0.10f, h * 0.28f), androidx.compose.ui.geometry.Size(w * 0.80f, h * 0.14f))
                drawRect(Color.White, androidx.compose.ui.geometry.Offset(w * 0.10f, h * 0.58f), androidx.compose.ui.geometry.Size(w * 0.80f, h * 0.14f))
                drawCircle(Color(0xFFE53935), radius = h * 0.16f, center = androidx.compose.ui.geometry.Offset(w * 0.22f, h * 0.50f))
            }

            FlagKind.CENTRAL_AMERICA -> {
                drawRoundRect(Color(0xFF0277BD), cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius, radius))
                drawRect(Color.White, androidx.compose.ui.geometry.Offset(0f, h * 0.28f), androidx.compose.ui.geometry.Size(w, h * 0.44f))
                drawCircle(Color(0xFF2E7D32), radius = h * 0.17f, center = androidx.compose.ui.geometry.Offset(w * 0.50f, h * 0.50f))
            }

            FlagKind.AFRICA -> {
                drawRoundRect(Color(0xFF2E7D32), cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius, radius))
                drawLine(Color(0xFFFFD600), androidx.compose.ui.geometry.Offset(w * 0.10f, h * 0.78f), androidx.compose.ui.geometry.Offset(w * 0.90f, h * 0.22f), strokeWidth = h * 0.16f)
                drawCircle(Color(0xFFD32F2F), radius = h * 0.18f, center = androidx.compose.ui.geometry.Offset(w * 0.72f, h * 0.35f))
            }

            FlagKind.ASIA -> {
                drawRoundRect(Color(0xFFD32F2F), cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius, radius))
                drawCircle(Color(0xFFFFD54F), radius = h * 0.28f, center = androidx.compose.ui.geometry.Offset(w * 0.50f, h * 0.50f))
                drawCircle(Color(0xFFD32F2F), radius = h * 0.20f, center = androidx.compose.ui.geometry.Offset(w * 0.56f, h * 0.44f))
            }

            FlagKind.OCEANIA -> {
                drawRoundRect(Color(0xFF1565C0), cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius, radius))
                drawCircle(Color(0xFFFFD54F), radius = h * 0.14f, center = androidx.compose.ui.geometry.Offset(w * 0.70f, h * 0.34f))
                drawCircle(Color.White, radius = h * 0.10f, center = androidx.compose.ui.geometry.Offset(w * 0.35f, h * 0.65f))
                drawCircle(Color.White, radius = h * 0.07f, center = androidx.compose.ui.geometry.Offset(w * 0.55f, h * 0.68f))
            }

            FlagKind.WORLD -> {
                drawRoundRect(Color(0xFF1565C0), cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius, radius))
                drawCircle(
                    color = Color(0xFF66BB6A),
                    radius = h * 0.36f,
                    center = androidx.compose.ui.geometry.Offset(w * 0.50f, h * 0.50f)
                )
                drawCircle(
                    color = Color(0xFF1565C0),
                    radius = h * 0.36f,
                    center = androidx.compose.ui.geometry.Offset(w * 0.55f, h * 0.45f)
                )
            }

            FlagKind.GENERIC -> {
                drawRoundRect(Color(0xFF455A64), cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius, radius))
                drawRect(Color.White, androidx.compose.ui.geometry.Offset(0f, h * 0.33f), androidx.compose.ui.geometry.Size(w, h * 0.34f))
            }

            FlagKind.COUNTRY -> Unit
        }
    }
}

private fun countryFlagResult(
    countryCode: String?,
    leagueName: String? = null
): FlagResult {
    val directCode = countryCode
        ?.trim()
        ?.uppercase()
        .orEmpty()

    if (directCode.length == 2 &&
        directCode[0] in 'A'..'Z' &&
        directCode[1] in 'A'..'Z'
    ) {
        return FlagResult(FlagKind.COUNTRY, isoFlag(directCode))
    }

    val name = leagueName
        ?.trim()
        ?.uppercase()
        .orEmpty()

    return when {
        startsWithRegion(name, "EURÓPA", "EUROPE", "UEFA") -> FlagResult(FlagKind.EUROPE)
        startsWithRegion(name, "DÉL-AMERIKA", "SOUTH AMERICA", "CONMEBOL", "SOUTHAMERICA") -> FlagResult(FlagKind.SOUTH_AMERICA)
        startsWithRegion(name, "KÖZÉP-AMERIKA", "CENTRAL AMERICA") -> FlagResult(FlagKind.CENTRAL_AMERICA)
        startsWithRegion(name, "ÉSZAK-AMERIKA", "NORTH AMERICA", "CONCACAF") -> FlagResult(FlagKind.NORTH_AMERICA)
        startsWithRegion(name, "AFRIKA", "AFRICA", "CAF") -> FlagResult(FlagKind.AFRICA)
        startsWithRegion(name, "ÁZSIA", "ASIA", "AFC") -> FlagResult(FlagKind.ASIA)
        startsWithRegion(name, "ÓCEÁNIA", "OCEANIA", "OFC") -> FlagResult(FlagKind.OCEANIA)
        startsWithRegion(name, "VILÁG", "WORLD", "NEMZETKÖZI", "INTERNATIONAL") -> FlagResult(FlagKind.WORLD)
        else -> countryCodeFromLeagueName(name)?.let { FlagResult(FlagKind.COUNTRY, isoFlag(it)) }
            ?: FlagResult(FlagKind.GENERIC)
    }
}

private fun startsWithRegion(name: String, vararg prefixes: String): Boolean =
    prefixes.any { prefix ->
        name == prefix ||
            name.startsWith("$prefix:") ||
            name.startsWith("$prefix ")
    }

private fun isoFlag(code: String): String {
    if (code == "EU") return "🇪🇺"
    if (code == "UN") return "🇺🇳"
    if (code == "XK") return "🇽🇰"
    if (code.length != 2) return "🏳️"

    val first = code[0]
    val second = code[1]

    if (first !in 'A'..'Z' || second !in 'A'..'Z') {
        return "🏳️"
    }

    return buildString {
        appendCodePoint(0x1F1E6 + (first - 'A'))
        appendCodePoint(0x1F1E6 + (second - 'A'))
    }
}

/**
 * Ország meghatározása a bajnokság nevéből.
 *
 * Ez a tartalék megoldás azért kell, mert több API-rekordnál a
 * countryCode üres / hiányzik, miközben a bajnokság neve egyértelműen
 * tartalmazza az országot.
 */
private fun countryCodeFromLeagueName(name: String): String? {
    val countries = linkedMapOf(
        "EGYESÜLT ARAB EMÍRSÉGEK" to "AE", "UNITED ARAB EMIRATES" to "AE",
        "DÉL-KOREA" to "KR", "SOUTH KOREA" to "KR",
        "ÉSZAK-MACEDÓNIA" to "MK", "NORTH MACEDONIA" to "MK",
        "CSEHORSZÁG" to "CZ", "CZECHIA" to "CZ", "CZECH REPUBLIC" to "CZ",
        "FEHÉROROSZORSZÁG" to "BY", "BELARUS" to "BY",
        "HORVÁTORSZÁG" to "HR", "CROATIA" to "HR",
        "SZERBIA" to "RS", "SERBIA" to "RS",
        "SZLOVÁKIA" to "SK", "SLOVAKIA" to "SK",
        "SZLOVÉNIA" to "SI", "SLOVENIA" to "SI",
        "LENGYELORSZÁG" to "PL", "POLAND" to "PL",
        "ROMÁNIA" to "RO", "ROMANIA" to "RO",
        "BULGÁRIA" to "BG", "BULGARIA" to "BG",
        "DÁNIA" to "DK", "DENMARK" to "DK",
        "ANGLIA" to "GB", "ENGLAND" to "GB", "SKÓCIA" to "GB", "SCOTLAND" to "GB",
        "WALES" to "GB", "ÉSZAK-ÍRORSZÁG" to "GB", "NORTHERN IRELAND" to "GB",
        "FRANCIAORSZÁG" to "FR", "FRANCE" to "FR",
        "NÉMETORSZÁG" to "DE", "GERMANY" to "DE",
        "OLASZORSZÁG" to "IT", "ITALY" to "IT",
        "SPANYOLORSZÁG" to "ES", "SPAIN" to "ES",
        "PORTUGÁLIA" to "PT", "PORTUGAL" to "PT",
        "HOLLANDIA" to "NL", "NETHERLANDS" to "NL",
        "BELGIUM" to "BE", "SVÁJC" to "CH", "SWITZERLAND" to "CH",
        "AUSZTRIA" to "AT", "AUSTRIA" to "AT",
        "TÖRÖKORSZÁG" to "TR", "TURKEY" to "TR",
        "GÖRÖGORSZÁG" to "GR", "GREECE" to "GR",
        "IZLAND" to "IS", "ICELAND" to "IS",
        "ÍRORSZÁG" to "IE", "IRELAND" to "IE",
        "NORVÉGIA" to "NO", "NORWAY" to "NO",
        "SVÉDORSZÁG" to "SE", "SWEDEN" to "SE",
        "FINNORSZÁG" to "FI", "FINLAND" to "FI",
        "UKRAJNA" to "UA", "UKRAINE" to "UA",
        "OROSZORSZÁG" to "RU", "RUSSIA" to "RU",
        "BOSZNIA-HERCEGOVINA" to "BA", "BOSNIA" to "BA", "MONTENEGRO" to "ME",
        "ÉSZTORSZÁG" to "EE", "ESTONIA" to "EE", "LETTORSZÁG" to "LV", "LATVIA" to "LV",
        "LITVÁNIA" to "LT", "LITHUANIA" to "LT", "MOLDOVA" to "MD",
        "KOSZOVÓ" to "XK", "KOSOVO" to "XK", "ÖRMÉNYORSZÁG" to "AM", "ARMENIA" to "AM",
        "AZERBAJDZSÁN" to "AZ", "AZERBAIJAN" to "AZ", "GRÚZIA" to "GE", "GEORGIA" to "GE",
        "KAZAHSZTÁN" to "KZ", "KAZAKHSTAN" to "KZ", "ÜZBEGISZTÁN" to "UZ", "UZBEKISTAN" to "UZ",
        "KIRGIZISZTÁN" to "KG", "KYRGYZSTAN" to "KG", "TÁDZSIKISZTÁN" to "TJ", "TAJIKISTAN" to "TJ",
        "TURKMENISZTÁN" to "TM", "TURKMENISTAN" to "TM", "IRÁN" to "IR", "IRAN" to "IR",
        "IRAK" to "IQ", "IRAQ" to "IQ", "IZRAEL" to "IL", "ISRAEL" to "IL",
        "KATAR" to "QA", "QATAR" to "QA", "SZAÚD-ARÁBIA" to "SA", "SAUDI ARABIA" to "SA",
        "INDIA" to "IN", "PAKISZTÁN" to "PK", "PAKISTAN" to "PK", "BANGLADESH" to "BD",
        "JAPÁN" to "JP", "JAPAN" to "JP", "KÍNA" to "CN", "CHINA" to "CN",
        "DÉL-AFRIKA" to "ZA", "SOUTH AFRICA" to "ZA", "EGYIPTOM" to "EG", "EGYPT" to "EG",
        "MAROKKÓ" to "MA", "MOROCCO" to "MA", "ALGÉRIA" to "DZ", "ALGERIA" to "DZ",
        "TUNÉZIA" to "TN", "TUNISIA" to "TN", "TANZÁNIA" to "TZ", "TANZANIA" to "TZ",
        "GHÁNA" to "GH", "GHANA" to "GH", "NIGÉRIA" to "NG", "NIGERIA" to "NG",
        "KENYA" to "KE", "UGANDA" to "UG", "ETIÓPIA" to "ET", "ETHIOPIA" to "ET",
        "USA" to "US", "EGYESÜLT ÁLLAMOK" to "US", "UNITED STATES" to "US", "KANADA" to "CA", "CANADA" to "CA",
        "MEXIKÓ" to "MX", "MEXICO" to "MX", "KOSTA RIKA" to "CR", "COSTA RICA" to "CR",
        "PANAMA" to "PA", "GUATEMALA" to "GT", "HONDURAS" to "HN", "NICARAGUA" to "NI", "EL SALVADOR" to "SV",
        "KOLUMBIA" to "CO", "COLOMBIA" to "CO", "ECUADOR" to "EC", "PERU" to "PE", "BOLÍVIA" to "BO", "BOLIVIA" to "BO",
        "CHILE" to "CL", "ARGENTÍNA" to "AR", "ARGENTINA" to "AR", "BRAZÍLIA" to "BR", "BRAZIL" to "BR",
        "PARAGUAY" to "PY", "URUGUAY" to "UY", "VENEZUELA" to "VE", "KUBA" to "CU", "CUBA" to "CU", "JAMAICA" to "JM",
        "DOMINIKAI KÖZTÁRSASÁG" to "DO", "DOMINICAN REPUBLIC" to "DO", "TRINIDAD ÉS TOBAGO" to "TT", "TRINIDAD AND TOBAGO" to "TT",
        "AUSTRÁLIA" to "AU", "AUSTRALIA" to "AU", "ÚJ-ZÉLAND" to "NZ", "NEW ZEALAND" to "NZ",
        "MALAJZIA" to "MY", "MALAYSIA" to "MY", "SZINGAPÚR" to "SG", "SINGAPORE" to "SG", "THAIFÖLD" to "TH", "THAILAND" to "TH",
        "VIETNÁM" to "VN", "VIETNAM" to "VN", "INDONÉZIA" to "ID", "INDONESIA" to "ID", "FÜLÖP-SZIGETEK" to "PH", "PHILIPPINES" to "PH",
        "MYANMAR" to "MM", "SRÍ LANKA" to "LK", "SRI LANKA" to "LK", "NEPÁL" to "NP", "NEPAL" to "NP",
        "CIPRUS" to "CY", "CYPRUS" to "CY", "MÁLTA" to "MT", "MALTA" to "MT", "LUXEMBURG" to "LU", "LUXEMBOURG" to "LU",
        "ANDORRA" to "AD", "SAN MARINO" to "SM", "GIBRALTÁR" to "GI", "GIBRALTAR" to "GI", "FERÖER-SZIGETEK" to "FO", "FAROE ISLANDS" to "FO",
        "LIECHTENSTEIN" to "LI"
    )

    for ((country, code) in countries) {
        if (name == country || name.startsWith("$country:") || name.startsWith("$country ")) {
            return code
        }
    }

    val containsCountry = listOf(
        "COPA PARAGUAY" to "PY",
        "COPA URUGUAY" to "UY",
        "BETANO POKALEN" to "DK",
        "CROATIAN CUP" to "HR",
        "ARMENIAN CUP" to "AM",
        "RUSSIAN CUP" to "RU",
        "SLOVAK CUP" to "SK",
        "CALCUTTA PREMIER" to "IN",
        "LIGA DE ASCENSO" to "CR"
    )

    for ((part, code) in containsCountry) {
        if (name.contains(part)) return code
    }

    return null
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
