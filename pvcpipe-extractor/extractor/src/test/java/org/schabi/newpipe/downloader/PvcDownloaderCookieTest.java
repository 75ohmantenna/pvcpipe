package org.schabi.newpipe.downloader;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import okhttp3.Cookie;
import okhttp3.CookieJar;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("offline")
class PvcDownloaderCookieTest {
    @Test
    void testDownloaderRejectsCookiesForUnrelatedDomains() {
        final OkHttpClient.Builder builder = new OkHttpClient.Builder();
        PvcDownloaderTestImplUtils.addCookieManager(builder);
        final CookieJar jar = builder.build().cookieJar();
        jar.saveFromResponse(HttpUrl.get("https://rumble.com/"), List.of(
                new Cookie.Builder().name("session").value("planted").domain("youtube.com")
                        .path("/").build()));
        assertTrue(jar.loadForRequest(HttpUrl.get("https://youtube.com/")).isEmpty());
    }

    @Test
    void validRumbleDomainCookiesStillReachRumbleSubdomains() {
        final OkHttpClient.Builder builder = new OkHttpClient.Builder();
        PvcDownloaderTestImplUtils.addCookieManager(builder);
        final CookieJar jar = builder.build().cookieJar();
        jar.saveFromResponse(HttpUrl.get("https://www.rumble.com/"), List.of(
                new Cookie.Builder().name("session").value("valid").domain("rumble.com")
                        .path("/").build()));
        assertEquals("valid", jar.loadForRequest(HttpUrl.get("https://wn0.rumble.com/"))
                .get(0).value());
    }
}
