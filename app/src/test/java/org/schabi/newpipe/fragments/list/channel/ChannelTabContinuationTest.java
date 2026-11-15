package org.schabi.newpipe.fragments.list.channel;

import android.app.Application;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.Page;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo;
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabs;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.linkhandler.ReadyChannelTabListLinkHandler;
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper;
import org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeChannelTabPlaylistExtractor;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;

import java.util.Map;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Real continuation parser and dispatcher; only HTTP execution/client headers are controlled. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, application = Application.class, sdk = 26)
public class ChannelTabContinuationTest {
    private static final String ID = "UC1234567890123456789012";
    private static final String RESPONSE = """
            {"onResponseReceivedActions":[{"appendContinuationItemsAction":{
              "continuationItems":[{"playlistVideoRenderer":{
                "videoId":"abcdefghijk", "isPlayable":true,
                "title":{"simpleText":"Fixture video"},
                "lengthText":{"simpleText":"1:00"},
                "shortBylineText":{"runs":[{"text":"Fixture creator",
                  "navigationEndpoint":{"browseEndpoint":{
                    "browseId":"UC1234567890123456789012"}}}]},
                "thumbnail":{"thumbnails":[{"url":"https://fixture.invalid/image.jpg"}]}
              }}]}}]}
            """;

    @Test
    public void freshSystemPlaylistContinuationReturnsItemsWithOneRequest() throws Exception {
        final Downloader downloader = mock(Downloader.class, CALLS_REAL_METHODS);
        NewPipe.init(downloader);
        when(downloader.execute(any())).thenAnswer(call -> {
            final org.schabi.newpipe.extractor.downloader.Request request = call.getArgument(0);
            assertEquals("POST", request.httpMethod());
            assertTrue(request.url().contains("/browse?"));
            return new Response(200, "OK", Map.of("Content-Type", List.of("application/json")),
                    RESPONSE, request.url());
        });
        try (MockedStatic<YoutubeParsingHelper> parsing =
                     mockStatic(YoutubeParsingHelper.class, call -> {
                         if (call.getMethod().getName().equals("getYouTubeHeaders")) {
                             return Map.of();
                         }
                         return call.callRealMethod();
                     })) {
            for (final var tab : List.of(ChannelTabs.VIDEOS, ChannelTabs.SHORTS,
                    ChannelTabs.LIVESTREAMS)) {
                final var handler = new ReadyChannelTabListLinkHandler(
                        "https://www.youtube.com/channel/" + ID, ID, tab,
                        YoutubeChannelTabPlaylistExtractor::new);
                final var page = ChannelTabInfo.getMoreItems(ServiceList.YouTube,
                        handler.getRestorationHandler(), new Page("https://fixture.invalid/page",
                                "false", new byte[]{'{', '}'}));
                assertEquals(1, page.getItems().size());
                final StreamInfoItem item = (StreamInfoItem) page.getItems().get(0);
                assertEquals("Fixture video", item.getName());
                assertEquals("https://www.youtube.com/watch?v=abcdefghijk", item.getUrl());
            }
        }
        // Exactly one continuation per fresh extractor.
        verify(downloader, times(3)).execute(any());
    }

    @Test
    public void initialMissingPlaylistStillEmptyAndInvalidContinuationRejected() throws Exception {
        final Downloader downloader = mock(Downloader.class, CALLS_REAL_METHODS);
        NewPipe.init(downloader);
        final var handler = new ReadyChannelTabListLinkHandler(
                "https://www.youtube.com/channel/" + ID, ID, ChannelTabs.VIDEOS,
                YoutubeChannelTabPlaylistExtractor::new);
        final var extractor = ServiceList.YouTube.getChannelTabExtractor(handler);
        assertTrue(extractor.getInitialPage().getItems().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> extractor.getPage(null));
        verifyNoInteractions(downloader);
    }
}
