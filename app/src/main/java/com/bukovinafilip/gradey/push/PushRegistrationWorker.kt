package com.bukovinafilip.gradey.push

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.bukovinafilip.gradey.GradeyApplication
import com.bukovinafilip.gradey.registerPush

/** Token registration needs network time beyond the short Firebase service callback. */
class PushRegistrationWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val app = applicationContext as GradeyApplication
        return if (registerPush(app, app.activeGraph)) Result.success()
        else if (runAttemptCount < 4) Result.retry() else Result.failure()
    }
}
