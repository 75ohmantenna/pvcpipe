package org.schabi.newpipe.player.helper

import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNotNull
import org.junit.Test
import org.junit.runner.RunWith

/** Reopens a populated legacy cache after an in-place APK update on a disposable installation. */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class LegacyCacheUpgradeIntegrationTest {
    @Test
    fun legacyKeysAndCommittedSpansSurviveTheMedia3Upgrade() {
        val args = InstrumentationRegistry.getArguments()
        val expectedKeys = args.getString("media3LegacyCacheKeysSha256")
        val expectedFiles = args.getString("media3LegacyCacheFilesSha256")
        assumeNotNull(expectedKeys, expectedFiles)
        check(args.getString("media3DisposableEnvironment") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        PlayerDataSource(context, DefaultBandwidthMeter.Builder(context).build())
        val field = PlayerDataSource::class.java.getDeclaredField("cache").apply { isAccessible = true }
        val cache = field.get(null) as SimpleCache
        try {
            val keys = cache.keys.sorted()
            val spans = keys.flatMap { cache.getCachedSpans(it) }
            assertTrue("The legacy cache contains committed media bytes", spans.sumOf { it.length } > 0)
            assertEquals(expectedKeys, digest(keys))
            assertEquals(expectedFiles, digest(spans.map { "${it.file!!.name}:${it.length}" }.sorted()))
        } finally {
            cache.release()
            field.set(null, null)
        }
    }

    private fun digest(values: List<String>): String = MessageDigest.getInstance("SHA-256")
        .digest(values.joinToString("\n").toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 255) }
}
