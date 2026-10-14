package org.schabi.newpipe.extractor.services.odysee;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.schabi.newpipe.extractor.NewPipe;
import java.io.InterruptedIOException;
import static org.junit.jupiter.api.Assertions.*;

@Tag("offline")
class OdyseeRecommendationsTest {
    private OdyseeFakeDownloader downloader;
    private static final String QUERY = "s=example&related_to=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    @BeforeEach
    void setUp() {
        downloader = new OdyseeFakeDownloader();
        NewPipe.init(downloader);
    }

    @Test
    void requestsAnonymousFreeRecommendations() throws Exception {
        downloader.on(OdyseeApi.RELATED_URL, "[{\"claimId\":\"example\"}]");
        assertEquals(1, OdyseeApi.related(QUERY).size());
        assertEquals(OdyseeApi.RELATED_URL + "?" + QUERY + "&free_only=true",
                downloader.requests.get(0).url());
        assertEquals(1, downloader.requests.size());
    }

    @Test
    void malformedResponseFallsBackToExistingSearch() throws Exception {
        downloader.on(OdyseeApi.RELATED_URL, "not json").on(OdyseeApi.SEARCH_URL, "[]");
        assertTrue(OdyseeApi.related(QUERY).isEmpty());
        assertEquals(2, downloader.requests.size());
    }

    @Test
    void cancellationDoesNotStartFallback() {
        downloader.onFailure(OdyseeApi.RELATED_URL, InterruptedIOException::new);
        assertThrows(InterruptedIOException.class, () -> OdyseeApi.related(QUERY));
        assertEquals(1, downloader.requests.size());
    }
}
