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
        val prefs = context.getSharedPreferences("match_screen_preferences", Context.MODE_PRIVATE)
        val favCount = prefs.getStringSet("favorite_matches", emptySet())?.size ?: 0
        val line1 = if (favCount > 0) "Kedvenc meccsek: $favCount" else "Nincs kedvenc – nyisd meg az appot"
        val line2 = "Koppints a friss listáért"
        for (id in appWidgetIds) {
            val views = RemoteViews(context.packageName, R.layout.sport_widget)
            views.setTextViewText(R.id.widget_title, "SportApp")
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
