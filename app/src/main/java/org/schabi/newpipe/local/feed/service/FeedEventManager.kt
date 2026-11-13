package org.schabi.newpipe.local.feed.service

import androidx.annotation.StringRes
import io.reactivex.rxjava3.core.Flowable
import io.reactivex.rxjava3.processors.BehaviorProcessor
import java.util.concurrent.atomic.AtomicBoolean
import org.schabi.newpipe.database.feed.model.FeedGroupEntity
import org.schabi.newpipe.local.feed.service.FeedEventManager.Event.IdleEvent

/** UI state belongs to one manual refresh, not background notification work. */
object FeedEventManager {
    private val store = EventStore()

    fun begin(groupId: Long): Session = store.begin(groupId)
    fun events(groupId: Long): Flowable<Envelope> = store.events(groupId)
    fun isCurrent(envelope: Envelope): Boolean = store.isCurrent(envelope)
    fun acknowledge(envelope: Envelope) = store.acknowledge(envelope)

    data class Envelope(
        val runId: Long,
        val groupId: Long,
        val sequence: Long,
        val event: Event,
        internal val silent: Boolean = false,
        internal val unrelatedGroup: Boolean = false
    )

    class Session internal constructor(
        private val store: EventStore,
        internal val runId: Long,
        internal val groupId: Long
    ) {
        internal var closed = false

        fun post(event: Event) = store.post(this, event)

        /** Revoke an interrupted producer, without clearing an already published result. */
        fun close() = store.close(this)
    }

    internal class EventStore {
        private var nextRunId = 0L
        private var current = Envelope(0L, FeedGroupEntity.GROUP_ALL_ID, 0L, IdleEvent)
        private val processor = BehaviorProcessor.createDefault(current).toSerialized()

        @Synchronized
        fun begin(groupId: Long): Session {
            val session = Session(this, ++nextRunId, groupId)
            publish(session, Event.ProgressEvent())
            return session
        }

        fun events(groupId: Long): Flowable<Envelope> = Flowable.defer {
            val first = AtomicBoolean(true)
            processor
                .map { envelope ->
                    if (first.getAndSet(false)) {
                        // Serialized emissions may still be queued during a reentrant
                        // subscription. Replay the owned snapshot, not the processor's
                        // potentially older cache, including an acknowledged Idle.
                        synchronized(this) { current.copy(silent = false) }
                    } else {
                        envelope
                    }
                }
                // Acknowledgment changes replay state, not the current screen's result.
                .filter { !it.silent }
                .map {
                    if (it.groupId == groupId || it.groupId == FeedGroupEntity.GROUP_ALL_ID) {
                        it
                    } else {
                        // Navigation away from a loading group must not retain its spinner.
                        it.copy(event = IdleEvent, unrelatedGroup = true)
                    }
                }
                // Do not reload an unrelated group's database for every progress update.
                .distinctUntilChanged { previous, next ->
                    previous.unrelatedGroup && next.unrelatedGroup && previous.runId == next.runId
                }
        }

        @Synchronized
        fun isCurrent(envelope: Envelope): Boolean = envelope.runId == current.runId &&
            // A run's group cannot change. Its unrelated Idle remains valid even if
            // progress advances before that Idle reaches the main thread.
            (envelope.unrelatedGroup || envelope.sequence == current.sequence)

        @Synchronized
        fun acknowledge(envelope: Envelope) {
            if (isCurrent(envelope) && envelope.event === current.event &&
                (current.event is Event.SuccessResultEvent || current.event is Event.ErrorResultEvent)
            ) {
                current = current.copy(event = IdleEvent, silent = true)
                processor.onNext(current)
            }
        }

        @Synchronized
        fun post(session: Session, event: Event) {
            if (session.closed || session.runId != current.runId) return
            if (event is Event.SuccessResultEvent || event is Event.ErrorResultEvent) {
                session.closed = true
            }
            publish(session, event)
        }

        @Synchronized
        fun close(session: Session) {
            if (session.closed) return
            session.closed = true
            if (session.runId == current.runId) publish(session, IdleEvent)
        }

        private fun publish(session: Session, event: Event) {
            current = Envelope(session.runId, session.groupId, current.sequence + 1L, event)
            processor.onNext(current)
        }
    }

    sealed class Event {
        data object IdleEvent : Event()
        data class ProgressEvent(val currentProgress: Int = -1, val maxProgress: Int = -1, @StringRes val progressMessage: Int = 0) : Event() {
            constructor(@StringRes progressMessage: Int) : this(-1, -1, progressMessage)
        }

        data class SuccessResultEvent(val itemsErrors: List<Throwable> = emptyList()) : Event()
        data class ErrorResultEvent(val error: Throwable) : Event()
    }
}
