/*
 * SPDX-FileCopyrightText: 2015-2026 NewPipe contributors <https://newpipe.net>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.error

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.MenuItem
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.IntentCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.grack.nanojson.JsonWriter
import java.io.File
import java.io.IOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.schabi.newpipe.BuildConfig
import org.schabi.newpipe.R
import org.schabi.newpipe.databinding.ActivityErrorBinding
import org.schabi.newpipe.util.Localization
import org.schabi.newpipe.util.ThemeHelper
import org.schabi.newpipe.util.external_communication.ShareUtils
import org.schabi.newpipe.util.text.setTextWithLinks
import org.schabi.newpipe.views.StatusBarBackground

/**
 * This activity is used to show error details and allow reporting them in various ways.
 * Use [ErrorUtil.openActivity] to correctly open this activity.
 */
class ErrorActivity : PvcErrorActivity() {
    private lateinit var errorInfo: ErrorInfo
    private lateinit var currentTimeStamp: String

    private lateinit var binding: ActivityErrorBinding
    private var reportGeneration = 0
    private var availableDiagnosticLog: File? = null
    private var selectedDiagnosticLog: File? = null
    private var diagnosticLogLookup: Job? = null
    private var reportingDialog: AlertDialog? = null

    private val contentCountryString: String
        get() = Localization.getPreferredContentCountry(this).countryCode

    private val contentLanguageString: String
        get() = Localization.getPreferredLocalization(this).localizationCode

    private val appLanguage: String
        get() = Localization.getAppLocale().toString()

    private val osString: String
        get() {
            val name = System.getProperty("os.name")!!
            val osBase = Build.VERSION.BASE_OS.ifEmpty { "Android" }
            return "$name $osBase ${Build.VERSION.RELEASE} - ${Build.VERSION.SDK_INT}"
        }

    // /////////////////////////////////////////////////////////////////////
    // Activity lifecycle
    // /////////////////////////////////////////////////////////////////////

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        ThemeHelper.setDayNightMode(this)
        ThemeHelper.setTheme(this)

        binding = ActivityErrorBinding.inflate(layoutInflater)
        setContentView(binding.getRoot())
        StatusBarBackground.install(this)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val safe = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout() or
                    WindowInsetsCompat.Type.ime()
            )
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            WindowInsetsCompat.CONSUMED
        }
        ViewCompat.requestApplyInsets(binding.root)

        setSupportActionBar(binding.toolbarLayout.toolbar)
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setTitle(R.string.error_report_title)
            setDisplayShowTitleEnabled(true)
        }

        addGuruMeditation()
        configureReportActions()
        displayReport()
    }

    override fun onResume() {
        super.onResume()
        refreshDiagnosticLog()
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        // Consent is deliberately not restored, even when Android restores other view state.
        binding.includeDiagnosticLog.isChecked = false
        selectedDiagnosticLog = null
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        binding.errorCommentBox.text?.clear()
        displayReport()
        refreshDiagnosticLog()
    }

    override fun onDestroy() {
        reportingDialog?.dismiss()
        super.onDestroy()
    }

    private fun displayReport() {
        reportGeneration++
        diagnosticLogLookup?.cancel()
        reportingDialog?.dismiss()
        availableDiagnosticLog = null
        selectedDiagnosticLog = null
        binding.includeDiagnosticLog.isChecked = false
        binding.includeDiagnosticLog.isEnabled = false
        errorInfo = IntentCompat.getParcelableExtra(intent, ERROR_INFO, ErrorInfo::class.java)!!
        val now = ZonedDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        currentTimeStamp = pvcGetErrorCreationTimestamp(now, errorInfo)

        buildInfo(errorInfo)
        binding.errorMessageView.setTextWithLinks(errorInfo.getMessage(this))
        binding.errorView.text = formErrorText(errorInfo.stackTraces)
        if (!pvcIsDumperEnabled()) {
            errorInfo.stackTraces.forEach { Log.e(TAG, it) }
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                onBackPressedDispatcher.onBackPressed()
                true
            }

            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun configureReportActions() {
        binding.errorReportCopyButton.setOnClickListener {
            ShareUtils.copyToClipboard(this, buildMarkdown())
        }
        binding.errorReportShareButton.setOnClickListener { shareReport() }
        binding.reportingNoticeButton.setOnClickListener {
            reportingDialog?.dismiss()
            reportingDialog = AlertDialog.Builder(this)
                .setTitle(R.string.reporting_notice_title)
                .setMessage(R.string.reporting_notice_body)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
        binding.inspectReportButton.setOnClickListener {
            val preview = getString(R.string.reporting_copy) + "\n\n" + buildMarkdown() +
                "\n\n" + getString(R.string.reporting_share) + "\n\n" + buildJson()
            showReportPreview(R.string.reporting_report_preview_title, preview)
        }
        binding.inspectDiagnosticLogButton.setOnClickListener { inspectDiagnosticLog() }
        binding.includeDiagnosticLog.setOnCheckedChangeListener { _, checked ->
            selectedDiagnosticLog = if (checked) availableDiagnosticLog else null
            if (checked && selectedDiagnosticLog == null) {
                binding.includeDiagnosticLog.isChecked = false
                showDiagnosticLogUnavailable()
            }
        }
    }

    private fun refreshDiagnosticLog() {
        val info = errorInfo
        val generation = reportGeneration
        diagnosticLogLookup?.cancel()
        diagnosticLogLookup = lifecycleScope.launch {
            val file = withContext(Dispatchers.IO) {
                ReportingLogFile.find(this@ErrorActivity, info)
            }
            if (generation == reportGeneration) updateDiagnosticLog(file)
        }
    }

    private fun updateDiagnosticLog(file: File?) {
        availableDiagnosticLog = file
        binding.includeDiagnosticLog.isEnabled = file != null
        if (file == null || selectedDiagnosticLog != file) {
            binding.includeDiagnosticLog.isChecked = false
            selectedDiagnosticLog = null
        }
    }

    private fun showDiagnosticLogUnavailable() {
        Toast.makeText(this, R.string.reporting_log_unavailable, Toast.LENGTH_LONG).show()
        refreshDiagnosticLog()
    }

    private fun inspectDiagnosticLog() {
        val info = errorInfo
        val generation = reportGeneration
        lifecycleScope.launch {
            try {
                val (file, text) = withContext(Dispatchers.IO) {
                    val file = ReportingLogFile.find(this@ErrorActivity, info)
                        ?: throw IOException("No completed diagnostic log for this report")
                    val text = file.readText(Charsets.UTF_8)
                    ReportingLogFile.requireComplete(file)
                    file to text
                }
                if (generation != reportGeneration) return@launch
                updateDiagnosticLog(file)
                showReportPreview(R.string.reporting_log_preview_title, text)
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: IOException) {
                if (generation == reportGeneration) showDiagnosticLogUnavailable()
            } catch (_: SecurityException) {
                if (generation == reportGeneration) {
                    Toast.makeText(
                        this@ErrorActivity,
                        R.string.reporting_log_read_failed,
                        Toast.LENGTH_LONG
                    ).show()
                    refreshDiagnosticLog()
                }
            }
        }
    }

    private fun showReportPreview(title: Int, text: String) {
        reportingDialog?.dismiss()
        reportingDialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(text)
            .setPositiveButton(android.R.string.ok, null)
            .show()
            .also { dialog ->
                dialog.findViewById<TextView>(android.R.id.message)?.apply {
                    setTextIsSelectable(true)
                    typeface = Typeface.MONOSPACE
                }
            }
    }

    private fun shareReport() {
        val info = errorInfo
        val generation = reportGeneration
        val includeLog = binding.includeDiagnosticLog.isChecked
        val selectedLog = selectedDiagnosticLog
        val text = buildJson()
        lifecycleScope.launch {
            val sendIntent = try {
                withContext(Dispatchers.IO) {
                    val file = if (includeLog) {
                        val matchingFile = ReportingLogFile.find(this@ErrorActivity, info)
                        if (selectedLog == null || matchingFile != selectedLog) {
                            throw IOException("The selected diagnostic log is no longer available")
                        }
                        matchingFile
                    } else {
                        null
                    }
                    ReportingShareIntent.create(this@ErrorActivity, text, file)
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: IOException) {
                if (generation == reportGeneration) showDiagnosticLogUnavailable()
                return@launch
            } catch (_: IllegalArgumentException) {
                if (generation == reportGeneration) showShareFailed()
                return@launch
            } catch (_: SecurityException) {
                if (generation == reportGeneration) showShareFailed()
                return@launch
            }
            if (generation != reportGeneration ||
                includeLog != binding.includeDiagnosticLog.isChecked ||
                (includeLog && selectedLog != selectedDiagnosticLog)
            ) {
                return@launch
            }
            try {
                startActivity(Intent.createChooser(sendIntent, getString(R.string.error_report_title)))
            } catch (_: ActivityNotFoundException) {
                showShareFailed()
            } catch (_: SecurityException) {
                showShareFailed()
            }
        }
    }

    private fun showShareFailed() {
        Toast.makeText(this, R.string.reporting_share_failed, Toast.LENGTH_LONG).show()
    }

    private fun formErrorText(stacktrace: Array<String>): String {
        val separator = "-------------------------------------"
        return stacktrace.joinToString(separator + "\n", separator + "\n", separator)
    }

    private fun buildInfo(info: ErrorInfo) {
        binding.errorInfoLabelsView.text = getString(R.string.info_labels)

        val text = info.userAction.message + "\n" +
            info.request + "\n" +
            contentLanguageString + "\n" +
            contentCountryString + "\n" +
            appLanguage + "\n" +
            info.getServiceName(this) + "\n" +
            currentTimeStamp + "\n" +
            packageName + "\n" +
            BuildConfig.VERSION_NAME + "\n" +
            osString

        binding.errorInfosView.text = text
    }

    private fun buildJson(): String {
        try {
            return JsonWriter.string()
                .`object`()
                .value("user_action", errorInfo.userAction.message)
                .value("request", errorInfo.request)
                .value("content_language", contentLanguageString)
                .value("content_country", contentCountryString)
                .value("app_language", appLanguage)
                .value("service", errorInfo.getServiceName())
                .value("package", packageName)
                .value("version", BuildConfig.VERSION_NAME)
                .value("os", osString)
                .value("time", currentTimeStamp)
                .array("exceptions", PvcErrorInfoHelper.truncate(errorInfo.stackTraces).toList())
                .value("user_comment", binding.errorCommentBox.getText().toString())
                .end()
                .done()
        } catch (exception: Exception) {
            Log.e(TAG, "Error while erroring: Could not build json", exception)
        }

        return ""
    }

    private fun buildMarkdown(): String {
        try {
            return buildString(1024) {
                val userComment = binding.errorCommentBox.text.toString()
                if (userComment.isNotEmpty()) {
                    appendLine(userComment)
                }

                // basic error info
                appendLine("## Exception")
                appendLine("* __User Action:__ ${errorInfo.userAction.message}")
                appendLine("* __Request:__ ${errorInfo.request}")
                appendLine("* __Content Country:__ $contentCountryString")
                appendLine("* __Content Language:__ $contentLanguageString")
                appendLine("* __App Language:__ $appLanguage")
                appendLine("* __Service:__ ${errorInfo.getServiceName()}")
                appendLine("* __Timestamp:__ $currentTimeStamp")
                appendLine("* __Package:__ $packageName")
                appendLine("* __Version:__ ${BuildConfig.VERSION_NAME}")
                appendLine("* __OS:__ $osString")

                // Group multiple traces in an outer collapsible section to keep the report compact.
                if (errorInfo.stackTraces.size > 1) {
                    append("<details><summary><b>Exceptions (")
                    append(errorInfo.stackTraces.size)
                    append(")</b></summary><p>\n")
                }

                // add the logs
                errorInfo.stackTraces.forEachIndexed { index, stacktrace ->
                    append("<details><summary><b>Crash log ")
                    if (errorInfo.stackTraces.size > 1) {
                        append(index + 1)
                    }
                    append("</b>")
                    append("</summary><p>\n")
                    append("\n```\n${stacktrace}\n```\n")
                    append("</details>\n")
                }

                // make sure to close everything
                if (errorInfo.stackTraces.size > 1) {
                    append("</p></details>\n")
                }

                append("<hr>\n")
            }
        } catch (exception: Exception) {
            Log.e(TAG, "Error while erroring: Could not build markdown", exception)
            return ""
        }
    }

    private fun addGuruMeditation() {
        // just an easter egg
        var text = binding.errorSorryView.text.toString()
        text += "\n" + getString(R.string.guru_meditation)
        binding.errorSorryView.text = text
    }

    companion object {
        // LOG TAGS
        private val TAG = ErrorActivity::class.java.toString()

        // BUNDLE TAGS
        const val ERROR_INFO = "error_info"
    }
}
