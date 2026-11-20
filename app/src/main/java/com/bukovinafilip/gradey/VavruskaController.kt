package com.bukovinafilip.gradey

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.bukovinafilip.gradey.data.AndroidGradeyGraph
import com.bukovinafilip.gradey.domain.VavruskaException
import com.bukovinafilip.gradey.domain.VavruskaSnapshot
import com.bukovinafilip.gradey.domain.VavruskaStoredCourse
import com.bukovinafilip.gradey.domain.VavruskaTicketSale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * @param enabled the user turned the dance class on in Settings (iOS `settings.showVavruska`).
 * @param seenNoticeDigest the notice board as the user last saw it; null until the first one loads.
 */
internal data class VavruskaState(
    val enabled: Boolean = false,
    val course: VavruskaStoredCourse? = null,
    val snapshot: VavruskaSnapshot = VavruskaSnapshot(),
    val refreshing: Boolean = false,
    val lastRefreshFailed: Boolean = false,
    val ticketAlerts: Boolean = false,
    val seenNoticeDigest: String? = null,
) {
    /** Whether Today, the Timetable and the dance class link should show anything. */
    val isActive: Boolean get() = enabled && course != null
    val hasUnreadNotices: Boolean get() = snapshot.noticeBoard?.digest.let { it != null && seenNoticeDigest != null && it != seenNoticeDigest }
}

/**
 * The one dance course that Today, the Timetable, Settings and the dance class screen all read
 * (iOS `VavruskaViewModel`). Lives in the view model, so it survives rotation.
 */
internal class VavruskaController(private val context: Context, private val scope: CoroutineScope, private val graph: () -> AndroidGradeyGraph) {
    private val mutableState = MutableStateFlow(VavruskaState())
    val state: StateFlow<VavruskaState> = mutableState.asStateFlow()
    private var refreshJob: Job? = null
    private var loadedGraph: AndroidGradeyGraph? = null
    private var settings: Settings = MemorySettings()

    /** The three switches. The demo keeps its own in memory, so trying it never changes the real ones. */
    private interface Settings {
        var enabled: Boolean
        var ticketAlerts: Boolean
        var seenNoticeDigest: String?
    }

    private class MemorySettings : Settings {
        override var enabled = false
        override var ticketAlerts = false
        override var seenNoticeDigest: String? = null
    }

    private class SavedSettings(context: Context) : Settings {
        private val preferences = context.getSharedPreferences(VavruskaTicketAlerts.preferences, Context.MODE_PRIVATE)
        override var enabled: Boolean
            get() = preferences.getBoolean("enabled", false)
            set(value) = preferences.edit().putBoolean("enabled", value).apply()
        override var ticketAlerts: Boolean
            get() = preferences.getBoolean("ticketAlerts", false)
            set(value) = preferences.edit().putBoolean("ticketAlerts", value).apply()
        override var seenNoticeDigest: String?
            get() = preferences.getString("seenNoticeDigest", null)
            set(value) = preferences.edit().putString("seenNoticeDigest", value).apply()
    }

    /** Reads what is saved, then refreshes what is due; called at launch and whenever the account, and with it the graph, changes. */
    fun reload() {
        val current = graph()
        if (loadedGraph !== current) {
            refreshJob?.cancel()
            loadedGraph = current
            settings = if (current.isDemo) MemorySettings() else SavedSettings(context)
            mutableState.value = VavruskaState()
        }
        mutableState.update { it.copy(enabled = settings.enabled, ticketAlerts = settings.ticketAlerts, seenNoticeDigest = settings.seenNoticeDigest) }
        scope.launch {
            val course = current.vavruskaRepository.storedCourse()
            val snapshot = if (course == null) VavruskaSnapshot() else current.vavruskaRepository.cachedSnapshot() ?: VavruskaSnapshot()
            if (loadedGraph !== current) return@launch
            mutableState.update { it.copy(course = course, snapshot = snapshot) }
            reconcileAlerts()
            refreshIfNeeded()
        }
    }

    fun setEnabled(enabled: Boolean) {
        settings.enabled = enabled
        mutableState.update { it.copy(enabled = enabled) }
        reconcileAlerts()
        if (enabled) refreshIfNeeded()
    }

    /** Signs in with the course card password, turns the dance class on and loads everything once. Throws when the sign-in fails. */
    suspend fun connect(password: String) {
        val current = graph()
        val course = current.vavruskaRepository.connect(password)
        if (loadedGraph !== current) return
        refreshJob?.cancel()
        settings.enabled = true
        mutableState.update { it.copy(enabled = true, course = course, snapshot = VavruskaSnapshot(), lastRefreshFailed = false) }
        refreshIfNeeded(force = true, includeTicketLinks = true)
    }

    /**
     * Cheap to call often: the repository decides what is actually due.
     *
     * @param includeTicketLinks also look for the direct link to a sale that is opening; only the dance class screen asks for it.
     */
    fun refreshIfNeeded(force: Boolean = false, includeTicketLinks: Boolean = false) {
        if (!state.value.isActive || refreshJob?.isActive == true) return
        val current = graph()
        mutableState.update { it.copy(refreshing = true) }
        refreshJob = scope.launch {
            try {
                val result = current.vavruskaRepository.refresh(force, includeTicketLinks)
                if (loadedGraph !== current || state.value.course == null) return@launch
                // The first board after connecting is the baseline, not news.
                val digest = result.snapshot.noticeBoard?.digest
                if (settings.seenNoticeDigest == null && digest != null) settings.seenNoticeDigest = digest
                mutableState.update { it.copy(snapshot = result.snapshot, lastRefreshFailed = result.failed, seenNoticeDigest = settings.seenNoticeDigest) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (loadedGraph === current) mutableState.update { it.copy(lastRefreshFailed = true) }
            } finally {
                if (loadedGraph === current) mutableState.update { it.copy(refreshing = false) }
            }
            reconcileAlerts()
        }
    }

    /** Forgets the course, its saved lesson plan and its ticket alerts; the switch in Settings stays as it is. */
    suspend fun disconnect() {
        refreshJob?.cancel()
        graph().vavruskaRepository.disconnect()
        settings.seenNoticeDigest = null
        mutableState.update { it.copy(course = null, snapshot = VavruskaSnapshot(), refreshing = false, lastRefreshFailed = false, seenNoticeDigest = null) }
        VavruskaTicketAlerts.invalidate(context)
    }

    /** Signing out takes the dance course, its switches and its alerts off the device. */
    suspend fun signedOut(signingOutGraph: AndroidGradeyGraph) {
        refreshJob?.cancel()
        signingOutGraph.vavruskaRepository.disconnect()
        VavruskaTicketAlerts.invalidate(context)
        context.getSharedPreferences(VavruskaTicketAlerts.preferences, Context.MODE_PRIVATE).edit().clear().commit()
        loadedGraph = null
        mutableState.value = VavruskaState()
    }

    fun setTicketAlerts(enabled: Boolean) {
        settings.ticketAlerts = enabled
        mutableState.update { it.copy(ticketAlerts = enabled) }
        reconcileAlerts()
    }

    fun markNoticeBoardSeen() {
        val digest = state.value.snapshot.noticeBoard?.digest ?: return
        settings.seenNoticeDigest = digest
        mutableState.update { it.copy(seenNoticeDigest = digest) }
    }

    /** Ticket alerts follow what is on screen; the demo's sample sales never notify. */
    fun reconcileAlerts() {
        val current = state.value
        val alerting = current.isActive && current.ticketAlerts && loadedGraph?.isDemo == false
        VavruskaTicketAlerts.reconcile(context, if (alerting) current.snapshot.ticketSales else emptyList())
    }

    fun message(error: Exception): String = when ((error as? VavruskaException)?.reason) {
        VavruskaException.Reason.INVALID_PASSWORD -> context.getString(R.string.app_vavruska_error_invalid_password)
        VavruskaException.Reason.UNEXPECTED_PAGE -> context.getString(R.string.app_vavruska_error_unexpected_page)
        VavruskaException.Reason.INVALID_RESPONSE -> context.getString(R.string.app_vavruska_error_invalid_response)
        VavruskaException.Reason.HTTP_STATUS -> context.getString(R.string.app_vavruska_error_status, error.status ?: 0)
        VavruskaException.Reason.CALENDAR_UNAVAILABLE -> context.getString(R.string.app_vavruska_error_calendar_unavailable)
        VavruskaException.Reason.NOT_CONNECTED -> context.getString(R.string.app_vavruska_error_not_connected)
        VavruskaException.Reason.MISSING_PASSWORD -> context.getString(R.string.app_vavruska_error_missing_password)
        null -> safeError(error, context)
    }
}

/**
 * A notification when ticket sales for the user's prodloužená or ball open (iOS
 * `VavruskaNotificationScheduler`). Scheduled on the device; WorkManager delivers within minutes of
 * the time, later when the phone is saving battery.
 */
internal object VavruskaTicketAlerts {
    const val preferences = "gradey-vavruska"
    private const val workName = "gradey-vavruska-ticket-sales"
    const val channel = "gradey-dance"
    private const val Horizon = 60L * 24 * 60 * 60 * 1000
    private const val MaxAlerts = 10

    fun invalidate(context: Context): Unit = synchronized(AccountNotifications.lock) {
        context.getSharedPreferences(preferences, Context.MODE_PRIVATE).edit().putString("generation", UUID.randomUUID().toString()).commit()
        AccountNotifications.clearChannel(context, channel)
        WorkManager.getInstance(context).cancelUniqueWork(workName)
        Unit
    }

    /** Replaces every scheduled alert with one per sale that opens within two months. An empty list cancels them. */
    fun reconcile(context: Context, sales: List<VavruskaTicketSale>, now: Long = System.currentTimeMillis()): Unit = synchronized(AccountNotifications.lock) {
        val due = sales.filter { it.saleStartEpochMillis > now + 60_000 && it.saleStartEpochMillis < now + Horizon }.sortedBy { it.saleStartEpochMillis }.take(MaxAlerts)
        if (due.isEmpty() || !canNotify(context)) {
            invalidate(context)
            return@synchronized
        }
        val generation = UUID.randomUUID().toString()
        // Do not schedule work whose ownership token was not durably stored.
        if (!context.getSharedPreferences(preferences, Context.MODE_PRIVATE).edit().putString("generation", generation).commit()) {
            invalidate(context)
            return@synchronized
        }
        val requests = due.map { sale ->
            OneTimeWorkRequestBuilder<VavruskaTicketAlertWorker>()
                .setInitialDelay(sale.saleStartEpochMillis - now, TimeUnit.MILLISECONDS)
                .setInputData(workDataOf("generation" to generation, "sale" to sale.id, "title" to sale.eventTitle, "eventStart" to sale.eventStartEpochMillis))
                .addTag(workName).build()
        }
        // Each alert waits for its own time: the requests run side by side, not one after another.
        WorkManager.getInstance(context).beginUniqueWork(workName, ExistingWorkPolicy.REPLACE, requests).enqueue()
        Unit
    }
}

class VavruskaTicketAlertWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    @android.annotation.SuppressLint("MissingPermission") // Runtime permission is checked before delivery.
    override suspend fun doWork(): Result {
        val context = AppLanguages.localizedContext(applicationContext)
        val saved = context.getSharedPreferences(VavruskaTicketAlerts.preferences, Context.MODE_PRIVATE)
        val generation = inputData.getString("generation") ?: return Result.success()
        val sale = inputData.getString("sale") ?: return Result.success()
        val eventStart = inputData.getLong("eventStart", 0)
        fun isCurrent() = saved.getString("generation", null) == generation && saved.getBoolean("enabled", false) && saved.getBoolean("ticketAlerts", false)
        // A late delivery after the event has started helps nobody.
        if (!isCurrent() || !canNotify(context) || System.currentTimeMillis() >= eventStart) return Result.success()
        if (context.getSharedPreferences("gradey-mode", Context.MODE_PRIVATE).getBoolean("demo", false)) return Result.success()
        val title = inputData.getString("title") ?: context.getString(R.string.app_vavruska_sale_default_title)
        val locale = context.resources.configuration.locales[0]
        val day = Instant.ofEpochMilli(eventStart).atZone(ZoneId.systemDefault()).let {
            it.format(DateTimeFormatter.ofPattern("EEE", locale)).trimEnd('.') + " " +
                it.format(DateTimeFormatter.ofPattern(android.text.format.DateFormat.getBestDateTimePattern(locale, "Md"), locale))
        }
        val body = context.getString(R.string.app_vavruska_notification_sale_body, title, day)
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(VavruskaTicketAlerts.channel, context.getString(R.string.app_vavruska_channel), NotificationManager.IMPORTANCE_HIGH))
        val pending = PendingIntent.getActivity(
            context, sale.hashCode(), Intent(context, MainActivity::class.java).setData(Uri.parse("gradey://vavruska")),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, VavruskaTicketAlerts.channel).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.app_vavruska_notification_sale_title)).setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body)).setContentIntent(pending).setAutoCancel(true)
            // The alert is stale once the event starts.
            .setTimeoutAfter((eventStart - System.currentTimeMillis()).coerceAtLeast(60_000)).build()
        synchronized(AccountNotifications.lock) {
            if (isCurrent() && canNotify(context)) NotificationManagerCompat.from(context).notify("vavruska:$sale".hashCode(), notification)
        }
        return Result.success()
    }
}
