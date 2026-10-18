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

    // Main-thread playback snapshot; SDK progress is measured in seconds.
    private var playbackState = 0
    private var positionMs = 0L
    private var durationMs = 0L
    private var speed = 1.0
    private var pauseOnLoad = false
    private var endedReported = false
    private var legacyReceiver = false
    private var mediaLoaded = false
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
        this.positionMs = positionMs.coerceAtLeast(0L)
        durationMs = 0L
        this.speed = speed.toDouble()
        playbackState = 3
        pauseOnLoad = !playWhenReady
        endedReported = false
        mediaLoaded = true
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
        emitPlayback()
    }

    fun pause() = onMain {
        pauseOnLoad = false
        command { it.pausePlayback() }
    }

    fun resume() = onMain {
        pauseOnLoad = false
        command { it.resumePlayback() }
    }

    fun seek(positionMs: Long) = onMain {
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
                            val address = state.localAddr.toInetAddress()
                            post(attempt) {
                                cancelTimeout()
                                localAddress = address
                                speedSupported = canSetSpeed
                                volumeSupported = canSetVolume
                                legacyReceiver = isLegacyReceiver
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

        override fun timeChanged(time: Double) = post(attempt) {
            if (time.isFinite()) {
                positionMs = secondsToMillis(time)
                emitPlayback()
            }
        }

        override fun durationChanged(duration: Double) = post(attempt) {
            if (duration.isFinite()) {
                durationMs = secondsToMillis(duration)
                emitPlayback()
            }
        }

        override fun speedChanged(speed: Double) = post(attempt) {
            if (speed.isFinite() && speed > 0.0) {
                this@FCastSession.speed = speed
                emitPlayback()
            }
        }

        override fun playbackStateChanged(state: PlaybackState) = post(attempt) {
            val ended = mediaLoaded && (
                state == PlaybackState.ENDED ||
                    (
                        state == PlaybackState.IDLE && isLegacyFCastCompletion(
                            legacyReceiver,
                            mediaLoaded,
                            playbackState == 1,
                            positionMs,
                            durationMs
                        )
                        )
                )
            playbackState = when (state) {
                PlaybackState.PLAYING -> 1
                PlaybackState.PAUSED -> 2
                PlaybackState.BUFFERING -> 3
                PlaybackState.IDLE, PlaybackState.ENDED -> 0
            }
            if (pauseOnLoad && (state == PlaybackState.PLAYING || state == PlaybackState.PAUSED)) {
                pauseOnLoad = false
                if (state == PlaybackState.PLAYING) command { it.pausePlayback() }
            }
            if (state == PlaybackState.PLAYING) endedReported = false
            emitPlayback()
            if (isCurrent(attempt.generation) && ended && !endedReported) {
                endedReported = true
                listener.onEnded()
            }
        }

        override fun playbackStopped() = post(attempt) {
            if (!mediaLoaded) return@post
            mediaLoaded = false
            playbackState = 0
            pauseOnLoad = false
            emitPlayback()
            if (isCurrent(attempt.generation)) listener.onStopped()
        }

        override fun playbackError(message: String) = post(attempt) { listener.onError(message) }
        override fun commandError(error: ReceiverError) = post(attempt) {
            listener.onError("FCast receiver: ${error.name.lowercase(Locale.ROOT).replace('_', ' ')}")
        }

        override fun sourceChanged(source: Source) = Unit
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
        playbackState = 0
        positionMs = 0L
        durationMs = 0L
        speed = 1.0
        currentVolume = 1.0
        pauseOnLoad = false
        endedReported = false
        legacyReceiver = false
        mediaLoaded = false
    }

    private fun emitPlayback() {
        if (!closed) listener.onPlaybackChanged(playbackState, positionMs, durationMs, speed)
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

    private fun secondsToMillis(seconds: Double) = (seconds.coerceAtLeast(0.0) * 1000.0).toLong()

    private companion object {
        const val TAG = "FCastSession"
        const val CONNECTION_TIMEOUT_MS = 15_000L
        const val DISCONNECT_TIMEOUT_MS = 1_500L
    }
}

/** v2 has no Ended event; never infer completion from an initial idle or unknown/live duration. */
internal fun isLegacyFCastCompletion(
    legacyReceiver: Boolean,
    mediaLoaded: Boolean,
    wasPlaying: Boolean,
    positionMs: Long,
    durationMs: Long
): Boolean {
    if (!legacyReceiver || !mediaLoaded || !wasPlaying || positionMs <= 0L || durationMs <= 0L) return false
    val tolerance = minOf(1_000L, durationMs / 10L)
    val remaining = durationMs - positionMs
    return remaining >= -tolerance && remaining <= tolerance
}
