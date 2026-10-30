/*
 * SPDX-FileCopyrightText: 2026 PVCPipe contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.util

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test
import org.schabi.newpipe.extractor.ServiceList

/**
 * [ThemeHelper] finds service themes by name, which the resource shrinker cannot see, so each one
 * has to be listed in the keep file or release builds silently use the base theme.
 */
class ServiceThemeKeepTest {
    private val serviceThemes = ServiceList.all().flatMap { service ->
        listOf("LightTheme", "DarkTheme", "BlackTheme")
            .map { base -> "$base.${service.serviceInfo.name}" }
    }.sorted()

    @Test
    fun `every service has its three themes`() {
        val styles = File("src/main/res/values/styles_services.xml").readText()
        val defined = Regex("""<style name="([^"]+)"""").findAll(styles)
            .map { it.groupValues[1] }.toList().sorted()

        assertEquals(serviceThemes, defined)
    }

    @Test
    fun `the keep file lists every service theme by its normalized name`() {
        val keep = File("src/main/res/raw/pvcpipe_theme_keep.xml").readText()
        val kept = Regex("""@style/([A-Za-z_]+)""").findAll(keep)
            .map { it.groupValues[1] }.toList().sorted()

        assertEquals(serviceThemes.map { it.replace('.', '_') }, kept)
    }
}
