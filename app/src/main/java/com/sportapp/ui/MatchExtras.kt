package com.sportapp.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.sportapp.api.RetrofitInstance
import com.sportapp.models.LineupPlayer
import kotlinx.coroutines.launch
import com.sportapp.models.MatchEvent
import com.sportapp.models.MatchResponse
import com.sportapp.models.StatItem
import org.json.JSONArray
import org.json.JSONObject

object DerbyPrefs {
    /** Klasszikus rivális párok – részleges név egyezés */
    val PAIRS: List<Pair<String, String>> = listOf(
        "Roma" to "Lazio",
        "Inter" to "Milan",
        "Juventus" to "Torino",
        "Barcelona" to "Real Madrid",
        "Atletico" to "Real Madrid",
        "Liverpool" to "Everton",
        "Manchester United" to "Manchester City",
        "Arsenal" to "Tottenham",
        "Chelsea" to "Arsenal",
        "Bayern" to "Dortmund",
        "PSG" to "Marseille",
        "Benfica" to "Porto",
        "Ajax" to "Feyenoord",
        "Celtic" to "Rangers",
        "Boca" to "River",
        "Galatasaray" to "Fenerbahce",
        "Olympiacos" to "Panathinaikos",
        "Ferencváros" to "Újpest",
        "Ferencvaros" to "Ujpest",
        "Fradi" to "Újpest"
    )
    fun isDerby(home: String, away: String): Boolean {
        val h = home.lowercase()
        val a = away.lowercase()
        return PAIRS.any { (x, y) ->
            val xl = x.lowercase(); val yl = y.lowercase()
            (h.contains(xl) && a.contains(yl)) || (h.contains(yl) && a.contains(xl))
        }
    }
}

object GlassPrefs {
    private const val P = "glass_prefs"
    fun alpha(ctx: Context): Float =
        ctx.getSharedPreferences(P, Context.MODE_PRIVATE).getFloat("alpha", 0.85f)
    fun setAlpha(ctx: Context, v: Float) {
        ctx.getSharedPreferences(P, Context.MODE_PRIVATE).edit().putFloat("alpha", v.coerceIn(0.4f, 1f)).apply()
    }
}

object HapticPrefs {
    private const val P = "haptic_prefs"
    fun enabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(P, Context.MODE_PRIVATE).getBoolean("goal_haptic", true)
    fun setEnabled(ctx: Context, v: Boolean) {
        ctx.getSharedPreferences(P, Context.MODE_PRIVATE).edit().putBoolean("goal_haptic", v).apply()
    }
    fun goalVibrate(ctx: Context) {
        if (!enabled(ctx)) return
        try {
            val v = ctx.getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator ?: return
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                v.vibrate(android.os.VibrationEffect.createOneShot(120, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(120)
            }
        } catch (_: Exception) {}
    }
}

object TeamFollowPrefs {
    private const val P = "team_follow_prefs"
    private const val KEY = "teams"
    fun teams(ctx: Context): Set<String> =
        ctx.getSharedPreferences(P, Context.MODE_PRIVATE).getStringSet(KEY, emptySet())?.toSet() ?: emptySet()
    fun toggle(ctx: Context, team: String): Set<String> {
        val name = team.trim()
        if (name.isEmpty()) return teams(ctx)
        val cur = teams(ctx).toMutableSet()
        val key = cur.find { it.equals(name, true) }
        if (key != null) cur.remove(key) else cur.add(name)
        ctx.getSharedPreferences(P, Context.MODE_PRIVATE).edit().putStringSet(KEY, cur).apply()
        return cur
    }
    fun isFollowed(ctx: Context, team: String): Boolean {
        val n = team.trim()
        return teams(ctx).any { it.equals(n, true) || n.contains(it, true) || it.contains(n, true) }
    }
}

object NotifPrefs {
    private const val P = "notif_ux_prefs"
    private const val HISTORY = "history_json"
    private const val QUIET_START = "quiet_start"
    private const val QUIET_END = "quiet_end"
    private const val TYPE_PREFIX = "type_"

    /** gól / sárga / piros / kezdés / félidő / vége külön ki-be */
    fun isTypeEnabled(ctx: Context, type: String): Boolean {
        val key = when (type.lowercase()) {
            "goal", "gól", "gol" -> "goal"
            "yellow", "card" -> "yellow"
            "red" -> "red"
            "kickoff", "start" -> "kickoff"
            "ht", "halftime" -> "ht"
            "ft", "fulltime", "status" -> "ft"
            else -> type.lowercase()
        }
        return ctx.getSharedPreferences(P, Context.MODE_PRIVATE)
            .getBoolean(TYPE_PREFIX + key, true)
    }

    fun setTypeEnabled(ctx: Context, key: String, enabled: Boolean) {
        ctx.getSharedPreferences(P, Context.MODE_PRIVATE)
            .edit().putBoolean(TYPE_PREFIX + key, enabled).apply()
    }

    fun allTypeKeys(): List<Pair<String, String>> = listOf(
        "goal" to "Gól",
        "yellow" to "Sárga lap",
        "red" to "Piros lap",
        "kickoff" to "Kezdés",
        "ht" to "Félidő",
        "ft" to "Vége"
    )

    fun history(ctx: Context): List<NotifHistoryItem> {
        return try {
            val raw = ctx.getSharedPreferences(P, Context.MODE_PRIVATE)
                .getString(HISTORY, null) ?: return emptyList()
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    add(
                        NotifHistoryItem(
                            id = o.optString("id"),
                            title = o.optString("title"),
                            body = o.optString("body"),
                            type = o.optString("type"),
                            matchId = o.optString("matchId"),
                            ts = o.optLong("ts")
                        )
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun pushHistory(ctx: Context, item: NotifHistoryItem) {
        val list = (listOf(item) + history(ctx)).take(20)
        val arr = JSONArray()
        list.forEach {
            arr.put(
                JSONObject()
                    .put("id", it.id)
                    .put("title", it.title)
                    .put("body", it.body)
                    .put("type", it.type)
                    .put("matchId", it.matchId)
                    .put("ts", it.ts)
            )
        }
        ctx.getSharedPreferences(P, Context.MODE_PRIVATE).edit()
            .putString(HISTORY, arr.toString()).apply()
    }

    fun quietHours(ctx: Context): Pair<Int, Int> {
        val p = ctx.getSharedPreferences(P, Context.MODE_PRIVATE)
        return p.getInt(QUIET_START, 23) to p.getInt(QUIET_END, 7)
    }

    fun setQuietHours(ctx: Context, start: Int, end: Int) {
        ctx.getSharedPreferences(P, Context.MODE_PRIVATE).edit()
            .putInt(QUIET_START, start.coerceIn(0, 23))
            .putInt(QUIET_END, end.coerceIn(0, 23))
            .apply()
    }

    fun allowFavoriteDuringQuiet(ctx: Context): Boolean =
        ctx.getSharedPreferences(P, Context.MODE_PRIVATE).getBoolean("quiet_allow_fav", true)

    fun setAllowFavoriteDuringQuiet(ctx: Context, v: Boolean) {
        ctx.getSharedPreferences(P, Context.MODE_PRIVATE).edit().putBoolean("quiet_allow_fav", v).apply()
    }

    fun isQuietNow(ctx: Context): Boolean {
        val (s, e) = quietHours(ctx)
        val h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        return if (s > e) h >= s || h < e else h in s until e
    }
}

data class NotifHistoryItem(
    val id: String,
    val title: String,
    val body: String,
    val type: String,
    val matchId: String,
    val ts: Long
)

object VotePrefs {
    private const val P = "match_votes"
    fun get(ctx: Context, matchId: String): String? =
        ctx.getSharedPreferences(P, Context.MODE_PRIVATE).getString(matchId, null)
    fun set(ctx: Context, matchId: String, vote: String) {
        ctx.getSharedPreferences(P, Context.MODE_PRIVATE).edit()
            .putString(matchId, vote).apply()
    }
}

fun eventBadge(ev: MatchEvent): Pair<String, Color>? {
    val t = (ev.type ?: "").lowercase()
    val extra = listOf(ev.player, ev.assist, ev.result, ev.substituted)
        .filterNotNull().joinToString(" ").lowercase()
    val all = "$t $extra"
    return when {
        "var" in all -> "VAR" to Color(0xFF7C4DFF)
        "pen" in all || "penalty" in all -> "11-es" to Color(0xFFFF7043)
        "red" in all -> "PIROS" to Color(0xFFE53935)
        "yellow" in all -> "SÁRGA" to Color(0xFFFFB300)
        "goal" in all || t == "g" -> "GÓL" to Color(0xFF00E676)
        "subst" in all || "sub" in all -> "CSERE" to Color(0xFF42A5F5)
        else -> null
    }
}

fun worthWatchScore(m: MatchResponse, favLeagues: Set<String>): Int {
    var s = 0
    if (favLeagues.contains(m.league ?: "")) s += 40
    if (topFiveRank(m.league, m.countryCode) != null) s += 35
    if (m.isValueBet == true) s += 20
    if (isMatchLive(m.status, m.minute)) s += 25
    if (isStartingSoon(m, 180)) s += 15
    val hs = m.homeScore ?: 0
    val aws = m.awayScore ?: 0
    if (kotlin.math.abs(hs - aws) <= 1 && isMatchLive(m.status, m.minute)) s += 10
    return s
}

@Composable
fun XgMomentumCard(
    stats: List<StatItem>,
    homeName: String,
    awayName: String,
    card: Color,
    text: Color,
    sub: Color
) {
    fun findXg(sideHome: Boolean): Float? {
        val keys = listOf("xg", "expected goals", "várható gól")
        for (st in stats) {
            val n = (st.name ?: "").lowercase()
            if (keys.any { n.contains(it) }) {
                val v = if (sideHome) st.home else st.away
                return v?.toString()?.replace(",", ".")?.toFloatOrNull()
            }
        }
        return null
    }
    val h = findXg(true) ?: return
    val a = findXg(false) ?: 0f
    val max = (h + a).coerceAtLeast(0.1f)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(card)
            .padding(12.dp)
    ) {
        Text("📈 Momentum / xG", color = text, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        Spacer(modifier = Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(homeName.take(12), color = sub, fontSize = 11.sp, modifier = Modifier.width(72.dp))
            LinearProgressIndicator(
                progress = h / max,
                modifier = Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(4.dp)),
                color = Color(0xFF4DA3FF),
                trackColor = Color(0x33FFFFFF)
            )
            Text(" ${"%.2f".format(h)}", color = text, fontSize = 11.sp)
        }
        Spacer(modifier = Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(awayName.take(12), color = sub, fontSize = 11.sp, modifier = Modifier.width(72.dp))
            LinearProgressIndicator(
                progress = a / max,
                modifier = Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(4.dp)),
                color = Color(0xFFFF7043),
                trackColor = Color(0x33FFFFFF)
            )
            Text(" ${"%.2f".format(a)}", color = text, fontSize = 11.sp)
        }
    }
}

@Composable
fun WhoWinsVote(
    match: MatchResponse,
    ctx: Context,
    card: Color,
    text: Color,
    sub: Color,
    green: Color
) {
    if (isMatchFinished(match.status)) return
    var vote by remember(match.id) { mutableStateOf(VotePrefs.get(ctx, match.id)) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(card)
            .padding(12.dp)
    ) {
        Text("🗳️ Ki nyeri?", color = text, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                "1" to match.homeTeam.take(10),
                "X" to "Döntetlen",
                "2" to match.awayTeam.take(10)
            ).forEach { (k, label) ->
                val sel = vote == k
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (sel) green.copy(alpha = 0.25f) else Color(0x22FFFFFF))
                        .border(
                            1.dp,
                            if (sel) green else Color(0x33FFFFFF),
                            RoundedCornerShape(10.dp)
                        )
                        .clickable {
                            vote = k
                            VotePrefs.set(ctx, match.id, k)
                        }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            k,
                            color = if (sel) green else text,
                            fontWeight = FontWeight.Black,
                            fontSize = 14.sp
                        )
                        Text(label, color = sub, fontSize = 9.sp, maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable
fun PlayerCardDialogWithOpen(
    player: LineupPlayer,
    teamHint: String = "",
    onDismiss: () -> Unit,
    onYoutube: (String) -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    val pos = (player.position ?: "").uppercase().ifBlank { "–" }
    val num = when (val n = player.number) {
        is Number -> n.toInt().toString()
        is String -> n.toDoubleOrNull()?.toInt()?.toString() ?: n
        else -> "–"
    }
    val name = player.name ?: "Játékos"
    val bench = player.isBench == true
    val pid = player.playerId?.trim().orEmpty()
    var summaryText by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }

    LaunchedEffect(pid) {
        if (pid.isBlank()) return@LaunchedEffect
        loading = true
        try {
            val s = RetrofitInstance.api.getPlayerSummary(pid)
            if (s.available == true) {
                val sb = StringBuilder()
                s.season?.let { sb.appendLine("Szezon/liga: $it") }
                s.team?.let { sb.appendLine("Csapat: $it") }
                s.position?.let { sb.appendLine("Poszt: $it") }
                val st = s.stats.orEmpty()
                if (st.isNotEmpty()) {
                    sb.appendLine("Statisztika:")
                    st.entries.take(12).forEach { (k, v) ->
                        sb.appendLine("  • $k: $v")
                    }
                }
                summaryText = sb.toString().ifBlank { s.message }
            } else {
                summaryText = s.message ?: "Nincs szezonadat."
            }
        } catch (e: Exception) {
            summaryText = "Stat betöltés sikertelen: ${e.message}"
        } finally {
            loading = false
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(name, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Csapat: ${teamHint.ifBlank { "–" }}", fontSize = 13.sp)
                Text("Mez: $num  ·  Poszt: $pos  ·  ${if (bench) "Cserepad" else "Kezdő"}", fontSize = 13.sp)
                if (pid.isNotBlank()) {
                    Text("ID: $pid", fontSize = 10.sp, color = Color(0xFF9BB0C9))
                }
                when {
                    loading -> Text("Szezonstat betöltése…", fontSize = 12.sp, color = Color(0xFF9BB0C9))
                    !summaryText.isNullOrBlank() -> Text(summaryText!!, fontSize = 12.sp)
                    else -> Text(
                        "Nincs player_id – a Highlightly nem adta át a játékos azonosítót.",
                        fontSize = 12.sp,
                        color = Color(0xFF9BB0C9)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onYoutube("$name $teamHint football")
            }) { Text("YouTube") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Bezár") }
        }
    )
}

/** Egyszerű csapat infó dialógus */
@Composable
fun TeamQuickDialog(
    teamName: String,
    formLine: String?,
    onDismiss: () -> Unit,
    onYoutube: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF152238))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(teamName, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                if (!formLine.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Forma: $formLine", color = Color(0xFF9BB0C9), fontSize = 13.sp)
                }
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = onYoutube,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5A8))
                ) {
                    Text("YouTube – csapat", color = Color.Black)
                }
                TextButton(onClick = onDismiss) {
                    Text("Bezárás", color = Color(0xFF9BB0C9))
                }
            }
        }
    }
}
