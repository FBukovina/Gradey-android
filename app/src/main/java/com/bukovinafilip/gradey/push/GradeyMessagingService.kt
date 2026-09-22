package com.bukovinafilip.gradey.push

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.bukovinafilip.gradey.*
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.*

class GradeyMessagingService : FirebaseMessagingService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    override fun onNewToken(token: String) {
        scope.launch { registerPush(this@GradeyMessagingService, (application as GradeyApplication).activeGraph, token) }
    }
    @android.annotation.SuppressLint("MissingPermission") // Checked with canNotify before launching delivery.
    override fun onMessageReceived(message: RemoteMessage) {
        val graph = (application as GradeyApplication).activeGraph
        if (graph.isDemo || !canNotify(this)) return
        scope.launch {
            try {
                val auth = graph.gradeyAuthRepository.bootstrapSession() ?: return@launch
                if (message.data["userID"] != auth.account.id || !graph.notificationSettings.load().newMarksEnabled) return@launch
                val pending = PendingIntent.getActivity(this@GradeyMessagingService, 0,
                    Intent(this@GradeyMessagingService, MainActivity::class.java).setData(Uri.parse("gradey://marks")),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                createNotificationChannel(this@GradeyMessagingService)
                val notification = NotificationCompat.Builder(this@GradeyMessagingService, MARKS_CHANNEL)
                    .setSmallIcon(R.drawable.ic_gradey_logo)
                    .setContentTitle(message.notification?.title ?: message.data["title"] ?: "New mark")
                    .setContentText(message.notification?.body ?: message.data["body"] ?: "Your school marks have changed.")
                    .setContentIntent(pending).setAutoCancel(true).build()
                NotificationManagerCompat.from(this@GradeyMessagingService).notify((message.messageId ?: System.currentTimeMillis().toString()).hashCode(), notification)
            } catch (e: Exception) { if (e is CancellationException) throw e }
        }
    }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
