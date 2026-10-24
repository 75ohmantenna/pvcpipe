package org.schabi.newpipe.error

import androidx.appcompat.app.AppCompatActivity
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import org.schabi.newpipe.pvc.feature.logcat.PvcLogcatDumper.Companion.isLogcatDumperEnabled

abstract class PvcErrorActivity : AppCompatActivity() {

    /**
     * Format the ErrorInfo construction timestamp, regardless of log capture settings.
     * For ACRA reports this is when the stored report was converted, not the original crash time.
     *
     * @param currentTimeStamp fallback when the creation timestamp is unavailable or invalid
     */
    protected fun pvcGetErrorCreationTimestamp(
        currentTimeStamp: String,
        errorInfo: ErrorInfo
    ): String {
        val timestamp = errorInfo.pvcErrorInfoCreationTimestamp
        if (timestamp <= 0) return currentTimeStamp

        val dateTime = Instant.ofEpochMilli(timestamp)
            .atZone(ZoneId.systemDefault())
        return dateTime.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
    }

    protected fun pvcIsDumperEnabled() = isLogcatDumperEnabled()
}
