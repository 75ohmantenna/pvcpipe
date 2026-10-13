/*
 * SPDX-FileCopyrightText: 2026 PVCPipe contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

import java.util.Properties

/**
 * PVCPipe's version, read from `version.properties` at the top of the repository.
 */
object PvcVersion {
    // The last version code of the numbering inherited from NewPipe and BravePipe was 581013.
    // Android refuses to install a lower code over a higher one, so the codes of PVCPipe's own
    // numbering start above it.
    private const val BASE = 1_000_000
    private val FORMAT = Regex("""(\d+)\.(\d{1,2})\.(\d{1,2})""")

    /**
     * @param properties the text of `version.properties`
     * @return the version name, such as `0.1.0`
     */
    fun name(properties: String): String {
        val name = Properties().apply { load(properties.reader()) }.getProperty("version")
            ?: error("version.properties has no version")
        code(name)
        return name
    }

    /**
     * Every version has one code, and a higher version has a higher code:
     * 0.1.0 is 1000100, 0.1.1 is 1000101, 0.2.0 is 1000200 and 1.0.0 is 1010000.
     *
     * @param name a version name of the form major.minor.patch
     * @return the Android version code for it
     */
    fun code(name: String): Int {
        val (major, minor, patch) = FORMAT.matchEntire(name)?.destructured
            ?: error("The version must be major.minor.patch with minor and patch below 100: '$name'")
        return BASE + major.toInt() * 10_000 + minor.toInt() * 100 + patch.toInt()
    }
}
