/*
 * SPDX-FileCopyrightText: 2026 PVCPipe contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.settings

import android.app.Application
import android.content.Context
import android.os.Build
import androidx.test.runner.AndroidJUnitRunner
import java.io.File

/**
 * Allows host-controlled recovery checkpoints before production startup consumes the journal.
 * Only the fault/fixture phases use this runner; verification uses the normal production App.
 */
class RestoreFixtureRunner : AndroidJUnitRunner() {
    override fun newApplication(loader: ClassLoader, className: String, context: Context): Application {
        check(Build.HARDWARE in setOf("ranchu", "goldfish")) { "Restore fixtures require an emulator" }
        check(context.packageName == "org.seventyfiveohmantenna.pvcpipe.debug")
        check(File(context.filesDir, "restore-emulator-allow").isFile) { "Disposable host marker missing" }
        return super.newApplication(loader, Application::class.java.name, context)
    }
}
