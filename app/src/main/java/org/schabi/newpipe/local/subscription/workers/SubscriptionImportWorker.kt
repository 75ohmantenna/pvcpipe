package org.schabi.newpipe.local.subscription.workers

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ServiceInfo
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.BuildConfig
import org.schabi.newpipe.R

class SubscriptionImportWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val input = try {
            SubscriptionImportInput.fromData(inputData)
        } catch (e: Exception) {
            return importFailure(e)
        }
        val outcome = SubscriptionTransfer.create(applicationContext).`import`(input) { progress ->
            when (progress) {
                is SubscriptionTransfer.Progress.Loading -> {
                    val title = applicationContext.resources.getQuantityString(
                        R.plurals.load_subscriptions,
                        progress.total,
                        progress.total
                    )
                    setForeground(createForegroundInfo(title, progress.name, progress.current, progress.total))
                }

                is SubscriptionTransfer.Progress.Importing -> {
                    val title = applicationContext.resources.getQuantityString(
                        R.plurals.import_subscriptions,
                        progress.total,
                        progress.total
                    )
                    setForeground(
                        createForegroundInfo(title, null, progress.current, if (progress.current == 0) 0 else progress.total)
                    )
                }

                is SubscriptionTransfer.Progress.Exporting -> Unit
            }
        }
        return when (outcome) {
            is SubscriptionTransfer.Outcome.Failure -> importFailure(outcome.cause)

            is SubscriptionTransfer.Outcome.Success -> {
                withContext(Dispatchers.Main) {
                    Toast.makeText(applicationContext, R.string.import_complete_toast, Toast.LENGTH_SHORT).show()
                }
                Result.success()
            }
        }
    }

    private suspend fun importFailure(cause: Exception): Result {
        if (BuildConfig.DEBUG) {
            Log.e(TAG, "Error while importing subscriptions", cause)
        }
        withContext(Dispatchers.Main) {
            Toast.makeText(applicationContext, R.string.subscriptions_import_unsuccessful, Toast.LENGTH_SHORT).show()
        }
        return Result.failure()
    }

    @SuppressLint("InlinedApi") // WorkManager handles the service type on older APIs.
    private fun createForegroundInfo(
        title: String,
        text: String?,
        currentProgress: Int,
        maxProgress: Int
    ): ForegroundInfo {
        val notification =
            NotificationCompat
                .Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_newpipe_triangle_white)
                .setOngoing(true)
                .setProgress(maxProgress, currentProgress, currentProgress == 0)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .setContentTitle(title)
                .setContentText(text)
                .addAction(
                    R.drawable.ic_close,
                    applicationContext.getString(R.string.cancel),
                    WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)
                ).apply {
                    if (currentProgress > 0 && maxProgress > 0) {
                        setSubText("$currentProgress/$maxProgress")
                    }
                }.build()
        return ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    companion object {
        private const val TAG = "SubscriptionImport"

        private const val NOTIFICATION_ID = 4568
        private const val NOTIFICATION_CHANNEL_ID = "newpipe"

        const val WORK_NAME = "SubscriptionImportWorker"
    }
}
