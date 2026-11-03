package org.schabi.newpipe.util.text;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Intent;

import androidx.core.content.IntentCompat;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.schabi.newpipe.RumbleSavedUrlTest;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.player.Player;
import org.schabi.newpipe.player.PlayerService;
import org.schabi.newpipe.player.TimestampChangeData;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, application = Application.class, sdk = 26)
public class InternalUrlsHandlerTimestampTest {
    @Test
    public void ordinaryTimestampsAreKept() {
        assertEquals(0, InternalUrlsHandler.parseTimestampSeconds("0"));
        assertEquals(90, InternalUrlsHandler.parseTimestampSeconds("90"));
        assertEquals(90, InternalUrlsHandler.parseTimestampSeconds("0000090"));
        assertEquals(Integer.MAX_VALUE,
                InternalUrlsHandler.parseTimestampSeconds("2147483647"));
    }

    @Test
    public void oversizedTimestampsAreClampedInsteadOfThrowing() {
        assertEquals(Integer.MAX_VALUE,
                InternalUrlsHandler.parseTimestampSeconds("2147483648"));
        assertEquals(Integer.MAX_VALUE,
                InternalUrlsHandler.parseTimestampSeconds("99999999999"));
        // longer than any long, as an uploader can write in a description
        assertEquals(Integer.MAX_VALUE,
                InternalUrlsHandler.parseTimestampSeconds("9".repeat(400)));
        assertEquals(Integer.MAX_VALUE,
                InternalUrlsHandler.parseTimestampSeconds("000" + "1".repeat(30)));
    }

    @Test
    public void theClampedValueStillFitsThePlayersMillisecondSeek() {
        final int seconds = InternalUrlsHandler.parseTimestampSeconds("9".repeat(40));
        // Player.playFromTimestamp seeks to seconds * 1000L
        assertEquals(2_147_483_647_000L, seconds * 1000L);
    }

    @Test
    public void watchTimestampReplaysFullSlugThroughTheExtractor() throws Exception {
        try (RumbleSavedUrlTest.Fixture fixture = new RumbleSavedUrlTest.Fixture()) {
            final TimestampChangeData data = dispatch(RumbleSavedUrlTest.WATCH_URL);
            assertEquals(RumbleSavedUrlTest.WATCH_URL, data.getUrl());
            final StreamInfo replay = fixture.extractCold(data.getUrl());
            RumbleSavedUrlTest.assertPlayable(replay);
            assertEquals(RumbleSavedUrlTest.WATCH_URL, replay.getUrl());
        }
    }

    @Test
    public void decoratedShortTimestampRetainsShortPathAndExtractsCanonicalDetails()
            throws Exception {
        final String input = RumbleSavedUrlTest.SHORT_URL + "-decoration.html?source=test#section";
        try (RumbleSavedUrlTest.Fixture fixture = new RumbleSavedUrlTest.Fixture()) {
            final TimestampChangeData data = dispatch(input);
            assertEquals(input, data.getUrl());
            final StreamInfo replay = fixture.extractCold(data.getUrl());
            RumbleSavedUrlTest.assertPlayable(replay);
            assertEquals(RumbleSavedUrlTest.SHORT_URL, replay.getUrl());
        }
    }

    @Test
    public void embedTimestampRetainsEmbedPathAndPlaysWithoutDetails() throws Exception {
        try (RumbleSavedUrlTest.Fixture fixture = new RumbleSavedUrlTest.Fixture()) {
            final TimestampChangeData data = dispatch(RumbleSavedUrlTest.EMBED_URL);
            assertEquals(RumbleSavedUrlTest.EMBED_URL, data.getUrl());
            final StreamInfo replay = fixture.extractCold(data.getUrl());
            assertEquals("v7fh6ig", replay.getId());
            assertEquals("Durable Rumble video", replay.getName());
            assertEquals(RumbleSavedUrlTest.MEDIA_URL,
                    replay.getVideoStreams().get(0).getContent());
            assertEquals("https://rumble.com/embed/v7fh6ig", replay.getUrl());
        }
    }

    @Test
    public void nonRumbleTimestampStillNormalizesTheUrl() {
        final Application context = RuntimeEnvironment.getApplication();
        assertTrue(InternalUrlsHandler.playOnPopup(context,
                "https://youtu.be/9D5B5IBBS-w?t=90", ServiceList.YouTube, 90));
        final TimestampChangeData data = timestampData(context);
        assertEquals("https://www.youtube.com/watch?v=9D5B5IBBS-w", data.getUrl());
        assertEquals(ServiceList.YouTube.getServiceId(), data.getServiceId());
        assertEquals(90, data.getSeconds());
    }

    @Test
    public void invalidRumbleInputDoesNotStartThePlayerService() {
        final Application context = RuntimeEnvironment.getApplication();
        assertFalse(InternalUrlsHandler.playOnPopup(context,
                "https://notrumble.com/v7hnjfy-durable-video.html", ServiceList.Rumble, 90));
        assertNull(Shadows.shadowOf(context).getNextStartedService());
    }

    private static TimestampChangeData dispatch(final String url) {
        final Application context = RuntimeEnvironment.getApplication();
        assertTrue(InternalUrlsHandler.playOnPopup(context, url, ServiceList.Rumble, 90));
        final TimestampChangeData data = timestampData(context);
        assertEquals(ServiceList.Rumble.getServiceId(), data.getServiceId());
        assertEquals(90, data.getSeconds());
        return data;
    }

    private static TimestampChangeData timestampData(final Application context) {
        final Intent intent = Shadows.shadowOf(context).getNextStartedService();
        assertNotNull(intent);
        assertNotNull(intent.getComponent());
        assertEquals(PlayerService.class.getName(), intent.getComponent().getClassName());
        final TimestampChangeData data = IntentCompat.getParcelableExtra(
                intent, Player.PLAYER_INTENT_DATA, TimestampChangeData.class);
        assertNotNull(data);
        return data;
    }
}
