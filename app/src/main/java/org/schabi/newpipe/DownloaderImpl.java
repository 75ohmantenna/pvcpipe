package org.schabi.newpipe;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;

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

    public static String getMediaUserAgent(final String url) {
        final HttpUrl parsed = HttpUrl.parse(url);
        // BitChute's seed CDN closes Chrome/145 connections without an HTTP response.
        // Keep this workaround confined to its media hosts.
        return parsed != null && parsed.host().matches("seed[a-z0-9]+\\.bitchute\\.com")
                ? BITCHUTE_MEDIA_USER_AGENT : USER_AGENT;
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
//                .cache(new Cache(new File(context.getExternalCacheDir(), "okhttp"),
//                        16 * 1024 * 1024))
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
     * It's recommended to call exactly once in the entire lifetime of the application.
     *
     * @param builder if null, default builder will be used. If supplying a builder always use
     * {@link #getNewBuilder()} to retrieve one - unless you know what you are doing.
     * @return a new instance of {@link DownloaderImpl}
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
                .addHeader("User-Agent", USER_AGENT);

        headers.forEach((headerName, headerValueList) -> {
            requestBuilder.removeHeader(headerName);
            headerValueList.forEach(headerValue ->
                    requestBuilder.addHeader(headerName, headerValue));
        });

        try (
                okhttp3.Response response = client.newCall(requestBuilder.build()).execute()
        ) {
            if (response.code() == 429) {
                throw new ReCaptchaException("reCaptcha Challenge requested", url);
            }

            String responseBodyToReturn = null;
            try (ResponseBody body = response.body()) {
                responseBodyToReturn = body.string();
            }

            final String latestUrl = response.request().url().toString();
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
