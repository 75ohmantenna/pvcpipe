/*
 * SPDX-FileCopyrightText: 2026 PVCPipe contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.Test

class PvcVersionTest {
    @Test
    fun componentRolloversIncreaseTheAndroidCode() {
        assertEquals(PvcVersion.code("0.1.99") + 1, PvcVersion.code("0.2.0"))
        assertEquals(PvcVersion.code("0.99.99") + 1, PvcVersion.code("1.0.0"))
    }

    @Test
    fun theMaximumAndroidCodeIsAcceptedButTheNextPatchIsRejected() {
        assertEquals(2_100_000_000, PvcVersion.code("209900.0.0"))
        assertFailsWith<IllegalArgumentException> { PvcVersion.code("209900.0.1") }
    }

    @Test
    fun oversizedMajorsCannotWrapIntoInstallableCodes() {
        for (version in listOf("214748.0.0", "429497.0.0", "999999999999999999.0.0")) {
            assertFailsWith<IllegalArgumentException>(version) { PvcVersion.code(version) }
        }
    }
}
