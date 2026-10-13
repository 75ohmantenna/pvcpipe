package org.schabi.newpipe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class PvcHostReplacementsTest {
    private static Map<String, String> parse(final String... entries) {
        return PvcDownloaderImplUtils.parseHostReplacements(Set.of(entries));
    }

    @Test
    public void theEntryOfTheSettingsScreenIsRead() {
        assertEquals(Map.of("yt3.ggpht.com", "yt4.ggpht.com"),
                parse("yt3.ggpht.com:yt4.ggpht.com"));
    }

    @Test
    public void anEntryAnImportBrokeIsLeftOut() {
        // each of these threw ArrayIndexOutOfBoundsException, or a later exception in
        // HttpUrl.Builder.host, while the app was starting
        assertTrue(parse("broken").isEmpty());
        assertTrue(parse("old:").isEmpty());
        assertTrue(parse(":new.example").isEmpty());
        assertTrue(parse(":").isEmpty());
        assertTrue(parse("").isEmpty());
        assertTrue(parse("old.example:new.example:8080").isEmpty());
        assertTrue(parse("old.example:new example").isEmpty());
        assertTrue(parse("old.example:new.example/path").isEmpty());
    }

    @Test
    public void aBrokenEntryDoesNotTakeTheOthersWithIt() {
        assertEquals(Map.of("a.example", "b.example"),
                parse("broken", "a.example:b.example", "old:"));
    }

    @Test
    public void noEntriesNoReplacements() {
        assertTrue(PvcDownloaderImplUtils.parseHostReplacements(null).isEmpty());
        final Set<String> withNull = new HashSet<>();
        withNull.add(null);
        assertTrue(PvcDownloaderImplUtils.parseHostReplacements(withNull).isEmpty());
    }
}
