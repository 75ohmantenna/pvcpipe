/*
 * SPDX-FileCopyrightText: 2026 PVCPipe contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.player.audio

import android.animation.ValueAnimator
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.media.AudioManager
import android.media.audiofx.AudioEffect
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import androidx.media.AudioManagerCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.Timeline
import com.google.android.exoplayer2.analytics.AnalyticsListener
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.schabi.newpipe.R

/** Uses real ExoPlayer, preferences and Android animations without loading media. */
class PlaybackAudioIntegrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var application: Context
    private lateinit var context: Context
    private lateinit var preferences: SharedPreferences
    private lateinit var preferenceName: String
    private lateinit var player: ExoPlayer
    private lateinit var environment: AndroidPlaybackAudioEnvironment
    private lateinit var audio: PlaybackAudio
    private val broadcasts = mutableListOf<Intent>()
    private var animationPending = false
    private var disposed = false

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        preferenceName = "playback-audio-test-${UUID.randomUUID()}"
        preferences = application.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)
        context = object : ContextWrapper(application) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = preferences
            override fun sendBroadcast(intent: Intent) {
                // Capture real Android intents without disturbing installed audio-effect apps.
                broadcasts.add(Intent(intent))
            }
        }
        onMain {
            player = ExoPlayer.Builder(context).setUsePlatformDiagnostics(false).build()
            player.volume = 0.65f
            environment = AndroidPlaybackAudioEnvironment(context, player)
            audio = PlaybackAudio(environment)
        }
    }

    @After
    fun tearDown() {
        awaitAnimation()
        onMain {
            if (!disposed) audio.dispose()
            player.release()
        }
        application.deleteSharedPreferences(preferenceName)
    }

    @Test
    fun initializationPreservesTheExistingInternalLevel() = onMain {
        audio.onPlaybackEvent(PlaybackAudio.Event.INITIALIZED)
        assertEquals(0.65f, audio.internalVolume, 0.001f)
        assertEquals(0.65f, player.volume, 0.001f)
        assertFalse(audio.isMuted)
    }

    @Test
    fun muteAndUnmuteRestoreTheActualGestureLevel() = onMain {
        audio.internalVolume = 0.45f
        audio.onPlaybackEvent(PlaybackAudio.Event.TOGGLE_MUTE)
        assertEquals(0f, player.volume, 0f)
        assertTrue(audio.isMuted)
        audio.onPlaybackEvent(PlaybackAudio.Event.TOGGLE_MUTE)
        assertEquals(0.45f, player.volume, 0.001f)
        assertFalse(audio.isMuted)
    }

    @Test
    fun internalVolumeChangesDoNotChangeTheSystemStream() = onMain {
        val systemBefore = audio.systemVolume
        audio.internalVolume = 0.35f
        assertEquals(0.35f, player.volume, 0.001f)
        assertEquals(systemBefore, audio.systemVolume)
    }

    @Test
    fun systemVolumeReadsUseTheMusicStreamAndItsPlatformMaximum() = onMain {
        val manager = context.getSystemService(AudioManager::class.java)
        assertEquals(manager.getStreamVolume(AudioManager.STREAM_MUSIC), audio.systemVolume)
        assertEquals(
            AudioManagerCompat.getStreamMaxVolume(manager, AudioManager.STREAM_MUSIC),
            audio.maxSystemVolume
        )
    }

    @Test
    fun permanentAndTransientFocusLossPauseTheRealPlayer() = onMain {
        for (change in listOf(AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)) {
            player.playWhenReady = true
            environment.onAudioFocusChange(change)
            assertFalse(player.playWhenReady)
        }
    }

    @Test
    fun duckAndGainRestoreTheRealPlayerThroughAndroidAnimation() {
        onMain {
            environment.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
            assertEquals(0.2f, player.volume, 0.001f)
            gainFocus()
            assertFalse(player.playWhenReady)
        }
        awaitAnimation()
        onMain { assertEquals(0.65f, player.volume, 0.001f) }
    }

    @Test
    fun enabledResumePreferenceStartsTheRealPlayerAfterAnInterruption() {
        assertTrue(
            preferences.edit()
                .putBoolean(application.getString(R.string.resume_on_audio_focus_gain_key), true)
                .commit()
        )
        onMain {
            player.playWhenReady = true
            environment.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
            gainFocus()
            assertTrue(player.playWhenReady)
        }
        awaitAnimation()
        onMain { assertEquals(0.65f, player.volume, 0.001f) }
    }

    @Test
    fun analyticsAndDisposalSendCorrectAudioEffectSessionIntents() = onMain {
        val eventTime = AnalyticsListener.EventTime(
            0, Timeline.EMPTY, 0, null, 0, Timeline.EMPTY, 0, null, 0, 0
        )
        environment.onAudioSessionIdChanged(eventTime, 37)
        val expectedClosingSession = player.audioSessionId
        disposeAudio()
        assertEquals(2, broadcasts.size)
        assertSessionIntent(broadcasts[0], AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION, 37)
        assertSessionIntent(
            broadcasts[1],
            AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION,
            expectedClosingSession
        )
    }

    @Test
    fun unavailableInternalGetterFallsBackWithoutChangingRawMuteDetection() = onMain {
        replaceCommandAvailability(com.google.android.exoplayer2.Player.COMMAND_GET_VOLUME) { false }
        assertEquals(1f, audio.internalVolume, 0f)
        assertFalse(audio.isMuted)
        audio.onPlaybackEvent(PlaybackAudio.Event.TOGGLE_MUTE)
        assertEquals(1f, audio.internalVolume, 0f)
        assertTrue(audio.isMuted)
    }

    @Test
    fun unavailableSetterKeepsActualVolumeAndItsRestoreValue() = onMain {
        var setterAvailable = false
        replaceCommandAvailability(com.google.android.exoplayer2.Player.COMMAND_SET_VOLUME) {
            setterAvailable
        }
        audio.internalVolume = 0.35f
        assertEquals(0.65f, player.volume, 0.001f)
        setterAvailable = true
        audio.onPlaybackEvent(PlaybackAudio.Event.TOGGLE_MUTE)
        assertEquals(0f, player.volume, 0f)
        audio.onPlaybackEvent(PlaybackAudio.Event.TOGGLE_MUTE)
        assertEquals(0.65f, player.volume, 0.001f)
    }

    private fun replaceCommandAvailability(command: Int, available: () -> Boolean) {
        audio.dispose()
        // ExoPlayer is an interface: filter only command availability and delegate all real effects.
        // This exercises the production command guard without an Android Mockito dependency.
        val commandFilteredPlayer = java.lang.reflect.Proxy.newProxyInstance(
            ExoPlayer::class.java.classLoader,
            arrayOf(ExoPlayer::class.java)
        ) { _, method, arguments ->
            if (method.name == "isCommandAvailable" && arguments?.get(0) == command) {
                available()
            } else {
                try {
                    method.invoke(player, *(arguments ?: emptyArray()))
                } catch (error: java.lang.reflect.InvocationTargetException) {
                    throw error.targetException
                }
            }
        } as ExoPlayer
        environment = AndroidPlaybackAudioEnvironment(context, commandFilteredPlayer)
        audio = PlaybackAudio(environment)
    }

    @Test
    fun focusGainCannotMakeMutedPlaybackAudible() {
        onMain {
            audio.onPlaybackEvent(PlaybackAudio.Event.TOGGLE_MUTE)
            gainFocus()
            assertEquals(0f, player.volume, 0f)
        }
        awaitAnimation()
        onMain { assertEquals(0f, player.volume, 0f) }
    }

    @Test
    fun repeatedDuckingCannotOverwriteTheRememberedUserLevel() {
        onMain {
            environment.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
            environment.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
            gainFocus()
        }
        awaitAnimation()
        onMain { assertEquals(0.65f, player.volume, 0.001f) }
    }

    @Test
    fun focusLossAfterDuckingCannotReplaceTheRestoreTarget() {
        onMain {
            environment.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
            environment.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
            gainFocus()
        }
        awaitAnimation()
        onMain { assertEquals(0.65f, player.volume, 0.001f) }
    }

    @Test
    fun gestureDuringRestorationSupersedesTheOlderAnimation() {
        requireEnabledAnimations()
        onMain {
            environment.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
            gainFocus()
            audio.internalVolume = 0.4f
            assertEquals(0.4f, player.volume, 0.001f)
        }
        awaitAnimation()
        onMain { assertEquals(0.4f, player.volume, 0.001f) }
    }

    @Test
    fun mutingDuringRestorationSupersedesTheOlderAnimation() {
        requireEnabledAnimations()
        onMain {
            environment.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
            gainFocus()
            audio.onPlaybackEvent(PlaybackAudio.Event.TOGGLE_MUTE)
            assertEquals(0f, player.volume, 0f)
        }
        awaitAnimation()
        onMain { assertEquals(0f, player.volume, 0f) }
    }

    @Test
    fun disposalEndsTheRealRestorationAnimationLifetime() {
        requireEnabledAnimations()
        var volumeAtDisposal = 0f
        onMain {
            environment.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
            gainFocus()
            volumeAtDisposal = player.volume
            disposeAudio()
        }
        awaitAnimation()
        onMain { assertEquals(volumeAtDisposal, player.volume, 0.001f) }
    }

    @Test
    fun focusCallbacksAfterDisposalCannotMutateThePlayer() = onMain {
        disposeAudio()
        player.playWhenReady = true
        environment.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)
        assertTrue(player.playWhenReady)
        environment.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
        assertEquals(0.65f, player.volume, 0.001f)
        gainFocus()
        assertEquals(0.65f, player.volume, 0.001f)
    }

    @Test
    fun disposingTwiceSendsOnlyOneClosingAudioEffectSession() = onMain {
        disposeAudio()
        audio.dispose()
        assertEquals(
            1,
            broadcasts.count {
                it.action == AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION
            }
        )
    }

    private fun requireEnabledAnimations() {
        val scale = animationScale()
        org.junit.Assume.assumeTrue("Requires an active Android restoration animation", scale > 0f)
    }

    @Test
    fun focusGainDoesNotResumeAnAlreadyPausedPlayer() {
        preferences.edit().putBoolean(
            application.getString(R.string.resume_on_audio_focus_gain_key),
            true
        ).commit()
        onMain {
            assertFalse(player.playWhenReady)
            gainFocus()
            assertFalse(player.playWhenReady)
        }
        awaitAnimation()
        onMain { assertFalse(player.playWhenReady) }
    }

    @Test
    fun manualPauseAfterFocusLossPreventsResume() {
        preferences.edit().putBoolean(
            application.getString(R.string.resume_on_audio_focus_gain_key),
            true
        ).commit()
        onMain {
            player.playWhenReady = true
            environment.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
            audio.onPlaybackEvent(PlaybackAudio.Event.PAUSE_REQUESTED)
            player.pause()
            gainFocus()
            assertFalse(player.playWhenReady)
        }
        awaitAnimation()
        onMain { assertFalse(player.playWhenReady) }
    }

    @Test
    fun sessionCallbacksAfterDisposalCannotReopenAudioEffects() = onMain {
        disposeAudio()
        val count = broadcasts.size
        val eventTime = AnalyticsListener.EventTime(
            0, Timeline.EMPTY, 0, null, 0, Timeline.EMPTY, 0, null, 0, 0
        )
        environment.onAudioSessionIdChanged(eventTime, 37)
        assertEquals(count, broadcasts.size)
    }

    private fun assertSessionIntent(intent: Intent, action: String, sessionId: Int) {
        assertEquals(action, intent.action)
        assertEquals(sessionId, intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, -1))
        assertEquals(context.packageName, intent.getStringExtra(AudioEffect.EXTRA_PACKAGE_NAME))
    }

    private fun gainFocus() {
        animationPending = true
        environment.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
    }

    private fun disposeAudio() {
        audio.dispose()
        disposed = true
    }

    private fun awaitAnimation() {
        if (!animationPending) return
        // Instrumentation can leave the process animator scale behind the system setting.
        // Allow at least normal duration, and any longer process duration.
        SystemClock.sleep((1500 * maxOf(1f, animationScale()) + 350).toLong())
        instrumentation.waitForIdleSync()
        animationPending = false
    }

    private fun animationScale(): Float = if (Build.VERSION.SDK_INT >= 33) {
        ValueAnimator.getDurationScale()
    } else {
        Settings.Global.getFloat(
            application.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f
        )
    }

    private fun onMain(block: () -> Unit) {
        instrumentation.runOnMainSync { block() }
    }
}
