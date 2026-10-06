package org.schabi.newpipe.local.subscription.workers

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ServiceInfo
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.BuildConfig
import org.schabi.newpipe.R

class SubscriptionExportWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        return try {
            val outcome = SubscriptionTransfer.create(applicationContext).export(
                inputData.getString(SubscriptionTransfer.EXPORT_PATH)!!
            ) { progress ->
                if (progress is SubscriptionTransfer.Progress.Exporting) {
                    val title = applicationContext.resources.getQuantityString(
                        R.plurals.export_subscriptions,
                        progress.total,
                        progress.total
                    )
                    setForeground(createForegroundInfo(title))
                }
            }
            if (outcome is SubscriptionTransfer.Outcome.Failure) {
                throw outcome.cause
            }
            val qty = (outcome as SubscriptionTransfer.Outcome.Success).count

            if (BuildConfig.DEBUG) {
                Log.i(TAG, "Exported $qty subscriptions")
            }

            withContext(Dispatchers.Main) {
                Toast
                    .makeText(applicationContext, R.string.export_complete_toast, Toast.LENGTH_SHORT)
                    .show()
            }

            Result.success()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (BuildConfig.DEBUG) {
                Log.e(TAG, "Error while exporting subscriptions", e)
            }

            withContext(Dispatchers.Main) {
                Toast
                    .makeText(applicationContext, R.string.subscriptions_export_unsuccessful, Toast.LENGTH_SHORT)
                    .show()
            }

            return Result.failure()
        }
    }

    @SuppressLint("InlinedApi") // WorkManager handles the service type on older APIs.
    private fun createForegroundInfo(title: String): ForegroundInfo {
        val notification =
            NotificationCompat
                .Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_newpipe_triangle_white)
                .setOngoing(true)
                .setProgress(-1, -1, true)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .setContentTitle(title)
                .build()
        return ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    companion object {
        private const val TAG = "SubscriptionExportWork"
        private const val NOTIFICATION_ID = 4567
        private const val NOTIFICATION_CHANNEL_ID = "newpipe"
    }
}
