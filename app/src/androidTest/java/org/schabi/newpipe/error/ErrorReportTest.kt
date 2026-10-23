package org.schabi.newpipe.error

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.SystemClock
import android.widget.EditText
import android.widget.TextView
import androidx.core.content.IntentCompat
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.grack.nanojson.JsonObject
import com.grack.nanojson.JsonParser
import java.time.Instant
import java.time.OffsetDateTime
import org.acra.ReportField
import org.acra.data.CrashReportData
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.schabi.newpipe.BuildConfig
import org.schabi.newpipe.MainActivity
import org.schabi.newpipe.R
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.util.Localization

/** Launches the real activity and invokes its actual report builders, without sharing anything. */
internal fun withErrorReports(
    info: ErrorInfo,
    comment: String = "",
    block: (ErrorActivity, String, JsonObject) -> Unit
) {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val context = ApplicationProvider.getApplicationContext<Context>()
    val unexpectedIntents = mutableListOf<String>()
    val monitor = object : Instrumentation.ActivityMonitor() {
        override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
            if (intent.component?.className == ErrorActivity::class.java.name) return null
            unexpectedIntents.add(intent.toUri(0))
            return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
        }
    }
    instrumentation.addMonitor(monitor)
    var launchedActivity: ErrorActivity? = null
    try {
        val activity = instrumentation.startActivitySync(
            Intent(context, ErrorActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(ErrorActivity.ERROR_INFO, info)
        ) as ErrorActivity
        launchedActivity = activity
        instrumentation.waitForIdleSync()
        var outcome: Result<Unit>? = null
        instrumentation.runOnMainSync {
            outcome = runCatching {
                activity.findViewById<EditText>(R.id.errorCommentBox).setText(comment)
                val markdownBuilder = ErrorActivity::class.java.getDeclaredMethod("buildMarkdown")
                markdownBuilder.isAccessible = true
                val jsonBuilder = ErrorActivity::class.java.getDeclaredMethod("buildJson")
                jsonBuilder.isAccessible = true
                val markdown = markdownBuilder.invoke(activity) as String
                val json = JsonParser.`object`().from(jsonBuilder.invoke(activity) as String)
                block(activity, markdown, json)
            }
        }
        checkNotNull(outcome).getOrThrow()
    } finally {
        try {
            instrumentation.runOnMainSync { launchedActivity?.finish() }
            instrumentation.waitForIdleSync()
        } finally {
            instrumentation.removeMonitor(monitor)
        }
        assertTrue("Unexpected outgoing intents: $unexpectedIntents", unexpectedIntents.isEmpty())
    }
}

@RunWith(AndroidJUnit4::class)
class ErrorReportTest {
    private lateinit var preferences: SharedPreferences
    private lateinit var captureKey: String
    private val savedBooleans = linkedMapOf<String, Boolean?>()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        preferences = PreferenceManager.getDefaultSharedPreferences(context)
        captureKey = context.getString(R.string.pvc_settings_debug_logcat_dumper_key)
        saveBoolean(captureKey)
        saveBoolean(context.getString(R.string.disable_error_reports_key))
        saveBoolean(MainActivity.KEY_IS_IN_BACKGROUND)
    }

    @After
    fun restorePreferences() {
        val editor = preferences.edit()
        savedBooleans.forEach { (key, value) ->
            if (value == null) editor.remove(key) else editor.putBoolean(key, value)
        }
        check(editor.commit())
    }

    private fun saveBoolean(key: String) {
        savedBooleans[key] = if (preferences.contains(key)) {
            preferences.getBoolean(key, false)
        } else {
            null
        }
    }

    private fun setCapture(enabled: Boolean) {
        check(preferences.edit().putBoolean(captureKey, enabled).commit())
    }

    private fun errorInfo(traces: Array<String> = arrayOf("synthetic report trace")) = ErrorInfo(
        traces,
        UserAction.UI_ERROR,
        "synthetic request with \"quotes\"; and a newline\nsecond line",
        ServiceList.YouTube.serviceId,
        R.string.app_ui_crash
    )

    @Test
    fun delayedOpeningUsesCreationTimeWithCaptureEnabled() {
        assertDelayedCreationTime(captureEnabled = true)
    }

    @Test
    fun delayedOpeningUsesCreationTimeWithCaptureDisabled() {
        assertDelayedCreationTime(captureEnabled = false)
    }

    private fun assertDelayedCreationTime(captureEnabled: Boolean) {
        setCapture(captureEnabled)
        val beforeConstruction = System.currentTimeMillis()
        val info = errorInfo()
        val afterConstruction = System.currentTimeMillis()
        val created = info.pvcErrorInfoCreationTimestamp
        assertTrue(created in beforeConstruction..afterConstruction)
        // ErrorInfo may sit in a notification long before the report is opened.
        SystemClock.sleep(1_100)
        val beforeOpening = Instant.now()
        withErrorReports(info) { activity, markdown, json ->
            val reported = OffsetDateTime.parse(json.getString("time")).toInstant()
            assertTrue(Instant.ofEpochMilli(created).isBefore(beforeOpening))
            assertEquals(Instant.ofEpochMilli(created), reported)
            assertTimestampMatchesAllOutputs(activity, markdown, json)
        }
    }

    @Test
    fun unavailableCreationTimeFallsBackToOpeningTime() {
        assertOpeningFallback(0L)
    }

    @Test
    fun invalidCreationTimeFallsBackToOpeningTime() {
        assertOpeningFallback(-1L)
        assertOpeningFallback(Long.MIN_VALUE)
    }

    private fun assertOpeningFallback(timestamp: Long) {
        for (captureEnabled in listOf(true, false)) {
            setCapture(captureEnabled)
            val info = errorInfo()
            // A missing/invalid timestamp can arrive in an ErrorInfo restored from a parcel.
            val field = ErrorInfo::class.java.getDeclaredField("pvcErrorInfoCreationTimestamp")
            field.isAccessible = true
            field.setLong(info, timestamp)
            val beforeOpening = Instant.now()
            withErrorReports(info) { activity, markdown, json ->
                val reported = OffsetDateTime.parse(json.getString("time")).toInstant()
                val afterOpening = Instant.now()
                assertFalse(reported.isBefore(beforeOpening))
                assertFalse(reported.isAfter(afterOpening))
                assertTimestampMatchesAllOutputs(activity, markdown, json)
            }
        }
    }

    private fun assertTimestampMatchesAllOutputs(
        activity: ErrorActivity,
        markdown: String,
        json: JsonObject
    ) {
        val timestamp = json.getString("time")
        assertEquals(
            listOf("* __Timestamp:__ $timestamp"),
            markdown.lineSequence().filter { it.startsWith("* __Timestamp:__ ") }.toList()
        )
        val displayed = activity.findViewById<TextView>(R.id.errorInfosView).text.toString()
        assertTrue(displayed.lineSequence().any { it == timestamp })
    }

    @Test
    fun markdownContainsServiceExactlyOnce() {
        setCapture(false)
        val info = errorInfo()
        withErrorReports(info) { _, markdown, _ ->
            assertEquals(
                listOf("* __Service:__ ${info.getServiceName()}"),
                markdown.lineSequence().filter { it.startsWith("* __Service:__ ") }.toList()
            )
        }
    }

    @Test
    fun reportFormatsPreserveCommentsMetadataAndBoundedExceptions() {
        setCapture(false)
        val info = errorInfo(
            arrayOf(
                "synthetic first; [Ljava.lang.String;\n" +
                    "a".repeat(PvcErrorInfoHelper.MAX_TRACE_LENGTH),
                "synthetic second\n" + "b".repeat(PvcErrorInfoHelper.MAX_TRACE_LENGTH),
                "synthetic third dropped when the total is full"
            )
        )
        assertEquals(2, info.stackTraces.size)
        assertEquals(PvcErrorInfoHelper.MAX_TOTAL_LENGTH, info.stackTraces.sumOf { it.length })
        assertTrue(info.stackTraces.all { it.length <= PvcErrorInfoHelper.MAX_TRACE_LENGTH })
        assertTrue(info.stackTraces.all { it.endsWith(PvcErrorInfoHelper.TRUNCATED) })
        val comment = "Synthetic user comment: \"quoted\" \\ slash\nsecond line; \uD83D\uDE00"
        withErrorReports(info, comment) { activity, markdown, json ->
            val expected = linkedMapOf(
                "user_action" to info.userAction.message,
                "request" to info.request,
                "content_language" to
                    Localization.getPreferredLocalization(activity).localizationCode,
                "content_country" to
                    Localization.getPreferredContentCountry(activity).countryCode,
                "app_language" to Localization.getAppLocale().toString(),
                "service" to info.getServiceName(),
                "package" to activity.packageName,
                "version" to BuildConfig.VERSION_NAME,
                "os" to "${System.getProperty("os.name")} " +
                    "${Build.VERSION.BASE_OS.ifEmpty { "Android" }} " +
                    "${Build.VERSION.RELEASE} - ${Build.VERSION.SDK_INT}",
                "user_comment" to comment
            )
            expected.forEach { (key, value) -> assertEquals(key, value, json.getString(key)) }
            assertEquals(expected.keys + setOf("time", "exceptions"), json.keys)
            assertArrayEquals(info.stackTraces, json.getArray("exceptions").toTypedArray())
            assertTrue(markdown.startsWith("$comment\n## Exception\n"))
            val markdownFields = linkedMapOf(
                "User Action" to "user_action",
                "Request" to "request",
                "Content Country" to "content_country",
                "Content Language" to "content_language",
                "App Language" to "app_language",
                "Service" to "service",
                "Package" to "package",
                "Version" to "version",
                "OS" to "os"
            )
            markdownFields.forEach { (label, key) ->
                assertTrue(label, markdown.contains("* __$label:__ ${expected.getValue(key)}\n"))
            }
            assertTimestampMatchesAllOutputs(activity, markdown, json)
            assertTrue(markdown.contains("<details><summary><b>Exceptions (2)</b></summary><p>\n"))
            info.stackTraces.forEachIndexed { index, trace ->
                assertTrue(markdown.contains("<details><summary><b>Crash log ${index + 1}</b>"))
                assertTrue(markdown.contains("\n```\n$trace\n```\n"))
            }
            assertTrue(markdown.endsWith("</p></details>\n<hr>\n"))
            assertFalse(markdown.contains("synthetic third dropped"))
        }
    }

    @Test
    fun emptyCommentAndSingleExceptionKeepTheirExistingFormat() {
        setCapture(false)
        val info = errorInfo(arrayOf("synthetic single; exception"))
        withErrorReports(info) { _, markdown, json ->
            assertTrue(markdown.startsWith("## Exception\n"))
            assertFalse(markdown.contains("<b>Exceptions ("))
            assertTrue(markdown.contains("<details><summary><b>Crash log </b></summary><p>\n"))
            assertEquals("", json.getString("user_comment"))
            assertArrayEquals(info.stackTraces, json.getArray("exceptions").toTypedArray())
        }
    }

    @Test
    fun acraSenderCreatesANewTimestampRatherThanUsingOriginalCrashDate() {
        setCapture(false)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = ApplicationProvider.getApplicationContext<Context>()
        check(
            preferences.edit()
                .putBoolean(context.getString(R.string.disable_error_reports_key), false)
                .putBoolean(MainActivity.KEY_IS_IN_BACKGROUND, false)
                .commit()
        )
        var sentInfo: ErrorInfo? = null
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.component?.className != ErrorActivity::class.java.name) return null
                sentInfo = IntentCompat.getParcelableExtra(
                    intent,
                    ErrorActivity.ERROR_INFO,
                    ErrorInfo::class.java
                )
                return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
            }
        }
        val report = CrashReportData().apply {
            put(ReportField.STACK_TRACE, "synthetic stored ACRA crash")
            put(ReportField.USER_CRASH_DATE, "2000-01-01T00:00:00.000+00:00")
        }
        instrumentation.addMonitor(monitor)
        val beforeSending = System.currentTimeMillis()
        try {
            instrumentation.runOnMainSync { AcraReportSender().send(context, report) }
        } finally {
            instrumentation.removeMonitor(monitor)
        }
        val afterSending = System.currentTimeMillis()
        assertNotNull("The real ACRA sender should open the intercepted report activity", sentInfo)
        val info = requireNotNull(sentInfo)
        assertEquals("ACRA report", info.request)
        assertArrayEquals(arrayOf("synthetic stored ACRA crash"), info.stackTraces)
        assertTrue(info.pvcErrorInfoCreationTimestamp in beforeSending..afterSending)
        assertNotEquals(
            Instant.parse("2000-01-01T00:00:00Z").toEpochMilli(),
            info.pvcErrorInfoCreationTimestamp
        )
    }
}
