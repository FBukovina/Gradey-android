package com.bukovinafilip.gradey

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.bukovinafilip.gradey.data.AndroidGradeyGraph
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await

const val MARKS_CHANNEL = "gradey-marks"
fun createNotificationChannel(context: Context) {
    val localized = AppLanguages.localizedContext(context)
    context.getSystemService(NotificationManager::class.java).createNotificationChannel(
        NotificationChannel(MARKS_CHANNEL, localized.getString(R.string.app_new_marks), NotificationManager.IMPORTANCE_DEFAULT).apply { description = localized.getString(R.string.app_new_marks_channel) },
    )
}
fun canNotify(context: Context): Boolean = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
suspend fun registerPush(context: Context, graph: AndroidGradeyGraph, token: String? = null): Boolean {
    if (graph.isDemo || !canNotify(context) || FirebaseApp.getApps(context).isEmpty()) return true
    try {
        val session = graph.gradeyAuthRepository.bootstrapSession() ?: return true
        if (!AccountNotifications.acceptsOwner(context, session.account.id)) return true
        val resolvedToken = token ?: FirebaseMessaging.getInstance().token.await()
        if (!AccountNotifications.acceptsOwner(context, session.account.id) || graph.gradeyAuthRepository.cachedSession()?.account?.id != session.account.id) return true
        graph.devicePushTokenClient.registerDeviceToken(resolvedToken, "android", if (BuildConfig.DEBUG) "sandbox" else "production", session)
        return true
    } catch (e: Exception) { if (e is CancellationException) throw e; return false }
}
