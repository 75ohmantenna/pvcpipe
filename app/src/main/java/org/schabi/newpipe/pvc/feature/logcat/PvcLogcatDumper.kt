package org.schabi.newpipe.pvc.feature.logcat

import android.content.Context
import androidx.preference.PreferenceManager
import com.github.logviewer.LogcatDumper
import java.util.Date
import org.schabi.newpipe.App
import org.schabi.newpipe.R
import org.schabi.newpipe.pvc.feature.logcat.PvcLogcatDumper.Companion.CAPTURE_PERIOD_BEFORE_TIMESTAMP

/**
 * Dump the logcat entries for [CAPTURE_PERIOD_BEFORE_TIMESTAMP] ms before a given timestamp.
 *
 * [triggerLogCapture] records the current wall-clock time when an ErrorInfo is constructed
 * and returns it even if capture is disabled. For stored ACRA reports this can be later than
 * the original crash; ACRA's sender constructs a new ErrorInfo when converting the report.
 *
 * Enabled capture can help explain errors shown in [org.schabi.newpipe.error.ErrorActivity].
 */
class PvcLogcatDumper {

    /**
     * we want the timestamp to set the filename.
     */
    class PvcLogFileNameErrorInfo : PvcLogFileName(), LogcatDumper.LogFileNameFromTimestamp {

        private var timestamp: Long = 0

        override fun getLogFileName(): String {
            return dateFormat.format(Date(timestamp))
        }

        override fun setTimestamp(timestamp: Long) {
            this.timestamp = timestamp
        }
    }

    companion object {
        val logcatDump = LogcatDumper(
            App.instance,
            PvcLogFileNameErrorInfo(),
            PvcLogFileFormat(),
            logCleanupStrategy = commonCleanupStrategy(),
            logStorageLocation = commonStorageLocation()
        )

        private const val CAPTURE_PERIOD_BEFORE_TIMESTAMP: Long = 30 * 1000

        fun triggerLogCapture(): Long {
            val time = System.currentTimeMillis()
            if (isLogcatDumperEnabled()) {
                logcatDump.dump(time, CAPTURE_PERIOD_BEFORE_TIMESTAMP)
            }
            return time
        }

        fun isLogcatDumperEnabled(context: Context = App.instance) = PreferenceManager.getDefaultSharedPreferences(context)
            .getBoolean(
                context.getString(R.string.pvc_settings_debug_logcat_dumper_key),
                true
            )
    }
}
