package org.schabi.newpipe.extractor.services.bitchute;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("offline")
class BitchuteCommentsCacheTest {

    @Test
    void getsFreshAuthFromApiWithoutFetchingWebsite() throws Exception {
        final String id = "cache-test-video";
        final String videoUrl = "https://www.bitchute.com/video/" + id + "/";
        final CommentsDownloader downloader = new CommentsDownloader(videoUrl);
        NewPipe.init(downloader);

        BitchuteParserHelper.getComments(id, videoUrl, 0);
        BitchuteParserHelper.getComments(id, videoUrl, 20);

        assertEquals(0, downloader.videoPageRequests);
        assertEquals(2, downloader.authRequests);
        assertEquals(2, downloader.commentsRequests);
        assertTrue(downloader.allCommentsRequestsUsedToken);
    }

    @Test
    void boundsAuthTokenCache() {
        final String prefix = "cache-bound-test-";
        for (int i = 0; i <= 64; i++) {
            assertTrue(BitchuteParserHelper.extractAndStoreCfAuth(
                    prefix + i, "{cf_auth: 'token-" + i + "'}"));
        }

        assertNull(BitchuteParserHelper.getCfAuth(prefix + 0));
        assertEquals("token-64", BitchuteParserHelper.getCfAuth(prefix + 64));
    }

    private static final class CommentsDownloader extends Downloader {
        private final String videoUrl;
        private int videoPageRequests;
        private int commentsRequests;
        private int authRequests;
        private boolean allCommentsRequestsUsedToken = true;

        private CommentsDownloader(final String videoUrl) {
            this.videoUrl = videoUrl;
        }

        @Override
        public Response execute(@Nonnull final Request request)
                throws IOException, ReCaptchaException {
            if (request.url().endsWith("/apps/commentfreely/video/")) {
                authRequests++;
                return response(request, "{\"auth\":\"cached-token\"}", Map.of());
            }
            if (videoUrl.equals(request.url())) {
                videoPageRequests++;
                return response(request, "<script>{cf_auth: 'cached-token'}</script>", Map.of());
            }
            if (request.url().contains("/api/get_comments/")) {
                commentsRequests++;
                final String payload = new String(request.dataToSend(), StandardCharsets.UTF_8);
                allCommentsRequestsUsedToken &= payload.contains("cf_auth=cached-token");
                return response(request, "[]", Map.of());
            }
            return response(request, "", Map.of(
                    "Set-Cookie", List.of("csrftoken=test-token; Path=/")));
        }

        private static Response response(final Request request,
                                         final String body,
                                         final Map<String, List<String>> headers) {
            return new Response(200, "OK", headers, body, request.url());
        }
    }
}
