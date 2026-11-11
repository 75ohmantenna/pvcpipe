package org.schabi.newpipe.player.mediabrowser

import android.Manifest.permission.MEDIA_CONTENT_CONTROL
import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Process
import androidx.core.app.NotificationManagerCompat
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [26])
@Suppress("deprecation")
class PackageValidatorTest {
    private val context = mock(Context::class.java)
    private val manager = mock(PackageManager::class.java)
    private val flags = PackageManager.GET_SIGNATURES or PackageManager.GET_PERMISSIONS

    private fun validator(): PackageValidator {
        `when`(context.applicationContext).thenReturn(context)
        `when`(context.packageManager).thenReturn(manager)
        install("android", Process.SYSTEM_UID, "platform")
        return PackageValidator(context)
    }

    private fun install(name: String, uid: Int, signature: String = "ordinary", mediaControl: Boolean = false) {
        val info = PackageInfo().apply {
            packageName = name
            applicationInfo = ApplicationInfo().apply {
                this.uid = uid
                packageName = name
                nonLocalizedLabel = name
            }
            signatures = arrayOf(Signature(signature.toByteArray()))
            if (mediaControl) {
                requestedPermissions = arrayOf(MEDIA_CONTENT_CONTROL)
                requestedPermissionsFlags = intArrayOf(PackageInfo.REQUESTED_PERMISSION_GRANTED)
            }
        }
        `when`(manager.getPackageInfo(name, flags)).thenReturn(info)
    }

    @Test
    fun `listener grant revocation and regrant are checked without rebuilding identity`() {
        val validator = validator()
        install("listener", 12345)
        mockStatic(NotificationManagerCompat::class.java).use { notifications ->
            notifications.`when`<Set<String>> { NotificationManagerCompat.getEnabledListenerPackages(context) }
                .thenReturn(setOf("listener"), emptySet(), setOf("listener"))
            assertTrue(validator.isKnownCaller("listener", 12345))
            assertFalse(validator.isKnownCaller("listener", 12345))
            assertTrue(validator.isKnownCaller("listener", 12345))
            verify(manager, times(1)).getPackageInfo("listener", flags)
        }
    }

    @Test
    fun `initial denial does not survive a later listener grant`() {
        val validator = validator()
        install("listener", 12345)
        mockStatic(NotificationManagerCompat::class.java).use { notifications ->
            notifications.`when`<Set<String>> { NotificationManagerCompat.getEnabledListenerPackages(context) }
                .thenReturn(emptySet(), setOf("listener"))
            assertFalse(validator.isKnownCaller("listener", 12345))
            assertTrue(validator.isKnownCaller("listener", 12345))
            verify(manager, times(1)).getPackageInfo("listener", flags)
        }
    }

    @Test
    fun `listener access never bypasses identity validation including a cached identity`() {
        val validator = validator()
        install("listener", 12345)
        mockStatic(NotificationManagerCompat::class.java).use { notifications ->
            notifications.`when`<Set<String>> { NotificationManagerCompat.getEnabledListenerPackages(context) }
                .thenReturn(setOf("listener"))
            assertTrue(runCatching { validator.isKnownCaller("listener", 54321) }.exceptionOrNull() is IllegalStateException)
            assertTrue(validator.isKnownCaller("listener", 12345))
            assertTrue(runCatching { validator.isKnownCaller("listener", 54321) }.exceptionOrNull() is IllegalStateException)
        }
    }

    @Test
    fun `own system platform and granted media control remain trusted without listener access`() {
        val validator = validator()
        install("own", Process.myUid())
        install("system", Process.SYSTEM_UID)
        install("platform", 12346, "platform")
        install("media", 12347, mediaControl = true)
        install("ordinary", 12348)
        mockStatic(NotificationManagerCompat::class.java).use { notifications ->
            notifications.`when`<Set<String>> { NotificationManagerCompat.getEnabledListenerPackages(context) }.thenReturn(emptySet())
            repeat(2) {
                assertTrue(validator.isKnownCaller("own", Process.myUid()))
                assertTrue(validator.isKnownCaller("system", Process.SYSTEM_UID))
                assertTrue(validator.isKnownCaller("platform", 12346))
                assertTrue(validator.isKnownCaller("media", 12347))
                assertFalse(validator.isKnownCaller("ordinary", 12348))
            }
            for (name in listOf("own", "system", "platform", "media", "ordinary")) {
                verify(manager, times(1)).getPackageInfo(name, flags)
            }
        }
    }
}
