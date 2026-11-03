package org.schabi.newpipe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.os.Build;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.schabi.newpipe.error.ReCaptchaActivity;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException;
import org.schabi.newpipe.extractor.services.kick.KickApi;

import java.io.BufferedReader;
import java.io.IOException;
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
import java.util.Map;
import java.util.TreeMap;
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
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

public class DownloaderImplTest {
    private static final String EXPECTED_RUMBLE_USER_AGENT =
            "Rumble-AndroidTV/2.11.0.10198 (" + Build.MODEL + "; Android "
                    + Build.VERSION.RELEASE + " " + Build.VERSION.SDK_INT + ") okhttp/5.1.0";

    @Test
    public void bitChuteSeedRequestsUseCompatibleMediaIdentity() {
        for (final String url : List.of("https://seed131b.bitchute.com/channel/video.mp4",
                "https://SEED125.BITCHUTE.COM/video.m3u8",
                "https://seedp29xb.bitchute.com/channel/video.mp4")) {
            assertTrue(DownloaderImpl.getMediaUserAgent(url).contains("Firefox/128.0"));
        }
    }

    @Test
    public void mediaIdentityDoesNotChangeOtherWebsiteOrUnapprovedRumbleRequests() {
        for (final String url : List.of("https://api.bitchute.com/api/beta/video/media",
                "https://www.bitchute.com/video/id",
                "https://seed131b.bitchute.com.evil.example/video.mp4",
                "https://example.org/seed131b.bitchute.com/video.mp4",
                "http://rumble.com/video.mp4", "http://www.rumble.com/video.mp4",
                "http://hugh.cdn.rumble.cloud/video.mp4",
                "https://rumble.com.evil.example/video.mp4", "https://notrumble.com/video.mp4",
                "https://rumble.cloud/video.mp4", "https://other.cdn.rumble.cloud/video.mp4",
                "https://sub.hugh.cdn.rumble.cloud/video.mp4",
                "https://hugh.cdn.rumble.cloud.evil.example/video.mp4",
                "https://example.org/rumble.com/video.mp4?url=https://rumble.com",
                "https://rumble.com@example.org/video.mp4", "not a URL")) {
            assertEquals(DownloaderImpl.USER_AGENT, DownloaderImpl.getMediaUserAgent(url));
        }
    }

    @Test
    public void approvedHttpsRumbleHostsUseTheOfficialTvIdentity() {
        for (final String url : List.of("https://rumble.com/service.php?name=media.details",
                "https://WWW.RUMBLE.COM/embedJS/u3/", "https://media.edge.rumble.com/file.mp4",
                "https://HUGH.CDN.RUMBLE.CLOUD/file.m3u8")) {
            assertEquals(url, EXPECTED_RUMBLE_USER_AGENT, DownloaderImpl.getMediaUserAgent(url));
        }
    }

    @Test
    public void odyseeMediaRequestsNameTheWebsiteAsReferrer() {
        for (final String url : List.of("https://cloud.odysee.live/content/id/master.m3u8",
                "https://CLOUD.ODYSEE.LIVE/content/id/1080_1.ts",
                "https://odysee.live/content/id/master.m3u8",
                "https://player.odycdn.com/v6/streams/id/file.mp4",
                "https://player.odycdn.com/v6/streams/id/hash/v0_s000001.ts")) {
            assertEquals(url, "https://odysee.com/", DownloaderImpl.getMediaReferer(url));
        }
    }

    @Test
    public void mediaReferrerIsNotSentToOtherHosts() {
        for (final String url : List.of("https://odysee.com/@channel:1/video:2",
                "https://api.na-backend.odysee.com/api/v1/proxy", "https://rumble.com/video.mp4",
                "https://cloud.odysee.live.evil.example/master.m3u8",
                "https://notodysee.live/master.m3u8", "https://notodycdn.com/file.mp4",
                "https://example.org/player.odycdn.com/file.mp4", "not a URL")) {
            assertNull(url, DownloaderImpl.getMediaReferer(url));
            assertFalse(url, DownloaderImpl.isHeadUnusable(url));
        }
    }

    @Test
    public void onlyUploadedFilesOnOdyseeFileHostAreNeverAskedWithHead() {
        assertTrue(DownloaderImpl.isHeadUnusable(
                "https://player.odycdn.com/v6/streams/id/file.mp4"));
        assertTrue(DownloaderImpl.isHeadUnusable("https://PLAYER.ODYCDN.COM/file.mp4?x=1"));
        // the HLS renditions on the same host answer HEAD as any file does
        assertFalse(DownloaderImpl.isHeadUnusable(
                "https://player.odycdn.com/v6/streams/id/hash/v0_s000001.ts"));
        assertFalse(DownloaderImpl.isHeadUnusable(
                "https://player.odycdn.com/v6/streams/id/hash/master.M3U8"));
        // the livestream host has no files to ask the size of
        assertFalse(DownloaderImpl.isHeadUnusable(
                "https://cloud.odysee.live/content/id/master.m3u8"));
    }

    @Test
    public void fallbackSizeProbeKeepsBitChuteMediaIdentity() throws Exception {
        final OkHttpClient client = new OkHttpClient.Builder().addInterceptor(chain -> {
            final Request request = chain.request();
            assertTrue(request.header("User-Agent").contains("Firefox/128.0"));
            assertEquals("bytes=0-0", request.header("Range"));
            assertEquals("identity", request.header("Accept-Encoding"));
            return new Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                    .code(206).message("Partial Content").header("Content-Range", "bytes 0-0/42")
                    .body(ResponseBody.create(new byte[]{0}, null)).build();
        }).build();
        assertEquals(42, PvcDownloaderImplUtils.getContentLengthViaGet(
                "https://seed125.bitchute.com/video.mp4", client));
    }

    @Test
    public void sizeIsReadFromTheAnswerToARequestForTheFirstByte() throws Exception {
        assertEquals(1628769, PvcDownloaderImplUtils.contentLength(206, "bytes 0-0/1628769",
                "1", "url"));
        // a server that ignores the range sends the whole file
        assertEquals(42, PvcDownloaderImplUtils.contentLength(200, null, "42", "url"));
        // an error page has a length too, which is not the size of the file
        for (final int status : new int[]{401, 403, 404, 429, 500}) {
            assertThrows(IOException.class,
                    () -> PvcDownloaderImplUtils.contentLength(status, null, "45", "url"));
        }
        assertThrows(IOException.class,
                () -> PvcDownloaderImplUtils.contentLength(206, "bytes 0-0/*", "1", "url"));
        assertThrows(IOException.class,
                () -> PvcDownloaderImplUtils.contentLength(206, null, "1", "url"));
        assertThrows(IOException.class,
                () -> PvcDownloaderImplUtils.contentLength(200, null, null, "url"));
    }

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
        final SSLContext tls = testTlsContext("/cookie-test.p12");
        final X509TrustManager trustManager = testTrustManager("/cookie-test.p12");
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

    @Test
    public void controlledTlsPeerObservesRumbleIdentityStatusesAndHeaderPrecedence()
            throws Exception {
        final SSLContext tls = testTlsContext("/rumble-request-test.p12");
        final X509TrustManager trustManager = testTrustManager("/rumble-request-test.p12");
        final ExecutorService serverExecutor = Executors.newSingleThreadExecutor();
        final java.lang.reflect.Field clientField = DownloaderImpl.class.getDeclaredField("client");
        clientField.setAccessible(true);
        final OkHttpClient previousClient = downloader.getClient();
        final List<String> hosts = List.of("rumble.com", "www.rumble.com",
                "media.edge.rumble.com", "hugh.cdn.rumble.cloud",
                "rumble.com.evil.example", "notrumble.com", "rumble.cloud",
                "other.cdn.rumble.cloud", "sub.hugh.cdn.rumble.cloud",
                "hugh.cdn.rumble.cloud.evil.example", "example.org", "seed125.bitchute.com");
        final List<Integer> statuses = new ArrayList<>();
        for (int host = 0; host < hosts.size(); host++) {
            statuses.addAll(List.of(200, 403, 429));
        }
        statuses.add(200);
        try (ServerSocket server = tls.getServerSocketFactory().createServerSocket(
                0, 50, InetAddress.getLoopbackAddress())) {
            server.setSoTimeout(5000);
            final Future<List<Map<String, String>>> received = serverExecutor.submit(
                    () -> serveResponses(server, statuses));
            clientField.set(downloader, new OkHttpClient.Builder()
                    .proxy(Proxy.NO_PROXY)
                    .sslSocketFactory(tls.getSocketFactory(), trustManager)
                    .dns(host -> Collections.singletonList(InetAddress.getLoopbackAddress()))
                    .callTimeout(10, TimeUnit.SECONDS)
                    .addNetworkInterceptor(new DownloaderImpl.YoutubeCookieInterceptor()).build());
            for (final String host : hosts) {
                final String url = "https://" + host + ":" + server.getLocalPort()
                        + "/rumble.com/video.mp4?url=https://rumble.com";
                for (final int status : new int[]{200, 403, 429}) {
                    final org.schabi.newpipe.extractor.downloader.Response response =
                            downloader.get(url);
                    assertEquals(host, status, response.responseCode());
                    assertEquals("{}", response.responseBody());
                }
            }
            final String explicitUrl = "https://rumble.com:" + server.getLocalPort() + "/";
            assertEquals(200, downloader.get(explicitUrl, Map.of(
                    "user-agent", List.of("Rumble-test/explicit"),
                    "Cookie", List.of("rumble=explicit"),
                    "Referer", List.of("https://example.org/"))).responseCode());
            final List<Map<String, String>> requests = received.get(10, TimeUnit.SECONDS);
            for (int host = 0; host < hosts.size(); host++) {
                final String expectedIdentity;
                if (host < 4) {
                    expectedIdentity = EXPECTED_RUMBLE_USER_AGENT;
                } else if (host == hosts.size() - 1) {
                    expectedIdentity = "Mozilla/5.0 (X11; Linux x86_64; rv:128.0) "
                            + "Gecko/20100101 Firefox/128.0";
                } else {
                    expectedIdentity = DownloaderImpl.USER_AGENT;
                }
                for (int status = 0; status < 3; status++) {
                    final Map<String, String> headers = requests.get(host * 3 + status);
                    assertEquals(hosts.get(host), expectedIdentity, headers.get("User-Agent"));
                    assertNull(hosts.get(host), headers.get("Cookie"));
                    assertNull(hosts.get(host), headers.get("Referer"));
                }
            }
            final Map<String, String> explicit = requests.get(requests.size() - 1);
            assertEquals("Rumble-test/explicit", explicit.get("User-Agent"));
            assertEquals("rumble=explicit", explicit.get("Cookie"));
            assertEquals("https://example.org/", explicit.get("Referer"));
        } finally {
            clientField.set(downloader, previousClient);
            serverExecutor.shutdownNow();
        }
    }

    @Test
    public void controlledHttpPeerReceivesDefaultIdentityWithoutYoutubeCookies() throws Exception {
        final ExecutorService serverExecutor = Executors.newSingleThreadExecutor();
        final java.lang.reflect.Field clientField = DownloaderImpl.class.getDeclaredField("client");
        clientField.setAccessible(true);
        final OkHttpClient previousClient = downloader.getClient();
        try (ServerSocket server = new ServerSocket(0, 3, InetAddress.getLoopbackAddress())) {
            server.setSoTimeout(5000);
            final Future<List<Map<String, String>>> received = serverExecutor.submit(
                    () -> serveResponses(server, List.of(200, 403, 429)));
            clientField.set(downloader, new OkHttpClient.Builder()
                    .proxy(Proxy.NO_PROXY)
                    .dns(host -> Collections.singletonList(InetAddress.getLoopbackAddress()))
                    .callTimeout(10, TimeUnit.SECONDS)
                    .addNetworkInterceptor(new DownloaderImpl.YoutubeCookieInterceptor()).build());
            final List<String> hosts = List.of("rumble.com", "www.rumble.com",
                    "hugh.cdn.rumble.cloud");
            final int[] statuses = {200, 403, 429};
            for (int index = 0; index < hosts.size(); index++) {
                assertEquals(statuses[index], downloader.get("http://" + hosts.get(index) + ":"
                        + server.getLocalPort() + "/").responseCode());
            }
            for (final Map<String, String> headers : received.get(10, TimeUnit.SECONDS)) {
                assertEquals(DownloaderImpl.USER_AGENT, headers.get("User-Agent"));
                assertNull(headers.get("Cookie"));
            }
        } finally {
            clientField.set(downloader, previousClient);
            serverExecutor.shutdownNow();
        }
    }

    @Test
    public void productionResponsesReachKickClassificationWithoutLeakingCookies() throws Exception {
        final java.lang.reflect.Field clientField = DownloaderImpl.class.getDeclaredField("client");
        clientField.setAccessible(true);
        final OkHttpClient previousClient = downloader.getClient();
        final Downloader previousDownloader = NewPipe.getDownloader();
        try {
            NewPipe.init(downloader);
            for (final int status : new int[]{429, 403}) {
                clientField.set(downloader, new OkHttpClient.Builder().addInterceptor(chain -> {
                    assertNull(chain.request().header("Cookie"));
                    return new Response.Builder().request(chain.request())
                            .protocol(Protocol.HTTP_1_1).code(status).message("Refused")
                            .body(ResponseBody.create("{}", null)).build();
                }).build());
                final IOException error = assertThrows(IOException.class,
                        () -> KickApi.object("https://kick.com/api/v2/channels/example"));
                assertTrue(error.getMessage(), error.getMessage().contains(
                        status == 429 ? "rate-limited" : "HTTP 403"));
            }
            clientField.set(downloader, new OkHttpClient.Builder().addInterceptor(chain ->
                    new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                            .code(429).message("Challenge").header("cf-mitigated", "challenge")
                            .body(ResponseBody.create("<html>challenge</html>", null))
                            .build()).build());
            assertThrows(ReCaptchaException.class,
                    () -> KickApi.object("https://kick.com/api/v2/channels/example"));
            assertThrows(ReCaptchaException.class,
                    () -> downloader.get("https://www.youtube.com/watch?v=id"));
        } finally {
            clientField.set(downloader, previousClient);
            NewPipe.init(previousDownloader);
        }
    }

    private static KeyStore testKeys(final String resource) throws Exception {
        final KeyStore keys = KeyStore.getInstance("PKCS12");
        try (InputStream input = DownloaderImplTest.class.getResourceAsStream(resource)) {
            assertNotNull(input);
            keys.load(input, "test-only".toCharArray());
        }
        return keys;
    }

    private static X509TrustManager testTrustManager(final String resource) throws Exception {
        final TrustManagerFactory trustManagers = TrustManagerFactory.getInstance(
                TrustManagerFactory.getDefaultAlgorithm());
        trustManagers.init(testKeys(resource));
        return (X509TrustManager) trustManagers.getTrustManagers()[0];
    }

    private static SSLContext testTlsContext(final String resource) throws Exception {
        final KeyManagerFactory keyManagers = KeyManagerFactory.getInstance(
                KeyManagerFactory.getDefaultAlgorithm());
        keyManagers.init(testKeys(resource), "test-only".toCharArray());
        final SSLContext tls = SSLContext.getInstance("TLS");
        tls.init(keyManagers.getKeyManagers(),
                new X509TrustManager[]{testTrustManager(resource)}, null);
        return tls;
    }

    private static List<Map<String, String>> serveResponses(final ServerSocket server,
                                                           final List<Integer> statuses)
            throws Exception {
        final List<Map<String, String>> requests = new ArrayList<>();
        for (final int status : statuses) {
            try (Socket socket = server.accept()) {
                socket.setSoTimeout(5000);
                final BufferedReader reader = new BufferedReader(new InputStreamReader(
                        socket.getInputStream(), StandardCharsets.UTF_8));
                final Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
                assertNotNull(reader.readLine());
                String line;
                while ((line = reader.readLine()) != null && !line.isEmpty()) {
                    final int colon = line.indexOf(':');
                    if (colon > 0) {
                        headers.merge(line.substring(0, colon), line.substring(colon + 1).trim(),
                                (first, second) -> first + ", " + second);
                    }
                }
                requests.add(headers);
                final String response = "HTTP/1.1 " + status + " Test\r\n"
                        + "Content-Type: application/json\r\nContent-Length: 2\r\n"
                        + "Connection: close\r\n\r\n{}";
                socket.getOutputStream().write(response.getBytes(StandardCharsets.UTF_8));
            }
        }
        return requests;
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
