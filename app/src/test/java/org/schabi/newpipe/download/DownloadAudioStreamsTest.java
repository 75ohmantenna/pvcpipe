package org.schabi.newpipe.download;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.schabi.newpipe.extractor.MediaFormat;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.DeliveryMethod;
import org.schabi.newpipe.util.ListHelper;

import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 26)
public class DownloadAudioStreamsTest {
    private final Context context = RuntimeEnvironment.getApplication();

    @Test
    public void acceptsOnlySupportedUrlAudioHls() {
        assertTrue(DownloadAudioStreams.isSupportedHls(stream(MediaFormat.M4A,
                DeliveryMethod.HLS, null, 160)));
        for (final MediaFormat format : List.of(MediaFormat.MP3, MediaFormat.OPUS,
                MediaFormat.AAC)) {
            assertFalse(DownloadAudioStreams.isSupportedHls(stream(format,
                    DeliveryMethod.HLS, null, 128)));
        }
        assertFalse(DownloadAudioStreams.isSupportedHls(stream(MediaFormat.M4A,
                DeliveryMethod.DASH, null, 128)));
    }

    @Test
    public void addingPreferredHlsDoesNotChangeProgressiveDefault() {
        final AudioStream progressive = stream(MediaFormat.MP3,
                DeliveryMethod.PROGRESSIVE_HTTP, null, 128);
        final AudioStream hls = stream(MediaFormat.M4A, DeliveryMethod.HLS, null, 160);
        final List<List<AudioStream>> groups = DownloadAudioStreams.groups(context,
                List.of(hls, progressive));
        assertEquals(1, groups.size());
        assertEquals(0, DownloadAudioStreams.defaultGroup(context, groups));
        assertSame(progressive, groups.get(0).get(
                DownloadAudioStreams.defaultFormat(context, groups.get(0))));
    }

    @Test
    public void hlsLanguageCannotHideUnknownProgressiveOrChangeVideoPreference() {
        final AudioStream progressive = stream(MediaFormat.M4A,
                DeliveryMethod.PROGRESSIVE_HTTP, null, 128);
        final AudioStream hls = stream(MediaFormat.M4A, DeliveryMethod.HLS, "en", 160);
        final List<List<AudioStream>> groups = DownloadAudioStreams.groups(context,
                List.of(progressive, hls));
        assertEquals(2, groups.size());
        assertEquals(1, DownloadAudioStreams.progressiveGroupCount(groups));
        assertSame(progressive,
                groups.get(DownloadAudioStreams.defaultGroup(context, groups)).get(0));
        assertTrue(groups.stream().anyMatch(group -> group.contains(hls)));
    }

    @Test
    public void hlsOnlyUsesExistingFormatPreferenceAndExcludesOtherCodecs() {
        final AudioStream low = stream(MediaFormat.M4A, DeliveryMethod.HLS, null, 96);
        final AudioStream high = stream(MediaFormat.M4A, DeliveryMethod.HLS, null, 160);
        final List<List<AudioStream>> groups = DownloadAudioStreams.groups(context,
                List.of(low, high, stream(MediaFormat.OPUS, DeliveryMethod.HLS, null, 64)));
        assertEquals(1, groups.size());
        assertEquals(2, groups.get(0).size());
        assertEquals(ListHelper.getDefaultAudioFormat(context, groups.get(0)),
                DownloadAudioStreams.defaultFormat(context, groups.get(0)));
        assertEquals(0, DownloadAudioStreams.progressiveGroupCount(groups));
    }

    private static AudioStream stream(final MediaFormat format, final DeliveryMethod delivery,
                                      final String track, final int bitrate) {
        return new AudioStream.Builder().setId(format.name() + bitrate)
                .setContent("https://media.test/" + format + bitrate, true)
                .setMediaFormat(format).setDeliveryMethod(delivery).setAverageBitrate(bitrate)
                .setAudioTrackId(track).build();
    }
}
