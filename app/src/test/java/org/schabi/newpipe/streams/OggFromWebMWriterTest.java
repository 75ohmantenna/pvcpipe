package org.schabi.newpipe.streams;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.schabi.newpipe.extractor.localization.DateWrapper;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.StreamType;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;

public class OggFromWebMWriterTest {
    private static StreamInfo info() {
        return new StreamInfo(0, "https://example.test/watch?v=1", "https://example.test/1",
                StreamType.AUDIO_STREAM, "1", "A title", 0);
    }

    private static Map<String, String> tags(final StreamInfo info) {
        final Map<String, String> tags = new LinkedHashMap<>();
        OggFromWebMWriter.forEachTag(info, tags::put);
        return tags;
    }

    @Test
    public void aStreamWithoutUploadDateStillGetsItsOtherTags() {
        // StreamInfo leaves the upload date unset when the extractor could not read it.
        final Map<String, String> tags = tags(info());
        assertEquals("https://example.test/watch?v=1", tags.get("COMMENT"));
        assertEquals("A title", tags.get("TITLE"));
        assertTrue(!tags.containsKey("DATE"));
        assertTrue(!tags.containsValue(null));
    }

    @Test
    public void everyKnownValueBecomesATag() {
        final StreamInfo info = info();
        info.setCategory("Music");
        info.setUploaderName("Someone");
        final DateWrapper uploaded = new DateWrapper(
                OffsetDateTime.of(2026, 10, 13, 12, 0, 0, 0, ZoneOffset.UTC));
        info.setUploadDate(uploaded);
        // the date is written in the device's time zone
        final String date = uploaded.getLocalDateTime().toLocalDate().toString();
        assertEquals(Map.of("COMMENT", "https://example.test/watch?v=1", "GENRE", "Music",
                "ARTIST", "Someone", "TITLE", "A title", "DATE", date), tags(info));
    }

    @Test
    public void noStreamNoTags() {
        assertTrue(tags(null).isEmpty());
    }
}
