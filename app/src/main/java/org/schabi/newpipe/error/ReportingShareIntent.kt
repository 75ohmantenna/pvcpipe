package org.schabi.newpipe.error

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.github.logviewer.LogcatFileProvider
import java.io.File

/** A reporting-only send intent, with no recipient, destination, or implicit attachment. */
internal object ReportingShareIntent {
    fun create(context: Context, text: String, file: File?): Intent {
        val intent = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, text)
        if (file != null) {
            ReportingLogFile.requireComplete(file)
            val uri = FileProvider.getUriForFile(
                context,
                LogcatFileProvider.getAuthority(context),
                file
            )
            intent.putExtra(Intent.EXTRA_STREAM, uri)
            intent.clipData = ClipData.newRawUri(file.name, uri)
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return intent
    }
}
