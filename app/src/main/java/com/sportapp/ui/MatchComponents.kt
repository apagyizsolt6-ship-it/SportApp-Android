package com.sportapp.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import coil.compose.SubcomposeAsyncImage
import com.sportapp.api.StandingTeam
import com.sportapp.models.MatchResponse


internal enum class FlagKind {
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

internal data class FlagResult(
    val kind: FlagKind,
    val emoji: String? = null
)

@Composable
internal fun LeagueFlagIcon(
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
internal fun RegionFlagIcon(
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

internal fun countryFlagResult(
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

internal fun startsWithRegion(name: String, vararg prefixes: String): Boolean =
    prefixes.any { prefix ->
        name == prefix ||
            name.startsWith("$prefix:") ||
            name.startsWith("$prefix ")
    }

internal fun isoFlag(code: String): String {
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
internal fun countryCodeFromLeagueName(name: String): String? {
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
internal fun TeamLogo(
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
    isReminderSet: Boolean = false,
    cardBgColor: Color,
    textColor: Color,
    subTextColor: Color,
    primaryGreen: Color,
    compact: Boolean = false,
    scoreFlash: Boolean = false,
    minutePulseMs: Long = 0L,
    isOnTicket: Boolean = false,
    onTicketClick: (() -> Unit)? = null,
    onFavoriteToggle: () -> Unit,
    onVideoClick: (MatchResponse) -> Unit,
    onAiClick: (MatchResponse) -> Unit,
    onMatchClick: (MatchResponse) -> Unit = {},
    onReminderClick: (MatchResponse) -> Unit = {},
    onShareClick: () -> Unit = {}
) {
    val isLive = isMatchLive(match.status, match.minute)
    val isFinished = isMatchFinished(match.status)
    val statusBarColor = when {
        isLive -> primaryGreen
        match.status == "HT" -> Color(0xFFFFD54F)
        isFinished -> Color(0xFF6B7C8F)
        else -> Color(0xFF4DA3FF)
    }
    val vPad = if (compact) 6.dp else 10.dp

    Column(modifier = Modifier.fillMaxWidth()) {
    // GÓL badge animáció
    if (scoreFlash) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
                .clip(RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp))
                .background(Color(0xFF00C853))
                .padding(vertical = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "⚽ GÓL!",
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = if (compact) 2.dp else 3.dp)
            .clip(
                if (scoreFlash) RoundedCornerShape(bottomStart = 14.dp, bottomEnd = 14.dp)
                else RoundedCornerShape(14.dp)
            )
            .background(if (scoreFlash) Color(0x5500E5A8) else cardBgColor)
            .border(1.dp, if (scoreFlash) primaryGreen else Color(0x28A0C4FF), RoundedCornerShape(14.dp))
            .clickable { onMatchClick(match) }
            .padding(
                horizontal = 12.dp,
                vertical = vPad
            ),

        verticalAlignment =
            Alignment.CenterVertically
    ) {
        // Státusz sáv
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(if (compact) 28.dp else 36.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(statusBarColor)
        )
        Spacer(Modifier.width(8.dp))


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
                .padding(end = 4.dp)
        )
        if (onTicketClick != null) {
            Text(
                text = if (isOnTicket) "🎫✓" else "🎫",
                fontSize = 14.sp,
                color = if (isOnTicket) primaryGreen else textColor,
                modifier = Modifier
                    .clickable { onTicketClick.invoke() }
                    .padding(end = 6.dp)
            )
        }

        // ============================================================
        // STÁTUSZ / IDŐ
        // ============================================================

        Column(
            modifier =
                Modifier.width(55.dp),

            horizontalAlignment =
                Alignment.Start
        ) {

            val isLive = isMatchLive(match.status, match.minute)

            // Folyamatos élő perc: szerver perc + eltelt idő (másodpercenként UI frissítés)
            val shownMinute = rememberLiveMinute(
                matchId = match.id,
                serverMinute = match.minute,
                status = match.status,
                isLive = isLive,
                pulseMs = minutePulseMs
            )

            val statusText =
                when {
                    match.status == "FT" -> "Vége"
                    match.status == "AET" -> "Hossz. után"
                    match.status == "PEN" || match.status == "Pen." -> "11-esek"
                    match.status == "HT" -> "Félidő"
                    match.status == "1H" -> "1. Félidő"
                    match.status == "2H" -> "2. Félidő"
                    match.status == "ET" -> "Hosszabbítás"
                    match.status == "NS" -> "Kezdés"
                    else -> match.status
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
                        text = if (shownMinute > 0) "${shownMinute}'" else "ÉLŐ",
                        color = primaryGreen,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
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
                    text = match.homeTeam,
                    color = textColor,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
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
                    text = match.awayTeam,
                    color = textColor,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
            }
        }

        // ============================================================
        // EREDMÉNY + AKCIÓ GOMBOK (nem zsugorodhatnak el)
        // ============================================================

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.wrapContentWidth()
        ) {
            Column(
                horizontalAlignment = Alignment.End,
                modifier = Modifier.widthIn(min = 20.dp)
            ) {
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

            Spacer(modifier = Modifier.width(8.dp))

            // Emlékeztető
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(
                        if (isReminderSet) Color(0xFFFF9100) else Color(0xFF455A64)
                    )
                    .clickable { onReminderClick(match) }
                    .padding(horizontal = 7.dp, vertical = 5.dp)
            ) {
                Text(
                    text = if (isReminderSet) "🔔" else "⏰",
                    color = Color.White,
                    fontSize = 12.sp
                )
            }

            Spacer(modifier = Modifier.width(6.dp))

            // AI gomb – mindig látszik
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFF0284C7))
                    .clickable { onAiClick(match) }
                    .padding(horizontal = 7.dp, vertical = 5.dp)
            ) {
                Text(
                    text = "🤖",
                    color = Color.White,
                    fontSize = 12.sp
                )
            }

            // Videó / multimédia gomb – mindig látszik
            Spacer(modifier = Modifier.width(6.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(
                        if (!match.highlightMatchId.isNullOrBlank()) {
                            Color(0xFF2979FF)
                        } else {
                            Color(0xFF455A64)
                        }
                    )
                    .clickable { onVideoClick(match) }
                    .padding(horizontal = 7.dp, vertical = 5.dp)
            ) {
                Text(
                    text = "🎥",
                    color = Color.White,
                    fontSize = 12.sp
                )
            }

            // Megosztás
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF546E7A))
                    .clickable { onShareClick() },
                contentAlignment = Alignment.Center
            ) {
                Text(text = "↗", color = Color.White, fontSize = 12.sp)
            }
        }
    }

    // Odds sor (ha van)
    if (match.oddsHome != null || match.oddsDraw != null || match.oddsAway != null) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 48.dp, end = 12.dp, bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf(
                "1" to match.oddsHome,
                "X" to match.oddsDraw,
                "2" to match.oddsAway
            ).forEach { (label, v) ->
                if (v != null) {
                    Text(
                        text = "$label ${"%.2f".format(v)}",
                        color = subTextColor,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
            if (match.isValueBet == true) {
                Text("ÉRTÉKES", color = Color(0xFFFFD54F), fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
    } // Column
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
