/*
 * Copyright 2019 Mauricio Colli <mauriciocolli@outlook.com>
 * FeedLoadService.kt is part of NewPipe
 *
 * License: GPL-3.0+
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package org.schabi.newpipe.local.feed.service

import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Flowable
import io.reactivex.rxjava3.disposables.Disposable
import io.reactivex.rxjava3.functions.Function
import java.util.concurrent.TimeUnit
import org.schabi.newpipe.App
import org.schabi.newpipe.MainActivity.DEBUG
import org.schabi.newpipe.R
import org.schabi.newpipe.database.feed.model.FeedGroupEntity
import org.schabi.newpipe.local.feed.service.FeedEventManager.Event.ErrorResultEvent
import org.schabi.newpipe.local.feed.service.FeedEventManager.postEvent

class FeedLoadService : Service() {
    companion object {
        private val TAG = FeedLoadService::class.java.simpleName
        const val NOTIFICATION_ID = 7293450
        private const val ACTION_CANCEL = App.PACKAGE_NAME + ".local.feed.service.FeedLoadService.CANCEL"

        /**
         * How often the notification will be updated.
         */
        private const val NOTIFICATION_SAMPLING_PERIOD = 1500

        const val EXTRA_GROUP_ID: String = "FeedLoadService.EXTRA_GROUP_ID"
    }

    private var loadingDisposable: Disposable? = null
    private var notificationDisposable: Disposable? = null

    private lateinit var feedLoadManager: FeedLoadManager
    private var feedRefresh: FeedRefresh? = null

    // /////////////////////////////////////////////////////////////////////////
    // Lifecycle
    // /////////////////////////////////////////////////////////////////////////

    override fun onCreate() {
        super.onCreate()
        feedLoadManager = FeedLoadManager(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (DEBUG) {
            Log.d(
                TAG,
                "onStartCommand() called with: intent = [" + intent + "]," +
                    " flags = [" + flags + "], startId = [" + startId + "]"
            )
        }

        if (intent == null || loadingDisposable != null) {
            return START_NOT_STICKY
        }

        val groupId = intent.getLongExtra(EXTRA_GROUP_ID, FeedGroupEntity.GROUP_ALL_ID)
        val refresh = feedLoadManager.createRefresh(groupId)
        feedRefresh = refresh
        setupNotification(refresh)
        setupBroadcastReceiver()

        loadingDisposable = refresh.result
            .observeOn(AndroidSchedulers.mainThread())
            .doOnSubscribe {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(
                        NOTIFICATION_ID,
                        notificationBuilder.build(),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                    )
                } else {
                    startForeground(NOTIFICATION_ID, notificationBuilder.build())
                }
            }
            .subscribe { _, error: Throwable? ->
                // explicitly mark error as nullable
                loadingDisposable = null
                if (error != null) {
                    Log.e(TAG, "Error while storing result", error)
                    handleError(error)
                    return@subscribe
                }
                stopService()
            }
        return START_NOT_STICKY
    }

    private fun disposeAll() {
        broadcastReceiver?.let { unregisterReceiver(it) }
        broadcastReceiver = null
        notificationDisposable?.dispose()
        notificationDisposable = null
    }

    private fun stopService() {
        disposeAll()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        // Stop foreground work promptly; cooperative cancellation flushes collected feed results.
        feedRefresh?.cancel()
        stopService()
    }

    override fun onDestroy() {
        feedRefresh?.cancel()
        disposeAll()
        // Do not dispose loading: its final buffered results must still reach the database.
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? {
        return null
    }

    // /////////////////////////////////////////////////////////////////////////
    // Notification
    // /////////////////////////////////////////////////////////////////////////

    private lateinit var notificationManager: NotificationManagerCompat
    private lateinit var notificationBuilder: NotificationCompat.Builder

    private fun createNotification(): NotificationCompat.Builder {
        val cancelActionIntent = PendingIntent.getBroadcast(
            this,
            NOTIFICATION_ID,
            Intent(ACTION_CANCEL).setPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, getString(R.string.notification_channel_id))
            .setOngoing(true)
            .setProgress(-1, -1, true)
            .setSmallIcon(R.drawable.ic_newpipe_triangle_white)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(0, getString(R.string.cancel), cancelActionIntent)
            .setContentTitle(getString(R.string.feed_notification_loading))
    }

    private fun setupNotification(refresh: FeedRefresh) {
        notificationManager = NotificationManagerCompat.from(this)
        notificationBuilder = createNotification()

        val throttleAfterFirstEmission = Function { flow: Flowable<FeedLoadState> ->
            flow.take(1).concatWith(flow.skip(1).throttleLatest(NOTIFICATION_SAMPLING_PERIOD.toLong(), TimeUnit.MILLISECONDS))
        }

        notificationDisposable = refresh.progress
            .publish(throttleAfterFirstEmission)
            .observeOn(AndroidSchedulers.mainThread())
            .doOnTerminate { notificationManager.cancel(NOTIFICATION_ID) }
            .subscribe(this::updateNotificationProgress)
    }

    private fun updateNotificationProgress(state: FeedLoadState) {
        notificationBuilder.setProgress(state.maxProgress, state.currentProgress, state.maxProgress == -1)

        if (state.maxProgress == -1) {
            notificationBuilder.setContentText(state.updateDescription)
        } else {
            val progressText = state.currentProgress.toString() + "/" + state.maxProgress

            if (state.updateDescription.isNotEmpty()) {
                notificationBuilder.setContentText("${state.updateDescription}  ($progressText)")
            }
        }

        if (notificationManager.areNotificationsEnabled()) {
            notificationManager.notify(NOTIFICATION_ID, notificationBuilder.build())
        }
    }

    // /////////////////////////////////////////////////////////////////////////
    // Notification Actions
    // /////////////////////////////////////////////////////////////////////////

    private var broadcastReceiver: BroadcastReceiver? = null

    private fun setupBroadcastReceiver() {
        broadcastReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == ACTION_CANCEL) {
                    feedRefresh?.cancel()
                }
            }
        }
        ContextCompat.registerReceiver(
            this,
            broadcastReceiver,
            IntentFilter(ACTION_CANCEL),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    // /////////////////////////////////////////////////////////////////////////
    // Error handling
    // /////////////////////////////////////////////////////////////////////////

    private fun handleError(error: Throwable) {
        postEvent(ErrorResultEvent(error))
        stopService()
    }
}
