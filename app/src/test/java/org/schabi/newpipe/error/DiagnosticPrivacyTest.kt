package org.schabi.newpipe.error

import android.app.Application
import android.content.ClipboardManager
import android.content.Intent
import android.os.Looper
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import androidx.preference.PreferenceManager
import java.io.IOException
import java.io.ObjectOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.acra.ReportField
import org.acra.builder.ReportBuilder
import org.acra.collector.Collector
import org.acra.data.CrashReportData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import org.schabi.newpipe.App
import org.schabi.newpipe.MainActivity
import org.schabi.newpipe.R
import org.schabi.newpipe.pvc.feature.logcat.PvcLogcatDumper
import org.schabi.newpipe.settings.export.BackupFileLocator
import org.schabi.newpipe.settings.export.ImportExportManager
import org.schabi.newpipe.settings.export.PendingDatabaseRestore
import org.schabi.newpipe.settings.export.RestoreTransaction
import org.schabi.newpipe.streams.io.StoredFileHelper
import us.shandian.giga.io.FileStream

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [26])
class DiagnosticPrivacyTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val preferences get() = PreferenceManager.getDefaultSharedPreferences(context)
    private val key = DiagnosticPrivacy.CAPTURE_OPT_IN_KEY

    @Before
    fun setup() {
        preferences.edit().clear().commit()
        val app = App()
        ReflectionHelpers.setField(app, "mBase", context)
        ReflectionHelpers.setStaticField(App::class.java, "instance", app)
    }

    @Test
    fun `fresh old enabled and malformed installs stay off until new local opt in`() {
        assertFalse(PvcLogcatDumper.isLogcatDumperEnabled(context))
        preferences.edit().putBoolean(context.getString(R.string.pvc_settings_debug_logcat_dumper_key), true).commit()
        assertFalse(PvcLogcatDumper.isLogcatDumperEnabled(context))
        preferences.edit().putString(key, "true").commit()
        assertFalse(PvcLogcatDumper.isLogcatDumperEnabled(context))
        preferences.edit().putBoolean(key, true).commit()
        assertTrue(PvcLogcatDumper.isLogcatDumperEnabled(context))
        assertTrue(DiagnosticPrivacy.captureEnabled(context.getSharedPreferences(context.packageName + "_preferences", 0)))
        preferences.edit().putBoolean(key, false).commit()
        assertFalse(PvcLogcatDumper.isLogcatDumperEnabled(context))
    }

    @Test
    fun `settings XML has the same new false default as runtime`() {
        assertEquals(key, context.getString(R.string.diagnostic_log_capture_opt_in_key))
        context.resources.getXml(R.xml.pvc_settings).use { parser ->
            var found = false
            while (parser.eventType != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == org.xmlpull.v1.XmlPullParser.START_TAG &&
                    parser.getAttributeResourceValue("http://schemas.android.com/apk/res/android", "key", 0) == R.string.diagnostic_log_capture_opt_in_key
                ) {
                    assertFalse(parser.getAttributeBooleanValue("http://schemas.android.com/apk/res/android", "defaultValue", true))
                    found = true
                }
                parser.next()
            }
            assertTrue(found)
        }
    }

    @Test
    fun `report screen keeps exact exports timestamp and unchecked attachment on recreation`() {
        val info = ErrorInfo(IllegalStateException("trace remains sensitive"), UserAction.UI_ERROR, "request remains sensitive")
        val intent = Intent(context, ErrorActivity::class.java).putExtra(ErrorActivity.ERROR_INFO, info)
        Robolectric.buildActivity(ErrorActivity::class.java, intent).setup().use { controller ->
            val activity = controller.get()
            activity.findViewById<EditText>(R.id.errorCommentBox).setText("personal comment")
            val json = ReflectionHelpers.callInstanceMethod<String>(activity, "buildJson")
            val markdown = ReflectionHelpers.callInstanceMethod<String>(activity, "buildMarkdown")
            assertTrue(json.contains("personal comment"))
            assertTrue(markdown.contains("personal comment"))
            assertTrue(json.contains("trace remains sensitive"))
            assertTrue(markdown.contains("request remains sensitive"))
            assertFalse(activity.findViewById<CheckBox>(R.id.includeDiagnosticLog).isChecked)
            activity.findViewById<View>(R.id.errorReportCopyButton).performClick()
            val clip = activity.getSystemService(ClipboardManager::class.java).primaryClip!!
            assertEquals(markdown, clip.getItemAt(0).text.toString())
            assertNull(clip.getItemAt(0).uri)
            activity.findViewById<View>(R.id.errorReportShareButton).performClick()
            val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10)
            var chooser: Intent? = null
            while (chooser == null && System.nanoTime() < deadline) {
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                chooser = Shadows.shadowOf(activity).nextStartedActivity
                if (chooser == null) Thread.sleep(10)
            }
            assertNotNull("Share preparation did not complete", chooser)
            val send = chooser!!.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
            assertEquals(json, send.getStringExtra(Intent.EXTRA_TEXT))
            assertFalse(send.hasExtra(Intent.EXTRA_STREAM))
            // Android may migrate EXTRA_TEXT to text ClipData and set a grant flag during
            // launch; with no URI this grants no file. The app-created intent has no grant.
            assertEquals(0, ReportingShareIntent.create(activity, json, null).flags and Intent.FLAG_GRANT_READ_URI_PERMISSION)
            send.clipData?.let { clipData ->
                for (index in 0 until clipData.itemCount) assertNull(clipData.getItemAt(index).uri)
            }
            controller.recreate()
            assertFalse(controller.get().findViewById<CheckBox>(R.id.includeDiagnosticLog).isChecked)
            val restored = ReflectionHelpers.callInstanceMethod<String>(controller.get(), "buildJson")
            val beforeTime = com.grack.nanojson.JsonParser.`object`().from(json).getString("time")
            assertEquals(beforeTime, com.grack.nanojson.JsonParser.`object`().from(restored).getString("time"))
        }
    }

    @Test
    fun `capture opt in controls only capture not timestamp`() {
        val captures = mutableListOf<Long>()
        assertEquals(123L, DiagnosticPrivacy.captureTimestamp(preferences, 123L, captures::add))
        assertTrue(captures.isEmpty())
        preferences.edit().putBoolean(key, true).commit()
        assertEquals(456L, DiagnosticPrivacy.captureTimestamp(preferences, 456L, captures::add))
        assertEquals(listOf(456L), captures)
        preferences.edit().putBoolean(key, false).commit()
        val before = System.currentTimeMillis()
        val info = ErrorInfo(IllegalStateException("fixture"), UserAction.UI_ERROR, "fixture")
        assertTrue(info.pvcErrorInfoCreationTimestamp in before..System.currentTimeMillis())
    }

    @Test
    fun `all imported preference versions preserve only the local choice`() {
        for (local in listOf(null, false, true)) {
            for (version in listOf(null, 0, 10, Int.MAX_VALUE)) {
                for (incoming in listOf(null, false, true, "true")) {
                    val current = mutableMapOf<String, Any>()
                    if (local != null) current[key] = local
                    val imported = mutableMapOf<String, Any>("pvc_settings_debug_logcat_dumper_key" to true)
                    if (incoming != null) imported[key] = incoming
                    if (version != null) imported["last_used_preferences_version"] = version
                    DiagnosticPrivacy.preserveLocalConsent(imported, current)
                    assertEquals(local, imported[key])
                    assertEquals(true, imported["pvc_settings_debug_logcat_dumper_key"])
                }
            }
        }
    }

    @Test
    fun `real JSON and legacy archive loaders cannot transfer log consent`() {
        for (local in listOf(null, false, true)) {
            for (version in listOf(null, 10, Int.MAX_VALUE)) {
                for (legacy in listOf(false, true)) {
                    preferences.edit().clear().apply()
                    if (local != null) preferences.edit().putBoolean(key, local).commit()
                    val values = hashMapOf<String, Any>(key to true, "pvc_settings_debug_logcat_dumper_key" to true, "ordinary" to "restored")
                    if (version != null) values[context.getString(R.string.last_used_preferences_version)] = version
                    val archive = Files.createTempFile("privacy-import", ".zip").toFile()
                    ZipOutputStream(archive.outputStream()).use { zip ->
                        zip.putNextEntry(ZipEntry(if (legacy) "newpipe.settings" else "preferences.json"))
                        if (legacy) {
                            val bytes = java.io.ByteArrayOutputStream()
                            ObjectOutputStream(bytes).use { it.writeObject(values) }
                            zip.write(bytes.toByteArray())
                        } else {
                            zip.write(org.json.JSONObject(values).toString().toByteArray())
                        }
                        zip.closeEntry()
                    }
                    val file = Mockito.mock(StoredFileHelper::class.java)
                    Mockito.`when`(file.stream).thenAnswer { FileStream(archive) }
                    val manager = ImportExportManager(Mockito.mock(BackupFileLocator::class.java)) { }
                    if (legacy) manager.loadSerializedPrefs(file, preferences) else manager.loadJsonPrefs(file, preferences)
                    assertEquals(local, preferences.all[key])
                    assertEquals("restored", preferences.getString("ordinary", null))
                    assertEquals(local == true, DiagnosticPrivacy.captureEnabled(preferences))
                }
            }
        }
    }

    @Test
    fun `queued restore uses activation time consent and rollback restores exact prior map`() {
        for (local in listOf(null, false, true)) {
            for (fail in listOf(false, true)) {
                preferences.edit().clear().putString("original", "value").commit()
                val directory = Files.createTempDirectory("privacy-restore")
                val database = directory.resolve("newpipe.db")
                val candidate = directory.resolve("candidate")
                Files.writeString(database, "old")
                Files.writeString(candidate, "new")
                PendingDatabaseRestore.publish(database, candidate, mapOf(key to true, "imported" to "value"), true)
                if (local != null) preferences.edit().putBoolean(key, local).commit()
                val before = HashMap(preferences.all)
                val io = object : RestoreTransaction.FileSystem() {
                    override fun checkpoint(step: RestoreTransaction.Step, path: java.nio.file.Path) {
                        if (fail && step == RestoreTransaction.Step.AFTER_PREFERENCES) throw IOException("fixture failure")
                    }
                }
                val error = RestoreTransaction(database, PendingDatabaseRestore.preferenceStore(preferences), { }, io).recover()
                if (fail) {
                    assertNotNull(error)
                    assertEquals(before, preferences.all)
                    assertEquals("old", Files.readString(database))
                } else {
                    assertNull(error)
                    assertEquals(local, preferences.all[key])
                    assertEquals("value", preferences.getString("imported", null))
                    assertEquals("new", Files.readString(database))
                }
            }
        }
    }

    @Test
    fun `actual enabled ACRA collectors produce only selected crash fields`() {
        val config = DiagnosticPrivacy.crashConfiguration().build()
        val expected = setOf(ReportField.STACK_TRACE, ReportField.REPORT_ID, ReportField.IS_SILENT, ReportField.USER_CRASH_DATE)
        assertEquals(expected, config.reportContent.toSet())
        val report = CrashReportData()
        val builder = ReportBuilder().exception(IllegalStateException("sensitive fixture remains in trace"))
        val collectors = config.pluginLoader.loadEnabled(config, Collector::class.java)
        assertTrue(collectors.isNotEmpty())
        collectors.forEach { it.collect(context, config, builder, report) }
        assertEquals(expected.map { it.toString() }.toSet(), report.toMap().keys)
        assertTrue(report.getString(ReportField.STACK_TRACE)!!.contains("sensitive fixture"))
        preferences.edit().putBoolean(MainActivity.KEY_IS_IN_BACKGROUND, false).commit()
        AcraReportSender().send(context, report)
        val intent = Shadows.shadowOf(context).nextStartedActivity
        assertNotNull(intent)
        val info = intent.getParcelableExtra<ErrorInfo>(ErrorActivity.ERROR_INFO)!!
        assertEquals(listOf(report.getString(ReportField.STACK_TRACE)), info.stackTraces.toList())
    }
}
