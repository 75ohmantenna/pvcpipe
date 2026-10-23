package org.schabi.newpipe.fragments.list.search;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import org.junit.Test;
import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.ListExtractor.InfoItemsPage;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.Page;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.StreamingService;
import org.schabi.newpipe.extractor.linkhandler.SearchQueryHandler;
import org.schabi.newpipe.extractor.linkhandler.SearchQueryHandlerFactory;
import org.schabi.newpipe.extractor.search.SearchExtractor;
import org.schabi.newpipe.util.ExtractorHelper;

import java.util.List;
import java.util.Map;

/** Real app helper and SearchInfo dispatch; only the service/extractor peer is controlled. */
public class SearchPaginationExtractionTest {
    @Test
    public void extractionReceivesTheOriginalPageAndCookies() throws Exception {
        final Map<String, String> cookies = Map.of("synthetic_session",
                "PVC_SYNTHETIC_PAGE_COOKIE_86f243e9");
        final Page page = new Page("https://example.invalid/page/2", cookies);
        final StreamingService service = mock(StreamingService.class);
        final SearchQueryHandlerFactory factory = mock(SearchQueryHandlerFactory.class);
        final SearchQueryHandler query = mock(SearchQueryHandler.class);
        final SearchExtractor extractor = mock(SearchExtractor.class);
        final InfoItemsPage<InfoItem> result = new InfoItemsPage<>(List.of(), null, List.of());
        when(service.getSearchQHFactory()).thenReturn(factory);
        when(factory.fromQuery("synthetic query", List.of(), List.of())).thenReturn(query);
        when(service.getSearchExtractor(query)).thenReturn(extractor);
        when(extractor.getPage(any(Page.class))).thenAnswer(call -> {
            final Page received = call.getArgument(0);
            assertSame(page, received);
            assertSame(cookies, received.getCookies());
            assertEquals("PVC_SYNTHETIC_PAGE_COOKIE_86f243e9",
                    received.getCookies().get("synthetic_session"));
            return result;
        });
        try (var services = mockStatic(NewPipe.class)) {
            services.when(() -> NewPipe.getService(ServiceList.YouTube.getServiceId()))
                    .thenReturn(service);
            assertSame(result, ExtractorHelper.getMoreSearchItems(
                    ServiceList.YouTube.getServiceId(), "synthetic query",
                    List.of(), List.of(), page).blockingGet());
        }
    }
}
