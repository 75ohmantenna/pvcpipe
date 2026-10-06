package org.schabi.newpipe.local.feed.service

import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Flowable
import io.reactivex.rxjava3.core.Notification
import io.reactivex.rxjava3.core.Scheduler
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.processors.PublishProcessor
import java.time.OffsetDateTime
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.schabi.newpipe.R
import org.schabi.newpipe.database.subscription.SubscriptionEntity
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.local.feed.FeedDatabaseManager

/** One subscription refresh, including extraction, persistence, progress and collected errors. */
class FeedRefresh internal constructor(
    private val groupId: Long,
    private val outdatedThreshold: OffsetDateTime,
    private val useFeedExtractor: Boolean,
    private val operations: Operations,
    private val processingDescription: String,
    private val io: Scheduler,
    private val main: Scheduler,
    private val postEvent: (FeedEventManager.Event) -> Unit
) {
    private val notificationUpdater = PublishProcessor.create<String>()
    private val currentProgress = AtomicInteger(-1)
    private val maxProgress = AtomicInteger(-1)
    private val cancelSignal = AtomicBoolean()
    private val itemsErrors = ArrayList<Throwable>()

    val progress: Flowable<FeedLoadState> = notificationUpdater.map { description ->
        FeedLoadState(description, maxProgress.get(), currentProgress.get())
    }

    val result: Single<List<Notification<FeedUpdateInfo>>> = buildResult()

    private fun buildResult(): Single<List<Notification<FeedUpdateInfo>>> {
        // like `currentProgress`, but counts the number of YouTube extractions that have begun, so
        // they can be properly throttled every once in a while (see doOnNext below)
        val youtubeExtractionCount = AtomicInteger()

        return operations.subscriptions(groupId, outdatedThreshold)
            .take(1)
            .doOnNext {
                currentProgress.set(0)
                maxProgress.set(it.size)
            }
            .filter { it.isNotEmpty() }
            .observeOn(main)
            .doOnNext {
                notificationUpdater.onNext("")
                broadcastProgress()
            }
            .observeOn(io)
            // Randomize user subscription ordering to attempt to resist fingerprinting
            .flatMap { Flowable.fromIterable(it.shuffled()) }
            .takeWhile { !cancelSignal.get() }
            .doOnNext { subscriptionEntity ->
                // throttle YouTube extractions once every BATCH_SIZE to avoid being rate limited
                if (subscriptionEntity.serviceId == ServiceList.YouTube.serviceId) {
                    val previousCount = youtubeExtractionCount.getAndIncrement()
                    if (previousCount != 0 && previousCount % BATCH_SIZE == 0) {
                        Thread.sleep(DELAY_BETWEEN_BATCHES_MILLIS.random())
                    }
                }
            }
            .parallel(PARALLEL_EXTRACTIONS, PARALLEL_EXTRACTIONS * 2)
            .runOn(io, PARALLEL_EXTRACTIONS * 2)
            .filter { !cancelSignal.get() }
            .map { subscriptionEntity ->
                loadStreams(subscriptionEntity)
            }
            .sequential()
            .observeOn(main)
            .doOnNext { item ->
                currentProgress.incrementAndGet()
                notificationUpdater.onNext(item.value?.name.orEmpty())
                broadcastProgress()
            }
            .observeOn(io)
            .buffer(BUFFER_COUNT_BEFORE_INSERT)
            .doOnNext { itemsErrors.addAll(operations.storeBatch(it)) }
            .subscribeOn(io)
            .toList()
            .flatMap { x -> postProcessFeed().toSingleDefault(x.flatten()) }
    }

    /** Stop admitting extractions while allowing already collected results to be stored. */
    fun cancel() {
        cancelSignal.set(true)
    }

    private fun broadcastProgress() {
        postEvent(
            FeedEventManager.Event.ProgressEvent(
                currentProgress.get(),
                maxProgress.get()
            )
        )
    }

    private fun loadStreams(subscription: SubscriptionEntity): Notification<FeedUpdateInfo> {
        return try {
            Notification.createOnNext(operations.fetch(subscription, useFeedExtractor))
        } catch (error: Throwable) {
            Notification.createOnError(
                RequestException(
                    subscription.uid,
                    "${subscription.serviceId}:${subscription.url}",
                    error
                )
            )
        }
    }

    /**
     * Keep the feed and the stream tables small
     * to reduce loading times when trying to display the feed.
     * <br>
     * Remove streams from the feed which are older than [FeedDatabaseManager.FEED_OLDEST_ALLOWED_DATE].
     * Remove streams from the database which are not linked / used by any table.
     */
    private fun postProcessFeed() = Completable.fromRunnable {
        postEvent(FeedEventManager.Event.ProgressEvent(R.string.feed_processing_message))
        operations.trim()

        postEvent(FeedEventManager.Event.SuccessResultEvent(itemsErrors))
    }.doOnSubscribe {
        currentProgress.set(-1)
        maxProgress.set(-1)

        notificationUpdater.onNext(processingDescription)
        postEvent(FeedEventManager.Event.ProgressEvent(R.string.feed_processing_message))
    }.subscribeOn(io)

    class RequestException(val subscriptionId: Long, message: String, cause: Throwable) : Exception(message, cause)

    internal interface Operations {
        fun subscriptions(groupId: Long, outdatedThreshold: OffsetDateTime): Flowable<List<SubscriptionEntity>>
        fun fetch(subscriptionEntity: SubscriptionEntity, useFeedExtractor: Boolean): FeedUpdateInfo
        fun storeBatch(list: List<Notification<FeedUpdateInfo>>): List<Throwable>
        fun trim()
    }

    companion object {
        /**
         * How many extractions will be running in parallel.
         */
        private const val PARALLEL_EXTRACTIONS = 3

        /**
         * How many YouTube extractions to perform before waiting [DELAY_BETWEEN_BATCHES_MILLIS]
         * to avoid being rate limited
         */
        private const val BATCH_SIZE = 50

        /**
         * Wait a random delay in this range once every [BATCH_SIZE] YouTube extractions to avoid
         * being rate limited
         */
        private val DELAY_BETWEEN_BATCHES_MILLIS = (6000L..12000L)

        /**
         * Number of items to buffer to mass-insert in the database.
         */
        private const val BUFFER_COUNT_BEFORE_INSERT = 20
    }
}
