package org.schabi.newpipe.player.gesture

import android.app.Application
import android.content.Context
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.preference.PreferenceManager
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.schabi.newpipe.R
import org.schabi.newpipe.databinding.PlayerBinding
import org.schabi.newpipe.player.Player
import org.schabi.newpipe.player.audio.PlaybackAudio
import org.schabi.newpipe.player.ui.MainPlayerUi

/** Real helper, touch routing and views, with controlled audio and detector callbacks. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class InternalVolumeGestureUiTest {
    private lateinit var context: Context
    private lateinit var binding: PlayerBinding
    private lateinit var ui: MainPlayerUi
    private lateinit var listener: MainPlayerGestureListener
    private lateinit var parent: TouchParent
    private var volume = 0.5f

    @Before
    fun setup() {
        context = ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.LightTheme)
        binding = PlayerBinding.inflate(LayoutInflater.from(context))
        parent = TouchParent(context)
        parent.addView(binding.root)
        binding.root.layout(0, 0, 1000, 1000)
        val audio = Mockito.mock(PlaybackAudio::class.java)
        `when`(audio.internalVolume).thenAnswer { volume }
        doAnswer {
            volume = it.getArgument(0)
            null
        }.`when`(audio).internalVolume = Mockito.anyFloat()
        val player = Mockito.mock(Player::class.java, Mockito.RETURNS_DEEP_STUBS)
        `when`(player.context).thenReturn(context)
        `when`(player.playbackAudio).thenReturn(audio)
        `when`(player.currentState).thenReturn(Player.STATE_PLAYING)
        ui = Mockito.mock(MainPlayerUi::class.java)
        `when`(ui.player).thenReturn(player)
        `when`(ui.binding).thenReturn(binding)
        `when`(ui.isFullscreen).thenReturn(true)
        `when`(ui.gestureDetector).thenReturn(Mockito.mock(GestureDetector::class.java))
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putBoolean(context.getString(R.string.pvc_settings_internal_volume_key), true).commit()
        listener = MainPlayerGestureListener(ui)
    }

    @Test
    fun `real helper applies split deltas rather than discarding each callback`() {
        touch(MotionEvent.ACTION_DOWN)
        repeat(8) { scroll(0.5f) }
        assertEquals(51, binding.pvcPlayerExt.internalVolProgressBar.progress)
        assertEquals(0.51f, volume, 0.00001f)
    }

    @Test
    fun `up and cancel clear movement ownership fractions and parent interception`() {
        for (end in listOf(MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL)) {
            volume = 0.5f
            touch(MotionEvent.ACTION_DOWN)
            scroll(3f)
            assertTrue(parent.disallowed)
            touch(end)
            assertFalse(parent.disallowed)
            volume = 0.2f
            // Direct controlled detector callback proves the end reset itself, not a new DOWN.
            scroll(1f)
            assertEquals(20, binding.pvcPlayerExt.internalVolProgressBar.progress)
            touch(end)
            // Moving flag must also have been cleared: a new below-threshold scroll is rejected.
            assertFalse(scroll(1f, moved = 10f))
        }
    }

    @Test
    fun `new down resets an interrupted gesture even without a previous up`() {
        touch(MotionEvent.ACTION_DOWN)
        scroll(3f)
        volume = 0.2f
        touch(MotionEvent.ACTION_DOWN)
        assertFalse(scroll(1f, moved = 10f))
        scroll(1f)
        assertEquals(20, binding.pvcPlayerExt.internalVolProgressBar.progress)
    }

    @Test
    fun `tap after an interrupted swipe still schedules the old overlay to hide`() {
        touch(MotionEvent.ACTION_DOWN)
        scroll(4f)
        val overlay = binding.pvcPlayerExt.internalVolRelativeLayout
        assertEquals(0L, overlay.animate().startDelay)
        touch(MotionEvent.ACTION_DOWN)
        touch(MotionEvent.ACTION_UP)
        assertEquals(200L, overlay.animate().startDelay)
    }

    @Test
    fun `restart during visible old fade resyncs volume and restores overlay`() {
        touch(MotionEvent.ACTION_DOWN)
        scroll(4f)
        touch(MotionEvent.ACTION_UP)
        val overlay = binding.pvcPlayerExt.internalVolRelativeLayout
        assertEquals(View.VISIBLE, overlay.visibility)
        // Model the real SCALE_AND_ALPHA animation's partially faded properties.
        overlay.alpha = 0.4f
        overlay.scaleX = 0.85f
        overlay.scaleY = 0.85f
        volume = 0.8f
        touch(MotionEvent.ACTION_DOWN)
        scroll(1f)
        assertEquals(80, binding.pvcPlayerExt.internalVolProgressBar.progress)
        assertEquals(1f, overlay.alpha, 0f)
        assertEquals(1f, overlay.scaleX, 0f)
        assertEquals(1f, overlay.scaleY, 0f)
        shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.SECONDS)
        assertEquals(View.VISIBLE, overlay.visibility)
        assertEquals(80, binding.pvcPlayerExt.internalVolProgressBar.progress)
    }

    private fun touch(action: Int) {
        val event = MotionEvent.obtain(0, 0, action, 990f, 200f, 0)
        try {
            listener.onTouch(binding.root, event)
        } finally {
            event.recycle()
        }
    }

    private fun scroll(distance: Float, moved: Float = 100f): Boolean {
        val initial = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 990f, 200f, 0)
        val moving = MotionEvent.obtain(0, 10, MotionEvent.ACTION_MOVE, 990f, 200f - moved, 0)
        return try {
            listener.onScroll(initial, moving, 0f, distance)
        } finally {
            initial.recycle()
            moving.recycle()
        }
    }

    private class TouchParent(context: Context) : FrameLayout(context) {
        var disallowed = false
        override fun requestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {
            disallowed = disallowIntercept
            super.requestDisallowInterceptTouchEvent(disallowIntercept)
        }
    }
}
