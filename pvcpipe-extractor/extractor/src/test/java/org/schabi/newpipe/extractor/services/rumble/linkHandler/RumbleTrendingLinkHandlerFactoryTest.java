package org.schabi.newpipe.extractor.services.rumble.linkHandler;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.schabi.newpipe.downloader.DownloaderTestImpl;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.exceptions.ParsingException;
import org.schabi.newpipe.extractor.linkhandler.LinkHandlerFactory;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.schabi.newpipe.extractor.ServiceList.Rumble;

/**
 * Test for {@link RumbleTrendingLinkHandlerFactory}
 */

@Tag("offline")
public class RumbleTrendingLinkHandlerFactoryTest {
    private static LinkHandlerFactory linkHandlerFactory;

    private static final String LIVE_KEY = "https://rumble.com/browse/live";
    private static final String BATTLE_LEADERBOARD_KEY = "https://rumble.com/battle-leaderboard";


    private static final Map<String, String> expectedTrendingUrl2IdMap = Map.of(
            LIVE_KEY, "Live",
            "https://rumble.com/editor-picks", "Editor Picks",
            BATTLE_LEADERBOARD_KEY, "Today's Battle Leaderboard Top 50",
            "https://rumble.com/videos?sort=views&date=today", "Trending Today",
            "https://rumble.com/videos?date=this-week", "Latest");

    @BeforeAll
    public static void setUp() throws Exception {
        // get the one and only instance. All 'trending' thingies use the same.
        linkHandlerFactory = Rumble.getKioskList().getListLinkHandlerFactoryByType(
                expectedTrendingUrl2IdMap.get(LIVE_KEY));
        NewPipe.init(DownloaderTestImpl.getInstance());
    }

    @Test
    public void getUrl() throws Exception {
        // assume TODAYS_BATTLE_LEADERBOARD_TOP_50 is set as DEFAULT_TRENDING
        // in RumbleTrendingLinkHandlerFactory
        assertEquals(BATTLE_LEADERBOARD_KEY, linkHandlerFactory.fromId("").getUrl());
    }

    @Test
    public void getId() throws Exception {

        for (final Map.Entry<String, String> entry : expectedTrendingUrl2IdMap.entrySet()) {
            assertEquals(linkHandlerFactory.fromUrl(entry.getKey()).getId(), entry.getValue());
            assertThrows(ParsingException.class,
                    () -> linkHandlerFactory.fromUrl(entry.getValue()).getId());
        }
    }

    @Test
    public void acceptUrl() throws ParsingException {

        for (final Map.Entry<String, String> entry : expectedTrendingUrl2IdMap.entrySet()) {
            assertTrue(linkHandlerFactory.acceptUrl(entry.getKey()));
            assertFalse(linkHandlerFactory.acceptUrl(entry.getValue()));
        }

        assertFalse(linkHandlerFactory.acceptUrl("https://famous.com/feed/trending"));
        assertFalse(linkHandlerFactory.acceptUrl("nope"));
        assertFalse(linkHandlerFactory.acceptUrl("https://www.rumble.com/take/abc"));
        assertFalse(linkHandlerFactory.acceptUrl("    rumble.com/trending"));
        assertFalse(linkHandlerFactory.acceptUrl(""));
    }
}
