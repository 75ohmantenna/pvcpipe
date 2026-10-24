package org.schabi.newpipe.error

import android.app.Activity
import android.app.Instrumentation
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.SystemClock
import android.widget.CheckBox
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.core.content.IntentCompat
import androidx.preference.PreferenceManager
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.IOException
import java.util.Collections
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.schabi.newpipe.R
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.pvc.feature.logcat.PvcLogcatDumper

/** Synthetic reports only. Every outgoing activity intent is canceled before any recipient opens. */
@RunWith(AndroidJUnit4::class)
class ReportingActionsTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val outgoing = Collections.synchronizedList(mutableListOf<Intent>())
    private val files = mutableListOf<File>()
    private lateinit var preferences: SharedPreferences
    private lateinit var captureKey: String
    private var savedCapture: Boolean? = null
    private var scenario: ActivityScenario<ErrorActivity>? = null
    private val monitor = object : Instrumentation.ActivityMonitor() {
        override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
            if (intent.component?.className == ErrorActivity::class.java.name) return null
            outgoing.add(Intent(intent))
            return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
        }
    }

    @Before
    fun setUp() {
        preferences = PreferenceManager.getDefaultSharedPreferences(context)
        captureKey = context.getString(R.string.pvc_settings_debug_logcat_dumper_key)
        savedCapture = if (preferences.contains(captureKey)) {
            preferences.getBoolean(captureKey, true)
        } else {
            null
        }
        check(preferences.edit().putBoolean(captureKey, false).commit())
        instrumentation.addMonitor(monitor)
    }

    @After
    fun tearDown() {
        try {
            scenario?.close()
            files.forEach {
                it.setReadable(true, true)
                it.delete()
            }
        } finally {
            instrumentation.removeMonitor(monitor)
            val editor = preferences.edit()
            if (savedCapture == null) editor.remove(captureKey) else editor.putBoolean(captureKey, savedCapture!!)
            check(editor.commit())
        }
    }

    @Test
    fun copyUsesCurrentMarkdownAndNeverAttachesSelectedLog() {
        val info = syntheticInfo()
        completeLog(info)
        launch(info)
        awaitLogAvailable()
        var expected = ""
        scenario!!.onActivity { activity ->
            activity.findViewById<EditText>(R.id.errorCommentBox).setText("synthetic copy comment")
            activity.findViewById<CheckBox>(R.id.includeDiagnosticLog).isChecked = true
            expected = report(activity, "buildMarkdown")
            activity.findViewById<android.view.View>(R.id.errorReportCopyButton).performClick()
        }
        scenario!!.onActivity { activity ->
            val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = checkNotNull(clipboard.primaryClip)
            assertEquals(1, clip.itemCount)
            assertEquals(expected, clip.getItemAt(0).coerceToText(activity).toString())
            assertNull(clip.getItemAt(0).uri)
            assertNull(clip.getItemAt(0).intent)
        }
        assertTrue(outgoing.isEmpty())
    }

    @Test
    fun uncheckedShareUsesCurrentJsonAndHasNoAttachmentOrUriGrant() {
        val info = syntheticInfo()
        completeLog(info)
        launch(info)
        awaitLogAvailable()
        var expected = ""
        scenario!!.onActivity { activity ->
            assertFalse(activity.findViewById<CheckBox>(R.id.includeDiagnosticLog).isChecked)
            activity.findViewById<EditText>(R.id.errorCommentBox).setText("synthetic share comment")
            expected = report(activity, "buildJson")
            activity.findViewById<android.view.View>(R.id.errorReportShareButton).performClick()
        }
        val send = awaitSend()
        assertEquals(expected, send.getStringExtra(Intent.EXTRA_TEXT))
        assertFalse(send.hasExtra(Intent.EXTRA_STREAM))
        assertNull(send.clipData)
        assertEquals(0, send.flags and URI_GRANTS)
        assertNoDestination(send)
    }

    @Test
    fun checkedShareGrantsReadOnlyToExactlyOneMatchingCompletedLog() {
        val info = syntheticInfo()
        val file = completeLog(info)
        launch(info)
        awaitLogAvailable()
        scenario!!.onActivity { activity ->
            activity.findViewById<CheckBox>(R.id.includeDiagnosticLog).isChecked = true
            activity.findViewById<android.view.View>(R.id.errorReportShareButton).performClick()
        }
        val send = awaitSend()
        val uri = IntentCompat.getParcelableExtra(send, Intent.EXTRA_STREAM, Uri::class.java)
        assertNotNull(uri)
        assertEquals("content", uri!!.scheme)
        assertEquals(1, checkNotNull(send.clipData).itemCount)
        assertEquals(uri, send.clipData!!.getItemAt(0).uri)
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, send.flags and URI_GRANTS)
        assertEquals(file.readText(), context.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() })
        assertNoDestination(send)
    }

    @Test
    fun recreationResetsLogSelection() {
        val info = syntheticInfo()
        completeLog(info)
        launch(info)
        awaitLogAvailable()
        scenario!!.onActivity { it.findViewById<CheckBox>(R.id.includeDiagnosticLog).isChecked = true }
        scenario!!.recreate()
        awaitLogAvailable()
        scenario!!.onActivity { assertFalse(it.findViewById<CheckBox>(R.id.includeDiagnosticLog).isChecked) }
        assertTrue(outgoing.isEmpty())
    }

    @Test
    fun newReportResetsLogSelectionAndUsesNewReportData() {
        val original = syntheticInfo()
        completeLog(original)
        launch(original)
        awaitLogAvailable()
        scenario!!.onActivity { it.findViewById<CheckBox>(R.id.includeDiagnosticLog).isChecked = true }
        val replacement = syntheticInfo("replacement synthetic request")
        completeLog(replacement)
        scenario!!.onActivity { activity ->
            val method = ErrorActivity::class.java.getDeclaredMethod("onNewIntent", Intent::class.java)
            method.isAccessible = true
            method.invoke(activity, reportIntent(replacement))
        }
        awaitLogAvailable()
        scenario!!.onActivity { activity ->
            assertFalse(activity.findViewById<CheckBox>(R.id.includeDiagnosticLog).isChecked)
            activity.findViewById<android.view.View>(R.id.errorReportShareButton).performClick()
        }
        val send = awaitSend()
        assertFalse(send.hasExtra(Intent.EXTRA_STREAM))
        assertTrue(checkNotNull(send.getStringExtra(Intent.EXTRA_TEXT)).contains(replacement.request))
    }

    @Test
    fun missingLogLeavesTextOnlyShareAvailable() {
        launch(syntheticInfo())
        awaitLogLookup()
        scenario!!.onActivity { activity ->
            val checkbox = activity.findViewById<CheckBox>(R.id.includeDiagnosticLog)
            assertFalse(checkbox.isChecked)
            assertFalse(checkbox.isEnabled)
            activity.findViewById<android.view.View>(R.id.errorReportShareButton).performClick()
        }
        assertFalse(awaitSend().hasExtra(Intent.EXTRA_STREAM))
    }

    @Test
    fun disappearedSelectedLogAbortsInsteadOfSilentlySharingTextOnly() {
        val info = syntheticInfo()
        val file = completeLog(info)
        launch(info)
        awaitLogAvailable()
        scenario!!.onActivity { it.findViewById<CheckBox>(R.id.includeDiagnosticLog).isChecked = true }
        check(file.delete())
        scenario!!.onActivity { it.findViewById<android.view.View>(R.id.errorReportShareButton).performClick() }
        awaitSelectionCleared()
        assertTrue(outgoing.isEmpty())
    }

    @Test
    fun unreadableSelectedLogAbortsInsteadOfLaunchingRecipient() {
        val info = syntheticInfo()
        val file = completeLog(info)
        launch(info)
        awaitLogAvailable()
        scenario!!.onActivity { it.findViewById<CheckBox>(R.id.includeDiagnosticLog).isChecked = true }
        check(file.setReadable(false, false))
        try {
            scenario!!.onActivity { it.findViewById<android.view.View>(R.id.errorReportShareButton).performClick() }
            awaitSelectionCleared()
            assertTrue(outgoing.isEmpty())
        } finally {
            check(file.setReadable(true, true))
        }
    }

    @Test
    fun selectedLogReplacedByUnfinishedCaptureAbortsShare() {
        val info = syntheticInfo()
        val file = completeLog(info)
        launch(info)
        awaitLogAvailable()
        scenario!!.onActivity { it.findViewById<CheckBox>(R.id.includeDiagnosticLog).isChecked = true }
        file.writeText(logHeader(file) + "synthetic unfinished replacement\n")
        scenario!!.onActivity { it.findViewById<android.view.View>(R.id.errorReportShareButton).performClick() }
        awaitSelectionCleared()
        assertTrue(outgoing.isEmpty())
    }

    @Test
    fun incompleteAndWrongTimestampFilesAreNeverOffered() {
        val info = syntheticInfo()
        val file = logFile(info)
        file.writeText(logHeader(file) + "synthetic unfinished log\n")
        assertNull(ReportingLogFile.find(context, info))
        expectIncomplete(file)
        file.writeText(logHeader(File(file.parentFile, "different-report.log")) + "synthetic\n" + LOG_FOOTER)
        assertNull(ReportingLogFile.find(context, info))
        expectIncomplete(file)
        launch(info)
        awaitLogLookup()
        scenario!!.onActivity { activity ->
            assertFalse(activity.findViewById<CheckBox>(R.id.includeDiagnosticLog).isChecked)
            assertFalse(activity.findViewById<CheckBox>(R.id.includeDiagnosticLog).isEnabled)
        }
        assertTrue(outgoing.isEmpty())
    }

    @Test
    fun noticeAndPreviewsRemainInAppAndDoNotChangeAttachmentChoice() {
        val info = syntheticInfo()
        val file = completeLog(info)
        launch(info)
        awaitLogAvailable()
        scenario!!.onActivity { activity ->
            activity.findViewById<android.view.View>(R.id.reportingNoticeButton).performClick()
        }
        awaitDialogText(context.getString(R.string.reporting_notice_body))
        dismissDialog()
        var markdown = ""
        var json = ""
        scenario!!.onActivity { activity ->
            markdown = report(activity, "buildMarkdown")
            json = report(activity, "buildJson")
            activity.findViewById<android.view.View>(R.id.inspectReportButton).performClick()
        }
        awaitDialogText(markdown)
        awaitDialogText(json)
        dismissDialog()
        scenario!!.onActivity { activity ->
            activity.findViewById<android.view.View>(R.id.inspectDiagnosticLogButton).performClick()
        }
        awaitDialogText(file.readText())
        dismissDialog()
        scenario!!.onActivity { assertFalse(it.findViewById<CheckBox>(R.id.includeDiagnosticLog).isChecked) }
        assertTrue("Notice and previews must not launch a browser, mail client or share recipient", outgoing.isEmpty())
    }

    private fun launch(info: ErrorInfo) {
        scenario = ActivityScenario.launch(reportIntent(info))
    }

    private fun reportIntent(info: ErrorInfo) = Intent(context, ErrorActivity::class.java)
        .putExtra(ErrorActivity.ERROR_INFO, info)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun syntheticInfo(request: String = "synthetic reporting action request") = ErrorInfo(
        arrayOf("synthetic reporting action trace"),
        UserAction.UI_ERROR,
        request,
        ServiceList.YouTube.serviceId,
        R.string.app_ui_crash
    ).also { SystemClock.sleep(2) }

    private fun logFile(info: ErrorInfo): File {
        val name = PvcLogcatDumper.PvcLogFileNameErrorInfo().apply {
            setTimestamp(info.pvcErrorInfoCreationTimestamp)
        }.getLogFileName()
        val folder = PvcLogcatDumper.logcatDump.getLogFolder(context)
        check(folder.mkdirs() || folder.isDirectory)
        return File(folder, name).also {
            check(!it.exists()) { "Synthetic fixture must not overwrite an existing log" }
            files.add(it)
        }
    }

    private fun completeLog(info: ErrorInfo) = logFile(info).apply {
        writeText(logHeader(this) + "PVC_SYNTHETIC_REPORTING_LOG_ONLY\n" + LOG_FOOTER)
    }

    private fun logHeader(file: File) = "<details><summary><b>Logcat: ${file.name}</b></summary><p>\n\n```\n"

    private fun expectIncomplete(file: File) {
        try {
            ReportingLogFile.requireComplete(file)
            throw AssertionError("Incomplete log was accepted")
        } catch (_: IOException) {
            // A capture writes its final name before its footer; existence alone is insufficient.
        }
    }

    private fun report(activity: ErrorActivity, builder: String): String {
        val method = ErrorActivity::class.java.getDeclaredMethod(builder)
        method.isAccessible = true
        return method.invoke(activity) as String
    }

    private fun awaitLogLookup() = awaitCondition {
        var completed = false
        scenario!!.onActivity { activity ->
            val field = ErrorActivity::class.java.getDeclaredField("diagnosticLogLookup")
            field.isAccessible = true
            completed = (field.get(activity) as? kotlinx.coroutines.Job)?.isCompleted == true
        }
        completed
    }

    private fun awaitLogAvailable() = awaitCondition {
        var enabled = false
        scenario!!.onActivity { enabled = it.findViewById<CheckBox>(R.id.includeDiagnosticLog).isEnabled }
        enabled
    }

    private fun awaitSelectionCleared() = awaitCondition {
        var cleared = false
        scenario!!.onActivity {
            val checkbox = it.findViewById<CheckBox>(R.id.includeDiagnosticLog)
            cleared = !checkbox.isChecked && !checkbox.isEnabled
        }
        cleared
    }

    private fun awaitSend(): Intent {
        awaitCondition { outgoing.isNotEmpty() }
        assertEquals(1, outgoing.size)
        val chooser = outgoing.single()
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        val send = checkNotNull(IntentCompat.getParcelableExtra(chooser, Intent.EXTRA_INTENT, Intent::class.java))
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("text/plain", send.type)
        return send
    }

    private fun assertNoDestination(send: Intent) {
        assertNull(send.component)
        assertNull(send.`package`)
        assertNull(send.data)
        assertFalse(send.hasExtra(Intent.EXTRA_EMAIL))
        assertFalse(send.hasExtra(Intent.EXTRA_CC))
        assertFalse(send.hasExtra(Intent.EXTRA_BCC))
    }

    private fun awaitDialogText(text: String) = awaitCondition {
        var present = false
        scenario!!.onActivity { activity ->
            val dialog = currentDialog(activity)
            present = dialog?.isShowing == true &&
                dialog.findViewById<android.widget.TextView>(android.R.id.message)
                    ?.text?.contains(text) == true
        }
        present
    }

    private fun currentDialog(activity: ErrorActivity): AlertDialog? {
        val field = ErrorActivity::class.java.getDeclaredField("reportingDialog")
        field.isAccessible = true
        return field.get(activity) as AlertDialog?
    }

    private fun dismissDialog() {
        scenario!!.onActivity { activity ->
            checkNotNull(currentDialog(activity)).getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        }
        instrumentation.waitForIdleSync()
    }

    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (!condition()) {
            check(SystemClock.uptimeMillis() < deadline) { "Reporting action did not reach the expected state" }
            SystemClock.sleep(25)
        }
    }

    private companion object {
        const val LOG_FOOTER = "\n```\n</details>\n<hr>\n"
        const val URI_GRANTS = Intent.FLAG_GRANT_READ_URI_PERMISSION or
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
            Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
    }
}
