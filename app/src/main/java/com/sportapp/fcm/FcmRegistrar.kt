package com.sportapp.fcm

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.FirebaseMessaging
import com.sportapp.api.RetrofitInstance
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

object FcmRegistrar {

    private const val TAG = "FcmRegistrar"
    private const val PREFS = "fcm_prefs"
    private const val KEY_TOKEN = "fcm_token"
    private const val KEY_FOLLOWED = "followed_matches"

    fun init(context: Context) {
        SportFirebaseMessagingService.ensureChannels(
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        )
        CoroutineScope(Dispatchers.IO).launch {
            val token = refreshToken(context) ?: return@launch
            try {
                val res = RetrofitInstance.api.registerFcmToken(mapOf("token" to token))
                Log.i(TAG, "register ok: $res")
            } catch (e: Exception) {
                Log.e(TAG, "register failed", e)
            }
            // Újraindítás után újra feliratkozás
            followedMatches(context).forEach { matchId ->
                try {
                    RetrofitInstance.api.fcmSubscribe(
                        mapOf("token" to token, "match_id" to matchId)
                    )
                    Log.i(TAG, "re-subscribe $matchId")
                } catch (e: Exception) {
                    Log.e(TAG, "re-subscribe failed $matchId", e)
                }
            }
        }
    }

    fun onNewToken(context: Context, token: String) {
        saveToken(context, token)
        CoroutineScope(Dispatchers.IO).launch {
            try {
                RetrofitInstance.api.registerFcmToken(mapOf("token" to token))
                followedMatches(context).forEach { matchId ->
                    RetrofitInstance.api.fcmSubscribe(
                        mapOf("token" to token, "match_id" to matchId)
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "onNewToken register failed", e)
            }
        }
    }

    suspend fun refreshToken(context: Context): String? = withContext(Dispatchers.IO) {
        try {
            val token = FirebaseMessaging.getInstance().token.await()
            if (token.isNotBlank()) {
                saveToken(context, token)
                Log.i(TAG, "FCM token: ${token.take(12)}…")
                token
            } else null
        } catch (e: Exception) {
            Log.e(TAG, "token fetch failed", e)
            null
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

        CoroutineScope(Dispatchers.IO).launch {
            val token = getToken(context) ?: refreshToken(context)
            if (token.isNullOrBlank()) {
                Log.e(TAG, "no FCM token – cannot subscribe")
                return@launch
            }
            try {
                RetrofitInstance.api.registerFcmToken(mapOf("token" to token))
                if (follow) {
                    val res = RetrofitInstance.api.fcmSubscribe(
                        mapOf("token" to token, "match_id" to matchId)
                    )
                    Log.i(TAG, "subscribe $matchId -> $res")
                    val configured = res["fcm_configured"]
                    if (configured == false || configured == "false") {
                        Log.e(TAG, "Backend FCM_SERVER_KEY hiányzik – push nem fog menni")
                    }
                } else {
                    RetrofitInstance.api.fcmUnsubscribe(
                        mapOf("token" to token, "match_id" to matchId)
                    )
                    Log.i(TAG, "unsubscribe $matchId")
                }
            } catch (e: Exception) {
                Log.e(TAG, "subscribe/unsubscribe failed", e)
            }
        }
    }

    /** Teszt push kérés a backendre */
    fun requestTestPush(context: Context) {
        CoroutineScope(Dispatchers.IO).launch {
            val token = getToken(context) ?: refreshToken(context) ?: return@launch
            try {
                RetrofitInstance.api.fcmTest(mapOf("token" to token))
                Log.i(TAG, "test push requested")
            } catch (e: Exception) {
                Log.e(TAG, "test push failed", e)
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
