package org.schabi.newpipe.error

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Looper
import androidx.core.app.NotificationManagerCompat
import androidx.preference.PreferenceManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast
import org.schabi.newpipe.R

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [26])
class ErrorNotificationAvailabilityTest {
    @Test
    fun `disabled application gives neutral toast`() {
        check(false, NotificationManager.IMPORTANCE_DEFAULT, false)
    }

    @Test
    fun `blocked error channel gives neutral toast`() {
        check(true, NotificationManager.IMPORTANCE_NONE, false)
    }

    @Test
    fun `missing error channel gives neutral toast`() {
        check(true, null, false)
    }

    @Test
    fun `available notification retains discovery guidance`() {
        check(true, NotificationManager.IMPORTANCE_DEFAULT, true)
    }

    @Test
    fun `hide prompts suppresses both notification and toast`() {
        check(true, NotificationManager.IMPORTANCE_DEFAULT, false, true)
    }

    private fun check(enabled: Boolean, importance: Int?, expectedNotification: Boolean, hide: Boolean = false) {
        val context = RuntimeEnvironment.getApplication()
        ShadowToast.reset()
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putBoolean(context.getString(R.string.disable_error_reports_key), hide).commit()
        val manager = mock(NotificationManagerCompat::class.java)
        val channelId = context.getString(R.string.error_report_channel_id)
        `when`(manager.areNotificationsEnabled()).thenReturn(enabled)
        `when`(manager.getNotificationChannel(channelId)).thenReturn(
            importance?.let { NotificationChannel(channelId, "Errors", it) }
        )
        val info = mock(ErrorInfo::class.java)
        `when`(info.getMessage(context)).thenReturn("Controlled diagnostic")
        mockStatic(NotificationManagerCompat::class.java).use { factory ->
            factory.`when`<NotificationManagerCompat> { NotificationManagerCompat.from(context) }.thenReturn(manager)
            ErrorUtil.createNotification(context, info)
            shadowOf(Looper.getMainLooper()).idle()
            if (expectedNotification) {
                verify(manager).notify(anyInt(), any())
                assertEquals(context.getString(R.string.error_report_notification_toast), ShadowToast.getTextOfLatestToast())
            } else {
                verify(manager, never()).notify(anyInt(), any())
                if (hide) assertNull(ShadowToast.getTextOfLatestToast()) else assertEquals("An error occurred", ShadowToast.getTextOfLatestToast())
            }
        }
    }
}
