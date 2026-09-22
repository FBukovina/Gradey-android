package com.bukovinafilip.gradey.updates

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.bukovinafilip.gradey.BuildConfig
import com.bukovinafilip.gradey.MainActivity
import com.bukovinafilip.gradey.R
import com.bukovinafilip.gradey.network.GitHubUpdateClient
import com.bukovinafilip.gradey.network.GradeyJson
import com.bukovinafilip.gradey.network.UpdateCheckException
import com.bukovinafilip.gradey.network.UpdateRelease
import com.bukovinafilip.gradey.network.UpdateReleaseParser
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Device-wide app updates are independent of school/account sign-in and contain no account data. */
class AppUpdateManager internal constructor(
    context: Context,
    private val enqueueDownloadWork: (OneTimeWorkRequest) -> Unit = { request ->
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(DOWNLOAD_WORK, ExistingWorkPolicy.REPLACE, request)
        Unit
    },
) {
    private val context = context.applicationContext
    private val preferences = this.context.getSharedPreferences("gradey-app-updates", Context.MODE_PRIVATE)
    private val work get() = WorkManager.getInstance(context)
    private val gate = Mutex()
    private val started = AtomicBoolean()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val client = GitHubUpdateClient(this.context.packageName)
    private val storeManaged = installedByGooglePlay()
    private val enabled = BuildConfig.DIRECT_UPDATES_ENABLED && !storeManaged
    private val directory get() = File(context.filesDir, "updates").apply { mkdirs() }
    private val mutableState = MutableStateFlow(loadState())
    val state = mutableState.asStateFlow()

    fun start() {
        if (!started.compareAndSet(false, true)) return
        val notifications = context.getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel(CHANNEL, "App updates", NotificationManager.IMPORTANCE_DEFAULT))
        scope.launch(Dispatchers.IO) {
            gate.withLock {
                scheduleChecks()
                // A process may die after saving QUEUED but before WorkManager commits its request.
                // Reconcile the generation tag so that such a download cannot remain queued forever.
                val token = preferences.getString("downloadToken", null)
                if (enabled && state.value.phase == UpdatePhase.QUEUED && token != null) {
                    try {
                        val manual = preferences.getBoolean("downloadManual", false)
                        if (!manual && (!state.value.automaticChecks || !state.value.automaticDownloads)) {
                            cancelDownloadLocked()
                        } else {
                            val requests = work.getWorkInfosForUniqueWorkFlow(DOWNLOAD_WORK).first()
                            if (requests.none { !it.state.isFinished && tokenTag(token) in it.tags }) {
                                // Use a fresh token even if an older database request was still pending.
                                state.value.release?.let { enqueueDownloadLocked(it, manual) }
                            }
                        }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        preferences.edit().remove("downloadToken").remove("downloadManual").apply()
                        publish(state.value.copy(phase = UpdatePhase.ERROR,
                            message = "Couldn't resume the update download. Tap Download update to try again."))
                    }
                }
                // Serialize cleanup with generation changes and READY renames. Otherwise startup
                // cleanup can delete a newly completed APK using an older state snapshot.
                val release = state.value.release.takeIf { enabled }
                val currentToken = preferences.getString("downloadToken", null)
                directory.listFiles()?.filter { file ->
                    file.name != release?.let(::apkName) && file.name != currentToken?.let { "$it.part" }
                }?.forEach(File::delete)
            }
        }
    }

    fun onForeground() {
        if (enabled && state.value.automaticChecks && automaticCheckIsDue(System.currentTimeMillis())) {
            scope.launch { checkForUpdates(automatic = true) }
        }
    }

    suspend fun checkForUpdates(automatic: Boolean = false) {
        if (!enabled || (automatic && !state.value.automaticChecks)) return
        // Foreground checks must survive the brief startup cleanup/recovery lock. Once acquired,
        // the persisted cooldown also deduplicates checks queued by repeated resume events.
        if (automatic) gate.lock() else if (!gate.tryLock()) return
        try {
            if (automatic && (!state.value.automaticChecks || !automaticCheckIsDue(System.currentTimeMillis()))) return
            if (state.value.phase in listOf(UpdatePhase.QUEUED, UpdatePhase.DOWNLOADING, UpdatePhase.READY)) return
            val previousRelease = state.value.release
            preferences.edit().putLong("lastAttemptAt", System.currentTimeMillis()).apply()
            publish(state.value.copy(phase = UpdatePhase.CHECKING, message = null))
            try {
                val release = client.checkLatest(BuildConfig.VERSION_CODE, Build.VERSION.SDK_INT)
                publish(state.value.copy(
                    phase = if (release == null) UpdatePhase.IDLE else UpdatePhase.AVAILABLE,
                    release = release, lastCheckedAt = System.currentTimeMillis(), downloadedBytes = 0,
                    totalBytes = release?.sizeBytes ?: 0, message = if (release == null) "No newer stable version is published yet." else null,
                ))
                if (release != null && state.value.automaticChecks && state.value.automaticDownloads && !wasCanceled(release)) {
                    enqueueDownloadLocked(release, manual = false)
                }
            } catch (error: CancellationException) {
                publish(state.value.copy(phase = if (previousRelease == null) UpdatePhase.IDLE else UpdatePhase.AVAILABLE, release = previousRelease))
                throw error
            } catch (error: Exception) {
                publish(state.value.copy(phase = UpdatePhase.ERROR, release = previousRelease, message =
                    (error as? UpdateCheckException)?.message ?: "Couldn't check for updates. Check your connection and try again."))
            }
        } finally { gate.unlock() }
    }

    suspend fun downloadNow() = gate.withLock {
        if (!enabled) return@withLock
        val release = state.value.release ?: return@withLock
        if (state.value.phase in listOf(UpdatePhase.READY, UpdatePhase.DOWNLOADING) ||
            (state.value.phase == UpdatePhase.QUEUED && state.value.manualDownload)) return@withLock
        preferences.edit().remove("canceledVersion").apply()
        enqueueDownloadLocked(release, manual = true)
    }

    private fun enqueueDownloadLocked(release: UpdateRelease, manual: Boolean) {
        val token = UUID.randomUUID().toString()
        preferences.edit().putString("downloadToken", token).putBoolean("downloadManual", manual).apply()
        publish(state.value.copy(phase = UpdatePhase.QUEUED, release = release, downloadedBytes = 0,
            totalBytes = release.sizeBytes, manualDownload = manual,
            message = if (manual) "The download will start using your current connection." else null))
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (!manual && state.value.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .setRequiresStorageNotLow(true).build()
        enqueueDownloadWork(
            OneTimeWorkRequestBuilder<UpdateDownloadWorker>().setConstraints(constraints)
                .addTag(tokenTag(token))
                .setInputData(workDataOf("token" to token)).setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
    }

    suspend fun cancelDownload() = gate.withLock { cancelDownloadLocked() }

    private fun cancelDownloadLocked() {
        if (state.value.phase !in listOf(UpdatePhase.QUEUED, UpdatePhase.DOWNLOADING)) return
        preferences.edit().remove("downloadToken").remove("downloadManual")
            .putInt("canceledVersion", state.value.release?.versionCode ?: 0).apply()
        work.cancelUniqueWork(DOWNLOAD_WORK)
        publish(state.value.copy(phase = if (state.value.release == null) UpdatePhase.IDLE else UpdatePhase.AVAILABLE,
            downloadedBytes = 0, manualDownload = false,
            message = "Automatic download paused for this version. You can download it when you're ready."))
    }

    suspend fun setAutomaticChecks(value: Boolean) = gate.withLock {
        val enabling = value && !state.value.automaticChecks
        publish(state.value.copy(automaticChecks = value))
        scheduleChecks()
        if (!value && isAutomaticDownload()) cancelDownloadLocked()
        if (enabling && state.value.automaticDownloads) {
            preferences.edit().remove("canceledVersion").apply()
            if (state.value.phase == UpdatePhase.AVAILABLE) {
                state.value.release?.let { enqueueDownloadLocked(it, manual = false) }
            }
        }
    }

    suspend fun setAutomaticDownloads(value: Boolean) = gate.withLock {
        publish(state.value.copy(automaticDownloads = value))
        if (!value && isAutomaticDownload()) cancelDownloadLocked()
        if (value && state.value.automaticChecks && state.value.phase == UpdatePhase.AVAILABLE) {
            preferences.edit().remove("canceledVersion").apply()
            state.value.release?.let { enqueueDownloadLocked(it, manual = false) }
        }
    }

    suspend fun setWifiOnly(value: Boolean) = gate.withLock {
        publish(state.value.copy(wifiOnly = value))
        if (isAutomaticDownload()) state.value.release?.let { enqueueDownloadLocked(it, manual = false) }
    }

    private fun isAutomaticDownload() = state.value.phase in listOf(UpdatePhase.QUEUED, UpdatePhase.DOWNLOADING) &&
        !preferences.getBoolean("downloadManual", false)

    private fun scheduleChecks() {
        if (!enabled || !state.value.automaticChecks) work.cancelUniqueWork(CHECK_WORK)
        else work.enqueueUniquePeriodicWork(CHECK_WORK, ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<UpdateCheckWorker>(12, TimeUnit.HOURS)
                .setInitialDelay(12, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
        if (!enabled) {
            preferences.edit().remove("downloadToken").remove("downloadManual").apply()
            work.cancelUniqueWork(DOWNLOAD_WORK)
        }
    }

    internal suspend fun beginDownload(token: String): UpdateRelease? = gate.withLock {
        if (!isCurrentDownload(token)) return@withLock null
        val release = state.value.release ?: return@withLock null
        publish(state.value.copy(phase = UpdatePhase.DOWNLOADING, downloadedBytes = 0, message = null))
        release
    }

    internal fun partialFile(token: String): File {
        require(token.matches(Regex("[a-f0-9-]{36}")))
        return File(directory, "$token.part")
    }

    internal fun progress(token: String, downloaded: Long) {
        mutableState.update { if (isCurrentDownload(token) && it.phase == UpdatePhase.DOWNLOADING) it.copy(downloadedBytes = downloaded) else it }
    }

    internal suspend fun downloadReady(token: String, release: UpdateRelease, partial: File) = gate.withLock {
        if (!isCurrentDownload(token)) { partial.delete(); return@withLock }
        val destination = File(directory, apkName(release))
        if (!partial.renameTo(destination)) throw java.io.IOException("Unable to save update")
        preferences.edit().remove("downloadToken").remove("downloadManual").apply()
        publish(state.value.copy(phase = UpdatePhase.READY, downloadedBytes = release.sizeBytes, manualDownload = false, message = null))
        notifyReady(release)
    }

    internal suspend fun downloadStopped(token: String, message: String?, retry: Boolean) = gate.withLock {
        if (isCurrentDownload(token)) {
            if (!retry) preferences.edit().remove("downloadToken").remove("downloadManual").apply()
            publish(state.value.copy(phase = if (retry) UpdatePhase.QUEUED else UpdatePhase.ERROR,
                downloadedBytes = 0, manualDownload = retry && state.value.manualDownload, message = message))
        }
    }

    private fun isCurrentDownload(token: String) = enabled && preferences.getString("downloadToken", null) == token
    private fun wasCanceled(release: UpdateRelease) = preferences.getInt("canceledVersion", 0) == release.versionCode

    private fun automaticCheckIsDue(now: Long): Boolean {
        val attempted = preferences.getLong("lastAttemptAt", 0)
        val successful = state.value.lastCheckedAt
        // A wall-clock correction must not disable checks indefinitely. A failed request has a
        // short persistent cooldown; a successful request retains the longer 12-hour interval.
        return !(attempted in 1..now && now - attempted < AUTOMATIC_RETRY_INTERVAL_MS) &&
            !(successful in 1..now && now - successful < CHECK_INTERVAL_MS)
    }

    /** Called only by a foreground tap. Never start an installer or permission screen in background. */
    suspend fun install(context: Context) = gate.withLock {
        if (!enabled || state.value.phase != UpdatePhase.READY) return@withLock
        val release = state.value.release ?: return@withLock
        try {
            val apk = File(directory, apkName(release))
            ApkUpdateVerifier(this.context).verify(apk, release)
            if (!this.context.packageManager.canRequestPackageInstalls()) {
                context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${this.context.packageName}")))
                publish(state.value.copy(message = "Allow Gradey to install updates, then return here and tap Install update."))
                return@withLock
            }
            val uri = FileProvider.getUriForFile(this.context, "${this.context.packageName}.updates", apk)
            context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .apply { clipData = ClipData.newRawUri("Gradey update", uri) })
            context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        } catch (error: CancellationException) { throw error }
        catch (error: UpdateVerificationException) {
            File(directory, apkName(release)).delete()
            publish(state.value.copy(phase = UpdatePhase.ERROR, message = error.message))
        } catch (_: Exception) {
            publish(state.value.copy(message = "Android couldn't open the installer. Check your device's app installation settings and try again."))
        }
    }

    fun openStore(context: Context) {
        try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=${this.context.packageName}"))) }
        catch (_: Exception) { mutableState.update { it.copy(message = "Open Google Play and look for Gradey to check for updates.") } }
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun notifyReady(release: UpdateRelease) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val intent = Intent(context, MainActivity::class.java).setData(Uri.parse("gradey://updates"))
        val pending = PendingIntent.getActivity(context, NOTIFICATION_ID, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_gradey_logo)
            .setContentTitle("Gradey ${release.versionName} is ready")
            .setContentText("Tap to review and install your downloaded update.").setContentIntent(pending).setAutoCancel(true).build()
        try { NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification) } catch (_: SecurityException) { }
    }

    private fun publish(value: UpdateUiState) {
        preferences.edit().putString("release", value.release?.let(::encodeRelease))
            .putString("phase", value.phase.name).putString("message", value.message)
            .putLong("lastCheckedAt", value.lastCheckedAt).putBoolean("automaticChecks", value.automaticChecks)
            .putBoolean("automaticDownloads", value.automaticDownloads).putBoolean("wifiOnly", value.wifiOnly).apply()
        mutableState.value = value
    }

    private fun loadState(): UpdateUiState {
        val savedRelease = runCatching { preferences.getString("release", null)?.let { UpdateReleaseParser.parse(it, context.packageName) } }
            .getOrNull()
        val release = savedRelease?.takeIf { it.versionCode > BuildConfig.VERSION_CODE }
        val savedPhase = runCatching { UpdatePhase.valueOf(preferences.getString("phase", "IDLE")!!) }.getOrDefault(UpdatePhase.IDLE)
        val validToken = preferences.getString("downloadToken", null)?.matches(Regex("[a-f0-9-]{36}")) == true
        val phase = when {
            storeManaged -> UpdatePhase.STORE_MANAGED
            !BuildConfig.DIRECT_UPDATES_ENABLED -> UpdatePhase.DISABLED
            savedRelease != null && release == null -> UpdatePhase.IDLE
            savedPhase == UpdatePhase.ERROR -> UpdatePhase.ERROR
            release == null -> UpdatePhase.IDLE
            savedPhase == UpdatePhase.READY && File(directory, apkName(release)).isFile -> UpdatePhase.READY
            savedPhase in listOf(UpdatePhase.DOWNLOADING, UpdatePhase.QUEUED) && validToken -> UpdatePhase.QUEUED
            else -> UpdatePhase.AVAILABLE
        }
        return UpdateUiState(phase = phase, release = release,
            lastCheckedAt = preferences.getLong("lastCheckedAt", 0),
            message = preferences.getString("message", null).takeIf { phase == UpdatePhase.ERROR || phase == UpdatePhase.QUEUED },
            totalBytes = release?.sizeBytes ?: 0, downloadedBytes = if (phase == UpdatePhase.READY) release?.sizeBytes ?: 0 else 0,
            automaticChecks = preferences.getBoolean("automaticChecks", true),
            automaticDownloads = preferences.getBoolean("automaticDownloads", true), wifiOnly = preferences.getBoolean("wifiOnly", true),
            manualDownload = phase == UpdatePhase.QUEUED && preferences.getBoolean("downloadManual", false))
    }

    @Suppress("DEPRECATION")
    private fun installedByGooglePlay(): Boolean = runCatching {
        val installer = if (Build.VERSION.SDK_INT >= 30) context.packageManager.getInstallSourceInfo(context.packageName).installingPackageName
        else context.packageManager.getInstallerPackageName(context.packageName)
        installer == "com.android.vending"
    }.getOrDefault(false)

    companion object {
        internal const val CHECK_WORK = "gradey-app-update-check"
        internal const val DOWNLOAD_WORK = "gradey-app-update-download"
        private const val CHECK_INTERVAL_MS = 12 * 60 * 60 * 1000L
        private const val AUTOMATIC_RETRY_INTERVAL_MS = 5 * 60 * 1000L
        private const val CHANNEL = "gradey-app-updates"
        private const val NOTIFICATION_ID = 2041
        private fun tokenTag(token: String) = "gradey-update-generation-$token"
        private fun apkName(release: UpdateRelease) = "gradey-${release.versionCode}-${release.sha256.take(12)}.apk"
        private fun encodeRelease(release: UpdateRelease): String {
            val fields = GradeyJson.encodeToJsonElement(UpdateRelease.serializer(), release) as JsonObject
            return JsonObject(fields + ("target" to JsonPrimitive("phone"))).toString()
        }
    }
}
