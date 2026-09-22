package com.bukovinafilip.gradey.updates

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.bukovinafilip.gradey.GradeyApplication
import com.bukovinafilip.gradey.network.UpdateApkDownloader
import com.bukovinafilip.gradey.network.UpdateCheckException
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext

class UpdateCheckWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        (applicationContext as GradeyApplication).updates.checkForUpdates(automatic = true)
        return Result.success()
    }
}

class UpdateDownloadWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val manager = (applicationContext as GradeyApplication).updates
        val token = inputData.getString("token") ?: return Result.failure()
        val release = manager.beginDownload(token) ?: return Result.success()
        val partial = manager.partialFile(token)
        return try {
            UpdateApkDownloader().download(release, partial) { manager.progress(token, it) }
            ApkUpdateVerifier(applicationContext).verify(partial, release)
            manager.downloadReady(token, release, partial)
            Result.success()
        } catch (error: CancellationException) {
            withContext(NonCancellable) { manager.downloadStopped(token, "The download will resume when its connection requirements are met.", retry = true) }
            throw error
        } catch (error: UpdateVerificationException) {
            manager.downloadStopped(token, error.message, retry = false)
            Result.failure()
        } catch (error: Exception) {
            // The downloader wraps transport errors in UpdateCheckException to provide safe UI
            // messages. Integrity/URL failures have no transport cause and must not be retried.
            val transientFailure = error !is UpdateCheckException ||
                error.cause is IOException || error.cause is TimeoutCancellationException
            val retry = runAttemptCount < 2 && transientFailure
            manager.downloadStopped(token, (error as? UpdateCheckException)?.message ?:
                if (retry) "Download interrupted. Gradey will try again when connected." else "Couldn't download the update. Check your connection and try again.", retry)
            if (retry) Result.retry() else Result.failure()
        } finally { partial.delete() }
    }
}
