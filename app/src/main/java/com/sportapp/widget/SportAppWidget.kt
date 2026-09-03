package com.sportapp.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.sportapp.MainActivity
import com.sportapp.R
import com.sportapp.ui.MatchCache
import java.time.LocalDate

class SportAppWidget : AppWidgetProvider() {
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        val favPrefs = context.getSharedPreferences("match_screen_preferences", Context.MODE_PRIVATE)
        val favCount = favPrefs.getStringSet("favorite_matches", emptySet())?.size ?: 0
        val wp = context.getSharedPreferences("sport_widget", Context.MODE_PRIVATE)
        val tName = wp.getString("ticket_name", null)
        val liveT = wp.getInt("live", 0)
        val won = wp.getInt("won", 0)
        val lost = wp.getInt("lost", 0)
        val total = wp.getInt("total", 0)

        // Élő / következő meccs a helyi cache-ből
        val today = try {
            LocalDate.now().toString()
        } catch (_: Exception) {
            ""
        }
        val matches = if (today.isNotEmpty()) MatchCache.load(context, today) else emptyList()
        val liveMatch = matches.firstOrNull { m ->
            val s = (m.status ?: "").trim().uppercase().replace(".", "")
            s in setOf("1H", "2H", "HT", "LIVE", "ET", "INPLAY") ||
                ((m.minute ?: 0) > 0 && s !in setOf("FT", "AET", "PEN", "NS", "TBD", "PST", "CANC"))
        }
        val nextMatch = matches.firstOrNull { m ->
            val s = (m.status ?: "").trim().uppercase()
            s == "NS" || s.contains(":")
        }

        val (title, line1, line2) = when {
            liveMatch != null -> {
                val score = "${liveMatch.homeScore ?: 0}–${liveMatch.awayScore ?: 0}"
                val min = liveMatch.minute?.takeIf { it > 0 }?.let { " $it'" } ?: ""
                Triple(
                    "SportApp · ÉLŐ",
                    "${liveMatch.homeTeam.orEmpty()} $score ${liveMatch.awayTeam.orEmpty()}",
                    "${liveMatch.league.orEmpty()}$min"
                )
            }
            nextMatch != null -> {
                Triple(
                    "SportApp · Következő",
                    "${nextMatch.homeTeam.orEmpty()} vs ${nextMatch.awayTeam.orEmpty()}",
                    listOfNotNull(nextMatch.kickoffTime, nextMatch.league).joinToString(" · ")
                )
            }
            tName != null && total > 0 -> Triple(
                "SportApp · Szelvény",
                "$tName: $won bejött · $lost bukott · $liveT él",
                "Még élhet: ${(total - lost).coerceAtLeast(0)}/$total"
            )
            favCount > 0 -> Triple(
                "SportApp",
                "Kedvenc meccsek: $favCount",
                "Koppints a listáért"
            )
            else -> Triple(
                "SportApp",
                "Nyisd meg az appot a meccsekhez",
                "Élő állás a cache után jelenik meg"
            )
        }

        for (id in appWidgetIds) {
            val views = RemoteViews(context.packageName, R.layout.sport_widget)
            views.setTextViewText(R.id.widget_title, title)
            views.setTextViewText(R.id.widget_line1, line1)
            views.setTextViewText(R.id.widget_line2, line2)
            val intent = Intent(context, MainActivity::class.java)
            val pi = PendingIntent.getActivity(
                context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_root, pi)
            appWidgetManager.updateAppWidget(id, views)
        }
    }
}
