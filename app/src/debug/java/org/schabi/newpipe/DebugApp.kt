package org.schabi.newpipe

import androidx.preference.PreferenceManager
import leakcanary.LeakCanary

class DebugApp : App() {
    override fun onCreate() {
        super.onCreate()

        LeakCanary.config = LeakCanary.config.copy(
            dumpHeap = PreferenceManager
                .getDefaultSharedPreferences(this).getBoolean(
                    getString(
                        R.string.allow_heap_dumping_key
                    ),
                    false
                )
        )
    }

    override fun isDisposedRxExceptionsReported(): Boolean {
        return PreferenceManager.getDefaultSharedPreferences(this)
            .getBoolean(getString(R.string.allow_disposed_exceptions_key), false)
    }
}
