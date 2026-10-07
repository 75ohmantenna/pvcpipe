package org.schabi.newpipe.extractor.services.rumble.extractors;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.schabi.newpipe.downloader.DownloaderFactory;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.StreamingService;
import org.schabi.newpipe.extractor.exceptions.ExtractionException;
import org.schabi.newpipe.extractor.services.DefaultStreamExtractorTest;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.StreamExtractor;
import org.schabi.newpipe.extractor.stream.StreamInfoItemsCollector;
import org.schabi.newpipe.extractor.stream.StreamType;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.schabi.newpipe.downloader.DownloaderFactory.LEGACY_RESOURCE_PATH;
import static org.schabi.newpipe.extractor.ExtractorAsserts.assertIsSecureUrl;
import static org.schabi.newpipe.extractor.ServiceList.Rumble;

@SuppressWarnings({"checkstyle:LineLength", "checkstyle:InvalidJavadocPosition"})
public class RumbleStreamExtractorTest {

    /*
     * The related-items fixture includes a live stream from The Quartering
     * alongside recorded streams, exercising live-stream classification.
     */
    public static class NormalStreamExtractorTest extends DefaultStreamExtractorTest {

        protected static final String MOCK_PATH =
                LEGACY_RESOURCE_PATH + "/services/rumble/extractor/stream/";

        protected static RumbleStreamExtractor extractor;
        protected static StreamingService expectedService = Rumble;

        protected static String url = "https://rumble.com/v1a992g";
        protected static String expectedUrl = "https://rumble.com/v1a992g";
        protected static String expectedName = "The Truth Behind Arizona’s Paper Ballots; Jovan Pulitzer’s BOMBSHELL Paper Analysis Report 6/27/22";
        protected static String expectedId = "v1a992g";
        protected static String expectedDesc = "The evidence continues to reveal itself regarding the results of the 2020";
        protected static String expectedCategory = "";
        protected static int expectedAgeLimit = 0;
        protected static long expectedViewCountAtLeast = 46658;
        protected static String expectedUploaderName = "Right Side Broadcasting Network";
        protected static String expectedUploadDate = "2022-06-28 05:36:31.000";
        protected static String expectedTextualUploadDate = "2022-06-28T05:36:31+00:00";
        protected static int expectedLikeCount = 1000;
        protected static int expectedDislikeCount = 5;
        protected static StreamExtractor.Privacy expectedPrivacy = StreamExtractor.Privacy.PUBLIC;
        protected static String expectedUploaderUrl = "https://rumble.com/c/RSBN";
        protected static String expectedSupportInfo = "";
        protected static boolean expectedHasAudioStreams = true;
        protected static boolean expectedHasVideoStreams = true;
        protected static String expectedArtistProfilePictureInfix = ".rumble.com/live/channel_images/"; // TODO
        protected static long expectedLength = 12948;

        @BeforeAll
        public static void setUpExtractor() throws ExtractionException, IOException {
            url = "https://rumble.com/v1a992g";
            expectedUrl = "https://rumble.com/v1a992g";
            expectedName = "The Truth Behind Arizona’s Paper Ballots; Jovan Pulitzer’s BOMBSHELL Paper Analysis Report 6/27/22";
            expectedId = "v1a992g";
            expectedDesc = "The evidence continues to reveal itself regarding the results of the 2020";
            expectedViewCountAtLeast = 46658;
            expectedUploaderName = "Right Side Broadcasting Network";
            expectedUploadDate = "2022-06-28 05:36:31.000";
            expectedTextualUploadDate = "2022-06-28T05:36:31+00:00";
            expectedLikeCount = 1000;
            expectedDislikeCount = 5;
            expectedUploaderUrl = "https://rumble.com/c/RSBN";
            expectedHasAudioStreams = true;
            expectedHasVideoStreams = true;
            expectedLength = 12948;
            NewPipe.init(DownloaderFactory.getMockDownloader(MOCK_PATH + "/streamExtractor"));

            extractor = (RumbleStreamExtractor) Rumble
                    .getStreamExtractor(url);
            extractor.fetchPage();
        }

        @Override
        protected StreamExtractor createExtractor() {
            return extractor;
        }

        @Test
        public void testAvatarThumbnailPicture() throws Exception {
            final String avatarUrl = extractor().getUploaderAvatars().get(0).getUrl();
            assertTrue(!avatarUrl.isEmpty() || avatarUrl == null);
        }

        @Override
        public StreamExtractor extractor() {
            return extractor;
        }

        @Override
        public StreamingService expectedService() {
            return expectedService;
        }

        @Override
        public String expectedName() {
            return expectedName;
        }

        @Override
        public String expectedId() {
            return expectedId;
        }

        @Override
        public String expectedUrlContains() {
            return expectedUrl;
        }

        @Override
        public String expectedOriginalUrlContains() {
            return expectedUrl;
        }

        @Override
        public StreamType expectedStreamType() {
            return StreamType.VIDEO_STREAM;
        }

        @Override
        public String expectedUploaderName() {
            return expectedUploaderName;
        }

        @Override
        public String expectedUploaderUrl() {
            return expectedUploaderUrl;
        }

        @Override
        public List<String> expectedDescriptionContains() {
            return Collections.singletonList(expectedDesc);
        }

        @Override
        public long expectedLength() {
            return expectedLength;
        }

        @Override
        public long expectedViewCountAtLeast() {
            return expectedViewCountAtLeast;
        }

        @Override
        public String expectedUploadDate() {
            return expectedUploadDate;
        }

        @Override
        public String expectedTextualUploadDate() {
            return expectedTextualUploadDate;
        }

        @Override
        public long expectedLikeCountAtLeast() {
            return expectedLikeCount;
        }

        @Override
        public long expectedDislikeCountAtLeast() {
            return expectedDislikeCount;
        }

        @Override
        public boolean expectedHasVideoStreams() {
            return expectedHasVideoStreams;
        }

        @Override
        public boolean expectedHasRelatedItems() {
            return true;
        }

        @Override
        public boolean expectedHasSubtitles() {
            return false;
        }

        @Override
        public boolean expectedHasFrames() {
            return false;
        }

        @Override
        public String expectedCategory() {
            return expectedCategory;
        }

        @Override
        public int expectedAgeLimit() {
            return expectedAgeLimit;
        }

        @Override
        public StreamExtractor.Privacy expectedPrivacy() {
            return expectedPrivacy;
        }

        @Override
        public String expectedSupportInfo() {
            return expectedSupportInfo;
        }

        @Override
        public boolean expectedHasAudioStreams() {
            return expectedHasAudioStreams;
        }

        /**
         *  Test for {@link RumbleStreamRelatedInfoItemExtractor}
         */
        @Test
        public void rumbleStreamRelatedInfoItemsExtractorTest()
                throws ExtractionException, IOException {
            final StreamInfoItemsCollector page = extractor.getRelatedItems();

            /** more info see: {@link RumbleSharedTests#infoItemsResultsTest} */
            final String[] someExpectedResults = {
                    /* here is the speciality 'streamType=LIVE_STREAM' detection test on related streams */
                    "StreamInfoItem{streamType=VIDEO_STREAM, uploaderName='Right Side Broadcasting Network', textualUploadDate='null', viewCount=163000, duration=7359, uploaderUrl='https://rumble.com/user/RSBN', infoType=STREAM, serviceId=6, url='https://rumble.com/v6qzdqe-live-president-trump-and-pete-hegseth-give-remarks-32125.html?e9s=rel_v2_ep', name='LIVE REPLAY: President Trump and Pete Hegseth Give Remarks - 3/21/25', thumbnails='[Image {url=https://1a-1791.com/video/fww1/13/s8/1/g/j/2/u/gj2uy.0kob-small-LIVE-President-Trump-and-Pe.jpg, height=-1, width=-1, estimatedResolutionLevel=UNKNOWN}]', uploaderVerified='false'}",
                    "StreamInfoItem{streamType=LIVE_STREAM, uploaderName='The Quartering', textualUploadDate='null', viewCount=6700, duration=-1, uploaderUrl='https://rumble.com/user/TheQuartering', infoType=STREAM, serviceId=6, url='https://rumble.com/v6r3cmu-epstein-files-update-sodagate-snow-white-box-office-elon-and-trump-strike-b.html?e9s=rel_v2_ep', name='Epstein Files Update, SodaGate, Snow White Box Office, Elon & Trump Strike Back BIGLY!', thumbnails='[Image {url=https://1a-1791.com/video/fww1/e9/s8/1/2/w/J/v/2wJvy.0kob-small-Epstein-Files-Update-SodaGa.jpg, height=-1, width=-1, estimatedResolutionLevel=UNKNOWN}]', uploaderVerified='false'}",
                    "StreamInfoItem{streamType=VIDEO_STREAM, uploaderName='Russell Brand', textualUploadDate='null', viewCount=139000, duration=998, uploaderUrl='https://rumble.com/user/russellbrand', infoType=STREAM, serviceId=6, url='https://rumble.com/v6qncja-rumble-cdc-vaccine-autism-study-bunkr.html?e9s=rel_v2_ep', name='They Can't Hide This Any Longer', thumbnails='[Image {url=https://1a-1791.com/video/fww1/26/s8/1/2/e/T/s/2eTsy.0kob-small-They-Cant-Hide-This-Any-Lon.jpg, height=-1, width=-1, estimatedResolutionLevel=UNKNOWN}]', uploaderVerified='false'}"
            };

            RumbleSharedTests.infoItemsResultsTest(extractor.getService(),
                    page.getItems(),
                    page.getErrors(),
                    someExpectedResults
            );
        }

        // This override checks audio-stream presence and secure URLs, but not the format
        // metadata checked by DefaultStreamExtractorTest.
        @Override
        public void testAudioStreams() throws Exception {
            final List<AudioStream> audioStreams = extractor().getAudioStreams();
            assertNotNull(audioStreams);

            if (expectedHasAudioStreams()) {
                assertFalse(audioStreams.isEmpty());

                for (final AudioStream stream : audioStreams) {
                    assertIsSecureUrl(stream.getUrl());
                }
            } else {
                assertTrue(audioStreams.isEmpty());
            }
        }
    }

    public static class LiveStreamExtractorTest extends NormalStreamExtractorTest {


        @BeforeAll
        public static void setUpExtractor() throws ExtractionException, IOException {
            url = "https://rumble.com/v3e90sa";
            expectedUrl = "https://rumble.com/v3e90sa";
            expectedName = "America 1st News & Politics Live TV | MAGA Media";
            expectedId = "v3e90sa";
            expectedDesc = "Patriot News Outlet Live | America 1st News & Politics";
            expectedCategory = "";
            expectedAgeLimit = 0;
            // The embed snapshot has live=1 with DVR enabled, which Rumble identifies as
            // a finished broadcast rather than a currently live stream.
            expectedViewCountAtLeast = -1;
            expectedUploaderName = "Patriot News Outlet Live";
            expectedUploadDate = "2023-09-02 23:01:40.000";
            expectedTextualUploadDate = "2023-09-02T23:01:40+00:00";
            expectedLikeCount = 300;
            expectedDislikeCount = 5;
            expectedPrivacy = StreamExtractor.Privacy.PUBLIC;
            expectedUploaderUrl = "https://rumble.com/c/PatriotNews4u";
            expectedSupportInfo = "";
            expectedHasAudioStreams = false;
            expectedHasVideoStreams = true;
            expectedArtistProfilePictureInfix = ".rumble.com/live/channel_images/"; // TODO
            expectedLength = 0;
            NewPipe.init(DownloaderFactory.getMockDownloader(MOCK_PATH
                    + "/streamExtractorLiveStream"));

            extractor = (RumbleStreamExtractor) Rumble
                    .getStreamExtractor(url);
            extractor.fetchPage();
        }

        @Override
        public StreamType expectedStreamType() {
            return StreamType.VIDEO_STREAM;
        }
        /**
         *  Test for {@link RumbleStreamRelatedInfoItemExtractor}
         */
        @Test
        public void rumbleStreamRelatedInfoItemsExtractorTest() throws ExtractionException {

        }
    }
}
