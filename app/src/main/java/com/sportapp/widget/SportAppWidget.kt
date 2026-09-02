package com.sportapp.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.sportapp.MainActivity
import com.sportapp.R

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
        val live = wp.getInt("live", 0)
        val won = wp.getInt("won", 0)
        val lost = wp.getInt("lost", 0)
        val total = wp.getInt("total", 0)

        val line1 = when {
            tName != null && total > 0 ->
                "$tName: $won bejött · $lost bukott · $live él (${total} tipp)"
            favCount > 0 -> "Kedvenc meccsek: $favCount"
            else -> "Nincs aktív szelvény"
        }
        val line2 = if (tName != null && total > 0) {
            val still = (total - lost).coerceAtLeast(0)
            "Még élhet: $still/$total · koppints az appra"
        } else {
            "Koppints a friss listáért"
        }
        for (id in appWidgetIds) {
            val views = RemoteViews(context.packageName, R.layout.sport_widget)
            views.setTextViewText(R.id.widget_title, "SportApp · Szelvény")
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
