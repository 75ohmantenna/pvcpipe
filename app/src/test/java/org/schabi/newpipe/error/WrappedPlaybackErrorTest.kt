package org.schabi.newpipe.error

import android.app.Application
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.upstream.Loader
import androidx.preference.PreferenceManager
import java.io.IOException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import org.schabi.newpipe.App
import org.schabi.newpipe.R
import org.schabi.newpipe.extractor.exceptions.PrivateContentException
import org.schabi.newpipe.player.mediasource.FailedMediaSource

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [26])
class WrappedPlaybackErrorTest {
    private var previousHandler: Thread.UncaughtExceptionHandler? = null

    @Before
    fun disableCaptureWithoutStartingApplicationServices() {
        previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        val context = RuntimeEnvironment.getApplication()
        // App.attachBaseContext installs a process-wide crash handler. This mapping fixture
        // needs only a context for the disabled capture preference, not production startup.
        val app = App()
        ReflectionHelpers.setField(app, "mBase", context)
        ReflectionHelpers.setStaticField(App::class.java, "instance", app)
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putBoolean(context.getString(R.string.pvc_settings_debug_logcat_dumper_key), false).commit()
    }

    @After
    fun leaveTheProcessCrashHandlerUnchanged() {
        val actual = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(previousHandler)
        assertSame(previousHandler, actual)
    }

    @Test
    fun `exact synthetic loader chain terminates and preserves outer metadata`() {
        val context = RuntimeEnvironment.getApplication()
        for (service in listOf(null, 0, 1)) {
            for (action in listOf(UserAction.PLAY_STREAM, UserAction.REQUESTED_COMMENTS)) {
                val inner = PrivateContentException("private")
                val outer = source(Loader.UnexpectedLoaderException(inner))
                val info = ErrorInfo(outer, action, "request", service, "https://example.org/video")
                assertEquals(context.getString(R.string.private_content), info.getMessage(context).toString())
                assertEquals(service, info.serviceId)
                assertEquals(action, info.userAction)
                assertEquals("request", info.request)
                assertEquals("https://example.org/video", info.openInBrowserUrl)
                assertTrue(info.isRetryable)
                assertTrue(info.isReportable)
                assertNull(info.recaptchaUrl)
                assertTrue(info.stackTraces.single().contains("ExoPlaybackException"))
            }
        }
    }

    @Test
    fun `unrelated loader and ordinary failed media mappings are unchanged`() {
        val context = RuntimeEnvironment.getApplication()
        val unrelated = source(Loader.UnexpectedLoaderException(IllegalStateException("other")))
        assertEquals(context.getString(R.string.player_stream_failure), ErrorInfo.getMessage(unrelated, UserAction.PLAY_STREAM, 0).getText(context).toString())
        val ordinary = FailedMediaSource.StreamInfoLoadException(PrivateContentException("private"))
        assertEquals(context.getString(R.string.private_content), ErrorInfo.getMessage(ordinary, UserAction.PLAY_STREAM, 0).getText(context).toString())
    }

    private fun source(cause: IOException) = ExoPlaybackException.createForSource(cause, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
}
