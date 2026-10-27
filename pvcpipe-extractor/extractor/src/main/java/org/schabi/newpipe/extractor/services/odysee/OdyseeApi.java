package org.schabi.newpipe.extractor.services.odysee;

import com.grack.nanojson.JsonArray;
import com.grack.nanojson.JsonObject;
import com.grack.nanojson.JsonParser;
import com.grack.nanojson.JsonParserException;
import com.grack.nanojson.JsonWriter;

import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException;
import org.schabi.newpipe.extractor.exceptions.ExtractionException;
import org.schabi.newpipe.extractor.exceptions.PaidContentException;
import org.schabi.newpipe.extractor.exceptions.ParsingException;
import org.schabi.newpipe.extractor.services.odysee.settings.OdyseeSettings;
import org.schabi.newpipe.extractor.utils.Utils;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * The requests the Odysee extractors make. All of them are the requests the website makes for
 * a visitor who is not signed in. Two of them, for view counts and follower counts, need the
 * visitor token of {@link OdyseeAuth} and are only made when
 * {@link OdyseeSettings#SHOW_COUNTS} is on.
 */
public final class OdyseeApi {

    public static final String PROXY_URL = "https://api.na-backend.odysee.com/api/v1/proxy";
    public static final String SEARCH_URL = "https://lighthouse.odysee.tv/search";
    public static final String RELATED_URL = "https://recsys.odysee.tv/search";
    public static final String COMMENTS_URL = "https://comments.odysee.tv/api/v2";
    public static final String ACCOUNT_API_URL = "https://api.odysee.com/";
    public static final String REGISTER_URL = ACCOUNT_API_URL + "user/new";
    public static final String REACTIONS_URL = ACCOUNT_API_URL + "reaction/list";
    public static final String VIEW_COUNT_URL = ACCOUNT_API_URL + "file/view_count";
    public static final String FOLLOWER_COUNT_URL = ACCOUNT_API_URL + "subscription/sub_count";
    public static final String LIVESTREAM_URL = "https://api.odysee.live/livestream";
    public static final String HOMEPAGE_URL = OdyseeUrl.BASE_URL + "/$/api/content/v2/get";

    /** The largest page the claim search accepts. */
    public static final int MAX_PAGE_SIZE = 50;
    /** The claim search does not return results past this position. */
    public static final int MAX_SEARCH_RESULTS = 1000;

    private static final String REFERER = OdyseeUrl.BASE_URL + "/";
    private static final String JSON_RPC = "application/json-rpc";
    private static final String FORM = "application/x-www-form-urlencoded";
    private static final int HTTP_UNAUTHORIZED = 401;
    private static final int HTTP_FORBIDDEN = 403;
    // The largest list this extractor makes holds 50 claims.
    private static final int MAX_COUNT_REQUEST = 100;

    private static final int RESOLVE_CACHE_SIZE = 32;
    private static final long RESOLVE_CACHE_MILLIS = 10 * 60 * 1000L;
    private static final long HOMEPAGE_CACHE_MILLIS = 60 * 60 * 1000L;
    private static final long COUNTS_RETRY_MILLIS = 60 * 1000L;

    private static final Map<String, CachedClaim> RESOLVED = Collections.synchronizedMap(
            new LinkedHashMap<String, CachedClaim>(RESOLVE_CACHE_SIZE, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(final Map.Entry<String, CachedClaim> eldest) {
                    return size() > RESOLVE_CACHE_SIZE;
                }
            });

    private static volatile CachedClaim homepage;
    private static volatile long countsRetryAt;
    private static final OdyseeCountCache COUNTS = new OdyseeCountCache(
            512, java.util.concurrent.TimeUnit.MINUTES.toNanos(5), System::nanoTime);

    private OdyseeApi() {
    }

    /**
     * Looks a claim up by its LBRY URI.
     *
     * @param lbryUri such as {@code lbry://@channel#c/video#a}
     * @return the claim
     * @throws ContentNotAvailableException if there is no such claim
     */
    @Nonnull
    public static JsonObject resolve(@Nonnull final String lbryUri)
            throws IOException, ExtractionException {
        final JsonObject params = new JsonObject();
        params.put("urls", array(List.of(lbryUri)));
        final JsonObject claim = resolveRequest(params).getObject(lbryUri);
        if (claim == null || claim.isEmpty()) {
            throw new ContentNotAvailableException("Odysee has nothing at " + lbryUri);
        }
        if (claim.has("error")) {
            throw new ContentNotAvailableException(
                    claim.getObject("error").getString("text", "Odysee has nothing at " + lbryUri));
        }
        RESOLVED.put(lbryUri, new CachedClaim(claim));
        return claim;
    }

    /**
     * Like {@link #resolve}, but reuses the answer when another extractor asked for the same
     * claim a moment ago. Comments and channel tabs open right after the video or channel they
     * belong to.
     */
    @Nonnull
    public static JsonObject resolveCached(@Nonnull final String lbryUri)
            throws IOException, ExtractionException {
        final CachedClaim cached = RESOLVED.get(lbryUri);
        if (cached != null && cached.isYoungerThan(RESOLVE_CACHE_MILLIS)) {
            return cached.claim;
        }
        return resolve(lbryUri);
    }

    /**
     * Searches claims with the supplied pagination parameters.
     *
     * @param params the claim search parameters
     * @return the claims on the requested page
     */
    @Nonnull
    public static JsonArray claimSearch(@Nonnull final JsonObject params)
            throws IOException, ExtractionException {
        params.put("no_totals", true);
        return callProxy("claim_search", params).getArray("items");
    }

    /**
     * Fetches claims by id and returns them in the order of the ids.
     *
     * @param claimIds at most {@link #MAX_PAGE_SIZE} claim ids
     * @return the claims that exist, in the order asked for
     */
    @Nonnull
    public static List<JsonObject> claimsByIds(@Nonnull final Collection<String> claimIds)
            throws IOException, ExtractionException {
        if (claimIds.isEmpty()) {
            return List.of();
        }
        final JsonObject params = new JsonObject();
        params.put("claim_ids", array(claimIds));
        params.put("page", 1);
        params.put("page_size", Math.min(claimIds.size(), MAX_PAGE_SIZE));
        final Map<String, JsonObject> byId = new HashMap<>();
        for (final Object item : claimSearch(params)) {
            if (item instanceof JsonObject) {
                byId.put(((JsonObject) item).getString("claim_id"), (JsonObject) item);
            }
        }
        // An id that was asked for twice, as a playlist may list a video twice, is answered
        // twice.
        final List<JsonObject> ordered = new ArrayList<>();
        for (final String claimId : claimIds) {
            final JsonObject claim = byId.get(claimId);
            if (claim != null) {
                ordered.add(claim);
            }
        }
        return ordered;
    }

    /**
     * Requests the media address for a stream.
     *
     * @param lbryUri   the stream to play
     * @param signature the signature and timestamp of the link the stream was opened with, as
     *                  {@link OdyseeUrl#signatureOf} returns them, or {@code null}
     * @return the address of its media file
     * @throws PaidContentException if Odysee wants a signed-in buyer or member
     */
    @Nonnull
    public static String streamingUrl(@Nonnull final String lbryUri,
                                      @Nullable final String[] signature)
            throws IOException, ExtractionException {
        final JsonObject params = new JsonObject();
        params.put("uri", lbryUri);
        params.put("save_file", false);
        if (signature != null) {
            params.put("signature", signature[0]);
            params.put("signature_ts", signature[1]);
        }
        final String url;
        try {
            url = callProxy("get", params).getString("streaming_url");
        } catch (final ApiErrorException e) {
            if (e.getMessage() != null && e.getMessage().contains("authentication required")) {
                throw new PaidContentException("This Odysee content has to be bought or needs a"
                        + " membership");
            }
            throw e;
        }
        if (Utils.isNullOrEmpty(url)) {
            throw new ParsingException("Odysee returned no streaming URL for " + lbryUri);
        }
        return url;
    }

    /**
     * Follows the media address the way a player would ask about it. Odysee answers a HEAD
     * request with a redirect to an HLS playlist when it has transcoded the file, and only
     * answers it at all when the request names the website as its referrer.
     *
     * @param streamingUrl the address from {@link #streamingUrl}
     * @return the address the request ended at
     */
    @Nonnull
    public static String followStreamingUrl(@Nonnull final String streamingUrl)
            throws IOException, ExtractionException {
        final Response response = NewPipe.getDownloader().head(streamingUrl, refererHeaders());
        return Utils.isNullOrEmpty(response.latestUrl()) ? streamingUrl : response.latestUrl();
    }

    @Nonnull
    public static String get(@Nonnull final String url) throws IOException, ExtractionException {
        return checked(NewPipe.getDownloader().get(url, refererHeaders()), url);
    }

    /**
     * Searches Odysee for matching claim ids and names.
     *
     * @param query the search query string, starting with {@code s=}
     * @return the hits, each with a {@code claimId} and a {@code name}
     */
    @Nonnull
    public static JsonArray search(@Nonnull final String query)
            throws IOException, ExtractionException {
        final String url = SEARCH_URL + "?" + query;
        try {
            return JsonParser.array().from(get(url));
        } catch (final JsonParserException e) {
            throw new ParsingException("Could not parse Odysee search results", e);
        }
    }

    /**
     * Fetches related claims, falling back to search if necessary.
     *
     * @param query the encoded search parameters, including the current claim id
     * @return recommendations, falling back to search when the recommender is unavailable
     */
    @Nonnull
    public static JsonArray related(@Nonnull final String query)
            throws IOException, ExtractionException {
        try {
            return JsonParser.array().from(get(RELATED_URL + "?" + query + "&free_only=true"));
        } catch (final IOException | ExtractionException | JsonParserException e) {
            if (OdyseeAuth.wasCancelled(e)) {
                throw new java.io.InterruptedIOException("Odysee recommendations cancelled");
            }
            return search(query);
        }
    }

    @Nonnull
    public static JsonObject callComments(@Nonnull final String method,
                                          @Nonnull final JsonObject params)
            throws IOException, ExtractionException {
        return callJsonRpc(COMMENTS_URL + "?m=" + method, method, params, "application/json");
    }

    /**
     * Fetches the claim's reaction counts.
     *
     * @param claimId the claim to ask about
     * @return its {@code like} and {@code dislike} counts
     */
    @Nonnull
    public static JsonObject reactions(@Nonnull final String claimId)
            throws IOException, ExtractionException {
        final Object data = callAccountApi(REACTIONS_URL, "claim_ids=" + claimId);
        return data instanceof JsonObject
                ? ((JsonObject) data).getObject("others_reactions").getObject(claimId)
                : new JsonObject();
    }

    /**
     * Asks how often claims were viewed. The answer is empty, and nothing is thrown, when the
     * counts are disabled, in backoff or unavailable. Cached values avoid requests for those ids.
     *
     * @param claimIds the claims to ask about
     * @return the view count of each claim Odysee answered for
     */
    @Nonnull
    public static Map<String, Long> viewCounts(@Nonnull final Collection<String> claimIds) {
        return counts(VIEW_COUNT_URL, claimIds);
    }

    /**
     * Asks how many followers channels have. Like {@link #viewCounts}, it answers with an
     * empty map when the counts are switched off or cannot be had.
     *
     * @param channelClaimIds the channels to ask about
     * @return the follower count of each channel Odysee answered for
     */
    @Nonnull
    public static Map<String, Long> followerCounts(
            @Nonnull final Collection<String> channelClaimIds) {
        return counts(FOLLOWER_COUNT_URL, channelClaimIds);
    }

    private static Map<String, Long> counts(final String url,
                                            final Collection<String> claimIds) {
        // Disabled counts and the failure backoff suppress cache reads as well as requests.
        if (!OdyseeSettings.getInstance().isSettingEnabled(OdyseeSettings.SHOW_COUNTS)
                || System.currentTimeMillis() < countsRetryAt) {
            return Map.of();
        }
        final List<String> ids = new ArrayList<>();
        for (final String claimId : claimIds) {
            if (OdyseeUrl.isClaimId(claimId) && !ids.contains(claimId)
                    && ids.size() < MAX_COUNT_REQUEST) {
                ids.add(claimId);
            }
        }
        if (ids.isEmpty()) {
            return Map.of();
        }
        final long startedIn = COUNTS.generation();
        final Map<String, Long> result = COUNTS.get(url, ids);
        ids.removeAll(result.keySet());
        if (ids.isEmpty()) {
            return result;
        }
        // A token that Odysee has stopped accepting is replaced once.
        for (int attempt = 0; attempt < 2; attempt++) {
            final String token = OdyseeAuth.token();
            if (token == null) {
                return Map.of();
            }
            try {
                final Object data = callAccountApi(url, "auth_token="
                        + Utils.encodeUrlUtf8(token) + "&claim_id=" + String.join(",", ids));
                OdyseeAuth.accepted();
                if (startedIn != COUNTS.generation()
                        || !OdyseeSettings.getInstance().isSettingEnabled(
                                OdyseeSettings.SHOW_COUNTS)) {
                    return Map.of();
                }
                final Map<String, Long> fetched = countsByClaim(ids, data);
                COUNTS.put(url, fetched, startedIn);
                result.putAll(fetched);
                return result;
            } catch (final NotAuthenticatedException e) {
                OdyseeAuth.discard(token);
            } catch (final IOException | ExtractionException | RuntimeException e) {
                // A caller that gave up, as when the user leaves the page, says nothing
                // about whether Odysee would have answered.
                if (!OdyseeAuth.wasCancelled(e)) {
                    countsRetryAt = System.currentTimeMillis() + COUNTS_RETRY_MILLIS;
                }
                return Map.of();
            }
        }
        return Map.of();
    }

    // Odysee answers with one number for each claim asked about, in the order asked.
    private static Map<String, Long> countsByClaim(final List<String> ids, final Object data) {
        if (!(data instanceof JsonArray) || ((JsonArray) data).size() != ids.size()) {
            return Map.of();
        }
        final Map<String, Long> counts = new HashMap<>();
        for (int i = 0; i < ids.size(); i++) {
            final Object count = ((JsonArray) data).get(i);
            if (count instanceof Number && ((Number) count).longValue() >= 0) {
                counts.put(ids.get(i), ((Number) count).longValue());
            }
        }
        return counts;
    }

    /**
     * Calls the API that Odysee keeps accounts, reactions and counts in.
     *
     * @param url  the address of the method, such as {@link #REACTIONS_URL}
     * @param form the parameters, as a form body
     * @return the {@code data} of the answer
     * @throws NotAuthenticatedException if the method wants a visitor token and did not get
     *                                   one it accepts
     */
    @Nullable
    static Object callAccountApi(@Nonnull final String url, @Nonnull final String form)
            throws IOException, ExtractionException {
        final Map<String, List<String>> headers = refererHeaders();
        headers.put("Content-Type", List.of(FORM));
        final Response response = NewPipe.getDownloader().post(url, headers,
                form.getBytes(StandardCharsets.UTF_8));
        // Odysee refuses a token with 401 or 403 and says so in its answer. Something in
        // front of Odysee can answer 403 too, for a reason that a new token would not cure.
        final int status = response.responseCode();
        if (status == HTTP_UNAUTHORIZED || status == HTTP_FORBIDDEN) {
            if (isAuthenticationError(response.responseBody())) {
                throw new NotAuthenticatedException();
            }
            throw new IOException("Odysee answered HTTP " + status + " for " + url);
        }
        final String body = checked(response, url);
        if (isAuthenticationError(body)) {
            throw new NotAuthenticatedException();
        }
        final JsonObject envelope = parse(body);
        requireSuccess(envelope);
        return envelope.get("data");
    }

    private static boolean isAuthenticationError(@Nullable final String body) {
        try {
            final JsonObject envelope = JsonParser.object().from(body == null ? "" : body);
            return !envelope.getBoolean("success") && errorText(envelope.get("error"))
                    .toLowerCase(Locale.ROOT).contains("authenticat");
        } catch (final JsonParserException e) {
            return false;
        }
    }

    /**
     * Fetches a channel's livestream state.
     *
     * @param channelClaimId the channel that may be broadcasting
     * @return the livestream state of the channel
     */
    @Nonnull
    public static JsonObject livestream(@Nonnull final String channelClaimId)
            throws IOException, ExtractionException {
        final String url = LIVESTREAM_URL + "/is_live?channel_claim_id=" + channelClaimId;
        return unwrap(parse(get(url)));
    }

    /**
     * Fetches the livestream states of all broadcasting channels.
     *
     * @return the livestream state of every channel that has one
     */
    @Nonnull
    public static JsonArray livestreams() throws IOException, ExtractionException {
        final Map<String, List<String>> headers = refererHeaders();
        headers.put("Content-Type", List.of(FORM));
        final String url = LIVESTREAM_URL + "/all";
        final Response response = NewPipe.getDownloader().post(url, headers, new byte[0]);
        final JsonObject envelope = parse(checked(response, url));
        requireSuccess(envelope);
        return envelope.getArray("data");
    }

    /**
     * Fetches the definition of a homepage category.
     *
     * @param categoryKey a key of the website's homepage, such as {@code PRIMARY_CONTENT}
     * @return the definition of that homepage row, or an empty object if it is gone
     */
    @Nonnull
    public static JsonObject homepageCategory(@Nonnull final String categoryKey)
            throws IOException, ExtractionException {
        final CachedClaim cached = homepage;
        if (cached != null && cached.isYoungerThan(HOMEPAGE_CACHE_MILLIS)) {
            return cached.claim.getObject(categoryKey);
        }
        final JsonObject categories = parse(get(HOMEPAGE_URL + "?hp=en"))
                .getObject("data").getObject("en").getObject("categories");
        // An answer without categories is a failure of the moment and is asked for again.
        if (!categories.isEmpty()) {
            homepage = new CachedClaim(categories);
        }
        return categories.getObject(categoryKey);
    }

    /** Forgets what earlier requests returned. */
    public static void clearCaches() {
        RESOLVED.clear();
        homepage = null;
        clearCountCache();
    }

    static void clearCountCache() {
        COUNTS.clear();
        countsRetryAt = 0;
    }

    @Nonnull
    public static JsonArray array(@Nonnull final Collection<?> values) {
        final JsonArray array = new JsonArray();
        array.addAll(values);
        return array;
    }

    @Nonnull
    private static JsonObject resolveRequest(final JsonObject params)
            throws IOException, ExtractionException {
        try {
            return callProxy("resolve", params);
        } catch (final SocketTimeoutException timeout) {
            if (Thread.currentThread().isInterrupted()) {
                throw timeout;
            }
        }
        // Resolve is read-only, even though the proxy uses POST. Retry one timed-out
        // lookup; other errors and a second timeout still reach the caller.
        return callProxy("resolve", params);
    }

    @Nonnull
    private static JsonObject callProxy(@Nonnull final String method,
                                        @Nonnull final JsonObject params)
            throws IOException, ExtractionException {
        return callJsonRpc(PROXY_URL + "?m=" + method, method, params, JSON_RPC);
    }

    @Nonnull
    private static JsonObject callJsonRpc(@Nonnull final String url,
                                          @Nonnull final String method,
                                          @Nonnull final JsonObject params,
                                          @Nonnull final String contentType)
            throws IOException, ExtractionException {
        final JsonObject request = new JsonObject();
        request.put("jsonrpc", "2.0");
        request.put("method", method);
        request.put("params", params);
        request.put("id", 1);

        final Map<String, List<String>> headers = refererHeaders();
        headers.put("Content-Type", List.of(contentType));
        final Response response = NewPipe.getDownloader().post(url, headers,
                JsonWriter.string(request).getBytes(StandardCharsets.UTF_8));
        final JsonObject envelope = parse(checked(response, url));
        if (envelope.has("error")) {
            throw new ApiErrorException(errorText(envelope.get("error")));
        }
        return envelope.getObject("result");
    }

    private static Map<String, List<String>> refererHeaders() {
        final Map<String, List<String>> headers = new HashMap<>();
        headers.put("Referer", List.of(REFERER));
        return headers;
    }

    private static String checked(final Response response, final String url) throws IOException {
        if (response.responseCode() < 200 || response.responseCode() >= 300) {
            throw new IOException("Odysee answered HTTP " + response.responseCode()
                    + " for " + url);
        }
        return response.responseBody();
    }

    private static JsonObject parse(final String body) throws ParsingException {
        try {
            return JsonParser.object().from(body);
        } catch (final JsonParserException e) {
            throw new ParsingException("Could not parse an Odysee response", e);
        }
    }

    private static JsonObject unwrap(final JsonObject envelope) throws ApiErrorException {
        requireSuccess(envelope);
        return envelope.getObject("data");
    }

    private static void requireSuccess(final JsonObject envelope) throws ApiErrorException {
        if (!envelope.getBoolean("success")) {
            throw new ApiErrorException(errorText(envelope.get("error")));
        }
    }

    private static String errorText(@Nullable final Object error) {
        if (error instanceof JsonObject) {
            return ((JsonObject) error).getString("message", "unknown error");
        }
        return error == null ? "unknown error" : error.toString();
    }

    /** Odysee answered, and the answer was an error message. */
    public static final class ApiErrorException extends ExtractionException {
        ApiErrorException(final String message) {
            super("Odysee: " + message);
        }
    }

    /** Odysee wants a visitor token for this and was given none that it accepts. */
    public static final class NotAuthenticatedException extends ExtractionException {
        NotAuthenticatedException() {
            super("Odysee: authentication required");
        }
    }

    private static final class CachedClaim {
        private final JsonObject claim;
        private final long fetchedAt = System.currentTimeMillis();

        CachedClaim(final JsonObject claim) {
            this.claim = claim;
        }

        boolean isYoungerThan(final long millis) {
            return System.currentTimeMillis() - fetchedAt < millis;
        }
    }
}
