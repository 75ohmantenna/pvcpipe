package org.schabi.newpipe.extractor.services;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.StreamingService;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.exceptions.ParsingException;
import org.schabi.newpipe.extractor.kiosk.KioskExtractor;
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler;
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandlerFactory;
import org.schabi.newpipe.extractor.localization.ContentCountry;
import org.schabi.newpipe.extractor.localization.Localization;

import java.net.URI;

import javax.annotation.Nonnull;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.schabi.newpipe.extractor.ServiceList.Bitchute;

/** URL identity tests deliberately construct kiosks without fetching a page. */
@Tag("offline")
class KioskUrlTest {
    private Downloader previousDownloader;
    private Localization previousLocalization;
    private ContentCountry previousCountry;

    @BeforeEach
    void forbidNetworkRequests() {
        previousDownloader = NewPipe.getDownloader();
        previousLocalization = NewPipe.getPreferredLocalization();
        previousCountry = NewPipe.getPreferredContentCountry();
        NewPipe.init(new Downloader() {
            @Override
            public Response execute(@Nonnull final Request request) {
                throw new AssertionError("Unexpected request: " + request.url());
            }
        });
    }

    @AfterEach
    void restoreExtractorConfiguration() {
        NewPipe.init(previousDownloader, previousLocalization, previousCountry);
    }

    @ParameterizedTest
    @CsvSource({
            "BitChute, Trending Today, https://www.bitchute.com/",
            "BitChute, Trending This Week, https://www.bitchute.com/#trending-week",
            "BitChute, Trending This Month, https://www.bitchute.com/#trending-month",
            "BitChute, Popular, https://www.bitchute.com/#Popular/",
            "BitChute, Suggested, https://www.bitchute.com/#Suggested/",
            "BitChute, live, https://www.bitchute.com/livenow",
            "Rumble, Live, https://rumble.com/browse/live",
            "Rumble, Editor Picks, https://rumble.com/editor-picks",
            "Rumble, Latest, https://rumble.com/videos?date=this-week",
            "Rumble, Today's Battle Leaderboard Top 50, https://rumble.com/battle-leaderboard",
            "Rumble, Trending Today, https://rumble.com/videos?sort=views&date=today"
    })
    void kioskConstructedByIdRetainsCanonicalUrl(final String serviceName, final String id,
                                                final String expectedUrl) throws Exception {
        final StreamingService service = NewPipe.getService(serviceName);
        final KioskExtractor<?> extractor = service.getKioskList().getExtractorById(id, null);

        assertEquals(id, extractor.getId());
        assertEquals(service.getServiceId(), extractor.getServiceId());
        assertEquals(expectedUrl, extractor.getUrl());
        // Construction by ID supplies a canonical URL rather than an original user input.
        assertEquals(expectedUrl, extractor.getOriginalUrl());
        assertEquals("https", URI.create(extractor.getUrl()).getScheme());

        final KioskExtractor<?> roundTrip = service.getKioskList()
                .getExtractorByUrl(expectedUrl, null);
        assertEquals(id, roundTrip.getId());
        assertEquals(expectedUrl, roundTrip.getUrl());
    }

    @ParameterizedTest
    @CsvSource({
            "https://www.bitchute.com/#trending-day, Trending Today, https://www.bitchute.com/",
            "https://www.bitchute.com/#trending-week/, Trending This Week, https://www.bitchute.com/#trending-week",
            "https://www.bitchute.com/#trending-month/, Trending This Month, https://www.bitchute.com/#trending-month",
            "https://www.bitchute.com/#Trending This Week/, Trending This Week, https://www.bitchute.com/#trending-week",
            "https://www.bitchute.com/#Trending This Month/, Trending This Month, https://www.bitchute.com/#trending-month"
    })
    void bitchuteAliasesKeepInputAndResolveRegisteredId(final String originalUrl,
                                                       final String id,
                                                       final String expectedUrl) throws Exception {
        final ListLinkHandlerFactory factory = Bitchute.getKioskList()
                .getListLinkHandlerFactoryByType(id);
        final ListLinkHandler handler = factory.fromUrl(originalUrl);
        assertEquals(originalUrl, handler.getOriginalUrl());
        assertEquals(id, handler.getId());
        assertEquals(expectedUrl, handler.getUrl());
        assertEquals(id, Bitchute.getKioskList().getExtractorByUrl(originalUrl, null).getId());
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://www.bitchute.com/not-a-kiosk",
            "https://www.bitchute.com/#", "https://www.bitchute.com/#/"})
    void bitchuteMissingCategoryFailsWithParsingException(final String url) {
        assertThrows(ParsingException.class, () -> Bitchute.getKioskList()
                .getListLinkHandlerFactoryByType("Trending Today").fromUrl(url));
    }
}
