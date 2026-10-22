/*
 * SPDX-FileCopyrightText: 2025 NewPipe e.V. <https://newpipe-ev.de>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

import java.util.Properties

plugins {
    alias(libs.plugins.google.protobuf) apply false
}

// The extractor ships only inside PVCPipe and has the app's version, from the file both builds
// read. The file is next to this directory, also when the extractor is built by itself.
val pvcpipeVersion = Properties().apply {
    load(
        providers.fileContents(layout.projectDirectory.file("../version.properties"))
            .asText.get().reader()
    )
}.getProperty("version") ?: error("version.properties has no version")

allprojects {
    apply(plugin = "java-library")

    version = pvcpipeVersion

    tasks.withType<JavaCompile> {
        options.encoding = Charsets.UTF_8.toString()
    }

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
    }
}

subprojects {
    // https://discuss.gradle.org/t/best-approach-gradle-multi-module-project-generate-just-one-global-javadoc/18657/21
    // Fixes unknown tag @implNote; the other two were added precautionary
    tasks.withType<Javadoc>().configureEach {
        (options as StandardJavadocDocletOptions).apply {
            encoding = Charsets.UTF_8.toString()
            links = listOf("https://docs.oracle.com/en/java/javase/21/docs/api/")
            tags = listOf(
                "apiNote:a:API Note:",
                "implSpec:a:Implementation Requirements:",
                "implNote:a:Implementation Note:"
            )
        }
    }
}
