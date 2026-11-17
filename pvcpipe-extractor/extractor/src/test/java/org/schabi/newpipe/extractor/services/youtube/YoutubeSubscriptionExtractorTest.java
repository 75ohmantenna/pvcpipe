package org.schabi.newpipe.extractor.services.youtube;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.schabi.newpipe.FileUtils.resolveTestResource;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.schabi.newpipe.extractor.InitNewPipeTest;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.linkhandler.LinkHandlerFactory;
import org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeSubscriptionExtractor;
import org.schabi.newpipe.extractor.subscription.SubscriptionExtractor;
import org.schabi.newpipe.extractor.subscription.SubscriptionItem;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Test for {@link YoutubeSubscriptionExtractor}
 */
@Tag("offline")
class YoutubeSubscriptionExtractorTest {

    private static YoutubeSubscriptionExtractor subscriptionExtractor;
    private static LinkHandlerFactory urlHandler;

    @BeforeAll
    public static void setupClass() {
        InitNewPipeTest.initEmpty();
        subscriptionExtractor = new YoutubeSubscriptionExtractor(ServiceList.YouTube);
        urlHandler = ServiceList.YouTube.getChannelLHFactory();
    }

    @Test
    void testFromInputStream() throws Exception {
        final List<SubscriptionItem> subscriptionItems = subscriptionExtractor.fromInputStream(
                new FileInputStream(resolveTestResource("youtube_takeout_import_test.json")));
        assertEquals(7, subscriptionItems.size());

        for (final SubscriptionItem item : subscriptionItems) {
            assertNotNull(item.getName());
            assertNotNull(item.getUrl());
            assertTrue(urlHandler.acceptUrl(item.getUrl()));
            assertEquals(ServiceList.YouTube.getServiceId(), item.getServiceId());
        }
    }

    @Test
    void testEmptySourceException() throws Exception {
        final List<SubscriptionItem> items = subscriptionExtractor.fromInputStream(
                new ByteArrayInputStream("[]".getBytes(StandardCharsets.UTF_8)));
        assertTrue(items.isEmpty());
    }

    @Test
    void testSubscriptionWithEmptyTitleInSource() throws Exception {
        final String source = "[{\"snippet\":{\"resourceId\":{\"channelId\":\"UCEOXxzW2vU0P-0THehuIIeg\"}}}]";
        final List<SubscriptionItem> items = subscriptionExtractor.fromInputStream(
                new ByteArrayInputStream(source.getBytes(StandardCharsets.UTF_8)));

        assertEquals(1, items.size());
        assertEquals(ServiceList.YouTube.getServiceId(), items.get(0).getServiceId());
        assertEquals("https://www.youtube.com/channel/UCEOXxzW2vU0P-0THehuIIeg", items.get(0).getUrl());
        assertEquals("", items.get(0).getName());
    }

    @Test
    void testSubscriptionWithInvalidUrlInSource() throws Exception {
        final String source = "[{\"snippet\":{\"resourceId\":{\"channelId\":\"gibberish\"},\"title\":\"name1\"}}," +
                "{\"snippet\":{\"resourceId\":{\"channelId\":\"UCEOXxzW2vU0P-0THehuIIeg\"},\"title\":\"name2\"}}]";
        final List<SubscriptionItem> items = subscriptionExtractor.fromInputStream(
                new ByteArrayInputStream(source.getBytes(StandardCharsets.UTF_8)));

        assertEquals(1, items.size());
        assertEquals(ServiceList.YouTube.getServiceId(), items.get(0).getServiceId());
        assertEquals("https://www.youtube.com/channel/UCEOXxzW2vU0P-0THehuIIeg", items.get(0).getUrl());
        assertEquals("name2", items.get(0).getName());
    }

    @Test
    void testInvalidSourceException() {
        final List<String> invalidList = Arrays.asList(
                "<xml><notvalid></notvalid></xml>",
                "<opml><notvalid></notvalid></opml>",
                "{\"a\":\"b\"}",
                "[{}]",
                "[\"\", 5]",
                "[{\"snippet\":{\"title\":\"name\"}}]",
                "[{\"snippet\":{\"resourceId\":{\"channelId\":\"gibberish\"}}}]",
                "",
                "\uD83D\uDC28\uD83D\uDC28\uD83D\uDC28",
                "gibberish");

        for (final String invalidContent : invalidList) {
            try {
                final byte[] bytes = invalidContent.getBytes(StandardCharsets.UTF_8);
                subscriptionExtractor.fromInputStream(new ByteArrayInputStream(bytes));
                fail("Extracting from \"" + invalidContent + "\" didn't throw an exception");
            } catch (final Exception e) {
                final boolean correctType =
                    e instanceof SubscriptionExtractor.InvalidSourceException;
                if (!correctType) {
                    e.printStackTrace();
                }
                assertTrue(correctType, e.getClass().getSimpleName() + " is not InvalidSourceException");
            }
        }
    }

    private static void assertSubscriptionItems(final List<SubscriptionItem> subscriptionItems)
            throws Exception {
        assertTrue(!subscriptionItems.isEmpty());

        for (final SubscriptionItem item : subscriptionItems) {
            assertNotNull(item.getName());
            assertNotNull(item.getUrl());
            assertTrue(urlHandler.acceptUrl(item.getUrl()));
            assertEquals(ServiceList.YouTube.getServiceId(), item.getServiceId());
        }
    }

    @Test
    void fromZipInputStream() throws Exception {
        final List<String> zipPaths = Arrays.asList(
                "youtube_takeout_import_test_1.zip",
                "youtube_takeout_import_test_2.zip"
        );

        for (final String path : zipPaths)
        {
            final File file = resolveTestResource(path);
            final FileInputStream fileInputStream = new FileInputStream(file);
            final List<SubscriptionItem> subscriptionItems = subscriptionExtractor.fromZipInputStream(fileInputStream);
            assertSubscriptionItems(subscriptionItems);
        }
    }

    @Test
    void zipFindsSubscriptionsAfterReadableUnrelatedCsv() throws Exception {
        final byte[] archive = makeZip("channel,url,title\nignored,https://example.test,Ignored");
        final int[] closeCount = {0};
        final var input = new ByteArrayInputStream(archive) {
            @Override
            public void close() {
                closeCount[0]++;
            }
        };
        final var subscriptions = subscriptionExtractor.fromInputStream(input, "application/zip");
        assertEquals(List.of("Fixture"), subscriptions.stream().map(SubscriptionItem::getName).toList());
        assertEquals(1, closeCount[0]);
    }

    @Test
    void zipReportsReadFailureBeforeTryingOtherEntries() throws Exception {
        final byte[] complete = makeZip("x".repeat(3000));
        final int localHeaderLength = 30;
        final int filenameLength = "other.csv".length();
        // Keep the local header and one compressed byte so the read fails inside the CSV parser.
        final var input = new ByteArrayInputStream(
                Arrays.copyOf(complete, localHeaderLength + filenameLength + 1));
        final var failure = assertThrows(SubscriptionExtractor.InvalidSourceException.class,
                () -> subscriptionExtractor.fromInputStream(input, "zip"));
        assertInstanceOf(IOException.class, failure.getCause());
    }

    private static byte[] makeZip(final String unrelated) throws IOException {
        final var bytes = new ByteArrayOutputStream();
        try (var archive = new ZipOutputStream(bytes)) {
            archive.putNextEntry(new ZipEntry("other.csv"));
            archive.write(unrelated.getBytes(StandardCharsets.UTF_8));
            archive.closeEntry();
            archive.putNextEntry(new ZipEntry("Takeout/SUBSCRIPTIONS.CSV"));
            archive.write(("Channel Id,Channel Url,Channel Title\nUC1JTQBa5QxZCpXrFSkMxmPw,"
                    + "http://www.youtube.com/channel/UC1JTQBa5QxZCpXrFSkMxmPw,Fixture\n")
                    .getBytes(StandardCharsets.UTF_8));
            archive.closeEntry();
        }
        return bytes.toByteArray();
    }

    @Test
    void fromCsvInputStream() throws Exception {
        final List<String> csvPaths = Arrays.asList(
                "youtube_takeout_import_test_1.csv",
                "youtube_takeout_import_test_2.csv"
        );

        for (final String path : csvPaths)
        {
            final File file = resolveTestResource(path);
            final FileInputStream fileInputStream = new FileInputStream(file);
            final List<SubscriptionItem> subscriptionItems = subscriptionExtractor.fromCsvInputStream(fileInputStream);
            assertSubscriptionItems(subscriptionItems);
        }
    }
}
