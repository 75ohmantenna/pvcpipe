package org.schabi.newpipe.player.cast

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.net.InetAddress
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.fcast.sender_sdk.ApplicationInfo
import org.fcast.sender_sdk.CastContext
import org.fcast.sender_sdk.CastingDevice
import org.fcast.sender_sdk.DeviceConnectionState
import org.fcast.sender_sdk.DeviceEventHandler
import org.fcast.sender_sdk.DeviceFeature
import org.fcast.sender_sdk.DeviceInfo
import org.fcast.sender_sdk.LoadRequest
import org.fcast.sender_sdk.MediaTrack
import org.fcast.sender_sdk.MediaTrackType
import org.fcast.sender_sdk.Metadata
import org.fcast.sender_sdk.PlaybackState
import org.fcast.sender_sdk.ProtocolType
import org.fcast.sender_sdk.QueueState
import org.fcast.sender_sdk.ReceiverError
import org.fcast.sender_sdk.Source
import org.fcast.sender_sdk.TrackList
import org.fcast.sender_sdk.deviceInfoFromUrl
import org.schabi.newpipe.BuildConfig
import org.schabi.newpipe.R

/**
 * Owns one SDK connection. Native calls and native resource disposal are serialized off main;
 * every observable state change and listener invocation is delivered on main.
 */
class FCastSession(context: Context, private val listener: Listener) : AutoCloseable {
    interface Listener {
        fun onConnectionChanged()
        fun onPlaybackChanged(state: Int, positionMs: Long, durationMs: Long, speed: Double)
        fun onVolumeChanged(volume: Double)
        fun onError(message: String)
        fun onEnded()
        fun onStopped()
    }

    private val appName = context.applicationContext.getString(R.string.app_name)
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "FCast session").apply { isDaemon = true }
    }

    @Volatile private var generation = 0L

    @Volatile private var closed = false

    @Volatile var connectionState: Int = 0
        private set

    @Volatile var deviceName: String = ""
        private set

    @Volatile var localAddress: InetAddress? = null
        private set

    @Volatile private var currentVolume = 1.0
    val volume: Double
        get() = currentVolume

    @Volatile var speedSupported: Boolean = false
        private set

    @Volatile var volumeSupported: Boolean = false
        private set

    private val playback = FCastPlaybackState(listener) { command { it.pausePlayback() } }
    private var timeout: Runnable? = null

    // Accessed only by the IO executor, never from a native callback.
    private var active: Attempt? = null

    private class Attempt(val generation: Long) {
        val disconnected = CountDownLatch(1)
        var context: CastContext? = null
        var device: CastingDevice? = null
        var started = false
    }

    fun connect(device: FCastDevice) = beginConnect(device.name) { device.info }

    /** Plain host connections have no identity fingerprint; secure receivers need discovery or a link. */
    fun connect(host: String, port: Int) = beginConnect(host) {
        require(host.isNotBlank() && port in 1..65535) { "Invalid FCast host or port" }
        val addresses = InetAddress.getAllByName(host.trim().removeSurrounding("[", "]"))
            .map { it.toFCastAddress() }
        DeviceInfo(host, ProtocolType.F_CAST, addresses, port.toUShort(), emptyMap())
    }

    /** Receiver links carry the identity fingerprint required by modern secure receivers. */
    fun connectUrl(url: String) = beginConnect("FCast") {
        val info = requireNotNull(deviceInfoFromUrl(url.trim())) { "Invalid FCast receiver link" }
        require(info.protocol == ProtocolType.F_CAST) { "This is not an FCast receiver link" }
        info
    }

    private fun beginConnect(name: String, deviceInfo: () -> DeviceInfo) = onMain {
        if (closed) return@onMain
        val token = ++generation
        cancelTimeout()
        deviceName = name
        resetPlayback()
        connectionState = 1
        localAddress = null
        speedSupported = false
        volumeSupported = false
        val deadline = Runnable { fail(token, "FCast connection timed out") }
        timeout = deadline
        main.postDelayed(deadline, CONNECTION_TIMEOUT_MS)
        io.execute {
            releaseActive(null, true)
            if (!isCurrent(token)) return@execute
            val attempt = Attempt(token)
            active = attempt
            try {
                val info = deviceInfo()
                require(info.protocol == ProtocolType.F_CAST && info.addresses.isNotEmpty() && info.port != 0.toUShort()) {
                    "FCast receiver has no resolved address or port"
                }
                if (!isCurrent(token)) {
                    releaseActive(token, false)
                    return@execute
                }
                val castContext = CastContext()
                attempt.context = castContext
                val nativeDevice = castContext.createDeviceFromInfo(info)
                attempt.device = nativeDevice
                post(attempt) {
                    if (deviceName != info.name) {
                        deviceName = info.name
                        listener.onConnectionChanged()
                    }
                }
                if (!isCurrent(token)) {
                    releaseActive(token, false)
                    return@execute
                }
                nativeDevice.connect(
                    ApplicationInfo(appName, BuildConfig.VERSION_NAME, Build.MODEL),
                    events(attempt),
                    0uL // The app owns the timeout and reconnection policy.
                )
                attempt.started = true
            } catch (error: Exception) {
                post(attempt) { fail(token, error.message ?: "FCast connection failed") }
                releaseActive(token, false)
            } catch (error: LinkageError) {
                post(attempt) { fail(token, error.message ?: "FCast SDK could not be loaded") }
                releaseActive(token, false)
            }
        }
        listener.onConnectionChanged()
    }

    fun load(
        media: CastMedia,
        positionMs: Long,
        speed: Float,
        playWhenReady: Boolean,
        title: String,
        thumbnail: String?
    ) = onMain {
        if (closed) return@onMain
        val source = media.url()?.let { Source.Url(it, media.mimeType()) }
            ?: Source.Content(requireNotNull(media.content()))
        playback.load(source, positionMs, speed.toDouble(), playWhenReady)
        command { device ->
            val metadata = Metadata(title, thumbnail)
            val requestedSpeed = if (speedSupported) speed.toDouble() else null
            val headers = media.headers().ifEmpty { null }
            val request = media.url()?.let { url ->
                LoadRequest.Video(
                    media.mimeType(),
                    url,
                    positionMs.coerceAtLeast(0L) / 1000.0,
                    requestedSpeed,
                    null,
                    metadata,
                    headers
                )
            } ?: LoadRequest.Content(
                media.mimeType(),
                requireNotNull(media.content()),
                positionMs.coerceAtLeast(0L) / 1000.0,
                requestedSpeed,
                null,
                metadata,
                headers
            )
            device.load(request, 500uL)
            if (!playWhenReady) device.pausePlayback()
        }
        playback.emit()
    }

    fun pause() = onMain {
        playback.clearPauseOnLoad()
        command { it.pausePlayback() }
    }

    fun resume() = onMain {
        playback.clearPauseOnLoad()
        command { it.resumePlayback() }
    }

    fun seek(positionMs: Long) = onMain {
        playback.seek(positionMs)
        command { it.seek(positionMs.coerceAtLeast(0L) / 1000.0) }
    }

    fun setVolume(volume: Double) = onMain {
        if (volume.isFinite()) command { it.changeVolume(volume.coerceIn(0.0, 1.0)) }
    }

    fun setSpeed(speed: Float) = onMain {
        if (speed.isFinite() && speed > 0f) command { it.changeSpeed(speed.toDouble()) }
    }

    fun disconnect(stopPlayback: Boolean) = onMain {
        if (!closed) disconnectOnMain(stopPlayback)
    }

    override fun close() = onMain {
        if (closed) return@onMain
        closed = true
        val previous = generation++
        cancelTimeout()
        main.removeCallbacksAndMessages(null)
        connectionState = 0
        localAddress = null
        speedSupported = false
        volumeSupported = false
        resetPlayback()
        io.execute { releaseActive(previous, true) }
        io.shutdown()
    }

    private fun disconnectOnMain(stopPlayback: Boolean) {
        val previous = generation++
        val wasConnected = connectionState != 0
        cancelTimeout()
        connectionState = 0
        localAddress = null
        speedSupported = false
        volumeSupported = false
        resetPlayback()
        io.execute { releaseActive(previous, stopPlayback) }
        if (wasConnected) listener.onConnectionChanged()
    }

    private fun command(action: (CastingDevice) -> Unit) {
        if (closed) return
        if (connectionState != 2) {
            listener.onError("FCast receiver is not connected")
            return
        }
        val token = generation
        io.execute {
            if (!isCurrent(token)) return@execute
            val attempt = active?.takeIf { it.generation == token } ?: return@execute
            try {
                attempt.device?.let(action)
            } catch (error: Exception) {
                post(attempt) { listener.onError(error.message ?: "FCast command failed") }
            }
        }
    }

    private fun events(attempt: Attempt) = object : DeviceEventHandler {
        override fun connectionStateChanged(state: DeviceConnectionState) {
            if (state is DeviceConnectionState.Disconnected) attempt.disconnected.countDown()
            if (!isCurrent(attempt.generation)) return
            when (state) {
                is DeviceConnectionState.Connected -> post(attempt) {
                    io.execute {
                        if (!isCurrent(attempt.generation)) return@execute
                        try {
                            val device = requireNotNull(attempt.device)
                            val canSetSpeed = device.supportsFeature(DeviceFeature.SET_SPEED)
                            val canSetVolume = device.supportsFeature(DeviceFeature.SET_VOLUME)
                            // FCast v2 lacks explicit end events; LOAD_IMAGE was introduced in v3.
                            val isLegacyReceiver = !device.supportsFeature(DeviceFeature.LOAD_IMAGE)
                            // Some SDK-v3 peers retain v2 playback updates without source/end events.
                            val legacyCompletionWithoutEcho =
                                device.supportsFeature(DeviceFeature.LOAD_PLAYLIST)
                            val address = state.localAddr.toInetAddress()
                            post(attempt) {
                                cancelTimeout()
                                localAddress = address
                                speedSupported = canSetSpeed
                                volumeSupported = canSetVolume
                                playback.legacyReceiver = isLegacyReceiver
                                playback.legacyCompletionWithoutEcho = legacyCompletionWithoutEcho
                                connectionState = 2
                                listener.onConnectionChanged()
                            }
                        } catch (error: Exception) {
                            post(attempt) { fail(attempt.generation, error.message ?: "FCast connection failed") }
                        }
                    }
                }

                is DeviceConnectionState.Disconnected -> post(attempt) {
                    val message = if (connectionState == 1) {
                        "Could not connect to the FCast receiver. Secure receivers require discovery or an FCast link."
                    } else {
                        "FCast receiver disconnected"
                    }
                    fail(attempt.generation, message)
                }

                is DeviceConnectionState.Connecting, is DeviceConnectionState.Reconnecting -> Unit
            }
        }

        override fun volumeChanged(volume: Double) = post(attempt) {
            if (volume.isFinite()) {
                currentVolume = volume.coerceIn(0.0, 1.0)
                listener.onVolumeChanged(currentVolume)
            }
        }

        override fun timeChanged(time: Double) = post(attempt) { playback.timeChanged(time) }

        override fun durationChanged(duration: Double) = post(attempt) {
            playback.durationChanged(duration)
        }

        override fun speedChanged(speed: Double) = post(attempt) { playback.speedChanged(speed) }

        override fun playbackStateChanged(state: PlaybackState) = post(attempt) {
            playback.playbackStateChanged(state)
        }

        override fun playbackStopped() = post(attempt) { playback.stopped() }

        override fun playbackError(message: String) = post(attempt) { listener.onError(message) }
        override fun commandError(error: ReceiverError) = post(attempt) {
            listener.onError("FCast receiver: ${error.name.lowercase(Locale.ROOT).replace('_', ' ')}")
        }

        override fun sourceChanged(source: Source) = post(attempt) { playback.sourceChanged(source) }
        override fun tracksAvailable(tracks: List<MediaTrack>) = Unit
        override fun trackSelected(id: UInt?, typ: MediaTrackType) = Unit
        override fun tracksChanged(tracks: TrackList) = Unit
        override fun queueChanged(queue: QueueState) = Unit
    }

    private fun fail(token: Long, message: String) {
        if (!isCurrent(token)) return
        disconnectOnMain(false)
        if (isCurrent(token + 1)) listener.onError(message)
    }

    private fun releaseActive(token: Long?, stopPlayback: Boolean) {
        val attempt = active ?: return
        if (token != null && attempt.generation != token) return
        active = null
        try {
            if (stopPlayback) attempt.device?.stopPlayback()
        } catch (error: Exception) {
            Log.w(TAG, "Could not stop remote playback during disconnect", error)
        }
        try {
            attempt.device?.disconnect()
            // disconnect() queues Quit; wait off main so Stop reaches the socket before the
            // runtime is destroyed. No SDK completion callback exists apart from Disconnected.
            if (attempt.started) attempt.disconnected.await(DISCONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (error: Exception) {
            Log.w(TAG, "Could not disconnect FCast receiver", error)
            if (error is InterruptedException) Thread.currentThread().interrupt()
        } finally {
            try {
                attempt.device?.close()
            } catch (error: Exception) {
                Log.w(TAG, "Could not release FCast device", error)
            }
            try {
                attempt.context?.close()
            } catch (error: Exception) {
                Log.w(TAG, "Could not release FCast runtime", error)
            }
        }
    }

    private fun resetPlayback() {
        playback.reset()
        currentVolume = 1.0
    }

    private fun cancelTimeout() {
        timeout?.let(main::removeCallbacks)
        timeout = null
    }

    private fun isCurrent(token: Long) = !closed && generation == token

    private fun post(attempt: Attempt, action: () -> Unit) {
        if (isCurrent(attempt.generation)) {
            main.post {
                if (isCurrent(attempt.generation)) action()
            }
        }
    }

    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else main.post { action() }
    }

    private companion object {
        const val TAG = "FCastSession"
        const val CONNECTION_TIMEOUT_MS = 15_000L
        const val DISCONNECT_TIMEOUT_MS = 1_500L
    }
}

/**
 * Main-thread playback policy, independent of the SDK connection and Android event loop.
 * Receiver state and duration are connection-wide: SDK 0.6.1 deduplicates unchanged v2/v3 values.
 * Source echoes are learned from matching acknowledgements, not SDK feature/version guesses:
 * some working receivers never echo their own loads, including observed SDK-v3 connections.
 * Until an own-load echo is observed, readiness must use playback evidence; the protocol
 * cannot distinguish an old source's pending progress from the new load's progress.
 */
internal class FCastPlaybackState(
    private val listener: FCastSession.Listener,
    private val pause: () -> Unit
) {
    var legacyReceiver = false
    var legacyCompletionWithoutEcho = false
    private var sourceEchoObserved = false
    private var receiverState = PlaybackState.IDLE
    private var expectedSource: Source? = null
    private var sourceAcknowledged = false
    private var pendingLoad = false
    private var positionMs = 0L
    private var durationMs = 0L
    private var speed = 1.0
    private var pauseOnLoad = false
    private var endedReported = false
    private var playedThisLoad = false
    private var revision = 0L

    fun load(source: Source, positionMs: Long, speed: Double, playWhenReady: Boolean) {
        revision++
        expectedSource = source
        sourceAcknowledged = false
        pendingLoad = true
        this.positionMs = positionMs.coerceAtLeast(0L)
        this.speed = speed
        pauseOnLoad = !playWhenReady
        endedReported = false
        playedThisLoad = false
    }

    fun sourceChanged(source: Source) {
        val expected = expectedSource ?: return
        if (source == expected) {
            sourceAcknowledged = true
            sourceEchoObserved = true
        } else if (sourceAcknowledged || !pendingLoad) {
            relinquish()
        }
        // Pending Initial/older in-flight loads can still report their source. Once this
        // connection has echoed our loads, block their callbacks until our next matching echo.
    }

    fun timeChanged(time: Double) {
        if (!acceptsPlayback() || !time.isFinite()) return
        val next = secondsToMillis(time)
        if (pendingLoad && receiverState == PlaybackState.PLAYING && next > positionMs) {
            // A playing receiver need not send another PLAYING callback after load().
            pendingLoad = false
            playedThisLoad = true
            applyPauseOnLoad()
        }
        positionMs = next
        emit()
    }

    fun durationChanged(duration: Double) {
        if (!duration.isFinite()) return
        // Cache even initial or pending-source snapshots: an unchanged duration is not re-emitted.
        durationMs = secondsToMillis(duration)
        if (acceptsPlayback()) emit()
    }

    fun speedChanged(speed: Double) {
        if (!acceptsPlayback() || !speed.isFinite() || speed <= 0.0) return
        this.speed = speed
        emit()
    }

    fun playbackStateChanged(state: PlaybackState) {
        val previous = receiverState
        receiverState = state
        if (!acceptsPlayback()) return
        // An initial idle/ended snapshot can precede the receiver accepting a load.
        if (pendingLoad && (state == PlaybackState.IDLE || state == PlaybackState.ENDED)) return
        pendingLoad = false
        val completed = state == PlaybackState.ENDED || legacyCompletion(previous, state)
        if (state == PlaybackState.IDLE && !completed && !endedReported) {
            relinquish()
            return
        }
        if (state == PlaybackState.PLAYING) {
            playedThisLoad = true
            endedReported = false
        }
        applyPauseOnLoad()
        val reportEnd = completed && !endedReported
        if (reportEnd) endedReported = true
        val current = revision
        emit()
        // A consumer can replace/disconnect the load while observing its final playback snapshot.
        if (current == revision && reportEnd) listener.onEnded()
    }

    fun stopped() {
        // SDK v4 can relay a stop of the previous source before accepting our pending load.
        if (expectedSource != null && !pendingLoad) relinquish()
    }

    fun clearPauseOnLoad() {
        pauseOnLoad = false
    }

    fun seek(positionMs: Long) {
        if (durationMs <= 0L || positionMs < durationMs) endedReported = false
    }

    fun reset() {
        revision++
        legacyReceiver = false
        legacyCompletionWithoutEcho = false
        sourceEchoObserved = false
        receiverState = PlaybackState.IDLE
        expectedSource = null
        sourceAcknowledged = false
        pendingLoad = false
        positionMs = 0L
        durationMs = 0L
        speed = 1.0
        pauseOnLoad = false
        endedReported = false
        playedThisLoad = false
    }

    fun emit() {
        if (expectedSource == null) return
        val state = if (pendingLoad) {
            3
        } else {
            when (receiverState) {
                PlaybackState.PLAYING -> 1
                PlaybackState.PAUSED -> 2
                PlaybackState.BUFFERING -> 3
                PlaybackState.IDLE, PlaybackState.ENDED -> 0
            }
        }
        listener.onPlaybackChanged(state, positionMs, if (pendingLoad) 0L else durationMs, speed)
    }

    private fun acceptsPlayback() = expectedSource != null &&
        (!sourceEchoObserved || sourceAcknowledged)

    private fun relinquish() {
        revision++
        expectedSource = null
        sourceAcknowledged = false
        pendingLoad = false
        pauseOnLoad = false
        listener.onStopped()
    }

    private fun applyPauseOnLoad() {
        if (pauseOnLoad && (receiverState == PlaybackState.PLAYING || receiverState == PlaybackState.PAUSED)) {
            pauseOnLoad = false
            if (receiverState == PlaybackState.PLAYING) pause()
        }
    }

    private fun legacyCompletion(previous: PlaybackState, state: PlaybackState): Boolean {
        val canInferCompletion = legacyReceiver ||
            (legacyCompletionWithoutEcho && !sourceEchoObserved)
        if (!canInferCompletion || !playedThisLoad || previous != PlaybackState.PLAYING ||
            positionMs <= 0L || durationMs <= 0L
        ) {
            return false
        }
        val tolerance = minOf(1_000L, durationMs / 10L)
        val remaining = durationMs - positionMs
        return when (state) {
            PlaybackState.IDLE -> remaining in -tolerance..tolerance

            // Android Receiver 2.1.5 maps STATE_ENDED to PAUSED. Apply this fallback only to
            // v2 or SDK-v3 peers with no observed own-source echo, never to v4. Require the
            // known duration, not merely near it. Without explicit source/end notifications,
            // a manual pause/seek exactly at the end is indistinguishable from completion.
            PlaybackState.PAUSED -> remaining in -tolerance..0L

            else -> false
        }
    }

    private fun secondsToMillis(seconds: Double) = (seconds.coerceAtLeast(0.0) * 1000.0).toLong()
}
