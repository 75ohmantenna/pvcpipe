package org.schabi.newpipe.extractor.services.youtube.extractors;

import com.grack.nanojson.JsonObject;
import com.grack.nanojson.JsonParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.schabi.newpipe.extractor.Extractor;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.exceptions.ParsingException;
import org.schabi.newpipe.extractor.localization.ContentCountry;
import org.schabi.newpipe.extractor.localization.DateWrapper;
import org.schabi.newpipe.extractor.localization.Localization;
import org.schabi.newpipe.extractor.stream.StreamType;

import javax.annotation.Nonnull;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.*;

@Tag("offline")
class YoutubeStreamExtractorUploadDateTest {
    private Downloader previousDownloader;
    private Localization previousLocalization;
    private ContentCountry previousCountry;

    @BeforeEach
    void setUp() {
        previousDownloader = NewPipe.getDownloader();
        previousLocalization = NewPipe.getPreferredLocalization();
        previousCountry = NewPipe.getPreferredContentCountry();
        NewPipe.init(new Downloader() {
            @Override
            public Response execute(@Nonnull final Request request) {
                throw new AssertionError("Upload-date tests must not make network requests");
            }
        });
    }

    @AfterEach
    void tearDown() {
        NewPipe.init(previousDownloader, previousLocalization, previousCountry);
    }

    @ParameterizedTest
    @CsvSource(value = {
            "youtubestreamextractorrelatedmix|9 Jul 2015|2015-07-09",
            "youtubestreamextractorcontroversial|9 Sept 2010|2010-09-09",
            "youtubestreamextractordefault/licenseddoctor|3 Jul 2022|2022-07-03",
            "youtubestreamextractordefault/publicbroadcaster|7 Jul 2023|2023-07-07"
    }, delimiter = '|')
    void parsesActualNextResponseWhenMicroformatDatesAreAbsent(
            final String fixture, final String text, final String expected) throws Exception {
        final String path = "/mocks/v1/org/schabi/newpipe/extractor/services/youtube/stream/"
                + fixture + "/generated_mock_8.json";
        final JsonObject response;
        try (InputStream input = getClass().getResourceAsStream(path)) {
            assertNotNull(input, path);
            response = JsonParser.object().from(input).getObject("response");
        }
        final YoutubeStreamExtractor extractor = extractor(new JsonObject(), null,
                StreamType.VIDEO_STREAM);
        set(extractor, YoutubeStreamExtractor.class, "nextResponse",
                JsonParser.object().from(response.getString("responseBody")));
        assertEquals(text, extractor.getTextualUploadDate());
        assertDate(extractor.getUploadDate(), expected);
    }

    @ParameterizedTest
    @CsvSource(value = {
            "Jul 9, 2015|2015-07-09", "Jul 09, 2015|2015-07-09",
            "Jul 19, 2015|2015-07-19", "9 Jul 2015|2015-07-09",
            "09 Jul 2015|2015-07-09", "19 Jul 2015|2015-07-19",
            "Sep 9, 2010|2010-09-09", "Sept 9, 2010|2010-09-09",
            "9 Sep 2010|2010-09-09", "9 Sept 2010|2010-09-09",
            "29 Feb 2020|2020-02-29", "Feb 29, 2000|2000-02-29",
            "Premiered on 9 Sept 2010|2010-09-09",
            "Premiered Feb 9, 2020|2020-02-09"
    }, delimiter = '|')
    void parsesEnglishAbsoluteFallback(final String text, final String expected) throws Exception {
        assertDate(extractor(new JsonObject(), text, StreamType.VIDEO_STREAM).getUploadDate(),
                expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"29 Feb 2019", "Feb 29, 1900", "31 Apr 2020", "Feb 30, 2020",
            "0 Jul 2015", "32 Jul 2015", "9 Jul 2015 trailing", "not a date"})
    void rejectsInvalidAbsoluteDates(final String text) throws Exception {
        final YoutubeStreamExtractor extractor = extractor(new JsonObject(), text,
                StreamType.VIDEO_STREAM);
        assertThrows(ParsingException.class, extractor::getUploadDate);
    }

    @Test
    void uploadTimestampRetainsPriorityOffsetAndPrecision() throws Exception {
        final JsonObject microformat = JsonParser.object().from("{"
                + "\"uploadDate\":\"2020-02-09T23:45:12-07:00\","
                + "\"publishDate\":\"2019-01-01T00:00:00Z\","
                + "\"liveBroadcastDetails\":{\"endTimestamp\":\"2018-01-01T00:00:00Z\"}}");
        final YoutubeStreamExtractor extractor = extractor(microformat, "not a date",
                StreamType.VIDEO_STREAM);
        assertEquals("2020-02-09T23:45:12-07:00", extractor.getTextualUploadDate());
        final DateWrapper result = extractor.getUploadDate();
        assertEquals(Instant.parse("2020-02-10T06:45:12Z"), result.getInstant());
        assertFalse(result.isApproximation());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"publishDate\":\"2020-02-09T00:00:00Z\"}",
            "{\"liveBroadcastDetails\":{\"endTimestamp\":\"2020-02-09T00:00:00Z\","
                    + "\"startTimestamp\":\"2019-01-01T00:00:00Z\"}}",
            "{\"liveBroadcastDetails\":{\"startTimestamp\":\"2020-02-09T00:00:00Z\"}}"
    })
    void retainsOtherMachineTimestampFallbacks(final String json) throws Exception {
        final YoutubeStreamExtractor extractor = extractor(JsonParser.object().from(json),
                "not a date", StreamType.LIVE_STREAM);
        assertEquals(Instant.parse("2020-02-09T00:00:00Z"), extractor.getUploadDate().getInstant());
    }

    @Test
    void missingDatesRemainNullIncludingLiveWithoutTimestamp() throws Exception {
        assertNull(extractor(new JsonObject(), null, StreamType.VIDEO_STREAM).getUploadDate());
        assertNull(extractor(new JsonObject(), "not a date", StreamType.LIVE_STREAM).getUploadDate());
    }

    @Test
    void relativePremiereRemainsRelative() throws Exception {
        final LocalDateTime before = LocalDateTime.now().minusHours(20).minusSeconds(1);
        final YoutubeStreamExtractor extractor = extractor(new JsonObject(),
                "Premiered 20 hours ago", StreamType.VIDEO_STREAM);
        assertEquals("20 hours ago", extractor.getTextualUploadDate());
        final DateWrapper result = extractor.getUploadDate();
        final LocalDateTime after = LocalDateTime.now().minusHours(20).plusSeconds(1);
        assertFalse(result.getLocalDateTime().isBefore(before));
        assertFalse(result.getLocalDateTime().isAfter(after));
        assertFalse(result.isApproximation());
    }

    private static void assertDate(final DateWrapper result, final String expected) {
        assertNotNull(result);
        assertEquals(LocalDate.parse(expected).atStartOfDay(ZoneId.systemDefault()).toInstant(),
                result.getInstant());
        assertTrue(result.isApproximation());
    }

    private static YoutubeStreamExtractor extractor(final JsonObject microformat,
                                                    final String dateText,
                                                    final StreamType streamType) throws Exception {
        final YoutubeStreamExtractor extractor = (YoutubeStreamExtractor) ServiceList.YouTube
                .getStreamExtractor("https://www.youtube.com/watch?v=fixtureVid1");
        set(extractor, Extractor.class, "pageFetched", true);
        set(extractor, YoutubeStreamExtractor.class, "streamType", streamType);
        set(extractor, YoutubeStreamExtractor.class, "playerMicroFormatRenderer", microformat);
        final JsonObject primary = new JsonObject();
        if (dateText != null) {
            final JsonObject text = new JsonObject();
            text.put("simpleText", dateText);
            primary.put("dateText", text);
        }
        // Actual fixture tests clear this cache and supply the full next-response below.
        set(extractor, YoutubeStreamExtractor.class, "videoPrimaryInfoRenderer", primary);
        if (dateText == null) {
            set(extractor, YoutubeStreamExtractor.class, "nextResponse", new JsonObject());
            set(extractor, YoutubeStreamExtractor.class, "videoPrimaryInfoRenderer", null);
        }
        return extractor;
    }

    private static void set(final Object target, final Class<?> owner, final String name,
                            final Object value) throws Exception {
        final Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
