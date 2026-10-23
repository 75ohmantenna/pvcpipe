package org.schabi.newpipe.local.subscription.workers

import android.content.Context
import android.content.res.Resources
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.LocaleManagerCompat
import java.text.NumberFormat
import java.util.Locale
import org.schabi.newpipe.R

internal object SubscriptionImportPresentation {
    fun completion(resources: Resources, skipped: Int): String = if (skipped > 0) {
        resources.getQuantityString(R.plurals.import_subscriptions_skipped, skipped, skipped)
    } else {
        resources.getString(R.string.subscription_import_finished)
    }

    fun progress(context: Context, current: Int, total: Int): String {
        // A cold WorkManager process may not have loaded AppCompat's saved UI locale yet.
        val locale = AppCompatDelegate.getApplicationLocales()[0]
            ?: LocaleManagerCompat.getApplicationLocales(context)[0]
            ?: Locale.getDefault()
        val numbers = NumberFormat.getIntegerInstance(locale)
        return context.getString(
            R.string.subscription_import_progress,
            numbers.format(current),
            numbers.format(total)
        )
    }
}
