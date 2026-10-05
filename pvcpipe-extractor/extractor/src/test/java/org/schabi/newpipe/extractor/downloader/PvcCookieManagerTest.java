package org.schabi.newpipe.extractor.downloader;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("offline")
class PvcCookieManagerTest {
    @Test
    void acceptsCookiesForRumbleAndItsSubdomains() throws Exception {
        for (final String host : List.of("rumble.com", "www.rumble.com", "wn0.rumble.com",
                "WWW.RUMBLE.COM")) {
            final PvcCookieManager manager = new PvcCookieManager();
            final URI uri = URI.create("https://" + host + "/");
            manager.put(uri, Map.of("Set-Cookie", List.of("session=secret; Path=/")));
            assertEquals(1, manager.getCookieStore().getCookies().size(), host);
            assertTrue(manager.get(uri, Map.of()).get("Cookie").stream()
                    .anyMatch(cookie -> cookie.contains("session=secret")), host);
        }
    }

    @Test
    void ignoresUnrelatedHostsAndHostlessUris() throws Exception {
        for (final String url : List.of("https://notrumble.com/", "https://rumble.com.example.org/",
                "https://rumble.example.org/", "https://example.org/rumble.com", "file:///tmp")) {
            final PvcCookieManager manager = new PvcCookieManager();
            manager.put(URI.create(url), Map.of("Set-Cookie", List.of("session=secret; Path=/")));
            assertTrue(manager.getCookieStore().getCookies().isEmpty(), url);
        }
    }
}
