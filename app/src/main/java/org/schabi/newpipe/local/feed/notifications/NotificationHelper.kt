package org.schabi.newpipe.local.feed.notifications

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.getSystemService
import androidx.preference.PreferenceManager
import org.schabi.newpipe.R
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.local.feed.service.FeedUpdateInfo
import org.schabi.newpipe.util.NavigationHelper
import org.schabi.newpipe.util.image.CoilHelper

/**
 * Helper for everything related to show notifications about new streams to the user.
 */
class NotificationHelper(val context: Context) {
    private val manager = NotificationManagerCompat.from(context)

    /**
     * Show expandable notifications for new streams from a single channel.
     *
     * Opening the summary notification will open the corresponding channel page. Opening the
     * individual notifications will open the corresponding video.
     */
    fun displayNewStreamsNotifications(data: FeedUpdateInfo) {
        val newStreams = data.newStreams
        val summary = context.resources.getQuantityString(
            R.plurals.new_streams,
            newStreams.size,
            newStreams.size
        )
        val summaryBuilder = NotificationCompat.Builder(
            context,
            context.getString(R.string.streams_notification_channel_id)
        )
            .setContentTitle(data.name)
            .setContentText(summary)
            .setNumber(newStreams.size)
            .setBadgeIconType(NotificationCompat.BADGE_ICON_LARGE)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setSmallIcon(R.drawable.ic_newpipe_triangle_white)
            .setColor(context.getColor(R.color.ic_launcher_background))
            .setColorized(true)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_SOCIAL)
            .setGroupSummary(true)
            .setGroup(data.url)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_SUMMARY)

        val style = NotificationCompat.InboxStyle().setBigContentTitle(data.name)
        newStreams.forEach { style.addLine(it.name) }
        summaryBuilder.setStyle(style)

        // open the channel page when clicking on the summary notification
        val intent = NavigationHelper
            .getChannelIntent(context, data.serviceId, data.url)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        summaryBuilder.setContentIntent(
            PendingIntent.getActivity(context, data.pseudoId, intent, PendingIntent.FLAG_IMMUTABLE)
        )

        val avatarIcon =
            CoilHelper.loadBitmapBlocking(context, data.avatarUrl, R.drawable.ic_newpipe_triangle_white)
        summaryBuilder.setLargeIcon(avatarIcon)

        // Show individual stream notifications, set channel icon only if there is actually one
        showStreamNotifications(newStreams, data.serviceId, avatarIcon)
        // Show summary notification
        if (manager.areNotificationsEnabled()) {
            manager.notify(data.pseudoId, summaryBuilder.build())
        }
    }

    private fun showStreamNotifications(
        newStreams: List<StreamInfoItem>,
        serviceId: Int,
        channelIcon: Bitmap?
    ) {
        if (manager.areNotificationsEnabled()) {
            newStreams.forEach { stream ->
                val notification =
                    createStreamNotification(stream, serviceId, channelIcon)
                manager.notify(stream.url.hashCode(), notification)
            }
        }
    }

    private fun createStreamNotification(
        item: StreamInfoItem,
        serviceId: Int,
        channelIcon: Bitmap?
    ): Notification {
        return NotificationCompat.Builder(
            context,
            context.getString(R.string.streams_notification_channel_id)
        )
            .setSmallIcon(R.drawable.ic_newpipe_triangle_white)
            .setLargeIcon(channelIcon)
            .setContentTitle(item.name)
            .setContentText(item.uploaderName)
            .setGroup(item.uploaderUrl)
            .setColor(context.getColor(R.color.ic_launcher_background))
            .setColorized(true)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_SOCIAL)
            .setContentIntent(
                // Open the stream link in the player when clicking on the notification.
                PendingIntent.getActivity(
                    context,
                    item.url.hashCode(),
                    NavigationHelper.getStreamIntent(context, serviceId, item.url, item.name),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            .setSilent(true) // Avoid creating noise for individual stream notifications.
            .build()
    }

    companion object {
        /**
         * Check whether notifications are enabled on the device.
         * Users can disable them via the system settings for a single app.
         * If this is the case, the app cannot create any notifications
         * and display them to the user.
         * <br>
         * Notification channels can be configured by the user, too.
         * The notification channel for new streams is also checked by this method.
         *
         * @param context Context
         * @return <code>true</code> if notifications are allowed and can be displayed;
         * <code>false</code> otherwise
         */
        fun areNotificationsEnabledOnDevice(context: Context): Boolean {
            val channelId = context.getString(R.string.streams_notification_channel_id)
            val manager = context.getSystemService<NotificationManager>()!!
            val channel = manager.getNotificationChannel(channelId)
            return manager.areNotificationsEnabled() &&
                channel?.importance != NotificationManager.IMPORTANCE_NONE
        }

        /**
         * Whether the user enabled the notifications for new streams in the app settings.
         */
        @JvmStatic
        fun areNewStreamsNotificationsEnabled(context: Context): Boolean {
            return (
                PreferenceManager.getDefaultSharedPreferences(context)
                    .getBoolean(context.getString(R.string.enable_streams_notifications), false) &&
                    areNotificationsEnabledOnDevice(context)
                )
        }

        /**
         * Open the system's notification settings for NewPipe.
         */
        fun openNewPipeSystemNotificationSettings(context: Context) {
            val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }
}
