package org.schabi.newpipe.error

import android.content.SharedPreferences
import org.acra.ReportField
import org.acra.config.CoreConfigurationBuilder
import org.schabi.newpipe.BuildConfig

/** Local collection consent is deliberately not transferable through a settings backup. */
object DiagnosticPrivacy {
    const val CAPTURE_OPT_IN_KEY = "pvc_diagnostic_log_capture_opt_in_v1"

    @JvmStatic
    fun captureEnabled(preferences: SharedPreferences): Boolean = try {
        preferences.getBoolean(CAPTURE_OPT_IN_KEY, false)
    } catch (_: ClassCastException) {
        false
    }

    @JvmStatic
    fun preserveLocalConsent(imported: MutableMap<String, Any>, current: Map<String, *>) {
        if (current.containsKey(CAPTURE_OPT_IN_KEY)) {
            imported[CAPTURE_OPT_IN_KEY] = current[CAPTURE_OPT_IN_KEY] == true
        } else {
            imported.remove(CAPTURE_OPT_IN_KEY)
        }
    }

    /** ACRA adds required bookkeeping fields even when callers select only STACK_TRACE. */
    fun crashConfiguration(): CoreConfigurationBuilder = CoreConfigurationBuilder()
        .withBuildConfigClass(BuildConfig::class.java)
        .withReportContent(ReportField.STACK_TRACE, ReportField.REPORT_ID, ReportField.IS_SILENT, ReportField.USER_CRASH_DATE)

    internal fun captureTimestamp(preferences: SharedPreferences, now: Long, capture: (Long) -> Unit): Long {
        if (captureEnabled(preferences)) capture(now)
        return now
    }
}
