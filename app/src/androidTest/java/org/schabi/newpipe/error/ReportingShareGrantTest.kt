package org.schabi.newpipe.error

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.schabi.newpipe.pvc.feature.logcat.PvcLogcatDumper

/** The only recipient is our test APK's isolated receiver; no external application is opened. */
@RunWith(AndroidJUnit4::class)
class ReportingShareGrantTest {
    @Test
    fun anotherUidCanReadButCannotWriteTheSingleSharedLog() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val folder = PvcLogcatDumper.logcatDump.getLogFolder(context)
        check(folder.mkdirs() || folder.isDirectory)
        val file = File(folder, "synthetic-share-grant-${UUID.randomUUID()}.log")
        val token = UUID.randomUUID().toString()
        val contents = "<details><summary><b>Logcat: ${file.name}</b></summary><p>\n\n```\n" +
            "synthetic cross-UID log\n\n```\n</details>\n<hr>\n"
        file.writeText(contents)
        val completed = CountDownLatch(1)
        var received: Intent? = null
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.getStringExtra("token") == token) {
                    received = Intent(intent)
                    completed.countDown()
                }
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(ReportingGrantReceiverActivity.RESULT_ACTION),
            ContextCompat.RECEIVER_EXPORTED
        )
        try {
            val send = ReportingShareIntent.create(context, "synthetic cross-UID report", file)
                .setComponent(ComponentName(instrumentation.context.packageName, ReportingGrantReceiverActivity::class.java.name))
                .putExtra(ReportingGrantReceiverActivity.TARGET_PACKAGE, context.packageName)
                .putExtra("token", token)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(send)
            assertTrue("Controlled cross-UID receiver did not return", completed.await(10, TimeUnit.SECONDS))
            val result = checkNotNull(received)
            assertNull(result.getStringExtra("failure"))
            assertNotEquals(android.os.Process.myUid(), result.getIntExtra("receiverUid", -1))
            assertEquals("synthetic cross-UID report", result.getStringExtra("text"))
            assertEquals(1, result.getIntExtra("clipCount", 0))
            assertEquals(result.getStringExtra("stream"), result.getStringExtra("clip"))
            assertEquals(contents, result.getStringExtra("contents"))
            assertTrue("Recipient received write access", result.getBooleanExtra("writeDenied", false))
            assertEquals(contents, file.readText())
        } finally {
            context.unregisterReceiver(receiver)
            file.delete()
        }
    }
}
