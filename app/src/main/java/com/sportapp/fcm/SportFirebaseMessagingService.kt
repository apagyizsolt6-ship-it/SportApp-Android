package com.sportapp.fcm

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.sportapp.MainActivity
import com.sportapp.ui.NotifHistoryItem
import com.sportapp.ui.NotifPrefs

class SportFirebaseMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        FcmRegistrar.onNewToken(applicationContext, token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        val data = message.data
        val title = data["title"] ?: message.notification?.title ?: "SportApp"
        val body = data["body"] ?: message.notification?.body ?: ""
        val type = data["type"] ?: "generic"
        showNotification(title, body, type, data["match_id"])
    }

    private fun showNotification(title: String, body: String, type: String, matchId: String?) {
        if (!NotifPrefs.isTypeEnabled(applicationContext, type)) return
        if (NotifPrefs.isQuietNow(applicationContext)) {
            val allowFav = NotifPrefs.allowFavoriteDuringQuiet(applicationContext)
            val isImportant = type in setOf("goal", "kickoff", "red", "ft", "ht")
            if (type in setOf("yellow", "card")) return
            if (!isImportant) return
            if (!allowFav) return
        }
        // Dupla push szűrés: ugyanaz a meccs+típus+szöveg 90 mp-en belül
        val dedupeKey = "fcm|$type|${matchId.orEmpty()}|$title|$body"
        val prefs = getSharedPreferences("fcm_dedupe", MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val last = prefs.getLong(dedupeKey, 0L)
        if (last > 0L && now - last < 90_000L) return
        prefs.edit().putLong(dedupeKey, now).apply()

        NotifPrefs.pushHistory(
            applicationContext,
            NotifHistoryItem(
                id = "${now}-$type",
                title = title,
                body = body,
                type = type,
                matchId = matchId.orEmpty(),
                ts = now
            )
        )
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        ensureChannels(nm)
        val channelId = when (type) {
            "goal" -> CHANNEL_GOALS
            "yellow", "red", "card" -> CHANNEL_CARDS
            "kickoff", "ht", "ft", "status" -> CHANNEL_STATUS
            else -> CHANNEL_STATUS
        }
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (!matchId.isNullOrBlank()) putExtra("open_match_id", matchId)
        }
        val pi = PendingIntent.getActivity(
            this,
            matchId?.hashCode() ?: 0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notif = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pi)
            .build()
        // Stabil ID: ugyanaz a gól felülírja, nem újabb push
        val nid = dedupeKey.hashCode()
        nm.notify(nid, notif)
    }

    companion object {
        const val CHANNEL_GOALS = "sport_goals"
        const val CHANNEL_CARDS = "sport_cards"
        const val CHANNEL_STATUS = "sport_status"

        fun ensureChannels(nm: NotificationManager) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            listOf(
                NotificationChannel(CHANNEL_GOALS, "Gólok", NotificationManager.IMPORTANCE_HIGH),
                NotificationChannel(CHANNEL_CARDS, "Lapok", NotificationManager.IMPORTANCE_DEFAULT),
                NotificationChannel(CHANNEL_STATUS, "Kezdés / félidő / vége", NotificationManager.IMPORTANCE_DEFAULT)
            ).forEach { ch ->
                ch.enableVibration(true)
                nm.createNotificationChannel(ch)
            }
        }
    }
}
