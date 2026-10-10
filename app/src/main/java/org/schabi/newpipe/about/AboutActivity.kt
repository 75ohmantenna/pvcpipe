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

        // Create the adapter that will return a fragment for each of the three
        // primary sections of the activity.
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
     * A placeholder fragment containing a simple view.
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
         * project (for example AndroidX or LeakCanary) are represented by one entry.
         */
        private val SOFTWARE_COMPONENTS = arrayListOf(
            SoftwareComponent("ACRA", "2013", "Kevin Gaudin", "https://github.com/ACRA/acra", StandardLicenses.APACHE2),
            SoftwareComponent("Accompanist", "2020 - 2024", "Google LLC", "https://github.com/google/accompanist", StandardLicenses.APACHE2),
            SoftwareComponent("Android-State", "2018", "Evernote", "https://github.com/Evernote/android-state", StandardLicenses.EPL1),
            SoftwareComponent("AndroidX", "2005 - 2026", "The Android Open Source Project", "https://developer.android.com/jetpack", StandardLicenses.APACHE2),
            SoftwareComponent("Android Universal Music Player", "2018", "Google Inc.", "https://github.com/android/uamp", StandardLicenses.APACHE2),
            SoftwareComponent("Apache Commons CLI", "2002 - 2026", "The Apache Software Foundation", "https://commons.apache.org/proper/commons-cli/", StandardLicenses.APACHE2),
            SoftwareComponent("Apache Commons Text", "2014 - 2026", "The Apache Software Foundation", "https://commons.apache.org/proper/commons-text/", StandardLicenses.APACHE2),
            SoftwareComponent("BravePipe", "2021 - 2026", "evermind-zz and BravePipe contributors", "https://github.com/bravepipeproject/BravePipe", StandardLicenses.GPL3),
            SoftwareComponent("Bridge", "2021", "Livefront", "https://github.com/livefront/bridge", StandardLicenses.APACHE2),
            SoftwareComponent("Checker Framework", "2007 - 2026", "The Checker Framework developers", "https://github.com/typetools/checker-framework", StandardLicenses.MIT),
            SoftwareComponent("Cloudflare-Bypass", "2024", "Xavier Alexander Torres Calderón", "https://github.com/evermind-zz/Cloudflare-Bypass", StandardLicenses.MIT),
            SoftwareComponent("Coil", "2023", "Coil Contributors", "https://coil-kt.github.io/coil/", StandardLicenses.APACHE2),
            SoftwareComponent("commonmark-java", "2015", "Robin Stocker", "https://github.com/commonmark/commonmark-java", StandardLicenses.BSD2),
            SoftwareComponent("Curtains", "2020 - 2026", "Square, Inc.", "https://github.com/square/curtains", StandardLicenses.APACHE2),
            SoftwareComponent("EventBus", "2012 - 2016", "Markus Junginger", "https://greenrobot.org/eventbus", StandardLicenses.APACHE2),
            SoftwareComponent("Error Prone Annotations", "2012 - 2026", "Google Inc.", "https://github.com/google/error-prone", StandardLicenses.APACHE2),
            SoftwareComponent("ExoPlayer", "2014 - 2020", "Google, Inc.", "https://github.com/google/ExoPlayer", StandardLicenses.APACHE2),
            SoftwareComponent("GigaGet", "2014 - 2015", "Peter Cai", "https://github.com/PaperAirplane-Dev-Team/GigaGet", StandardLicenses.GPL3),
            SoftwareComponent("Google Auto", "2013 - 2026", "Google Inc.", "https://github.com/google/auto", StandardLicenses.APACHE2),
            SoftwareComponent("Guava", "2010 - 2026", "Google Inc.", "https://github.com/google/guava", StandardLicenses.APACHE2),
            SoftwareComponent("Groupie", "2016", "Lisa Wray", "https://github.com/lisawray/groupie", StandardLicenses.MIT),
            SoftwareComponent("HlsDownloader", "2025", "evermind-zz", "https://github.com/evermind-zz/HlsDownloader", StandardLicenses.GPL3),
            SoftwareComponent("J2ObjC Annotations", "2012 - 2026", "Google Inc.", "https://github.com/google/j2objc", StandardLicenses.APACHE2),
            SoftwareComponent("JetBrains Compose Multiplatform", "2020 - 2026", "JetBrains and Compose Multiplatform contributors", "https://github.com/JetBrains/compose-multiplatform", StandardLicenses.APACHE2),
            SoftwareComponent("JetBrains Java Annotations", "2000 - 2026", "JetBrains", "https://github.com/JetBrains/java-annotations", StandardLicenses.APACHE2),
            SoftwareComponent("Jsoup", "2009 - 2020", "Jonathan Hedley", "https://github.com/jhy/jsoup", StandardLicenses.MIT),
            SoftwareComponent("JSpecify", "2017 - 2026", "JSpecify contributors", "https://github.com/jspecify/jspecify", StandardLicenses.APACHE2),
            SoftwareComponent("Kotlin", "2010 - 2026", "JetBrains and Kotlin contributors", "https://github.com/JetBrains/kotlin", StandardLicenses.APACHE2),
            SoftwareComponent("kotlinx.coroutines", "2016 - 2026", "JetBrains and Kotlin contributors", "https://github.com/Kotlin/kotlinx.coroutines", StandardLicenses.APACHE2),
            SoftwareComponent("kotlinx.datetime", "2019 - 2026", "JetBrains and Kotlin contributors", "https://github.com/Kotlin/kotlinx-datetime", StandardLicenses.APACHE2),
            SoftwareComponent("kotlinx.io", "2021 - 2026", "JetBrains and Kotlin contributors", "https://github.com/Kotlin/kotlinx-io", StandardLicenses.APACHE2),
            SoftwareComponent("kotlinx.serialization", "2017 - 2026", "JetBrains and Kotlin contributors", "https://github.com/Kotlin/kotlinx.serialization", StandardLicenses.APACHE2),
            SoftwareComponent("LeakCanary", "2015 - 2026", "Square, Inc.", "https://github.com/square/leakcanary", StandardLicenses.APACHE2),
            SoftwareComponent("LogcatToolkit", "2017 - 2026", "evermind-zz and kyze8439690", "https://github.com/evermind-zz/logcat-toolkit", StandardLicenses.APACHE2),
            SoftwareComponent("Markwon", "2019", "Dimitry Ivanov", "https://github.com/noties/Markwon", StandardLicenses.APACHE2),
            SoftwareComponent("Material Components for Android", "2016 - 2020", "Google, Inc.", "https://github.com/material-components/material-components-android", StandardLicenses.APACHE2),
            SoftwareComponent("nanojson", "2011", "The nanojson Authors", "https://github.com/TeamNewPipe/nanojson", StandardLicenses.APACHE2),
            SoftwareComponent("NewPipe", "2014 - 2026", "NewPipe contributors", "https://github.com/TeamNewPipe/NewPipe", StandardLicenses.GPL3),
            SoftwareComponent("OkHttp", "2019", "Square, Inc.", "https://square.github.io/okhttp/", StandardLicenses.APACHE2),
            SoftwareComponent("Okio", "2013 - 2026", "Square, Inc.", "https://github.com/square/okio", StandardLicenses.APACHE2),
            SoftwareComponent("PrettyTime", "2012 - 2020", "Lincoln Baxter, III", "https://github.com/ocpsoft/prettytime", StandardLicenses.APACHE2),
            SoftwareComponent("ProcessPhoenix", "2015", "Jake Wharton", "https://github.com/JakeWharton/ProcessPhoenix", StandardLicenses.APACHE2),
            SoftwareComponent("Protocol Buffers", "2008", "Google Inc.", "https://github.com/protocolbuffers/protobuf", StandardLicenses.BSD3),
            SoftwareComponent("PVCPipe Extractor", "2017 - 2026", "NewPipe and PVCPipe Extractor contributors", "https://github.com/75ohmantenna/pvcpipe-extractor", StandardLicenses.GPL3),
            SoftwareComponent("Reactive Streams", "2014 - 2026", "Reactive Streams contributors", "https://github.com/reactive-streams/reactive-streams-jvm", StandardLicenses.MIT0),
            SoftwareComponent("Rhino", "1997 - 2026", "Mozilla and Rhino contributors", "https://github.com/mozilla/rhino", StandardLicenses.MPL2),
            SoftwareComponent("RxAndroid", "2015", "The RxAndroid authors", "https://github.com/ReactiveX/RxAndroid", StandardLicenses.APACHE2),
            SoftwareComponent("RxBinding", "2015", "Jake Wharton", "https://github.com/JakeWharton/RxBinding", StandardLicenses.APACHE2),
            SoftwareComponent("RxJava", "2016 - 2020", "RxJava Contributors", "https://github.com/ReactiveX/RxJava", StandardLicenses.APACHE2),
            SoftwareComponent("SearchPreference", "2018", "ByteHamster", "https://github.com/ByteHamster/SearchPreference", StandardLicenses.MIT),
            SoftwareComponent("JSR-305", "2007 - 2015", "FindBugs contributors", "https://github.com/findbugsproject/findbugs", StandardLicenses.APACHE2),
            SoftwareComponent("slimhls-converter", "2025", "evermind-zz", "https://github.com/evermind-zz/slimhls-converter", StandardLicenses.GPL3),
            SoftwareComponent("Stetho", "2015 - 2026", "Facebook, Inc.", "https://github.com/facebook/stetho", StandardLicenses.MIT),
            SoftwareComponent("Wire", "2013 - 2026", "Square, Inc.", "https://github.com/square/wire", StandardLicenses.APACHE2),
            SoftwareComponent("challengeFloatsAway", "2026", "evermind-zz", "https://github.com/evermind-zz/challengeFloatsAway", StandardLicenses.GPL3)
        )
    }
}
