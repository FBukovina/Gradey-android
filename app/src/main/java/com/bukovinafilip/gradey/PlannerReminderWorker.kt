package com.bukovinafilip.gradey

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.*
import com.bukovinafilip.gradey.domain.PlannerReminders
import com.bukovinafilip.gradey.model.*
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

internal object PlannerReminderScheduler {
    const val workName = "gradey-personal-planner-reminders"
    const val channel = "gradey-planner"
    fun invalidate(context: Context): Unit = synchronized(AccountNotifications.lock) {
        context.getSharedPreferences(workName, 0).edit().putString("generation", UUID.randomUUID().toString()).commit()
        AccountNotifications.clearChannel(context, channel)
        // Queue cancellation before another reconcile can queue its replacement.
        WorkManager.getInstance(context).cancelUniqueWork(workName)
        Unit
    }
    fun reconcile(context: Context, state: GradeyState, enabled: Boolean): Unit = synchronized(AccountNotifications.lock) {
        if (!enabled || state.isDemo || state.phase != AppPhase.SIGNED_IN || !canNotify(context)) {
            invalidate(context)
            return@synchronized
        }
        val generation = UUID.randomUUID().toString()
        val manager = WorkManager.getInstance(context)
        val now = Instant.now()
        val scope = state.school?.cacheScope
        val zone = ZoneId.systemDefault()
        val requests = PlannerReminders.make(state.personalPlannerItems, scope, state.preferences, now, zone).map { reminder ->
            OneTimeWorkRequestBuilder<PlannerReminderWorker>()
                .setInitialDelay(Duration.between(now, reminder.fireAt).toMillis().coerceAtLeast(0), TimeUnit.MILLISECONDS)
                .setInputData(workDataOf("generation" to generation, "owner" to (state.account?.id ?: "guest-local".takeIf { state.isGuest }),
                    "scope" to scope, "day" to reminder.day, "plannedAt" to now.toEpochMilli(), "fireAt" to reminder.fireAt.toEpochMilli(), "zone" to zone.id))
                .addTag(workName).build()
        }
        if (requests.isEmpty()) {
            invalidate(context)
            return@synchronized
        }
        val saved = context.getSharedPreferences(workName, 0).edit().putString("generation", generation).commit()
        if (!saved) {
            // Do not schedule work whose ownership token was not durably stored.
            invalidate(context)
            return@synchronized
        }
        manager.enqueueUniqueWork(workName, ExistingWorkPolicy.REPLACE, requests)
        Unit
    }
}

class PlannerReminderWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    @android.annotation.SuppressLint("MissingPermission") // Runtime permission is checked before delivery.
    override suspend fun doWork(): Result {
        val context = AppLanguages.localizedContext(applicationContext)
        val generation = inputData.getString("generation") ?: return Result.success()
        fun isCurrent() = context.getSharedPreferences(PlannerReminderScheduler.workName, 0).getString("generation", null) == generation &&
            context.getSharedPreferences("gradey-app-preferences", 0).getBoolean("plannerReminders", false)
        if (!isCurrent() || !canNotify(context) || context.getSharedPreferences("gradey-mode", 0).getBoolean("demo", false)) return Result.success()
        try {
            val app = applicationContext as GradeyApplication
            val graph = app.activeGraph
            if (graph.isDemo) return Result.success()
            val isGuest = context.getSharedPreferences("gradey-mode", 0).getBoolean("guest", false)
            val owner = if (isGuest) "guest-local" else graph.gradeyAuthRepository.cachedSession()?.account?.id ?: return Result.success()
            val schoolScope = graph.schoolRepository.currentStoredSession()?.cacheScope
            if (!AccountNotifications.acceptsOwner(context, owner) || owner != inputData.getString("owner") || schoolScope != inputData.getString("scope")) return Result.success()
            val fireAt = Instant.ofEpochMilli(inputData.getLong("fireAt", 0))
            val zone = ZoneId.of(inputData.getString("zone") ?: ZoneId.systemDefault().id)
            val preferences = if (isGuest) GuestPreferences(context).load() else graph.notificationSettings.load()
            val items = app.personalPlanner.load()
            val reminder = PlannerReminders.make(items, schoolScope, preferences, Instant.ofEpochMilli(inputData.getLong("plannedAt", 0)), zone)
                .firstOrNull { it.day == inputData.getString("day") && it.fireAt == fireAt } ?: return Result.success()
            // WorkManager is best-effort; battery delays must never create a past-due reminder.
            if (Instant.now() >= reminder.expiresAt || !isCurrent()) return Result.success()
            val count = reminder.items.size
            val privateBody = context.getString(R.string.app_reminder_private)
            val body = when (preferences.lockScreenDetail) {
                NotificationLockScreenDetail.PRIVATE_SUMMARY -> privateBody
                NotificationLockScreenDetail.MARK_AND_SUBJECT -> context.getString(R.string.app_reminder_count, count) + reminder.items.mapNotNull { it.subject?.name }.distinct().take(2).joinToString(", ").let { if (it.isEmpty()) "" else " · $it" }
                NotificationLockScreenDetail.FULL_DETAILS -> context.getString(R.string.app_reminder_count, count) + " · " + reminder.items.take(2).joinToString(", ") { it.title }
            }
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(PlannerReminderScheduler.channel, context.getString(R.string.app_reminders), NotificationManager.IMPORTANCE_DEFAULT))
            val pending = PendingIntent.getActivity(context, reminder.day.hashCode(),
                Intent(context, MainActivity::class.java).setData(Uri.parse("gradey://planner?day=${reminder.day}")),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val public = NotificationCompat.Builder(context, PlannerReminderScheduler.channel).setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.getString(R.string.app_tomorrow_gradey)).setContentText(if (preferences.lockScreenDetail == NotificationLockScreenDetail.PRIVATE_SUMMARY) privateBody else body).build()
            val notification = NotificationCompat.Builder(context, PlannerReminderScheduler.channel).setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.getString(R.string.app_tomorrow_gradey)).setContentText(body).setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setPublicVersion(public).setContentIntent(pending).setAutoCancel(true).build()
            // Reads above may suspend while the app signs out, switches a school, or upgrades a guest.
            val currentGuest = context.getSharedPreferences("gradey-mode", 0).getBoolean("guest", false)
            val currentOwner = if (currentGuest) "guest-local" else graph.gradeyAuthRepository.cachedSession()?.account?.id
            val currentScope = graph.schoolRepository.currentStoredSession()?.cacheScope
            synchronized(AccountNotifications.lock) {
                if (isCurrent() && AccountNotifications.acceptsOwner(context, owner) && app.activeGraph === graph && currentOwner == owner && currentScope == schoolScope && canNotify(context)) {
                    NotificationManagerCompat.from(context).notify("planner:${reminder.day}".hashCode(), notification)
                }
            }
            return Result.success()
        } catch (error: CancellationException) { throw error }
        catch (_: Exception) { return Result.success() } // Do not issue a stale catch-up after a storage or identity failure.
    }
}
