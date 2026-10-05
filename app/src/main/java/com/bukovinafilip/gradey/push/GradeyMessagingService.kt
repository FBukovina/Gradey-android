package com.bukovinafilip.gradey.push

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.bukovinafilip.gradey.*
import com.bukovinafilip.gradey.domain.NotificationPolicy
import com.bukovinafilip.gradey.model.NotificationLockScreenDetail
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.*

class GradeyMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        val work = androidx.work.OneTimeWorkRequestBuilder<PushRegistrationWorker>()
            .setConstraints(androidx.work.Constraints.Builder().setRequiredNetworkType(androidx.work.NetworkType.CONNECTED).build())
            .build()
        androidx.work.WorkManager.getInstance(this).enqueueUniqueWork("gradey-register-push", androidx.work.ExistingWorkPolicy.REPLACE, work)
    }
    @android.annotation.SuppressLint("MissingPermission") // Checked with canNotify before launching delivery.
    override fun onMessageReceived(message: RemoteMessage) {
        val localized = AppLanguages.localizedContext(this)
        val graph = (application as GradeyApplication).activeGraph
        if (graph.isDemo || !canNotify(this)) return
        val policyEpoch = AccountNotifications.captureMarksPolicyEpoch()
        // FCM invokes this on its background executor. All reads below are local; finish delivery
        // within the callback lifetime instead of launching a coroutine the service can cancel.
        runBlocking {
            try { withTimeout(7_000) {
                val auth = graph.gradeyAuthRepository.cachedSession() ?: return@withTimeout
                if (!AccountNotifications.acceptsOwner(this@GradeyMessagingService, auth.account.id)) return@withTimeout
                val preferences = graph.notificationSettings.load()
                if (message.data["userID"] != auth.account.id || !preferences.newMarksEnabled || NotificationPolicy.isQuiet(preferences)) return@withTimeout
                val accountScope = PushAccountScope.parse(message.data) ?: return@withTimeout
                if (!accountScope.allEnabledIn(graph.linkedAccountRepository.cachedAccounts())) return@withTimeout
                // The identity can change while loading preferences or accounts.
                if ((application as GradeyApplication).activeGraph !== graph || graph.gradeyAuthRepository.cachedSession()?.account?.id != auth.account.id) return@withTimeout
                val pending = PendingIntent.getActivity(this@GradeyMessagingService, 0,
                    Intent(this@GradeyMessagingService, MainActivity::class.java).setData(Uri.parse("gradey://marks")),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                createNotificationChannel(this@GradeyMessagingService)
                val body = message.notification?.body ?: message.data["body"] ?: localized.getString(R.string.app_marks_changed)
                val publicBody = NotificationPolicy.lockScreenBody(preferences.lockScreenDetail, body, message.data["subjectName"], message.data["markText"], localized.getString(R.string.app_private_marks))
                val publicVersion = NotificationCompat.Builder(this@GradeyMessagingService, MARKS_CHANNEL)
                    .setSmallIcon(R.drawable.ic_notification).setContentTitle("Gradey").setContentText(publicBody).build()
                val notification = NotificationCompat.Builder(this@GradeyMessagingService, MARKS_CHANNEL)
                    .setOnlyAlertOnce(true)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle(if (preferences.lockScreenDetail == NotificationLockScreenDetail.PRIVATE_SUMMARY) "Gradey" else message.notification?.title ?: message.data["title"] ?: localized.getString(R.string.app_new_mark))
                    .setContentText(if (preferences.lockScreenDetail == NotificationLockScreenDetail.PRIVATE_SUMMARY) publicBody else body)
                    .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setPublicVersion(publicVersion)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(if (preferences.lockScreenDetail == NotificationLockScreenDetail.PRIVATE_SUMMARY) publicBody else body))
                    .setContentIntent(pending).setAutoCancel(true).build()
                val dispatchID = message.data["dispatchID"]?.takeIf { it.isNotBlank() && it.length <= 256 }
                    ?: message.messageId ?: return@withTimeout
                PushDeliveryHistory.deliverOnce(this@GradeyMessagingService, auth.account.id, dispatchID) {
                    val stillLinked = accountScope.allEnabledIn(graph.linkedAccountRepository.cachedAccounts())
                    val currentPreferences = graph.notificationSettings.load()
                    if ((application as GradeyApplication).activeGraph !== graph ||
                        !stillLinked || currentPreferences != preferences || !currentPreferences.newMarksEnabled || NotificationPolicy.isQuiet(currentPreferences) ||
                        graph.gradeyAuthRepository.cachedSession()?.account?.id != auth.account.id || !canNotify(this@GradeyMessagingService)) false
                    else {
                        AccountNotifications.deliverMarksIfCurrent(policyEpoch) {
                            if (!AccountNotifications.acceptsOwner(this@GradeyMessagingService, auth.account.id)) false
                            else {
                                NotificationManagerCompat.from(this@GradeyMessagingService).notify(PushDeliveryHistory.notificationID(auth.account.id, dispatchID), notification)
                                true
                            }
                        }
                    }
                }
            } } catch (e: Exception) { if (e is CancellationException && e !is TimeoutCancellationException) throw e }
        }
    }
}
