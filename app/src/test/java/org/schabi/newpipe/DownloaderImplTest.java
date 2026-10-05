package org.schabi.newpipe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.schabi.newpipe.error.ReCaptchaActivity;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.Proxy;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

import okhttp3.Cookie;
import okhttp3.CookieJar;
import okhttp3.HttpUrl;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class DownloaderImplTest {
    private final DownloaderImpl downloader = DownloaderImpl.getInstance();
    private String previousRestrictedCookie;
    private String previousCaptchaCookie;

    @Before
    public void setUp() {
        previousRestrictedCookie = downloader.getCookie(
                DownloaderImpl.YOUTUBE_RESTRICTED_MODE_COOKIE_KEY);
        previousCaptchaCookie = downloader.getCookie(ReCaptchaActivity.RECAPTCHA_COOKIES_KEY);
        downloader.setCookie(DownloaderImpl.YOUTUBE_RESTRICTED_MODE_COOKIE_KEY, "PREF=f2=8000000");
        downloader.setCookie(ReCaptchaActivity.RECAPTCHA_COOKIES_KEY,
                "captcha=secret; PREF=f2=8000000");
    }

    @After
    public void tearDown() {
        downloader.setCookie(DownloaderImpl.YOUTUBE_RESTRICTED_MODE_COOKIE_KEY,
                previousRestrictedCookie);
        downloader.setCookie(ReCaptchaActivity.RECAPTCHA_COOKIES_KEY, previousCaptchaCookie);
    }

    @Test
    public void youtubeAndSubdomainsKeepCaptchaAndRestrictedModeCookies() {
        for (final String url : new String[]{"https://youtube.com/watch?v=id",
                "https://www.youtube.com/watch?v=id", "https://m.youtube.com/",
                "https://music.youtube.com/", "https://WWW.YOUTUBE.COM/"}) {
            assertEquals(url, "PREF=f2=8000000; captcha=secret", downloader.getCookies(url));
        }
    }

    @Test
    public void otherServicesAndLookalikeUrlsDoNotReceiveYoutubeCookies() {
        for (final String url : new String[]{"https://rumble.com/", "https://soundcloud.com/",
                "https://youtube.com.example.org/", "https://notyoutube.com/",
                "https://example.org/youtube.com", "https://example.org/?url=youtube.com",
                "https://youtube.com@example.org/", "not a URL"}) {
            assertEquals(url, "", downloader.getCookies(url));
        }
    }

    @Test
    public void plainHttpYoutubeRequestsDoNotReceiveSavedCookies() throws Exception {
        assertEquals("", downloader.getCookies("http://youtube.com/"));
        assertEquals("", downloader.getCookies("http://www.youtube.com/"));
        final Interceptor.Chain chain = mock(Interceptor.Chain.class);
        final Request request = new Request.Builder().url("http://youtube.com/").build();
        when(chain.request()).thenReturn(request);
        new DownloaderImpl.YoutubeCookieInterceptor().intercept(chain);
        verify(chain).proceed(request);
    }

    @Test
    public void rumbleCannotPlantCookiesForAnotherService() {
        final OkHttpClient.Builder builder = new OkHttpClient.Builder();
        PvcDownloaderImplUtils.addCookieManager(builder);
        final CookieJar jar = builder.build().cookieJar();
        jar.saveFromResponse(HttpUrl.get("https://rumble.com/"), Collections.singletonList(
                new Cookie.Builder().name("session").value("planted").domain("youtube.com")
                        .path("/").build()));
        assertTrue(jar.loadForRequest(HttpUrl.get("https://youtube.com/")).isEmpty());
    }

    @Test
    public void youtubeStillWorksWithoutSavedCookies() {
        downloader.removeCookie(DownloaderImpl.YOUTUBE_RESTRICTED_MODE_COOKIE_KEY);
        downloader.removeCookie(ReCaptchaActivity.RECAPTCHA_COOKIES_KEY);
        assertEquals("", downloader.getCookies("https://youtube.com/"));
    }

    @Test
    public void nullCookiesRemainSupportedWhenRestoringPreferences() {
        downloader.setCookie(DownloaderImpl.YOUTUBE_RESTRICTED_MODE_COOKIE_KEY, null);
        assertEquals("captcha=secret; PREF=f2=8000000",
                downloader.getCookies("https://youtube.com/"));
        downloader.setCookie(ReCaptchaActivity.RECAPTCHA_COOKIES_KEY, null);
        assertEquals("", downloader.getCookies("https://youtube.com/"));
    }

    @Test
    public void crossHostRedirectDoesNotForwardSavedYoutubeCookies() throws Exception {
        // This self-signed fixture is trusted only by this local test client.
        final KeyStore keys = KeyStore.getInstance("PKCS12");
        try (InputStream input = getClass().getResourceAsStream("/cookie-test.p12")) {
            assertNotNull(input);
            keys.load(input, "test-only".toCharArray());
        }
        final KeyManagerFactory keyManagers = KeyManagerFactory.getInstance(
                KeyManagerFactory.getDefaultAlgorithm());
        keyManagers.init(keys, "test-only".toCharArray());
        final TrustManagerFactory trustManagers = TrustManagerFactory.getInstance(
                TrustManagerFactory.getDefaultAlgorithm());
        trustManagers.init(keys);
        final X509TrustManager trustManager =
                (X509TrustManager) trustManagers.getTrustManagers()[0];
        final SSLContext tls = SSLContext.getInstance("TLS");
        tls.init(keyManagers.getKeyManagers(), trustManagers.getTrustManagers(), null);
        final ExecutorService serverExecutor = Executors.newSingleThreadExecutor();
        try (ServerSocket server = tls.getServerSocketFactory().createServerSocket(
                0, 2, InetAddress.getLoopbackAddress())) {
            server.setSoTimeout(5000);
            final Future<List<String>> receivedCookies = serverExecutor.submit(
                    () -> serveRedirect(server));
            final OkHttpClient client = new OkHttpClient.Builder()
                    .proxy(Proxy.NO_PROXY)
                    .sslSocketFactory(tls.getSocketFactory(), trustManager)
                    .dns(host -> Collections.singletonList(InetAddress.getLoopbackAddress()))
                    .callTimeout(10, TimeUnit.SECONDS)
                    .addNetworkInterceptor(new DownloaderImpl.YoutubeCookieInterceptor()).build();
            final Request request = new Request.Builder()
                    .url("https://youtube.com:" + server.getLocalPort() + "/").build();
            try (Response response = client.newCall(request).execute()) {
                assertEquals(200, response.code());
                assertEquals("OK", response.body().string());
            }
            final List<String> cookies = receivedCookies.get(10, TimeUnit.SECONDS);
            assertEquals("PREF=f2=8000000; captcha=secret", cookies.get(0));
            assertEquals(null, cookies.get(1));
        } finally {
            serverExecutor.shutdownNow();
        }
    }

    @Test
    public void explicitCookieHeadersKeepPrecedence() throws Exception {
        final Interceptor.Chain chain = mock(Interceptor.Chain.class);
        final Request request = new Request.Builder().url("https://www.youtube.com/")
                .header("Cookie", "PREF=custom").build();
        when(chain.request()).thenReturn(request);
        new DownloaderImpl.YoutubeCookieInterceptor().intercept(chain);
        verify(chain).proceed(request);
    }

    private static List<String> serveRedirect(final ServerSocket server) throws Exception {
        final List<String> cookies = new ArrayList<>();
        for (int hop = 0; hop < 2; hop++) {
            try (Socket socket = server.accept()) {
                socket.setSoTimeout(5000);
                final BufferedReader reader = new BufferedReader(new InputStreamReader(
                        socket.getInputStream(), StandardCharsets.UTF_8));
                String cookie = null;
                String line;
                while ((line = reader.readLine()) != null && !line.isEmpty()) {
                    if (line.regionMatches(true, 0, "Cookie: ", 0, 8)) {
                        cookie = line.substring(8);
                    }
                }
                cookies.add(cookie);
                final String response = hop == 0
                        ? "HTTP/1.1 302 Found\r\nLocation: https://example.org:"
                        + server.getLocalPort() + "/\r\nContent-Length: 0\r\n"
                        + "Connection: close\r\n\r\n"
                        : "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nOK";
                socket.getOutputStream().write(response.getBytes(StandardCharsets.UTF_8));
            }
        }
        return cookies;
    }
}
