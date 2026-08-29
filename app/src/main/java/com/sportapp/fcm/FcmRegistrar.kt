package com.sportapp.fcm

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.FirebaseMessaging
import com.sportapp.api.RetrofitInstance
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

object FcmRegistrar {

    private const val PREFS = "fcm_prefs"
    private const val KEY_TOKEN = "fcm_token"
    private const val KEY_FOLLOWED = "followed_matches"

    fun init(context: Context) {
        SportFirebaseMessagingService.ensureChannels(
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        )
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val token = FirebaseMessaging.getInstance().token.await()
                saveToken(context, token)
                RetrofitInstance.api.registerFcmToken(mapOf("token" to token))
            } catch (_: Exception) {
            }
        }
    }

    fun onNewToken(context: Context, token: String) {
        saveToken(context, token)
        CoroutineScope(Dispatchers.IO).launch {
            try {
                RetrofitInstance.api.registerFcmToken(mapOf("token" to token))
            } catch (_: Exception) {
            }
        }
    }

    fun getToken(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_TOKEN, null)

    private fun saveToken(context: Context, token: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_TOKEN, token).apply()
    }

    fun followedMatches(context: Context): Set<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY_FOLLOWED, emptySet())?.toSet() ?: emptySet()

    fun isFollowing(context: Context, matchId: String): Boolean =
        followedMatches(context).contains(matchId)

    fun setFollowing(context: Context, matchId: String, follow: Boolean) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val set = prefs.getStringSet(KEY_FOLLOWED, emptySet())?.toMutableSet() ?: mutableSetOf()
        if (follow) set.add(matchId) else set.remove(matchId)
        prefs.edit().putStringSet(KEY_FOLLOWED, set).apply()
        val token = getToken(context) ?: return
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (follow) {
                    RetrofitInstance.api.fcmSubscribe(mapOf("token" to token, "match_id" to matchId))
                } else {
                    RetrofitInstance.api.fcmUnsubscribe(mapOf("token" to token, "match_id" to matchId))
                }
            } catch (_: Exception) {
            }
        }
    }

    fun hasNotificationPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 33) return true
        return ContextCompat.checkSelfPermission(
            context, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }
}
