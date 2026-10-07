package org.schabi.newpipe.extractor.services.youtube.extractors;

import com.grack.nanojson.JsonParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.exceptions.AgeRestrictedContentException;
import org.schabi.newpipe.extractor.exceptions.ExtractionException;
import org.schabi.newpipe.extractor.exceptions.GeographicRestrictionException;
import org.schabi.newpipe.extractor.exceptions.PrivateContentException;
import org.schabi.newpipe.extractor.exceptions.SignInConfirmNotBotException;
import org.schabi.newpipe.extractor.localization.ContentCountry;
import org.schabi.newpipe.extractor.localization.Localization;
import org.schabi.newpipe.extractor.services.youtube.YoutubeJavaScriptPlayerManager;
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("offline")
class YoutubeStreamExtractorClientFallbackTest {
    private static final String ID = "fixtureVid1";
    private static final String OTHER_ID = "anotherVid1";
    private Downloader previousDownloader;
    private Localization previousLocalization;
    private ContentCountry previousCountry;
    private Object previousProvider;
    private boolean previousIos;
    private Object previousSignatureTimestamp;
    private Object previousClientVersion;
    private Object previousClientVersionExtracted;

    @BeforeEach
    void setUp() throws Exception {
        previousDownloader = NewPipe.getDownloader();
        previousLocalization = NewPipe.getPreferredLocalization();
        previousCountry = NewPipe.getPreferredContentCountry();
        previousProvider = field(YoutubeStreamExtractor.class, "poTokenProvider").get(null);
        previousIos = (boolean) field(YoutubeStreamExtractor.class, "fetchIosClient").get(null);
        previousSignatureTimestamp = field(YoutubeJavaScriptPlayerManager.class,
                "cachedSignatureTimestamp").get(null);
        previousClientVersion = field(YoutubeParsingHelper.class, "clientVersion").get(null);
        previousClientVersionExtracted = field(YoutubeParsingHelper.class,
                "clientVersionExtracted").get(null);
        YoutubeStreamExtractor.setPoTokenProvider(null);
        YoutubeStreamExtractor.setFetchIosClient(false);
        field(YoutubeJavaScriptPlayerManager.class, "cachedSignatureTimestamp").set(null, 12345);
        field(YoutubeParsingHelper.class, "clientVersion").set(null, "2.20261001.00.00");
        field(YoutubeParsingHelper.class, "clientVersionExtracted").set(null, true);
    }

    @AfterEach
    void tearDown() throws Exception {
        field(YoutubeStreamExtractor.class, "poTokenProvider").set(null, previousProvider);
        YoutubeStreamExtractor.setFetchIosClient(previousIos);
        field(YoutubeJavaScriptPlayerManager.class,
                "cachedSignatureTimestamp").set(null, previousSignatureTimestamp);
        field(YoutubeParsingHelper.class, "clientVersion").set(null, previousClientVersion);
        field(YoutubeParsingHelper.class, "clientVersionExtracted")
                .set(null, previousClientVersionExtracted);
        NewPipe.init(previousDownloader, previousLocalization, previousCountry);
    }

    @Test
    void androidRequestFailureFallsBackToVisionOsFormatsAndMetadata() throws Exception {
        final PlayerFixtures fixtures = new PlayerFixtures();
        fixtures.vision = playable(ID, "Vision title", "vision");
        final YoutubeStreamExtractor extractor = fetch(fixtures);

        assertEquals("Vision title", extractor.getName());
        assertEquals(1, extractor.getVideoStreams().size());
        assertTrue(extractor.getVideoStreams().get(0).getContent().contains("/vision?"));
        assertEquals(1, fixtures.androidRequests);
        assertEquals(1, fixtures.visionRequests);
    }

    @Test
    void wrongIdAndroidResponseDoesNotContaminateVisionOsFormats() throws Exception {
        final PlayerFixtures fixtures = new PlayerFixtures();
        fixtures.android = playable(OTHER_ID, "Wrong video", "wrong");
        fixtures.vision = playable(ID, "Right video", "vision");
        final YoutubeStreamExtractor extractor = fetch(fixtures);

        assertEquals("Right video", extractor.getName());
        assertEquals(1, extractor.getVideoStreams().size());
        assertTrue(extractor.getVideoStreams().get(0).getContent().contains("/vision?"));
    }

    @Test
    void rejectedVisionOsResponseDoesNotReplaceValidAndroidMetadata() throws Exception {
        final PlayerFixtures fixtures = new PlayerFixtures();
        fixtures.android = playable(ID, "Android title", "android");
        fixtures.vision = playable(OTHER_ID, "Wrong video", "wrong");
        final YoutubeStreamExtractor extractor = fetch(fixtures);

        assertEquals("Android title", extractor.getName());
        assertEquals(1, extractor.getVideoStreams().size());
        assertTrue(extractor.getVideoStreams().get(0).getContent().contains("/android?"));
    }

    @Test
    void visionOsUnplayableStatusDoesNotReplaceValidAndroidMetadata() throws Exception {
        final PlayerFixtures fixtures = new PlayerFixtures();
        fixtures.android = playable(ID, "Android title", "android");
        fixtures.vision = playable(ID, "Unplayable vision", "vision")
                .replace("\"status\":\"OK\"", "\"status\":\"UNPLAYABLE\","
                        + "\"reason\":\"Video unavailable\"");
        final YoutubeStreamExtractor extractor = fetch(fixtures);

        assertEquals("Android title", extractor.getName());
        assertEquals(1, extractor.getVideoStreams().size());
        assertTrue(extractor.getVideoStreams().get(0).getContent().contains("/android?"));
    }

    @Test
    void wrongIdWebMetadataLeavesVisionOsThumbnailsIntact() throws Exception {
        final PlayerFixtures fixtures = new PlayerFixtures();
        fixtures.vision = playable(ID, "Vision title", "vision");
        fixtures.metadataId = OTHER_ID;
        final YoutubeStreamExtractor extractor = fetch(fixtures);

        assertEquals("Vision title", extractor.getName());
        assertEquals(1, extractor.getThumbnails().size());
        assertEquals("https://example.com/thumb.jpg", extractor.getThumbnails().get(0).getUrl());
    }

    @Test
    void visionOsAdaptiveFormatsProvideDurationWithoutAndroidVideoDetails() throws Exception {
        final PlayerFixtures fixtures = new PlayerFixtures();
        fixtures.vision = playable(ID, "Vision title", "vision")
                .replace("\"formats\":[{\"itag\":18,\"url\":\"https://example.com/vision?foo=1\"}]",
                        "\"adaptiveFormats\":[{\"itag\":140,"
                                + "\"url\":\"https://example.com/audio?foo=1\","
                                + "\"audioSampleRate\":\"44100\",\"approxDurationMs\":\"12500\"}]");
        final YoutubeStreamExtractor extractor = fetch(fixtures);

        assertEquals(13, extractor.getLength());
        assertEquals(1, extractor.getAudioStreams().size());
    }

    @Test
    void allRequestsFailKeepsTheFirstTransportFailureAndSuppressesTheOther() throws Exception {
        final PlayerFixtures fixtures = new PlayerFixtures();
        final IOException failure = assertThrows(IOException.class, () -> fetch(fixtures));

        assertEquals("ANDROID fixture unavailable", failure.getMessage());
        assertEquals(1, failure.getSuppressed().length);
        assertEquals("VISIONOS fixture unavailable", failure.getSuppressed()[0].getMessage());
        assertEquals(0, fixtures.metadataRequests);
    }

    @Test
    void geoRestrictionIsNotReplacedByAnotherClientsGenericFailure() throws Exception {
        final PlayerFixtures fixtures = new PlayerFixtures();
        fixtures.android = unavailable("UNPLAYABLE", "This video is not available in your country");
        fixtures.vision = unavailable("UNPLAYABLE", "Video unavailable");

        assertThrows(GeographicRestrictionException.class, () -> fetch(fixtures));
    }

    @Test
    void privateRestrictionIsNotReplacedByWrongIdResponse() throws Exception {
        final PlayerFixtures fixtures = new PlayerFixtures();
        fixtures.android = unavailable("LOGIN_REQUIRED", "This video is private");
        fixtures.vision = playable(OTHER_ID, "Wrong video", "wrong");

        assertThrows(PrivateContentException.class, () -> fetch(fixtures));
    }

    @Test
    void botVerificationIsNotDowngradedToGenericUnavailability() throws Exception {
        final PlayerFixtures fixtures = new PlayerFixtures();
        fixtures.android = unavailable("LOGIN_REQUIRED",
                "Sign in to confirm you're not a bot");
        fixtures.vision = unavailable("UNPLAYABLE", "Video unavailable");

        assertThrows(SignInConfirmNotBotException.class, () -> fetch(fixtures));
    }

    @Test
    void restrictionFromVisionOsOutranksAndroidTransportFailure() throws Exception {
        final PlayerFixtures fixtures = new PlayerFixtures();
        fixtures.vision = unavailable("UNPLAYABLE", "This video is not available in your country");

        final GeographicRestrictionException failure = assertThrows(
                GeographicRestrictionException.class, () -> fetch(fixtures));
        assertEquals("ANDROID fixture unavailable", failure.getSuppressed()[0].getMessage());
    }

    @Test
    void ageRestrictedAndroidResponseUsesPlayableEmbeddedResponse() throws Exception {
        final PlayerFixtures fixtures = new PlayerFixtures();
        fixtures.android = unavailable("LOGIN_REQUIRED", "This video is inappropriate for some users");
        fixtures.embed = playable(ID, "Embedded title", "embedded");
        final YoutubeStreamExtractor extractor = fetch(fixtures);

        assertEquals("Embedded title", extractor.getName());
        assertEquals(1, extractor.getVideoStreams().size());
        assertTrue(extractor.getVideoStreams().get(0).getContent().contains("/embedded?"));
        assertEquals(1, fixtures.embedRequests);
    }

    @Test
    void ageRestrictedAndroidCanFallBackToVisionOsWhenEmbedFails() throws Exception {
        final PlayerFixtures fixtures = new PlayerFixtures();
        fixtures.android = unavailable("LOGIN_REQUIRED", "Sign in to confirm your age");
        fixtures.embed = playable(OTHER_ID, "Wrong video", "wrong");
        fixtures.vision = playable(ID, "Vision title", "vision");
        final YoutubeStreamExtractor extractor = fetch(fixtures);

        assertEquals("Vision title", extractor.getName());
        assertEquals(1, fixtures.embedRequests);
        assertTrue(extractor.getVideoStreams().get(0).getContent().contains("/vision?"));
    }

    @Test
    void ageRestrictionSurvivesFailedEmbedAndVisionOsRequests() throws Exception {
        final PlayerFixtures fixtures = new PlayerFixtures();
        fixtures.android = unavailable("LOGIN_REQUIRED", "This video is inappropriate for some users");
        fixtures.embed = playable(OTHER_ID, "Wrong video", "wrong");

        final AgeRestrictedContentException failure = assertThrows(
                AgeRestrictedContentException.class, () -> fetch(fixtures));
        assertEquals(2, failure.getSuppressed().length);
        assertEquals(1, fixtures.embedRequests);
    }

    @Test
    void unusableStatusAndMissingFormatsCannotProvideFallbackMetadata() throws Exception {
        final PlayerFixtures fixtures = new PlayerFixtures();
        fixtures.android = unavailable("UNPLAYABLE", "Video unavailable");
        fixtures.vision = playable(ID, "No streams", "vision")
                .replace("\"formats\":[{\"itag\":18,\"url\":\"https://example.com/vision?foo=1\"}]",
                        "\"formats\":[]");

        final ExtractionException failure = assertThrows(ExtractionException.class,
                () -> fetch(fixtures));
        assertTrue(failure.getMessage().contains("Video unavailable"));
        assertEquals(0, fixtures.metadataRequests);
    }

    @Test
    void unsupportedFormatsCannotOverrideUnavailableContent() throws Exception {
        final PlayerFixtures fixtures = new PlayerFixtures();
        fixtures.android = unavailable("UNPLAYABLE", "Video unavailable");
        fixtures.vision = playable(ID, "Unusable streams", "vision")
                .replace("\"itag\":18", "\"itag\":999999");

        final ExtractionException failure = assertThrows(ExtractionException.class,
                () -> fetch(fixtures));
        assertTrue(failure.getMessage().contains("Video unavailable"));
        assertEquals(0, fixtures.metadataRequests);
    }

    private static YoutubeStreamExtractor fetch(final PlayerFixtures fixtures) throws Exception {
        NewPipe.init(fixtures);
        final YoutubeStreamExtractor extractor = (YoutubeStreamExtractor) ServiceList.YouTube
                .getStreamExtractor("https://www.youtube.com/watch?v=" + ID);
        extractor.fetchPage();
        return extractor;
    }

    private static String playable(final String id, final String title, final String stream) {
        return "{\"playabilityStatus\":{\"status\":\"OK\"},"
                + "\"videoDetails\":{\"videoId\":\"" + id + "\",\"title\":\"" + title
                + "\",\"thumbnail\":{\"thumbnails\":[{\"url\":\"https://example.com/thumb.jpg\"}]}},"
                + "\"streamingData\":{\"formats\":[{\"itag\":18,\"url\":\"https://example.com/"
                + stream + "?foo=1\"}]}}";
    }

    private static String unavailable(final String status, final String reason) {
        return "{\"playabilityStatus\":{\"status\":\"" + status + "\",\"reason\":\""
                + reason + "\"}}";
    }

    private static Field field(final Class<?> owner, final String name) throws Exception {
        final Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static final class PlayerFixtures extends Downloader {
        private String android;
        private String vision;
        private String embed;
        private String metadataId = ID;
        private int androidRequests;
        private int visionRequests;
        private int embedRequests;
        private int metadataRequests;

        @Override
        public Response execute(@Nonnull final Request request) throws IOException {
            final String url = request.url();
            if (url.contains("/guide?")) {
                return response(request, "x".repeat(5001));
            }
            if (url.contains("/visitor_id?")) {
                return response(request, "{\"responseContext\":{\"visitorData\":\"offline-visitor\"}}");
            }
            if (url.contains("/reel/reel_item_watch?")) {
                androidRequests++;
                if (android == null) {
                    throw new IOException("ANDROID fixture unavailable");
                }
                return response(request, "{\"playerResponse\":" + android + "}");
            }
            if (url.contains("/player?") && url.contains("$fields=")) {
                metadataRequests++;
                return response(request, "{\"videoDetails\":{\"videoId\":\"" + metadataId
                        + "\",\"thumbnail\":{\"thumbnails\":[{\"url\":\"https://example.com/web-thumb.jpg\"}]}},"
                        + "\"microformat\":{\"playerMicroformatRenderer\":{}}}");
            }
            if (url.contains("/player?")) {
                final String name;
                try {
                    name = JsonParser.object()
                            .from(new String(request.dataToSend(), StandardCharsets.UTF_8))
                            .getObject("context").getObject("client").getString("clientName");
                } catch (final Exception e) {
                    throw new IOException("Could not parse fixture request", e);
                }
                if ("VISIONOS".equals(name)) {
                    visionRequests++;
                    if (vision == null) {
                        throw new IOException("VISIONOS fixture unavailable");
                    }
                    return response(request, vision);
                }
                if ("WEB_EMBEDDED_PLAYER".equals(name)) {
                    embedRequests++;
                    if (embed == null) {
                        throw new IOException("EMBED fixture unavailable");
                    }
                    return response(request, embed);
                }
                throw new IOException("Unexpected player client " + name);
            }
            if (url.contains("/next?")) {
                return response(request,
                        "{\"contents\":{\"twoColumnWatchNextResults\":{\"results\":{\"results\":"
                                + "{\"contents\":[]}}}}}");
            }
            throw new IOException("Unexpected fixture request: " + url);
        }

        private static Response response(final Request request, final String body) {
            return new Response(200, "OK", Map.of(), body, request.url());
        }
    }
}
