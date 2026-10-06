package org.schabi.newpipe.local.feed.service

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Flowable
import io.reactivex.rxjava3.core.Notification
import io.reactivex.rxjava3.schedulers.Schedulers
import java.time.OffsetDateTime
import java.time.ZoneOffset
import org.schabi.newpipe.R
import org.schabi.newpipe.database.feed.model.FeedGroupEntity
import org.schabi.newpipe.database.subscription.NotificationMode
import org.schabi.newpipe.database.subscription.SubscriptionEntity
import org.schabi.newpipe.extractor.Info
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.feed.FeedInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.ktx.getStringSafe
import org.schabi.newpipe.local.feed.FeedDatabaseManager
import org.schabi.newpipe.local.subscription.SubscriptionManager
import org.schabi.newpipe.util.ChannelTabHelper
import org.schabi.newpipe.util.ExtractorHelper.getChannelInfo
import org.schabi.newpipe.util.ExtractorHelper.getChannelTab
import org.schabi.newpipe.util.ExtractorHelper.getMoreChannelTabItems

/** Creates independent refresh runs using the application's extraction and database policy. */
class FeedLoadManager(private val context: Context) {
    private val subscriptionManager = SubscriptionManager(context)
    private val feedDatabaseManager = FeedDatabaseManager(context)

    /**
     * Prepare a subscription refresh. Work begins when [FeedRefresh.result] is subscribed.
     * [groupId] selects all subscriptions, notification-enabled subscriptions, or a feed group.
     * [ignoreOutdatedThreshold] checks all selected subscriptions regardless of update age.
     */
    fun createRefresh(
        groupId: Long = FeedGroupEntity.GROUP_ALL_ID,
        ignoreOutdatedThreshold: Boolean = false
    ): FeedRefresh {
        val defaultSharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
        val useFeedExtractor = defaultSharedPreferences.getBoolean(
            context.getString(R.string.feed_use_dedicated_fetch_method_key),
            false
        )

        val outdatedThreshold = if (ignoreOutdatedThreshold) {
            OffsetDateTime.now(ZoneOffset.UTC)
        } else {
            val thresholdOutdatedSeconds = defaultSharedPreferences.getStringSafe(
                context.getString(R.string.feed_update_threshold_key),
                context.getString(R.string.feed_update_threshold_default_value)
            ).toInt()
            OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(thresholdOutdatedSeconds.toLong())
        }

        return FeedRefresh(
            groupId,
            outdatedThreshold,
            useFeedExtractor,
            AndroidOperations(
                context,
                defaultSharedPreferences,
                subscriptionManager,
                feedDatabaseManager
            ),
            context.getString(R.string.feed_processing_message),
            Schedulers.io(),
            AndroidSchedulers.mainThread(),
            FeedEventManager::postEvent
        )
    }

    /** External extraction and database operations used by the refresh pipeline. */
    internal class AndroidOperations(
        private val context: Context,
        private val defaultSharedPreferences: SharedPreferences,
        private val subscriptionManager: SubscriptionManager,
        private val feedDatabaseManager: FeedDatabaseManager
    ) : FeedRefresh.Operations {
        override fun subscriptions(
            groupId: Long,
            outdatedThreshold: OffsetDateTime
        ): Flowable<List<SubscriptionEntity>> {
            return when (groupId) {
                FeedGroupEntity.GROUP_ALL_ID -> feedDatabaseManager.outdatedSubscriptions(
                    outdatedThreshold
                )

                GROUP_NOTIFICATION_ENABLED -> feedDatabaseManager.outdatedSubscriptionsWithNotificationMode(
                    outdatedThreshold,
                    NotificationMode.ENABLED
                )

                else -> feedDatabaseManager.outdatedSubscriptionsForGroup(groupId, outdatedThreshold)
            }
        }

        override fun fetch(
            subscriptionEntity: SubscriptionEntity,
            useFeedExtractor: Boolean
        ): FeedUpdateInfo {
            var error: Throwable? = null
            val storeOriginalErrorAndRethrow = { e: Throwable ->
                // keep original to prevent blockingGet() from wrapping it into RuntimeException
                error = e
                throw e
            }

            try {
                // check for and load new streams
                // either by using the dedicated feed method or by getting the channel info
                var originalInfo: Info? = null
                var streams: List<StreamInfoItem>? = null
                val errors = ArrayList<Throwable>()

                if (useFeedExtractor) {
                    NewPipe.getService(subscriptionEntity.serviceId)
                        .getFeedExtractor(subscriptionEntity.url)
                        ?.also { feedExtractor ->
                            // the user wants to use a feed extractor and there is one, use it
                            val feedInfo = FeedInfo.getInfo(feedExtractor)
                            errors.addAll(feedInfo.errors)
                            originalInfo = feedInfo
                            streams = feedInfo.relatedItems
                        }
                }

                if (originalInfo == null) {
                    // use the normal channel tabs extractor if either the user wants it, or
                    // the current service does not have a dedicated feed extractor

                    val channelInfo = getChannelInfo(
                        subscriptionEntity.serviceId,
                        subscriptionEntity.url,
                        true
                    )
                        .onErrorReturn(storeOriginalErrorAndRethrow)
                        .blockingGet()
                    errors.addAll(channelInfo.errors)
                    originalInfo = channelInfo

                    streams = channelInfo.tabs
                        .filter { tab ->
                            ChannelTabHelper.fetchFeedChannelTab(
                                context,
                                defaultSharedPreferences,
                                tab
                            )
                        }
                        .map {
                            Pair(
                                getChannelTab(subscriptionEntity.serviceId, it, true)
                                    .onErrorReturn(storeOriginalErrorAndRethrow)
                                    .blockingGet(),
                                it
                            )
                        }
                        .flatMap { (channelTabInfo, linkHandler) ->
                            errors.addAll(channelTabInfo.errors)
                            if (channelTabInfo.relatedItems.isEmpty() &&
                                channelTabInfo.nextPage != null
                            ) {
                                val infoItemsPage = getMoreChannelTabItems(
                                    subscriptionEntity.serviceId,
                                    linkHandler,
                                    channelTabInfo.nextPage
                                )
                                    .blockingGet()

                                errors.addAll(infoItemsPage.errors)
                                return@flatMap infoItemsPage.items
                            } else {
                                return@flatMap channelTabInfo.relatedItems
                            }
                        }
                        .filterIsInstance<StreamInfoItem>()
                }

                return FeedUpdateInfo(
                    subscriptionEntity,
                    originalInfo!!,
                    streams!!,
                    errors
                )
            } catch (e: Throwable) {
                // Preserve the original extraction error rather than blockingGet's wrapper.
                throw error ?: e
            }
        }

        override fun trim() {
            feedDatabaseManager.removeOrphansOrOlderStreams()
        }

        override fun storeBatch(list: List<Notification<FeedUpdateInfo>>): List<Throwable> {
            val errors = ArrayList<Throwable>()
            feedDatabaseManager.database().runInTransaction {
                for (notification in list) {
                    when {
                        notification.isOnNext -> {
                            val info = notification.value!!

                            notification.value!!.newStreams = filterNewStreams(info.streams)

                            feedDatabaseManager.upsertAll(info.uid, info.streams)
                            subscriptionManager.updateFromInfo(info)

                            if (info.errors.isNotEmpty()) {
                                errors.addAll(
                                    info.errors.map {
                                        FeedRefresh.RequestException(
                                            info.uid,
                                            "${info.serviceId}:${info.url}",
                                            it
                                        )
                                    }
                                )
                                feedDatabaseManager.markAsOutdated(info.uid)
                            }
                        }

                        notification.isOnError -> {
                            val error = notification.error
                            errors.add(error!!)

                            if (error is FeedRefresh.RequestException) {
                                feedDatabaseManager.markAsOutdated(error.subscriptionId)
                            }
                        }
                    }
                }
            }
            return errors
        }

        private fun filterNewStreams(list: List<StreamInfoItem>): List<StreamInfoItem> {
            return list.filter {
                !feedDatabaseManager.doesStreamExist(it) &&
                    it.uploadDate != null &&
                    // Streams older than this date are automatically removed from the feed.
                    // Therefore, streams which are not in the database,
                    // but older than this date, are considered old.
                    it.uploadDate!!.offsetDateTime().isAfter(
                        FeedDatabaseManager.FEED_OLDEST_ALLOWED_DATE
                    )
            }
        }
    }

    companion object {
        /** Select subscriptions with enabled new-stream notifications. */
        const val GROUP_NOTIFICATION_ENABLED = -2L
    }
}
