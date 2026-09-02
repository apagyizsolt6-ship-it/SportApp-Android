package com.sportapp.ui

@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import com.sportapp.models.MatchResponse
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Egy szelvény-sor (Tippmix-szerű segéd) */
data class TicketLeg(
    val id: String,
    val matchId: String,
    val homeTeam: String,
    val awayTeam: String,
    val market: String, // 1X2 | BTTS | OU25 | DC_1X | DC_X2
    val pick: String,
    val addedAt: Long = System.currentTimeMillis()
)

enum class LegStatus {
    PENDING, LIVE, WON, LOST
}

object TicketPrefs {
    private const val P = "ticket_szelveny"
    private const val KEY = "legs_json"

    fun load(ctx: Context): List<TicketLeg> {
        val raw = ctx.getSharedPreferences(P, Context.MODE_PRIVATE).getString(KEY, "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    add(
                        TicketLeg(
                            id = o.optString("id"),
                            matchId = o.optString("matchId"),
                            homeTeam = o.optString("homeTeam"),
                            awayTeam = o.optString("awayTeam"),
                            market = o.optString("market"),
                            pick = o.optString("pick"),
                            addedAt = o.optLong("addedAt", 0L)
                        )
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun save(ctx: Context, legs: List<TicketLeg>) {
        val arr = JSONArray()
        legs.forEach { leg ->
            arr.put(
                JSONObject()
                    .put("id", leg.id)
                    .put("matchId", leg.matchId)
                    .put("homeTeam", leg.homeTeam)
                    .put("awayTeam", leg.awayTeam)
                    .put("market", leg.market)
                    .put("pick", leg.pick)
                    .put("addedAt", leg.addedAt)
            )
        }
        ctx.getSharedPreferences(P, Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply()
    }

    fun add(ctx: Context, leg: TicketLeg, maxLegs: Int = 12): List<TicketLeg> {
        val cur = load(ctx).filter { it.matchId != leg.matchId || it.market != leg.market }.toMutableList()
        cur.add(0, leg)
        val next = cur.take(maxLegs)
        save(ctx, next)
        return next
    }

    fun remove(ctx: Context, legId: String): List<TicketLeg> {
        val next = load(ctx).filter { it.id != legId }
        save(ctx, next)
        return next
    }

    fun clear(ctx: Context) {
        save(ctx, emptyList())
    }
}

fun evaluateTicketLeg(leg: TicketLeg, match: MatchResponse?): LegStatus {
    if (match == null) return LegStatus.PENDING
    val hs = match.homeScore
    val as_ = match.awayScore
    val finished = isMatchFinished(match.status)
    val live = isMatchLive(match.status, match.minute)

    fun result1x2(): String? {
        if (hs == null || as_ == null) return null
        return when {
            hs > as_ -> "1"
            hs < as_ -> "2"
            else -> "X"
        }
    }

    val market = leg.market.uppercase()
    val pick = leg.pick.trim().uppercase()

    when {
        market == "1X2" || market == "MATCH" -> {
            val r = result1x2()
            if (r == null) return if (live) LegStatus.LIVE else LegStatus.PENDING
            val want = when {
                pick.startsWith("1") || pick.contains("HAZAI") || pick == leg.homeTeam.uppercase() -> "1"
                pick.startsWith("2") || pick.contains("VENDÉG") || pick.contains("VENDEG") || pick == leg.awayTeam.uppercase() -> "2"
                pick.startsWith("X") || pick.contains("DÖNTETLEN") || pick.contains("DONTETLEN") || pick.contains("DRAW") -> "X"
                else -> pick.take(1)
            }
            return when {
                finished -> if (r == want) LegStatus.WON else LegStatus.LOST
                live -> {
                    // Előre elbukott? (pl. 2-0 és X tipp) – óvatos: csak döntetlen tippnél ha van gól
                    if (want == "X" && (hs ?: 0) + (as_ ?: 0) > 0) LegStatus.LIVE
                    else LegStatus.LIVE
                }
                else -> LegStatus.PENDING
            }
        }
        market == "BTTS" -> {
            if (hs == null || as_ == null) return if (live) LegStatus.LIVE else LegStatus.PENDING
            val both = hs > 0 && as_ > 0
            val wantYes = pick.contains("IGEN") || pick == "YES" || pick == "I" || pick == "Y"
            val wantNo = pick.contains("NEM") || pick == "NO" || pick == "N"
            if (!finished) {
                if (wantYes && both) return LegStatus.WON // már bejött élőben is
                if (wantNo && both) return LegStatus.LOST
                return if (live) LegStatus.LIVE else LegStatus.PENDING
            }
            val ok = if (wantNo) !both else both
            return if (ok) LegStatus.WON else LegStatus.LOST
        }
        market == "OU25" || market.contains("OVER") || market.contains("UNDER") -> {
            if (hs == null || as_ == null) return if (live) LegStatus.LIVE else LegStatus.PENDING
            val total = hs + as_
            val over = pick.contains("OVER") || pick.contains("TÖBB") || pick.contains("TOBB") || pick.contains("+")
            val under = pick.contains("UNDER") || pick.contains("KEVESEBB")
            if (!finished) {
                if (over && total >= 3) return LegStatus.WON
                if (under && total >= 3) return LegStatus.LOST
                return if (live) LegStatus.LIVE else LegStatus.PENDING
            }
            val ok = if (under) total < 3 else total >= 3
            return if (ok) LegStatus.WON else LegStatus.LOST
        }
        market == "DC_1X" || market == "1X" -> {
            val r = result1x2()
            if (r == null) return if (live) LegStatus.LIVE else LegStatus.PENDING
            if (!finished) return if (live) LegStatus.LIVE else LegStatus.PENDING
            return if (r == "1" || r == "X") LegStatus.WON else LegStatus.LOST
        }
        market == "DC_X2" || market == "X2" -> {
            val r = result1x2()
            if (r == null) return if (live) LegStatus.LIVE else LegStatus.PENDING
            if (!finished) return if (live) LegStatus.LIVE else LegStatus.PENDING
            return if (r == "X" || r == "2") LegStatus.WON else LegStatus.LOST
        }
        else -> {
            if (finished) return LegStatus.PENDING
            return if (live) LegStatus.LIVE else LegStatus.PENDING
        }
    }
}

@Composable
fun TicketAssistantPanel(
    matches: List<MatchResponse>,
    isDarkMode: Boolean,
    primaryGreen: Color,
    cardBg: Color,
    textColor: Color,
    subTextColor: Color,
    onOpenMatch: (MatchResponse) -> Unit,
    onRequestAddFromList: () -> Unit = {}
) {
    val context = LocalContext.current
    var legs by remember { mutableStateOf(TicketPrefs.load(context)) }
    val matchMap = remember(matches) { matches.associateBy { it.id } }

    val evaluated = legs.map { leg ->
        leg to evaluateTicketLeg(leg, matchMap[leg.matchId])
    }
    val liveCount = evaluated.count { it.second == LegStatus.LIVE }
    val wonCount = evaluated.count { it.second == LegStatus.WON }
    val lostCount = evaluated.count { it.second == LegStatus.LOST }
    val total = legs.size.coerceAtLeast(1)
    val aliveFrac = (liveCount + wonCount).toFloat() / total.toFloat()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(
            "Szelvény segéd",
            color = textColor,
            fontWeight = FontWeight.Bold,
            fontSize = 20.sp
        )
        Text(
            "Élő követés · tájékoztató – nem fogadási tanács",
            color = subTextColor,
            fontSize = 11.sp
        )
        Spacer(Modifier.height(12.dp))

        // Összegző
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(cardBg)
                .padding(12.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Mai szelvény", color = textColor, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text("${legs.size} tipp", color = subTextColor, fontSize = 12.sp)
            }
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = aliveFrac.coerceIn(0f, 1f),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(4.dp)),
                color = primaryGreen,
                trackColor = Color(0x33FFFFFF)
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("$liveCount él", color = primaryGreen, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Text("$wonCount bejött", color = Color(0xFF00C853), fontSize = 12.sp)
                Text("$lostCount elbukott", color = Color(0xFFE53935), fontSize = 12.sp)
            }
        }

        Spacer(Modifier.height(10.dp))

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = onRequestAddFromList,
                modifier = Modifier.weight(1f)
            ) { Text("Meccs hozzáadása", fontSize = 12.sp) }
            if (legs.isNotEmpty()) {
                TextButton(onClick = {
                    TicketPrefs.clear(context)
                    legs = emptyList()
                }) { Text("Ürít", fontSize = 12.sp, color = Color(0xFFE53935)) }
                TextButton(onClick = {
                    val body = buildString {
                        append("⚽ SportApp szelvény\n")
                        evaluated.forEach { (leg, st) ->
                            val label = when (st) {
                                LegStatus.WON -> "BEJÖTT"
                                LegStatus.LOST -> "ELBUKOTT"
                                LegStatus.LIVE -> "ÉL"
                                else -> "VÁR"
                            }
                            append("• ${leg.homeTeam} – ${leg.awayTeam}: ${leg.market} ${leg.pick} [$label]\n")
                        }
                        append("\nTájékoztató – nem fogadási tanács.")
                    }
                    context.startActivity(
                        Intent.createChooser(
                            Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, body)
                            },
                            "Szelvény megosztása"
                        )
                    )
                }) { Text("Megosztás", fontSize = 12.sp) }
            }
        }

        Spacer(Modifier.height(8.dp))

        if (legs.isEmpty()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "Még üres a szelvény.\nA listán a 🎫 gombbal vagy innen „Meccs hozzáadása”.",
                    color = subTextColor,
                    fontSize = 13.sp
                )
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(evaluated, key = { it.first.id }) { (leg, status) ->
                    val m = matchMap[leg.matchId]
                    TicketLegRow(
                        leg = leg,
                        match = m,
                        status = status,
                        primaryGreen = primaryGreen,
                        cardBg = cardBg,
                        textColor = textColor,
                        subTextColor = subTextColor,
                        onClick = { m?.let(onOpenMatch) },
                        onRemove = {
                            legs = TicketPrefs.remove(context, leg.id)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun TicketLegRow(
    leg: TicketLeg,
    match: MatchResponse?,
    status: LegStatus,
    primaryGreen: Color,
    cardBg: Color,
    textColor: Color,
    subTextColor: Color,
    onClick: () -> Unit,
    onRemove: () -> Unit
) {
    val badgeColor = when (status) {
        LegStatus.WON -> Color(0xFF00C853)
        LegStatus.LOST -> Color(0xFFE53935)
        LegStatus.LIVE -> primaryGreen
        LegStatus.PENDING -> subTextColor
    }
    val badgeText = when (status) {
        LegStatus.WON -> "BEJÖTT"
        LegStatus.LOST -> "ELBUKOTT"
        LegStatus.LIVE -> "ÉL"
        LegStatus.PENDING -> "VÁR"
    }
    val score = if (match?.homeScore != null && match.awayScore != null) {
        "${match.homeScore}–${match.awayScore}"
    } else "–"
    val minuteTxt = when {
        match == null -> ""
        isMatchFinished(match.status) -> "FT"
        (match.minute ?: 0) > 0 -> "${match.minute}'"
        isMatchLive(match.status, match.minute) -> "ÉLŐ"
        else -> match.kickoffTime ?: ""
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(cardBg)
            .border(1.dp, badgeColor.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(badgeColor)
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "${leg.homeTeam} – ${leg.awayTeam}",
                color = textColor,
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
                maxLines = 1
            )
            Text(
                "${leg.market} · ${leg.pick}",
                color = subTextColor,
                fontSize = 11.sp
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(score, color = primaryGreen, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            if (minuteTxt.isNotBlank()) {
                Text(minuteTxt, color = subTextColor, fontSize = 11.sp)
            }
        }
        Spacer(Modifier.width(8.dp))
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = badgeColor.copy(alpha = 0.2f)
        ) {
            Text(
                badgeText,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                color = badgeColor,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(Modifier.width(4.dp))
        Text(
            "✕",
            color = subTextColor,
            fontSize = 14.sp,
            modifier = Modifier
                .clickable(onClick = onRemove)
                .padding(4.dp)
        )
    }
}

@Composable
fun AddToTicketDialog(
    match: MatchResponse,
    onDismiss: () -> Unit,
    onAdded: () -> Unit
) {
    val context = LocalContext.current
    var market by remember { mutableStateOf("1X2") }
    var pick by remember { mutableStateOf("1") }

    val picks = when (market) {
        "1X2" -> listOf("1" to "Hazai (1)", "X" to "Döntetlen", "2" to "Vendég (2)")
        "BTTS" -> listOf("Igen" to "BTTS Igen", "Nem" to "BTTS Nem")
        "OU25" -> listOf("Over 2.5" to "Több mint 2.5", "Under 2.5" to "Kevesebb mint 2.5")
        "DC_1X" -> listOf("1X" to "Hazai nem kap ki")
        "DC_X2" -> listOf("X2" to "Vendég nem kap ki")
        else -> listOf("1" to "1")
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Szelvényre", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${match.homeTeam} – ${match.awayTeam}", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text("Piac", fontSize = 12.sp, color = Color.Gray)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("1X2", "BTTS", "OU25", "DC_1X", "DC_X2").forEach { m ->
                        FilterChip(
                            selected = market == m,
                            onClick = {
                                market = m
                                pick = when (m) {
                                    "1X2" -> "1"
                                    "BTTS" -> "Igen"
                                    "OU25" -> "Over 2.5"
                                    "DC_1X" -> "1X"
                                    "DC_X2" -> "X2"
                                    else -> "1"
                                }
                            },
                            label = { Text(m, fontSize = 10.sp) }
                        )
                    }
                }
                Text("Választás", fontSize = 12.sp, color = Color.Gray)
                picks.forEach { (value, label) ->
                    FilterChip(
                        selected = pick == value,
                        onClick = { pick = value },
                        label = { Text(label, fontSize = 12.sp) }
                    )
                }
                Text(
                    "Tájékoztató jellegű – nem fogadási tanács.",
                    fontSize = 10.sp,
                    color = Color.Gray
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                TicketPrefs.add(
                    context,
                    TicketLeg(
                        id = UUID.randomUUID().toString(),
                        matchId = match.id,
                        homeTeam = match.homeTeam,
                        awayTeam = match.awayTeam,
                        market = market,
                        pick = pick
                    )
                )
                onAdded()
                onDismiss()
            }) { Text("Hozzáad") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Mégse") }
        }
    )
}
