package org.schabi.newpipe;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;

import org.schabi.newpipe.error.ErrorInfo;
import org.schabi.newpipe.error.ReCaptchaActivity;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException;
import org.schabi.newpipe.util.InfoCache;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import okhttp3.HttpUrl;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.RequestBody;
import okhttp3.ResponseBody;

public final class DownloaderImpl extends Downloader {
    public static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/145.0.0.0 Safari/537.36";
    private static final String BITCHUTE_MEDIA_USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64; rv:128.0) Gecko/20100101 Firefox/128.0";
    private static final String ODYSEE_MEDIA_REFERER = "https://odysee.com/";

    public static String getMediaUserAgent(final String url) {
        final HttpUrl parsed = HttpUrl.parse(url);
        // BitChute's seed CDN closes Chrome/145 connections without an HTTP response.
        // Keep this workaround confined to its media hosts.
        return parsed != null && parsed.host().matches("seed[a-z0-9]+\\.bitchute\\.com")
                ? BITCHUTE_MEDIA_USER_AGENT : USER_AGENT;
    }

    /**
     * Supplies Odysee's website referrer for odysee.live and odycdn.com, including subdomains.
     * See docs/services.md#odysee for the policy and dated media-host evidence.
     *
     * @param url the address of a media request
     * @return the configured referrer, or {@code null} for other or invalid URLs
     */
    @Nullable
    public static String getMediaReferer(final String url) {
        final HttpUrl parsed = HttpUrl.parse(url);
        return parsed != null && (isHostOrSubdomain(parsed.host(), "odysee.live")
                || isHostOrSubdomain(parsed.host(), "odycdn.com")) ? ODYSEE_MEDIA_REFERER : null;
    }

    /**
     * Selects GET-based size probes for odycdn.com URLs other than .ts and .m3u8 paths.
     * HEAD can redirect an uploaded file to an HLS playlist and report that playlist's size.
     * The extension check is case-insensitive and excludes the query string.
     *
     * @param url the address of a media file
     * @return whether the URL uses the GET-probe policy
     */
    public static boolean isHeadUnusable(final String url) {
        final HttpUrl parsed = HttpUrl.parse(url);
        if (parsed == null || !isHostOrSubdomain(parsed.host(), "odycdn.com")) {
            return false;
        }
        final String path = parsed.encodedPath().toLowerCase(Locale.ROOT);
        return !path.endsWith(".ts") && !path.endsWith(".m3u8");
    }

    private static boolean isHostOrSubdomain(final String host, final String domain) {
        return host.equals(domain) || host.endsWith("." + domain);
    }

    public static final String YOUTUBE_RESTRICTED_MODE_COOKIE_KEY =
            "youtube_restricted_mode_key";
    public static final String YOUTUBE_RESTRICTED_MODE_COOKIE = "PREF=f2=8000000";
    public static final String YOUTUBE_DOMAIN = "youtube.com";

    private static final DownloaderImpl INSTANCE = new DownloaderImpl();
    private final Map<String, String> mCookies;
    private volatile OkHttpClient client = new OkHttpClient();

    private DownloaderImpl() {
        // Preferences and captcha UI update cookies while network threads read them.
        this.mCookies = Collections.synchronizedMap(new HashMap<>());
    }

    private void initInternal(final @Nullable OkHttpClient.Builder builder) {
        final OkHttpClient.Builder theBuilder =
                builder != null ? builder : client.newBuilder();
        theBuilder.readTimeout(30, TimeUnit.SECONDS);
        PvcDownloaderImplUtils.addOrRemoveInterceptors(theBuilder);
        PvcDownloaderImplUtils.addCookieManager(theBuilder);
        if (theBuilder.networkInterceptors().stream()
                .noneMatch(YoutubeCookieInterceptor.class::isInstance)) {
            theBuilder.addNetworkInterceptor(new YoutubeCookieInterceptor());
        }
        this.client = theBuilder.build();
    }

    public void reInitInterceptors() {
        final OkHttpClient.Builder builder = client.newBuilder();
        PvcDownloaderImplUtils.addOrRemoveInterceptors(builder);
        this.client = builder.build();
    }

    @NonNull
    public OkHttpClient getClient() {
        return client;
    }

    /**
     * Initialize the shared downloader, preferably once during application startup.
     *
     * @param builder if null, the current client's builder is used. If supplying a builder,
     *                use {@link #getNewBuilder()} unless custom client configuration is intended.
     * @return the shared {@link DownloaderImpl} instance
     */
    public DownloaderImpl init(@Nullable final OkHttpClient.Builder builder) {
        initInternal(builder);
        return INSTANCE;
    }

    public static DownloaderImpl getInstance() {
        return INSTANCE;
    }

    public OkHttpClient.Builder getNewBuilder() {
        return client.newBuilder();
    }

    public String getCookies(final String url) {
        final HttpUrl parsedUrl = HttpUrl.parse(url);
        if (parsedUrl == null || !parsedUrl.isHttps() || !(parsedUrl.host().equals(YOUTUBE_DOMAIN)
                || parsedUrl.host().endsWith("." + YOUTUBE_DOMAIN))) {
            return "";
        }

        // ReCaptchaActivity currently collects only YouTube cookies.
        return Stream.of(getCookie(YOUTUBE_RESTRICTED_MODE_COOKIE_KEY),
                        getCookie(ReCaptchaActivity.RECAPTCHA_COOKIES_KEY))
                .filter(Objects::nonNull)
                .flatMap(cookies -> Arrays.stream(cookies.split("; *")))
                .distinct()
                .collect(Collectors.joining("; "));
    }

    public String getCookie(final String key) {
        return mCookies.get(key);
    }

    public void setCookie(final String key, final String cookie) {
        mCookies.put(key, cookie);
    }

    public void removeCookie(final String key) {
        mCookies.remove(key);
    }

    public void updateYoutubeRestrictedModeCookies(final Context context) {
        final String restrictedModeEnabledKey =
                context.getString(R.string.youtube_restricted_mode_enabled);
        final boolean restrictedModeEnabled = PreferenceManager.getDefaultSharedPreferences(context)
                .getBoolean(restrictedModeEnabledKey, false);
        updateYoutubeRestrictedModeCookies(restrictedModeEnabled);
    }

    public void updateYoutubeRestrictedModeCookies(final boolean youtubeRestrictedModeEnabled) {
        if (youtubeRestrictedModeEnabled) {
            setCookie(YOUTUBE_RESTRICTED_MODE_COOKIE_KEY,
                    YOUTUBE_RESTRICTED_MODE_COOKIE);
        } else {
            removeCookie(YOUTUBE_RESTRICTED_MODE_COOKIE_KEY);
        }
        InfoCache.getInstance().clearCache();
    }

    /**
     * Get the size of the content that the url is pointing by firing a HEAD request.
     *
     * @param url an url pointing to the content
     * @return the size of the content, in bytes
     */
    public long getContentLength(final String url) throws IOException {
        try {
            final Response response = head(url);
            if (response.responseCode() == 405) { // HEAD Method not allowed
                return PvcDownloaderImplUtils.getContentLengthViaGet(url);
            } else if (response.responseCode() < 200 || response.responseCode() >= 300) {
                // The length of an error page is not the length of the content.
                throw new IOException("HTTP " + response.responseCode() + " for " + url);
            } else {
                return Long.parseLong(response.getHeader("Content-Length"));
            }
        } catch (final NumberFormatException e) {
            throw new IOException("Invalid content length", e);
        } catch (final ReCaptchaException e) {
            throw new IOException(e);
        }
    }

    @Override
    public Response execute(@NonNull final Request request)
            throws IOException, ReCaptchaException {
        final String httpMethod = request.httpMethod();
        final String url = request.url();
        final Map<String, List<String>> headers = request.headers();
        final byte[] dataToSend = request.dataToSend();

        RequestBody requestBody = null;
        if (dataToSend != null) {
            requestBody = RequestBody.create(dataToSend);
        }

        final okhttp3.Request.Builder requestBuilder = new okhttp3.Request.Builder()
                .method(httpMethod, requestBody)
                .url(url)
                // The media hosts that refuse the default one are asked for sizes this way.
                .addHeader("User-Agent", getMediaUserAgent(url));

        headers.forEach((headerName, headerValueList) -> {
            requestBuilder.removeHeader(headerName);
            headerValueList.forEach(headerValue ->
                    requestBuilder.addHeader(headerName, headerValue));
        });

        final String mediaReferer = getMediaReferer(url);
        if (mediaReferer != null
                && headers.keySet().stream().noneMatch("Referer"::equalsIgnoreCase)) {
            requestBuilder.addHeader("Referer", mediaReferer);
        }

        try (
                okhttp3.Response response = client.newCall(requestBuilder.build()).execute()
        ) {
            final String latestUrl = response.request().url().toString();
            if (response.code() == 429 && ErrorInfo.isCaptchaSupportedUrl(latestUrl)) {
                throw new ReCaptchaException("reCaptcha Challenge requested", latestUrl);
            }

            String responseBodyToReturn = null;
            try (ResponseBody body = response.body()) {
                responseBodyToReturn = body.string();
            }

            return new Response(
                    response.code(),
                    response.message(),
                    response.headers().toMultimap(),
                    responseBodyToReturn,
                    latestUrl);
        }
    }

    static final class YoutubeCookieInterceptor implements Interceptor {
        @NonNull
        @Override
        public okhttp3.Response intercept(@NonNull final Chain chain) throws IOException {
            final okhttp3.Request request = chain.request();
            // Network interceptors run after host replacement and on every redirect hop.
            final String cookies = INSTANCE.getCookies(request.url().toString());
            if (!cookies.isEmpty() && request.header("Cookie") == null) {
                return chain.proceed(request.newBuilder().header("Cookie", cookies).build());
            }
            return chain.proceed(request);
        }
    }
}
