package org.schabi.newpipe.about

import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.google.android.material.tabs.TabLayoutMediator
import org.schabi.newpipe.BuildConfig
import org.schabi.newpipe.R
import org.schabi.newpipe.databinding.ActivityAboutBinding
import org.schabi.newpipe.databinding.FragmentAboutBinding
import org.schabi.newpipe.util.ThemeHelper
import org.schabi.newpipe.util.external_communication.ShareUtils

class AboutActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeHelper.setTheme(this)
        title = getString(R.string.title_activity_about)

        val aboutBinding = ActivityAboutBinding.inflate(layoutInflater)
        setContentView(aboutBinding.root)
        setSupportActionBar(aboutBinding.aboutToolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        // The About and Licenses tabs share this pager.
        val mAboutStateAdapter = AboutStateAdapter(this)
        // Set up the ViewPager with the sections adapter.
        aboutBinding.aboutViewPager2.adapter = mAboutStateAdapter
        TabLayoutMediator(
            aboutBinding.aboutTabLayout,
            aboutBinding.aboutViewPager2
        ) { tab, position ->
            tab.setText(mAboutStateAdapter.getPageTitle(position))
        }.attach()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    /**
     * Displays the installed build's version, application ID, build type and project link.
     */
    class AboutFragment : Fragment() {
        override fun onCreateView(
            inflater: LayoutInflater,
            container: ViewGroup?,
            savedInstanceState: Bundle?
        ): View {
            FragmentAboutBinding.inflate(inflater, container, false).apply {
                aboutAppVersion.text = BuildConfig.VERSION_NAME
                pvcMore.pvcAppSignature.text = BuildConfig.APPLICATION_ID
                pvcMore.aboutAppBuildType.text = BuildConfig.BUILD_TYPE
                pvcAbout.pvcAboutGithubLink.setOnClickListener {
                    ShareUtils.openUrlInApp(requireContext(), getString(R.string.pvc_github_url))
                }
                return root
            }
        }
    }

    /**
     * A [FragmentStateAdapter] that returns a fragment corresponding to
     * one of the sections/tabs/pages.
     */
    private class AboutStateAdapter(fa: FragmentActivity) : FragmentStateAdapter(fa) {
        private val posAbout = 0
        private val posLicense = 1
        private val totalCount = 2

        override fun createFragment(position: Int): Fragment {
            return when (position) {
                posAbout -> AboutFragment()
                posLicense -> LicenseFragment.newInstance(SOFTWARE_COMPONENTS)
                else -> error("Unknown position for ViewPager2")
            }
        }

        override fun getItemCount(): Int {
            // Show 2 total pages.
            return totalCount
        }

        fun getPageTitle(position: Int): Int {
            return when (position) {
                posAbout -> R.string.tab_about
                posLicense -> R.string.tab_licenses
                else -> error("Unknown position for ViewPager2")
            }
        }
    }

    companion object {
        /**
         * Third-party project families found on the release and debug runtime classpaths, plus
         * projects from which source code has been incorporated. Multiple artifacts from one
         * project (for example AndroidX or LeakCanary) are represented by one entry. Copyright
         * dates come from the resolved version's notices/source headers, never the current year.
         * An empty date means no project-wide copyright date was established for this version.
         * Evidence and exact artifact versions: docs/license-audit.json (2026-10-26).
         */
        private val SOFTWARE_COMPONENTS = arrayListOf(
            SoftwareComponent("ACRA", "2013", "Kevin Gaudin", "https://github.com/ACRA/acra", StandardLicenses.APACHE2, noticeFilename = "third_party/acra_notice.txt"),
            SoftwareComponent("AndroidX", "2006 - 2025", "The Android Open Source Project", "https://developer.android.com/jetpack", StandardLicenses.APACHE2),
            SoftwareComponent("Android Universal Music Player", "2018", "Google Inc.", "https://github.com/android/uamp", StandardLicenses.APACHE2),
            SoftwareComponent("Apache Commons Text", "2014 - 2020", "The Apache Software Foundation", "https://commons.apache.org/proper/commons-text/", StandardLicenses.APACHE2, noticeFilename = "third_party/commons_text_notice.txt"),
            SoftwareComponent("BravePipe", "", "evermind-zz and BravePipe contributors", "https://github.com/bravepipeproject/BravePipe", StandardLicenses.GPL3),
            SoftwareComponent("Checker Framework", "2004 - present", "The Checker Framework developers", "https://github.com/typetools/checker-framework", StandardLicenses.MIT),
            SoftwareComponent("Cloudflare-Bypass", "2024", "Xavier Alexander Torres Calderón", "https://github.com/evermind-zz/Cloudflare-Bypass", StandardLicenses.MIT),
            SoftwareComponent("Coil", "2025", "Coil Contributors", "https://coil-kt.github.io/coil/", StandardLicenses.APACHE2),
            SoftwareComponent("commonmark-java", "2015 - 2016", "Atlassian Pty Ltd", "https://github.com/commonmark/commonmark-java", StandardLicenses.BSD2),
            SoftwareComponent("EventBus", "2012 - 2020", "Markus Junginger, greenrobot", "https://greenrobot.org/eventbus", StandardLicenses.APACHE2),
            SoftwareComponent("Error Prone Annotations", "", "The Error Prone Authors", "https://github.com/google/error-prone", StandardLicenses.APACHE2),
            SoftwareComponent("ExoPlayer", "2014 - 2023", "The Android Open Source Project", "https://github.com/google/ExoPlayer", StandardLicenses.APACHE2),
            SoftwareComponent("FCast sender SDK", "2023", "FUTO", "https://github.com/futo-org/fcast", StandardLicenses.MIT),
            SoftwareComponent("GigaGet", "2014", "Peter Cai", "https://github.com/PaperAirplane-Dev-Team/GigaGet", StandardLicenses.GPL3),
            SoftwareComponent("FFmpeg", "", "FFmpeg developers", "https://ffmpeg.org/", StandardLicenses.LGPL21),
            SoftwareComponent("Google Auto", "", "Google LLC", "https://github.com/google/auto", StandardLicenses.APACHE2),
            SoftwareComponent("Guava", "", "The Guava Authors", "https://github.com/google/guava", StandardLicenses.APACHE2),
            SoftwareComponent("Groupie", "2016", "Lisa Wray", "https://github.com/lisawray/groupie", StandardLicenses.MIT),
            SoftwareComponent("HlsDownloader", "", "evermind-zz", "https://github.com/evermind-zz/HlsDownloader", StandardLicenses.GPL3),
            SoftwareComponent("J2ObjC Annotations", "2012", "Google Inc.", "https://github.com/google/j2objc", StandardLicenses.APACHE2),
            SoftwareComponent("Java Native Access", "", "JNA contributors", "https://github.com/java-native-access/jna", StandardLicenses.JNA),
            SoftwareComponent("JetBrains Java Annotations", "2000 - 2021", "JetBrains s.r.o.", "https://github.com/JetBrains/java-annotations", StandardLicenses.APACHE2),
            SoftwareComponent("Jsoup", "2009 - 2026", "Jonathan Hedley", "https://github.com/jhy/jsoup", StandardLicenses.MIT),
            SoftwareComponent("JSpecify", "2018 - 2022", "The JSpecify Authors", "https://github.com/jspecify/jspecify", StandardLicenses.APACHE2),
            SoftwareComponent("Kotlin", "2010 - 2026", "JetBrains s.r.o. and Kotlin Programming Language contributors", "https://github.com/JetBrains/kotlin", StandardLicenses.APACHE2),
            SoftwareComponent("kotlinx.coroutines", "2000 - 2020", "JetBrains s.r.o. and Kotlin Programming Language contributors", "https://github.com/Kotlin/kotlinx.coroutines", StandardLicenses.APACHE2),
            SoftwareComponent("kotlinx.datetime", "2019 - 2024", "JetBrains s.r.o. and contributors", "https://github.com/Kotlin/kotlinx-datetime", StandardLicenses.APACHE2),
            SoftwareComponent("kotlinx.io", "", "JetBrains and Kotlin contributors", "https://github.com/Kotlin/kotlinx-io", StandardLicenses.APACHE2),
            SoftwareComponent("kotlinx.serialization", "2017 - 2025", "JetBrains s.r.o. and contributors", "https://github.com/Kotlin/kotlinx.serialization", StandardLicenses.APACHE2),
            SoftwareComponent("LogcatToolkit", "", "evermind-zz and kyze8439690", "https://github.com/evermind-zz/logcat-toolkit", StandardLicenses.APACHE2),
            SoftwareComponent("Markwon", "", "Dimitry Ivanov", "https://github.com/noties/Markwon", StandardLicenses.APACHE2),
            SoftwareComponent("Material Components for Android", "2014 - 2023", "The Android Open Source Project", "https://github.com/material-components/material-components-android", StandardLicenses.APACHE2),
            SoftwareComponent("nanojson", "2011", "The nanojson Authors", "https://github.com/TeamNewPipe/nanojson", StandardLicenses.APACHE2),
            SoftwareComponent("NewPipe", "2015 - 2026", "NewPipe contributors", "https://github.com/TeamNewPipe/NewPipe", StandardLicenses.GPL3),
            SoftwareComponent("OkHttp", "2012 - 2025", "Square, Inc.", "https://square.github.io/okhttp/", StandardLicenses.APACHE2),
            SoftwareComponent("Okio", "2014 - 2025", "Square, Inc.", "https://github.com/square/okio", StandardLicenses.APACHE2),
            SoftwareComponent("PrettyTime", "2012 - 2022", "Lincoln Baxter, III and contributors", "https://github.com/ocpsoft/prettytime", StandardLicenses.APACHE2),
            SoftwareComponent("ProcessPhoenix", "2014", "Jake Wharton", "https://github.com/JakeWharton/ProcessPhoenix", StandardLicenses.APACHE2),
            SoftwareComponent("Protocol Buffers", "2008", "Google Inc.", "https://github.com/protocolbuffers/protobuf", StandardLicenses.BSD3),
            SoftwareComponent("PVCPipe Extractor", "", "NewPipe and PVCPipe Extractor contributors", "https://github.com/75ohmantenna/pvcpipe/tree/master/pvcpipe-extractor", StandardLicenses.GPL3),
            SoftwareComponent("Reactive Streams", "2014", "Reactive Streams", "https://github.com/reactive-streams/reactive-streams-jvm", StandardLicenses.MIT0),
            SoftwareComponent("Rhino", "", "Mozilla and Rhino contributors", "https://github.com/mozilla/rhino", StandardLicenses.RHINO),
            SoftwareComponent("RxAndroid", "", "The RxAndroid authors", "https://github.com/ReactiveX/RxAndroid", StandardLicenses.APACHE2),
            SoftwareComponent("RxJava", "2016 - present", "RxJava Contributors", "https://github.com/ReactiveX/RxJava", StandardLicenses.APACHE2),
            SoftwareComponent("SearchPreference", "2018", "ByteHamster", "https://github.com/ByteHamster/SearchPreference", StandardLicenses.MIT),
            SoftwareComponent("JSR-305", "", "FindBugs contributors and Brian Goetz", "https://github.com/findbugsproject/findbugs", StandardLicenses.JSR305),
            SoftwareComponent("slimhls-converter", "", "evermind-zz and PVCPipe contributors", "https://github.com/75ohmantenna/slimhls-converter/tree/7e7f373dbf", StandardLicenses.GPL3),
            SoftwareComponent("Wire", "2013 - 2024", "Square, Inc.", "https://github.com/square/wire", StandardLicenses.APACHE2),
            SoftwareComponent("challengeFloatsAway", "", "evermind-zz", "https://github.com/evermind-zz/challengeFloatsAway", StandardLicenses.GPL3),
            SoftwareComponent("desugar_jdk_libs", "", "Oracle, Google and OpenJDK contributors", "https://github.com/google/desugar_jdk_libs", StandardLicenses.GPL2_CLASSPATH)
        ).apply {
            if (BuildConfig.DEBUG) {
                addAll(
                    listOf(
                        SoftwareComponent("Apache Commons CLI", "2001 - 2009", "The Apache Software Foundation", "https://commons.apache.org/proper/commons-cli/", StandardLicenses.APACHE2, noticeFilename = "third_party/commons_cli_notice.txt"),
                        SoftwareComponent("Curtains", "", "Square, Inc.", "https://github.com/square/curtains", StandardLicenses.APACHE2),
                        SoftwareComponent("LeakCanary", "2015 - 2019", "Square, Inc.", "https://github.com/square/leakcanary", StandardLicenses.APACHE2),
                        SoftwareComponent("Stetho", "", "Facebook, Inc. and its affiliates", "https://github.com/facebook/stetho", StandardLicenses.MIT)
                    )
                )
            }
        }
    }
}
