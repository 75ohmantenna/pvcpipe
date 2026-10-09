package org.schabi.newpipe.error

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.github.logviewer.LogcatFileProvider
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.schabi.newpipe.R
import org.schabi.newpipe.pvc.feature.logcat.PvcLogcatDumper.Companion.isLogcatDumperEnabled
import org.schabi.newpipe.pvc.feature.logcat.PvcLogcatDumper.Companion.logcatDump
import org.schabi.newpipe.pvc.feature.logcat.PvcLogcatDumper.PvcLogFileNameErrorInfo
import org.schabi.newpipe.util.external_communication.ShareUtils

abstract class PvcErrorActivity : AppCompatActivity() {

    /**
     * Skip some traces as we might get TransactionTooLargeException exception.
     *
     * @param stackTraces the full stack traces
     * @return the truncated traces list that will not crash the Binder or whatever.
     */
    protected fun pvcTruncateAsNeeded(stackTraces: Array<String>): MutableList<String> {
        val limit = 104857 // limit to around 100k

        var size = 0
        val finalList: MutableList<String> = ArrayList()

        for (trace in stackTraces) {
            if (limit < size) {
                finalList.add("PVCPipe TRUNCATED trace")
                break
            }
            size += trace.length
            finalList.add(trace)
        }
        return finalList
    }

    protected fun pvcAddLogcatLogAttachmentToMail(
        context: Context,
        emailIntent: Intent,
        errorInfo: ErrorInfo
    ): Intent {
        if (!isLogcatDumperEnabled(context)) return emailIntent

        val uris = ArrayList<Uri?>()

        val possibleLogcatLogFile = getPossibleLogFileBasedOnTimestamp(context, errorInfo)
        possibleLogcatLogFile?.let {
            val uri = FileProvider.getUriForFile(
                context,
                LogcatFileProvider.getAuthority(context),
                possibleLogcatLogFile
            )
            uris.add(uri)
        }

        if (!uris.isEmpty()) {
            emailIntent.apply {
                action = Intent.ACTION_SEND_MULTIPLE
                type = "text/plain"
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)

                // val clipData = ClipData.newRawUri("Logs", uris.get(0))
                // for (i in 1..<uris.size) {
                //    clipData.addItem(ClipData.Item(uris.get(i)))
                // }
                // emailIntent.clipData = clipData
            }
        }

        return emailIntent
    }

    /**
     * Create the timestamp based on the time the ErrorInfo was first created.
     *
     * @param currentTimeStamp in case we have no own timestamp we give this one back to the caller
     */
    protected fun pvcGetErrorCreationTimestamp(
        currentTimeStamp: String,
        errorInfo: ErrorInfo
    ): String {
        if (!isLogcatDumperEnabled()) return currentTimeStamp

        val dateTime = Instant.ofEpochMilli(errorInfo.pvcErrorInfoCreationTimestamp)
            .atZone(ZoneId.systemDefault())
        return dateTime.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
    }

    protected fun pvcAddCopyLogcatLogButton(
        activity: AppCompatActivity,
        copyButton: Button,
        errorInfo: ErrorInfo
    ) {
        if (!isLogcatDumperEnabled(activity)) return

        val parentLayout = copyButton.parent as? ViewGroup ?: return
        val context = copyButton.context

        val newCopyLogcatButton = Button(context).apply {
            setText(R.string.pvc_copy_related_logcat_entries)
            id = View.generateViewId() // important for accessibility and constraints
        }

        // copy layout also to have same look and feel
        val params = copyButton.layoutParams
        newCopyLogcatButton.layoutParams = params

        if (parentLayout is LinearLayout) {
            val index = parentLayout.indexOfChild(copyButton)
            parentLayout.addView(newCopyLogcatButton, index + 1)
        }

        newCopyLogcatButton.setOnClickListener {
            activity.lifecycleScope.launch {
                try {
                    val fileContent = withContext(Dispatchers.IO) {
                        val possibleLogcatLogFile =
                            getPossibleLogFileBasedOnTimestamp(activity, errorInfo)
                        possibleLogcatLogFile?.readText(Charsets.UTF_8)
                    }

                    if (null != fileContent) {
                        ShareUtils.copyToClipboard(context, fileContent)
                    } else {
                        Toast.makeText(context, R.string.logcat_log_unavailable, Toast.LENGTH_LONG).show()
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    Toast.makeText(context, R.string.logcat_log_read_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun getPossibleLogFileBasedOnTimestamp(
        context: Context,
        errorInfo: ErrorInfo
    ): File? {
        val logFileNameCreator = PvcLogFileNameErrorInfo()
        logFileNameCreator.setTimestamp(errorInfo.pvcErrorInfoCreationTimestamp)
        // we assume that while the app was crashing a log file with
        // that name was created
        val possibleLogFileBasedOnTimestamp = logFileNameCreator.getLogFileName()

        val logDir = logcatDump.getLogFolder(context)
        val file = File(logDir, possibleLogFileBasedOnTimestamp)
        if (file.exists()) {
            return file
        }
        return null
    }

    protected fun pvcIsDumperEnabled() = isLogcatDumperEnabled()
}
