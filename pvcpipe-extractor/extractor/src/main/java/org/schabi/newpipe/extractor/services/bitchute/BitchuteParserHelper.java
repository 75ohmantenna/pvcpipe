package org.schabi.newpipe.extractor.services.bitchute;

import com.grack.nanojson.JsonArray;
import com.grack.nanojson.JsonBuilder;
import com.grack.nanojson.JsonObject;
import com.grack.nanojson.JsonParser;
import com.grack.nanojson.JsonParserException;
import com.grack.nanojson.JsonWriter;

import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException;
import org.schabi.newpipe.extractor.exceptions.ExtractionException;
import org.schabi.newpipe.extractor.exceptions.GeographicRestrictionException;
import org.schabi.newpipe.extractor.exceptions.ParsingException;
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException;
import org.schabi.newpipe.extractor.pvc.PvcCloudFlareChallengeException;
import org.schabi.newpipe.extractor.utils.Utils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import static org.schabi.newpipe.extractor.NewPipe.getDownloader;
import static org.schabi.newpipe.extractor.services.bitchute.BitchuteConstants.BASE_URL;
import static org.schabi.newpipe.extractor.services.bitchute.BitchuteConstants.BITCHUTE_LOCALE;
import static org.schabi.newpipe.extractor.services.bitchute.BitchuteConstants.SEARCH_AUTH_URL;
import static org.schabi.newpipe.extractor.services.bitchute.BitchuteService.BITCHUTE_LINK;

public final class BitchuteParserHelper {

    private static final int COMMENT_AUTH_CACHE_SIZE = 64;
    private static final Map<String, String> VIDEO_ID_2_COMMENT_CF_AUTH =
            Collections.synchronizedMap(new LinkedHashMap<String, String>(
                    COMMENT_AUTH_CACHE_SIZE, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(final Map.Entry<String, String> eldest) {
                    return size() > COMMENT_AUTH_CACHE_SIZE;
                }
            });
    // the time interval the searchAuthTimestamp/Nonce value should be used (in seconds)
    // before renewing
    private static final int SEARCH_AUTH_DATA_TIMEOUT = 60 * 10;
    private static final Pattern SEARCH_AUTH_PATTERN =
            Pattern.compile("searchAuth\\('([^']+)', '([^']+)'");
    private static final Pattern CF_AUTH_PATTERN = Pattern.compile("\\{cf_auth: '([^']+)'");
    private static volatile String cookies;
    private static volatile String csrfToken;
    private static volatile String searchAuthNonce;
    private static volatile String searchAuthTimestamp;

    private BitchuteParserHelper() {
    }

    public static boolean isInitDone() {
        return !Utils.isNullOrEmpty(cookies) && !Utils.isNullOrEmpty(csrfToken);
    }

    public static synchronized void init() throws ReCaptchaException, IOException {
        final Response response = getDownloader().get(BITCHUTE_LINK);
        initCookies(response);
    }

    private static void initCookies(final Response response) {
        final StringBuilder sb = new StringBuilder();
        for (final Map.Entry<String, List<String>> entry : response.responseHeaders().entrySet()) {
            if ("set-cookie".equalsIgnoreCase(entry.getKey())) {
                final List<String> values = entry.getValue();
                for (final String v : values) {
                    final String val = v.split(";", 2)[0];
                    sb.append(val).append(";");
                    if (val.contains("csrf")) {
                        csrfToken = val.split("=", 2)[1];
                    }
                }
                break;
            }
        }
        cookies = sb.toString();
    }

    public static Map<String, List<String>> getPostHeader(final int contentLength)
            throws IOException, ReCaptchaException {
        final Map<String, List<String>> headers = getBasicHeader();
        headers.put("Content-Type", Collections.singletonList("application/x-www-form-urlencoded"));
        headers.put("Content-Length", Collections.singletonList(String.valueOf(contentLength)));
        return headers;
    }

    private static Map<String, List<String>> getJsonApiHeaders() {
        return Map.of(
                "Accept", List.of("application/json"),
                "Content-Type", List.of("application/json"));
    }

    public static Map<String, List<String>> getBasicHeader()
            throws IOException, ReCaptchaException {
        if (!isInitDone()) {
            init();
        }
        final Map<String, List<String>> headers = new HashMap<>();
        headers.put("Cookie", Collections.singletonList(cookies));
        headers.put("Referer", Collections.singletonList(BITCHUTE_LINK));
        return headers;
    }

    @Nonnull
    public static JsonArray getComments(@Nonnull final String id,
                                        @Nonnull final String url,
                                        final int commentCount)
            throws IOException, ExtractionException {
        // The current website is a SPA: its HTML no longer contains cf_auth.
        // Obtain a fresh anonymous token, as the API's signed tokens can expire.
        final JsonObject auth = callJsonApi(JsonObject.builder().value("video_id", id),
                "https://api.bitchute.com/api/beta/apps/commentfreely/video/");
        final String cfAuth = auth.getString("auth");
        if (Utils.isNullOrEmpty(cfAuth)) {
            throw new ParsingException("BitChute comments response contains no auth token");
        }
        return getComments(cfAuth, commentCount);
    }

    @Nonnull
    private static JsonArray getComments(@Nonnull final String cfAuth, final int commentCount)
            throws IOException, ExtractionException {
        // in case you get a JsonObject instead of JsonArray that might be because they changed
        // something with this parameter 'isNameValuesArrays=false'
        // -> basically ATM if you add this parameter to the url with the help of 'moreHeaders'
        //    parameter (regardless if set to false or true) you will get an JsonObject.
        //    ONLY dropping this parameter results in getting a JsonArray as we want.
        return (JsonArray) getJsonDataFromCommentEndpoints("/api/get_comments/", cfAuth,
                String.format(BITCHUTE_LOCALE, "&commentCount=%d", commentCount));
    }

    /*
    private static int getCommentsCount(final String cfAuth)
            throws IOException, ExtractionException {
        final String key = "commentCount";
        final JsonObject counts = (JsonObject)
                getJsonDataFromCommentEndpoints("/api/get_comment_count/", cfAuth, "");

        Objects.requireNonNull(counts.get(key));
        return counts.getInt(key);
    }
     */

    @Nonnull
    private static Object getJsonDataFromCommentEndpoints(
            @Nonnull final String apiEndpoint,
            @Nonnull final String cfAuth,
            @Nonnull final String moreHeaders) throws IOException, ExtractionException {
        final String dataWithPlaceholders = "cf_auth=%s" + moreHeaders;

        final String urlEncodeCfAuth = Utils.encodeUrlUtf8(cfAuth);
        final byte[] data = String.format(dataWithPlaceholders, urlEncodeCfAuth)
                .getBytes(StandardCharsets.UTF_8);

        final Response response = getDownloader().post(
                String.format(BITCHUTE_LOCALE, "%s%s", BitchuteConstants.COMMENTS_URL, apiEndpoint),
                Map.of("Content-Type", List.of("application/x-www-form-urlencoded"),
                        "Referer", List.of(BITCHUTE_LINK)),
                data
        );

        if (response.responseCode() != 200) {
            throw new ExtractionException("BitChute comments request failed: HTTP "
                    + response.responseCode());
        }
        try {
            final Object jsonObject = JsonParser.any().from(response.responseBody());
            if (!(jsonObject instanceof JsonArray)) {
                throw new ParsingException("BitChute comments response is not an array");
            }
            return jsonObject;
        } catch (final JsonParserException e) {
            throw new ParsingException("Could not parse BitChute comments response", e);
        }
    }

    public static JsonObject callJsonApi(
            final JsonBuilder<JsonObject> sortQueryJson,
            final String endpoint)
            throws IOException, ExtractionException {
        return getJsonObject(postJsonApi(sortQueryJson, endpoint));
    }

    public static JsonArray callJsonArrayApi(final JsonBuilder<JsonObject> query,
                                             final String endpoint)
            throws IOException, ExtractionException {
        final Response response = postJsonApi(query, endpoint);
        if (response.responseCode() != 200) {
            getJsonObject(response); // Raise the same API errors as object endpoints.
        }
        try {
            return JsonParser.array().from(response.responseBody());
        } catch (final JsonParserException e) {
            throw new ParsingException("Could not parse BitChute API array response", e);
        }
    }

    private static Response postJsonApi(final JsonBuilder<JsonObject> sortQueryJson,
                                         final String endpoint)
            throws IOException, ExtractionException {
        final JsonObject thing = sortQueryJson.done();
        final byte[] data = JsonWriter.string(thing).getBytes(StandardCharsets.UTF_8);

        return getDownloader().post(
                endpoint,
                getJsonApiHeaders(),
                data
        );
    }

    private static JsonObject getJsonObject(final Response response) throws ExtractionException {
        final JsonObject jsonObject;
        try {
            jsonObject = JsonParser.object().from(response.responseBody());
            if (response.responseCode() == 200) {

                return jsonObject;
            }
        } catch (final JsonParserException e) {
            if (response.responseCode() == 403
                    && (response.responseBody().contains("challenge-platform")
                    || response.responseBody().contains("Just a moment"))) {
                throw new PvcCloudFlareChallengeException(
                        "BitChute Cloudflare challenge: " + response.latestUrl());
            }
            if (response.responseCode() >= 400) {
                throw new ContentNotAvailableException("BitChute API request failed: HTTP "
                        + response.responseCode(), e);
            }
            throw new ParsingException("Could not parse BitChute API response", e);
        }

        final String errorsKey = "errors";
        if (jsonObject.has(errorsKey)) {
            if (!jsonObject.getArray(errorsKey).isEmpty()) {
                for (final Object entry : jsonObject.getArray(errorsKey)) {
                    final JsonObject error = (JsonObject) entry;
                    final String reason = error.getString("message", "BitChute API request failed");
                    if (response.responseCode() == 403
                            && "reason".equals(error.getString("context"))
                            && reason.toLowerCase(Locale.ROOT).contains("location")) {
                        throw new GeographicRestrictionException(reason);
                    }
                    if (response.responseCode() == 404 && reason.contains("Not Found")) {
                        throw new ContentNotAvailableException(reason);
                    }
                }
            }
        }

        throw new ExtractionException(
                "BitChute API request failed: (httpCode="
                        + response.responseCode() + " body: " + response.responseBody());
    }

    private static void initSearch() throws ReCaptchaException, IOException {
        final Response response = getDownloader().get(SEARCH_AUTH_URL);
        initCookies(response);

        extractAndStoreSearchAuth(response.responseBody());
    }

    public static boolean extractAndStoreSearchAuth(@Nonnull final String body) {
        final Matcher match = SEARCH_AUTH_PATTERN.matcher(body);

        if (match.find()) {
            searchAuthTimestamp = match.group(1);
            searchAuthNonce = match.group(2);

            return true;
        }
        return false;
    }

    private static boolean isSearchInitDone() {
        if (searchAuthTimestamp == null || searchAuthNonce == null) {
            return false;
        } else {
            return isSearchAuthStillUsable(searchAuthTimestamp);
        }
    }

    // Check if the timestamp we currently have is not too old.
    private static boolean isSearchAuthStillUsable(@Nonnull final String timestamp) {
        final LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);

        final LocalDateTime authTimestamp = LocalDateTime.parse(timestamp,
                DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSSXXX"));

        final long diff =
                now.toEpochSecond(ZoneOffset.UTC) - authTimestamp.toEpochSecond(ZoneOffset.UTC);

        return diff < SEARCH_AUTH_DATA_TIMEOUT;
    }

    public static boolean extractAndStoreCfAuth(@Nonnull final String id,
                                                @Nonnull final String body) {
        final Matcher match = CF_AUTH_PATTERN.matcher(body);

        if (match.find()) {
            VIDEO_ID_2_COMMENT_CF_AUTH.put(id, match.group(1));
            return true;
        }
        return false;
    }

    @Nullable
    public static String getCfAuth(@Nonnull final String id) {
        return VIDEO_ID_2_COMMENT_CF_AUTH.get(id);
    }

    public static String prependBaseUrl(final String urlPath) {
        if (Utils.isNullOrEmpty(urlPath)) {
            return "";
        }
        if (urlPath.startsWith("https://") || urlPath.startsWith("http://")) {
            return urlPath;
        }
        return urlPath.startsWith("//") ? "https:" + urlPath : BASE_URL + urlPath;
    }
}
