package com.sportapp.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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
import com.sportapp.models.MatchResponse
import com.sportapp.api.RetrofitInstance
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/** Egy szelvény-sor */
data class TicketLeg(
    val id: String,
    val matchId: String,
    val homeTeam: String,
    val awayTeam: String,
    val market: String,
    val pick: String,
    val odds: Double? = null,
    val isBanker: Boolean = false,
    val addedAt: Long = System.currentTimeMillis()
)

/** Mentett szelvény (max 20) */
data class SavedTicket(
    val id: String,
    val name: String,
    val createdAt: Long,
    val legs: List<TicketLeg>,
    /** none | all | sys4 | sys5 */
    val systemMode: String = "all"
)

enum class LegStatus {
    PENDING, LIVE, WON, LOST
}

object TicketPrefs {
    private const val P = "ticket_szelveny_v2"
    private const val KEY_ALL = "tickets_json"
    private const val KEY_ACTIVE = "active_id"
    private const val LEGACY = "ticket_szelveny"
    const val MAX_TICKETS = 20

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(P, Context.MODE_PRIVATE)

    private fun legFromJson(o: JSONObject) = TicketLeg(
        id = o.optString("id"),
        matchId = o.optString("matchId"),
        homeTeam = o.optString("homeTeam"),
        awayTeam = o.optString("awayTeam"),
        market = o.optString("market"),
        pick = o.optString("pick"),
        odds = o.optDouble("odds", Double.NaN).let { if (it.isNaN()) null else it },
        isBanker = o.optBoolean("isBanker", false),
        addedAt = o.optLong("addedAt", 0L)
    )

    private fun legToJson(leg: TicketLeg) = JSONObject()
        .put("id", leg.id)
        .put("matchId", leg.matchId)
        .put("homeTeam", leg.homeTeam)
        .put("awayTeam", leg.awayTeam)
        .put("market", leg.market)
        .put("pick", leg.pick)
        .put("odds", leg.odds ?: JSONObject.NULL)
        .put("isBanker", leg.isBanker)
        .put("addedAt", leg.addedAt)

    private fun ticketFromJson(o: JSONObject): SavedTicket {
        val legsArr = o.optJSONArray("legs") ?: JSONArray()
        val legs = buildList {
            for (i in 0 until legsArr.length()) {
                add(legFromJson(legsArr.getJSONObject(i)))
            }
        }
        return SavedTicket(
            id = o.optString("id"),
            name = o.optString("name", "Szelvény"),
            createdAt = o.optLong("createdAt", System.currentTimeMillis()),
            legs = legs,
            systemMode = o.optString("systemMode", "all")
        )
    }

    private fun ticketToJson(t: SavedTicket): JSONObject {
        val legsArr = JSONArray()
        t.legs.forEach { legsArr.put(legToJson(it)) }
        return JSONObject()
            .put("id", t.id)
            .put("name", t.name)
            .put("createdAt", t.createdAt)
            .put("legs", legsArr)
            .put("systemMode", t.systemMode)
    }

    /** Régi egy-szelvényes mentés átvétele */
    private fun migrateLegacy(ctx: Context): List<SavedTicket> {
        val legacy = ctx.getSharedPreferences(LEGACY, Context.MODE_PRIVATE)
        val raw = legacy.getString("legs_json", null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            if (arr.length() == 0) return emptyList()
            val legs = buildList {
                for (i in 0 until arr.length()) add(legFromJson(arr.getJSONObject(i)))
            }
            listOf(
                SavedTicket(
                    id = UUID.randomUUID().toString(),
                    name = "Szelvény 1",
                    createdAt = System.currentTimeMillis(),
                    legs = legs
                )
            )
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun loadAll(ctx: Context): List<SavedTicket> {
        val raw = prefs(ctx).getString(KEY_ALL, null)
        if (raw.isNullOrBlank()) {
            val migrated = migrateLegacy(ctx)
            if (migrated.isNotEmpty()) {
                saveAll(ctx, migrated)
                setActiveId(ctx, migrated.first().id)
                ctx.getSharedPreferences(LEGACY, Context.MODE_PRIVATE).edit().remove("legs_json").apply()
            }
            return migrated
        }
        return try {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) add(ticketFromJson(arr.getJSONObject(i)))
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun saveAll(ctx: Context, tickets: List<SavedTicket>) {
        val arr = JSONArray()
        tickets.take(MAX_TICKETS).forEach { arr.put(ticketToJson(it)) }
        prefs(ctx).edit().putString(KEY_ALL, arr.toString()).apply()
    }

    fun activeId(ctx: Context): String? = prefs(ctx).getString(KEY_ACTIVE, null)

    fun setActiveId(ctx: Context, id: String) {
        prefs(ctx).edit().putString(KEY_ACTIVE, id).apply()
    }

    fun getActive(ctx: Context): SavedTicket? {
        val all = loadAll(ctx)
        if (all.isEmpty()) return null
        val aid = activeId(ctx)
        return all.find { it.id == aid } ?: all.first().also { setActiveId(ctx, it.id) }
    }

    fun createTicket(ctx: Context, name: String? = null): SavedTicket? {
        val all = loadAll(ctx).toMutableList()
        if (all.size >= MAX_TICKETS) return null
        val n = all.size + 1
        val ticket = SavedTicket(
            id = UUID.randomUUID().toString(),
            name = name?.takeIf { it.isNotBlank() } ?: "Szelvény $n",
            createdAt = System.currentTimeMillis(),
            legs = emptyList()
        )
        all.add(0, ticket)
        saveAll(ctx, all)
        setActiveId(ctx, ticket.id)
        return ticket
    }

    fun updateTicket(ctx: Context, ticket: SavedTicket) {
        val all = loadAll(ctx).map { if (it.id == ticket.id) ticket else it }
        saveAll(ctx, all)
    }

    fun deleteTicket(ctx: Context, id: String) {
        val all = loadAll(ctx).filter { it.id != id }
        saveAll(ctx, all)
        if (activeId(ctx) == id) {
            if (all.isNotEmpty()) setActiveId(ctx, all.first().id)
            else prefs(ctx).edit().remove(KEY_ACTIVE).apply()
        }
    }

    fun renameTicket(ctx: Context, id: String, name: String) {
        val all = loadAll(ctx).map {
            if (it.id == id) it.copy(name = name.take(40).ifBlank { it.name }) else it
        }
        saveAll(ctx, all)
    }

    /** Láb hozzáadása az aktív szelvényhez */
    fun addLeg(ctx: Context, leg: TicketLeg, maxLegs: Int = 15): Boolean {
        var active = getActive(ctx)
        if (active == null) {
            active = createTicket(ctx) ?: return false
        }
        val legs = active.legs
            .filter { it.matchId != leg.matchId || it.market != leg.market }
            .toMutableList()
        legs.add(0, leg)
        updateTicket(ctx, active.copy(legs = legs.take(maxLegs)))
        return true
    }

    fun removeLeg(ctx: Context, legId: String) {
        val active = getActive(ctx) ?: return
        updateTicket(ctx, active.copy(legs = active.legs.filter { it.id != legId }))
    }

    fun clearActiveLegs(ctx: Context) {
        val active = getActive(ctx) ?: return
        updateTicket(ctx, active.copy(legs = emptyList()))
    }

    fun toggleBanker(ctx: Context, legId: String) {
        val active = getActive(ctx) ?: return
        val legs = active.legs.map {
            if (it.id == legId) it.copy(isBanker = !it.isBanker) else it
        }
        updateTicket(ctx, active.copy(legs = legs))
    }

    fun setSystemMode(ctx: Context, mode: String) {
        val active = getActive(ctx) ?: return
        updateTicket(ctx, active.copy(systemMode = mode))
    }

    fun applyTemplate(ctx: Context, templateName: String): SavedTicket? {
        val t = createTicket(ctx, templateName) ?: return null
        return t
    }

    /** Aktív szelvény meccs-ID-k – lista badge-hez */
    fun activeMatchIds(ctx: Context): Set<String> {
        return getActive(ctx)?.legs?.map { it.matchId }?.toSet() ?: emptySet()
    }

    fun minOdds(ctx: Context): Double =
        prefs(ctx).getFloat("min_odds", 0f).toDouble()

    fun setMinOdds(ctx: Context, v: Double) {
        prefs(ctx).edit().putFloat("min_odds", v.toFloat()).apply()
    }

    /** Archív: lezárt szelvények statja */
    private const val KEY_ARCHIVE = "archive_json"

    data class ArchiveEntry(
        val ticketName: String,
        val ts: Long,
        val won: Int,
        val lost: Int,
        val total: Int
    )

    fun loadArchive(ctx: Context): List<ArchiveEntry> {
        val raw = prefs(ctx).getString(KEY_ARCHIVE, "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    add(
                        ArchiveEntry(
                            ticketName = o.optString("name"),
                            ts = o.optLong("ts"),
                            won = o.optInt("won"),
                            lost = o.optInt("lost"),
                            total = o.optInt("total")
                        )
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun pushArchive(ctx: Context, name: String, won: Int, lost: Int, total: Int) {
        val cur = loadArchive(ctx).toMutableList()
        cur.add(0, ArchiveEntry(name, System.currentTimeMillis(), won, lost, total))
        val arr = JSONArray()
        cur.take(30).forEach { e ->
            arr.put(
                JSONObject()
                    .put("name", e.ticketName)
                    .put("ts", e.ts)
                    .put("won", e.won)
                    .put("lost", e.lost)
                    .put("total", e.total)
            )
        }
        prefs(ctx).edit().putString(KEY_ARCHIVE, arr.toString()).apply()
    }

    fun hitRatePercent(ctx: Context): Int {
        val arch = loadArchive(ctx)
        val w = arch.sumOf { it.won }
        val tot = arch.sumOf { it.total }.coerceAtLeast(1)
        return ((w * 100) / tot)
    }

    /** Widget + cache */
    fun writeWidgetSnapshot(ctx: Context, live: Int, won: Int, lost: Int, total: Int, name: String) {
        ctx.getSharedPreferences("sport_widget", Context.MODE_PRIVATE).edit()
            .putString("ticket_name", name)
            .putInt("live", live)
            .putInt("won", won)
            .putInt("lost", lost)
            .putInt("total", total)
            .putLong("ts", System.currentTimeMillis())
            .apply()
    }
}

/** Kombinációk száma bankárokkal (egyszerűsített) */
/** Egyszerű korreláció: túl sok ugyanabból a meccs-napból / ugyanaz a pick irány */
fun ticketCorrelationWarning(legs: List<TicketLeg>): String? {
    if (legs.size < 3) return null
    val homes = legs.count { it.pick.trim().uppercase().let { p -> p == "1" || p.startsWith("1") || "HAZAI" in p } }
    if (homes >= legs.size - 1 && legs.size >= 4) {
        return "Figyelem: majdnem minden láb hazai – erős korreláció."
    }
    val sameMatch = legs.groupBy { it.matchId }.any { it.value.size > 1 }
    if (sameMatch) return "Ugyanarra a meccsre több piac is van a szelvényen."
    return null
}

fun systemComboCount(legs: List<TicketLeg>, mode: String): Int {
    val bankers = legs.count { it.isBanker }
    val rest = legs.size - bankers
    if (rest < 0) return 0
    fun comb(n: Int, k: Int): Int {
        if (k < 0 || k > n) return 0
        if (k == 0 || k == n) return 1
        var r = 1
        for (i in 0 until k) r = r * (n - i) / (i + 1)
        return r
    }
    return when (mode) {
        "sys4" -> if (legs.size < 4) 0 else comb(rest, (4 - bankers).coerceAtLeast(0))
        "sys5" -> if (legs.size < 5) 0 else comb(rest, (5 - bankers).coerceAtLeast(0))
        "all" -> 1
        else -> 1
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

    return when {
        market == "1X2" || market == "MATCH" -> {
            val r = result1x2()
            if (r == null) return if (live) LegStatus.LIVE else LegStatus.PENDING
            val want = when {
                pick.startsWith("1") || pick.contains("HAZAI") -> "1"
                pick.startsWith("2") || pick.contains("VENDÉG") || pick.contains("VENDEG") -> "2"
                pick.startsWith("X") || pick.contains("DÖNTETLEN") || pick.contains("DONTETLEN") || pick.contains("DRAW") -> "X"
                else -> pick.take(1)
            }
            return if (finished) {
                if (r == want) LegStatus.WON else LegStatus.LOST
            } else if (live) LegStatus.LIVE else LegStatus.PENDING
        }
        market == "BTTS" -> {
            if (hs == null || as_ == null) return if (live) LegStatus.LIVE else LegStatus.PENDING
            val both = hs > 0 && as_ > 0
            val wantYes = pick.contains("IGEN") || pick == "YES" || pick == "Y"
            val wantNo = pick.contains("NEM") || pick == "NO" || pick == "N"
            if (!finished) {
                if (wantYes && both) return LegStatus.WON
                if (wantNo && both) return LegStatus.LOST
                return if (live) LegStatus.LIVE else LegStatus.PENDING
            }
            val ok = if (wantNo) !both else both
            return if (ok) LegStatus.WON else LegStatus.LOST
        }
        market == "OU25" || market.contains("OVER") || market.contains("UNDER") -> {
            if (hs == null || as_ == null) return if (live) LegStatus.LIVE else LegStatus.PENDING
            val total = hs + as_
            val under = pick.contains("UNDER") || pick.contains("KEVESEBB")
            if (!finished) {
                if (!under && total >= 3) return LegStatus.WON
                if (under && total >= 3) return LegStatus.LOST
                return if (live) LegStatus.LIVE else LegStatus.PENDING
            }
            val ok = if (under) total < 3 else total >= 3
            return if (ok) LegStatus.WON else LegStatus.LOST
        }
        market == "DC_1X" || market == "1X" -> {
            val r = result1x2() ?: return if (live) LegStatus.LIVE else LegStatus.PENDING
            if (!finished) return if (live) LegStatus.LIVE else LegStatus.PENDING
            return if (r == "1" || r == "X") LegStatus.WON else LegStatus.LOST
        }
        market == "DC_X2" || market == "X2" -> {
            val r = result1x2() ?: return if (live) LegStatus.LIVE else LegStatus.PENDING
            if (!finished) return if (live) LegStatus.LIVE else LegStatus.PENDING
            return if (r == "X" || r == "2") LegStatus.WON else LegStatus.LOST
        }
        market == "DNB" -> {
            // Döntetlennél visszajár – élőben csak követünk
            val r = result1x2()
            if (r == null) return if (live) LegStatus.LIVE else LegStatus.PENDING
            if (!finished) return if (live) LegStatus.LIVE else LegStatus.PENDING
            if (r == "X") return LegStatus.PENDING // push / void-szerű
            val wantHome = pick.contains("1") || pick.contains("HAZAI")
            return if (wantHome) {
                if (r == "1") LegStatus.WON else LegStatus.LOST
            } else {
                if (r == "2") LegStatus.WON else LegStatus.LOST
            }
        }
        market == "AH" || market.contains("HANDICAP") || market.contains("HENDIK") -> {
            // Egyszerű ázsiai: pick pl. "Hazai -0.5" – ha nincs szám, 1X2-ként
            if (!finished) return if (live) LegStatus.LIVE else LegStatus.PENDING
            val r = result1x2() ?: return LegStatus.PENDING
            val wantHome = pick.contains("HAZAI") || pick.startsWith("1") || pick.contains("-")
            return if (wantHome) {
                if (r == "1") LegStatus.WON else LegStatus.LOST
            } else {
                if (r == "2") LegStatus.WON else LegStatus.LOST
            }
        }
        market == "OU15" -> {
            if (hs == null || as_ == null) return if (live) LegStatus.LIVE else LegStatus.PENDING
            val total = hs + as_
            val under = pick.contains("UNDER") || pick.contains("KEVESEBB")
            if (!finished) {
                if (!under && total >= 2) return LegStatus.WON
                if (under && total >= 2) return LegStatus.LOST
                return if (live) LegStatus.LIVE else LegStatus.PENDING
            }
            val ok = if (under) total < 2 else total >= 2
            return if (ok) LegStatus.WON else LegStatus.LOST
        }
        market == "OU35" -> {
            if (hs == null || as_ == null) return if (live) LegStatus.LIVE else LegStatus.PENDING
            val total = hs + as_
            val under = pick.contains("UNDER") || pick.contains("KEVESEBB")
            if (!finished) {
                if (!under && total >= 4) return LegStatus.WON
                if (under && total >= 4) return LegStatus.LOST
                return if (live) LegStatus.LIVE else LegStatus.PENDING
            }
            val ok = if (under) total < 4 else total >= 4
            return if (ok) LegStatus.WON else LegStatus.LOST
        }
        else -> if (finished) LegStatus.PENDING else if (live) LegStatus.LIVE else LegStatus.PENDING
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
    var tickets by remember { mutableStateOf(TicketPrefs.loadAll(context)) }
    var activeId by remember {
        mutableStateOf(TicketPrefs.activeId(context) ?: tickets.firstOrNull()?.id)
    }
    var showNameDialog by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<SavedTicket?>(null) }
    var newName by remember { mutableStateOf("") }

    fun reload() {
        tickets = TicketPrefs.loadAll(context)
        activeId = TicketPrefs.activeId(context) ?: tickets.firstOrNull()?.id
    }

    val active = tickets.find { it.id == activeId } ?: tickets.firstOrNull()
    val legs = active?.legs.orEmpty()
    val matchMap = remember(matches) { matches.associateBy { it.id } }

    val minOddsFilter = TicketPrefs.minOdds(context)
    val legsFiltered = if (minOddsFilter > 0.01) {
        legs.filter { leg -> leg.odds == null || (leg.odds ?: 0.0) >= minOddsFilter }
    } else legs
    val evaluated = legsFiltered.map { leg -> leg to evaluateTicketLeg(leg, matchMap[leg.matchId]) }
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
        Text("Szelvény segéd", color = textColor, fontWeight = FontWeight.Bold, fontSize = 20.sp)
        Text(
            "Élő követés · max ${TicketPrefs.MAX_TICKETS} mentett szelvény · nem fogadási tanács",
            color = subTextColor,
            fontSize = 11.sp
        )
        Spacer(Modifier.height(10.dp))

        // Szelvény választó sáv
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            tickets.forEach { t ->
                val selected = t.id == active?.id
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (selected) primaryGreen.copy(alpha = 0.25f) else cardBg,
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        if (selected) primaryGreen else Color(0x33A0C4FF)
                    ),
                    modifier = Modifier.clickable {
                        TicketPrefs.setActiveId(context, t.id)
                        activeId = t.id
                    }
                ) {
                    Text(
                        "${t.name} (${t.legs.size})",
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        color = if (selected) primaryGreen else textColor,
                        fontSize = 12.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = cardBg,
                border = androidx.compose.foundation.BorderStroke(1.dp, primaryGreen.copy(alpha = 0.5f)),
                modifier = Modifier.clickable {
                    if (tickets.size >= TicketPrefs.MAX_TICKETS) return@clickable
                    newName = "Szelvény ${tickets.size + 1}"
                    showNameDialog = true
                }
            ) {
                Text(
                    "+ Új",
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    color = primaryGreen,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Spacer(Modifier.height(10.dp))

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
                Column {
                    Text(
                        active?.name ?: "Nincs szelvény",
                        color = textColor,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp
                    )
                    active?.let {
                        val df = remember { SimpleDateFormat("MM.dd HH:mm", Locale.getDefault()) }
                        Text(
                            "Mentve: ${df.format(Date(it.createdAt))} · ${it.legs.size} tipp",
                            color = subTextColor,
                            fontSize = 11.sp
                        )
                    }
                }
                Text("${tickets.size}/${TicketPrefs.MAX_TICKETS}", color = subTextColor, fontSize = 12.sp)
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
            val settled = wonCount + lostCount
            if (legs.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "Szelvény állás: $wonCount/$settled lezárt · ${legs.size - lostCount}/${legs.size} még élhet",
                    color = subTextColor,
                    fontSize = 11.sp
                )
            }
            ticketCorrelationWarning(legs)?.let { warn ->
                Spacer(Modifier.height(4.dp))
                Text(warn, color = Color(0xFFFFB74D), fontSize = 11.sp)
            }
            // Widget snapshot
            LaunchedEffect(wonCount, lostCount, liveCount, legs.size, active?.name) {
                TicketPrefs.writeWidgetSnapshot(
                    context,
                    liveCount, wonCount, lostCount, legs.size,
                    active?.name ?: "Szelvény"
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        // Rendszer + sablonok
        if (active != null) {
            val combo = systemComboCount(legs, active.systemMode)
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf(
                    "all" to "Egyes",
                    "sys4" to "4-es rsz.",
                    "sys5" to "5-ös rsz."
                ).forEach { (mode, label) ->
                    val sel = active.systemMode == mode
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (sel) primaryGreen.copy(alpha = 0.25f) else cardBg,
                        modifier = Modifier.clickable {
                            TicketPrefs.setSystemMode(context, mode)
                            reload()
                        }
                    ) {
                        Text(
                            label,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            color = if (sel) primaryGreen else textColor,
                            fontSize = 11.sp
                        )
                    }
                }
                Text(
                    "≈ $combo szelvény",
                    color = subTextColor,
                    fontSize = 11.sp,
                    modifier = Modifier.align(Alignment.CenterVertically)
                )
            }
            Spacer(Modifier.height(6.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                var aiLoading by remember { mutableStateOf(false) }
                val scope = rememberCoroutineScope()
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = cardBg,
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF4DA3FF).copy(alpha = 0.5f)),
                    modifier = Modifier.clickable(enabled = !aiLoading) {
                        aiLoading = true
                        scope.launch {
                            try {
                                val r = RetrofitInstance.api.getDailyTips(refresh = 1)
                                val raw = r["tips"]
                                if (raw is List<*>) {
                                    if (TicketPrefs.getActive(context) == null) TicketPrefs.createTicket(context, "AI napi")
                                    raw.take(3).forEach { item ->
                                        if (item is Map<*, *>) {
                                            val matchName = item["match"]?.toString().orEmpty()
                                            val market = item["market"]?.toString() ?: "1X2"
                                            val pick = item["pick"]?.toString() ?: "1"
                                            val parts = matchName.split("–", "-", "vs", "VS").map { it.trim() }
                                            val home = parts.getOrNull(0) ?: matchName
                                            val away = parts.getOrNull(1) ?: ""
                                            val mid = matches.find {
                                                it.homeTeam.contains(home.take(6), true) ||
                                                    (away.isNotBlank() && it.awayTeam.contains(away.take(6), true))
                                            }?.id ?: "ai-${matchName.hashCode()}"
                                            TicketPrefs.addLeg(
                                                context,
                                                TicketLeg(
                                                    id = java.util.UUID.randomUUID().toString(),
                                                    matchId = mid,
                                                    homeTeam = home,
                                                    awayTeam = away.ifBlank { "?" },
                                                    market = when {
                                                        "btts" in market.lowercase() -> "BTTS"
                                                        "over" in market.lowercase() || "under" in market.lowercase() -> "OU25"
                                                        "1x" in market.lowercase() -> "DC_1X"
                                                        else -> "1X2"
                                                    },
                                                    pick = pick
                                                )
                                            )
                                        }
                                    }
                                    reload()
                                }
                            } catch (_: Exception) {
                            } finally {
                                aiLoading = false
                            }
                        }
                    }
                ) {
                    Text(
                        if (aiLoading) "AI…" else "AI tippek →",
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        color = Color(0xFF4DA3FF),
                        fontSize = 11.sp
                    )
                }
                listOf("Gólos nap", "1X védő", "Favoritok", "Esti mix").forEach { tpl ->
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = cardBg,
                        border = androidx.compose.foundation.BorderStroke(1.dp, primaryGreen.copy(alpha = 0.4f)),
                        modifier = Modifier.clickable {
                            TicketPrefs.applyTemplate(context, tpl)
                            reload()
                        }
                    ) {
                        Text(
                            tpl,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            color = primaryGreen,
                            fontSize = 11.sp
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(
                onClick = onRequestAddFromList,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
            ) { Text("Meccs +", fontSize = 11.sp) }
            TextButton(
                onClick = {
                    active?.let {
                        newName = it.name
                        renameTarget = it
                    }
                },
                enabled = active != null
            ) { Text("Átnevez", fontSize = 11.sp) }
            TextButton(onClick = {
                TicketPrefs.clearActiveLegs(context)
                reload()
            }, enabled = legs.isNotEmpty()) {
                Text("Ürít", fontSize = 11.sp, color = Color(0xFFFF9800))
            }
            TextButton(
                onClick = {
                    if (legs.isNotEmpty() && lostCount + wonCount > 0) {
                        TicketPrefs.pushArchive(
                            context,
                            active?.name ?: "Szelvény",
                            wonCount,
                            lostCount,
                            legs.size
                        )
                        reload()
                    }
                },
                enabled = legs.isNotEmpty() && (wonCount + lostCount) > 0
            ) { Text("Archívál", fontSize = 11.sp) }
            TextButton(
                onClick = {
                    active?.let {
                        TicketPrefs.deleteTicket(context, it.id)
                        reload()
                    }
                },
                enabled = active != null
            ) { Text("Töröl", fontSize = 11.sp, color = Color(0xFFE53935)) }
            if (legs.isNotEmpty()) {
                TextButton(onClick = {
                    val body = buildString {
                        append("⚽ ${active?.name ?: "Szelvény"}\n")
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
                }) { Text("↗", fontSize = 12.sp) }
            }
        }

        Spacer(Modifier.height(8.dp))


        // Min. szorzó + archív
        var minOdds by remember { mutableStateOf(TicketPrefs.minOdds(context)) }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Min. odd:", color = subTextColor, fontSize = 11.sp)
            listOf(0.0 to "Mind", 1.5 to "1.5+", 1.8 to "1.8+", 2.0 to "2.0+").forEach { (v, lab) ->
                val sel = kotlin.math.abs(minOdds - v) < 0.01
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (sel) primaryGreen.copy(alpha = 0.25f) else cardBg,
                    modifier = Modifier.clickable {
                        minOdds = v
                        TicketPrefs.setMinOdds(context, v)
                    }
                ) {
                    Text(lab, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), fontSize = 10.sp, color = if (sel) primaryGreen else textColor)
                }
            }
            val hr = TicketPrefs.hitRatePercent(context)
            if (hr > 0 || TicketPrefs.loadArchive(context).isNotEmpty()) {
                Text("Archív $hr%", color = primaryGreen, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        Spacer(Modifier.height(6.dp))
        val archive = remember { TicketPrefs.loadArchive(context) }
        if (archive.isNotEmpty()) {
            Text("Legutóbbi szelvények", color = subTextColor, fontSize = 11.sp)
            archive.take(3).forEach { e ->
                Text(
                    "• ${e.ticketName}: ${e.won}/${e.total} (${if (e.total > 0) e.won * 100 / e.total else 0}%)",
                    color = textColor,
                    fontSize = 11.sp
                )
            }
            Spacer(Modifier.height(6.dp))
        }

        if (tickets.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Még nincs mentett szelvény.", color = subTextColor, fontSize = 13.sp)
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = {
                        TicketPrefs.createTicket(context)
                        reload()
                    }) { Text("Első szelvény létrehozása") }
                }
            }
        } else if (legs.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(
                    "Ez a szelvény üres.\n🎫 a listán, vagy „Meccs +”.\nA sorok automatikusan mentődnek.",
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
                        onToggleBanker = {
                            TicketPrefs.toggleBanker(context, leg.id)
                            reload()
                        },
                        onRemove = {
                            TicketPrefs.removeLeg(context, leg.id)
                            reload()
                        }
                    )
                }
            }
        }
    }

    // Új szelvény név
    if (showNameDialog) {
        AlertDialog(
            onDismissRequest = { showNameDialog = false },
            title = { Text("Új szelvény") },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("Név") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val t = TicketPrefs.createTicket(context, newName)
                    showNameDialog = false
                    reload()
                    if (t == null) {
                        // max elérve
                    }
                }) { Text("Mentés") }
            },
            dismissButton = {
                TextButton(onClick = { showNameDialog = false }) { Text("Mégse") }
            }
        )
    }

    // Átnevezés
    renameTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Átnevezés") },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("Név") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    TicketPrefs.renameTicket(context, target.id, newName)
                    renameTarget = null
                    reload()
                }) { Text("Mentés") }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) { Text("Mégse") }
            }
        )
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
    onToggleBanker: () -> Unit = {},
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
                buildString {
                    append("${leg.market} · ${leg.pick}")
                    leg.odds?.let { append(" @ ${"%.2f".format(it)}") }
                    if (leg.isBanker) append(" · BANKÁR")
                },
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
        Surface(shape = RoundedCornerShape(8.dp), color = badgeColor.copy(alpha = 0.2f)) {
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
            if (leg.isBanker) "🔒" else "🏦",
            fontSize = 13.sp,
            modifier = Modifier.clickable(onClick = onToggleBanker).padding(4.dp)
        )
        Text(
            "✕",
            color = subTextColor,
            fontSize = 14.sp,
            modifier = Modifier.clickable(onClick = onRemove).padding(4.dp)
        )
    }
}

@Composable
fun AddToTicketDialog(
    match: MatchResponse,
    onDismiss: () -> Unit,
    onAdded: () -> Unit,
    prefillMarket: String? = null,
    prefillPick: String? = null,
    prefillOdds: Double? = null
) {
    val context = LocalContext.current
    var market by remember { mutableStateOf(prefillMarket ?: "1X2") }
    var pick by remember { mutableStateOf(prefillPick ?: "1") }
    var oddsText by remember {
        mutableStateOf(prefillOdds?.let { "%.2f".format(it) } ?: "")
    }
    val activeName = remember { TicketPrefs.getActive(context)?.name ?: "aktív szelvény" }

    val picks = when (market) {
        "1X2" -> listOf("1" to "Hazai (1)", "X" to "Döntetlen", "2" to "Vendég (2)")
        "BTTS" -> listOf("Igen" to "BTTS Igen", "Nem" to "BTTS Nem")
        "OU15" -> listOf("Over 1.5" to "Több 1.5", "Under 1.5" to "Kevesebb 1.5")
        "OU25" -> listOf("Over 2.5" to "Több 2.5", "Under 2.5" to "Kevesebb 2.5")
        "OU35" -> listOf("Over 3.5" to "Több 3.5", "Under 3.5" to "Kevesebb 3.5")
        "DC_1X" -> listOf("1X" to "Hazai nem kap ki")
        "DC_X2" -> listOf("X2" to "Vendég nem kap ki")
        "DNB" -> listOf("1" to "Hazai DNB", "2" to "Vendég DNB")
        "AH" -> listOf("Hazai -0.5" to "Hazai -0.5", "Vendég -0.5" to "Vendég -0.5", "Hazai +0.5" to "Hazai +0.5")
        else -> listOf("1" to "1")
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Szelvényre", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${match.homeTeam} – ${match.awayTeam}", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text("Mentés ide: $activeName", fontSize = 11.sp, color = Color.Gray)
                Text("Piac", fontSize = 12.sp, color = Color.Gray)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("1X2", "BTTS", "OU15", "OU25", "OU35", "DC_1X", "DC_X2", "DNB", "AH").forEach { m ->
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = if (market == m) Color(0x3300E5A8) else Color(0x22FFFFFF),
                            modifier = Modifier.clickable {
                                market = m
                                pick = when (m) {
                                    "1X2" -> "1"
                                    "BTTS" -> "Igen"
                                    "OU15" -> "Over 1.5"
                                    "OU25" -> "Over 2.5"
                                    "OU35" -> "Over 3.5"
                                    "DC_1X" -> "1X"
                                    "DC_X2" -> "X2"
                                    "DNB" -> "1"
                                    "AH" -> "Hazai -0.5"
                                    else -> "1"
                                }
                            }
                        ) {
                            Text(m, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp), fontSize = 11.sp)
                        }
                    }
                }
                Text("Választás", fontSize = 12.sp, color = Color.Gray)
                picks.forEach { (value, label) ->
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = if (pick == value) Color(0x3300E5A8) else Color(0x22FFFFFF),
                        modifier = Modifier.clickable { pick = value }
                    ) {
                        Text(label, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp), fontSize = 12.sp)
                    }
                }
                OutlinedTextField(
                    value = oddsText,
                    onValueChange = { oddsText = it },
                    label = { Text("Szorzó (opcionális)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text("Automatikusan mentődik az aktív szelvényre.", fontSize = 10.sp, color = Color.Gray)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (TicketPrefs.getActive(context) == null) {
                    TicketPrefs.createTicket(context)
                }
                val oddsVal = oddsText.replace(",", ".").toDoubleOrNull()
                    ?: match.oddsHome.takeIf { market == "1X2" && pick == "1" }
                    ?: match.oddsDraw.takeIf { market == "1X2" && pick == "X" }
                    ?: match.oddsAway.takeIf { market == "1X2" && pick == "2" }
                TicketPrefs.addLeg(
                    context,
                    TicketLeg(
                        id = UUID.randomUUID().toString(),
                        matchId = match.id,
                        homeTeam = match.homeTeam,
                        awayTeam = match.awayTeam,
                        market = market,
                        pick = pick,
                        odds = oddsVal
                    )
                )
                onAdded()
                onDismiss()
            }) { Text("Mentés") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Mégse") }
        }
    )
}
