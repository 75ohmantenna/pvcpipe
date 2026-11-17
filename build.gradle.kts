/*
 * SPDX-FileCopyrightText: 2025 NewPipe e.V. <https://newpipe-ev.de>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

buildscript {
    // Security fixes for transitive dependencies of the Android Gradle plugin.
    dependencies {
        constraints {
            add("classpath", "com.google.guava:guava:33.7.2-jre")
            add("classpath", "org.apache.commons:commons-lang3:3.20.0")
            add("classpath", "org.bitbucket.b_c:jose4j:0.9.6")
            add("classpath", "org.bouncycastle:bcpkix-jdk18on:1.86")
            add("classpath", "org.bouncycastle:bcprov-jdk18on:1.86")
            add("classpath", "org.bouncycastle:bcutil-jdk18on:1.86")
            add("classpath", "org.jdom:jdom2:2.0.6.1")
        }
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.google.ksp) apply false
    alias(libs.plugins.jetbrains.kotlin.parcelize) apply false
    alias(libs.plugins.jetbrains.kotlinx.serialization) apply false
}
