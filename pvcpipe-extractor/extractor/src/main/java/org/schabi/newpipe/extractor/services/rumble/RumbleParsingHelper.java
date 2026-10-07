package org.schabi.newpipe.extractor.services.rumble;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.schabi.newpipe.extractor.pvc.AttachException;
import org.schabi.newpipe.extractor.pvc.PvcCloudFlareChallengeException;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.localization.Localization;
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException;
import org.schabi.newpipe.extractor.exceptions.ParsingException;
import org.schabi.newpipe.extractor.exceptions.PrivateContentException;
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException;
import org.schabi.newpipe.extractor.utils.Utils;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

import static org.schabi.newpipe.extractor.ServiceList.Rumble;

@SuppressWarnings({"checkstyle:FinalLocalVariable", "checkstyle:FinalParameters"})
public final class RumbleParsingHelper {

    private RumbleParsingHelper() {
    }

    private static final Map<String, List<String>> HEADERS = new HashMap<>();

    public static int parseDurationStringForRelatedStreams(final String input)
            throws ParsingException {
        // input has the form of h:m:s
        return parseDurationString(input, "(:|:|:)");
    }

    public static int parseDurationString(final String input, final String split)
            throws ParsingException, NumberFormatException {

        final String[] splitInput = input.split(split);
        String days = "0";
        String hours = "0";
        String minutes = "0";
        final String seconds;

        switch (splitInput.length) {
            case 4:
                days = splitInput[0];
                hours = splitInput[1];
                minutes = splitInput[2];
                seconds = splitInput[3];
                break;
            case 3:
                hours = splitInput[0];
                minutes = splitInput[1];
                seconds = splitInput[2];
                break;
            case 2:
                minutes = splitInput[0];
                seconds = splitInput[1];
                break;
            case 1:
                seconds = splitInput[0];
                break;
            default:
                throw new ParsingException("Error duration string with unknown format: " + input);
        }

        return ((Integer.parseInt(Utils.removeNonDigitCharacters(days)) * 24
                + Integer.parseInt(Utils.removeNonDigitCharacters(hours))) * 60
                + Integer.parseInt(Utils.removeNonDigitCharacters(minutes))) * 60
                + Integer.parseInt(Utils.removeNonDigitCharacters(seconds));
    }

    /**
     * @param shouldThrowOnError if true a ParsingException is thrown on error
     * @param msg                in case of Exception the error message that is passed
     * @param callable           the function that extract the desired string
     * @return the extracted string or null if shouldThrowOnError is set to false
     * @throws ParsingException
     */
    public static <T> T extractSafely(
            final boolean shouldThrowOnError,
            final String msg,
            final Callable<T> callable) throws ParsingException {
        try {
            return Objects.requireNonNull(callable.call());
        } catch (final Exception e) {
            if (shouldThrowOnError) {
                throw new ParsingException(msg + ": " + e);
            }
        }
        return null;
    }

    @Nullable
    public static String getErrFromTitle(@Nullable final Document doc) {
        if (doc != null && !doc.title().isEmpty()) {
            return doc.title();
        } else {
            return null;
        }
    }

    public static String extractUploaderAvatarUrl(final String classStr,
                                                  final Document doc)
            throws ParsingException {
        return extractThumbnail(doc, classStr,
                () -> {
                    // extract checksum to use as identifier
                    final Pattern matchChecksum = Pattern.compile("([a-fA-F0-9]{32})");
                    final Matcher match2 = matchChecksum.matcher(classStr);
                    if (match2.find()) {
                        final String chkSum = match2.group(1);
                        return chkSum;
                    } else {
                        return null;
                    }
                });
    }

    /**
     * @return {@code null} when Rumble renders a letter avatar instead of an image
     */
    public static String extractThumbnail(final Document document,
                                          final String classStr,
                                          final Callable<String> function) throws ParsingException {

        // special case there is only a letter and no image as user thumbnail
        if (classStr.contains("user-image--letter")) {
            // assume uploader name will do the job
            return null;
        }

        final String thumbIdentifier;
        try {
            thumbIdentifier = function.call();
        } catch (final Exception e) {
            throw new ParsingException(e.getMessage(), e);
        }
        if (thumbIdentifier == null) {
            return null;
        }

        // extract thumbnail url
        final String matchThat = document.toString();
        final int pos = matchThat.indexOf(thumbIdentifier);
        final String preciselyMatchHere = matchThat.substring(pos);

        final Pattern channelThumbUrl =
                Pattern.compile("\\W+background-image:\\W+url(?:\\()([^)]*)(?:\\));");
        final Matcher match = channelThumbUrl.matcher(preciselyMatchHere);
        if (match.find()) {
            return match.group(1);
        }
        throw new ParsingException("Could not extract thumbUrl: " + thumbIdentifier);
    }

    public static String inferUploaderUrl(final String classStr,
                                          final Document doc,
                                          final String uploaderName)
            throws ParsingException, MalformedURLException {

        final String thumbnailUrl = extractUploaderAvatarUrl(classStr, doc);
        if (thumbnailUrl == null) {
            final String uploaderUrl = Rumble.getBaseUrl() + "/user/" + uploaderName
                    // remove all non alphanumeric characters except dash
                    .replaceAll("[^a-zA-Z0-9\\-]", "");
            return uploaderUrl;
        }

        // Again another special case here
        final URL url = Utils.stringToURL(thumbnailUrl);
        if (!url.getAuthority().contains("rmbl.ws")
                && !url.getAuthority().contains("rumble")
                && !url.getAuthority().contains("1a-1791.com")) {
            // there is no img hosted on rumble so we can't rely on it to extract the Channel.
            // So we try to use the name here too.
            final String uploaderUrl = Rumble.getBaseUrl() + "/user/" + uploaderName;
            return uploaderUrl;
        }

        // extract uploader name
        final int skipNoOfLetters = 5;
        final String path = thumbnailUrl.substring(thumbnailUrl.lastIndexOf("/")
                + 1 // skip '/'
                // the letters are not relevant but cause problems if there is a '-' -> skip them
                + skipNoOfLetters);
        final String[] splitPath = path.split("-", 0);
        final String theUploader = splitPath[1];

        // the uploaderUrl
        final String uploaderUrl = Rumble.getBaseUrl() + "/user/" + theUploader;
        return uploaderUrl;
    }

    public static long getViewCount(final Element element, final String pattern)
            throws ParsingException {
        final String errorMsg = "Could not extract the view count";
        final String viewCount =
                RumbleParsingHelper.extractSafely(true, errorMsg,
                        () -> element.select(pattern).first().text());
        try {
            return Utils.mixedNumberWordToLong(viewCount);
        } catch (final NumberFormatException e) {
            throw new ParsingException(errorMsg, e);
        }
    }

    /**
     * Generates the PNRC/RNRC cookie sent on category browse requests by
     * {@link org.schabi.newpipe.extractor.services.rumble.extractors.RumbleTrendingExtractor}.
     * {@link #getMinimalHeaders()} retains the first generated value for later pages.
     *
     * @return Cookie with pseudo-random values
     */
    private static String randomCookieGenerator() {
        final String rand = String.valueOf((int) (Math.random() * 10000));
        final String rand2 = String.valueOf((int) (Math.random() * 10000));
        final String randomCookie = "PNRC=" + rand + " ; RNRC=" + rand2;
        return randomCookie;
    }

    public static synchronized Map<String, List<String>> getMinimalHeaders() {
        final String cookie = "Cookie";
        if (!HEADERS.containsKey(cookie)) {
            HEADERS.put("Cookie", Collections.singletonList(randomCookieGenerator()));
        }
        return HEADERS;
    }
    private static final int EMBED_VIDEO_ID_CACHE_SIZE = 64;
    private static final Map<String, String> EMBED_VIDEO_IDS_CACHE =
            Collections.synchronizedMap(new LinkedHashMap<String, String>(
                    EMBED_VIDEO_ID_CACHE_SIZE, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(final Map.Entry<String, String> eldest) {
                    return size() > EMBED_VIDEO_ID_CACHE_SIZE;
                }
            });

    public static String getEmbedVideoId(
            final String url,
            final Callable<String> contentProvider) throws ParsingException {
        final String cachedId = EMBED_VIDEO_IDS_CACHE.get(url);
        if (cachedId != null) {
            return cachedId;
        }
        final String validUrl = "https?://(?:www\\.)?rumble\\.com/embed/"
                + "(?:[0-9a-z]+\\.)?([0-9a-z]+)"; // id is group 1
        final String embedRegex = "(?:<(?:script|iframe)[^>]+\\bsrc=|"
                + "[\"']embedUrl[\"']\\s*:\\s*)[\"']" + validUrl;
        Pattern pattern = Pattern.compile(embedRegex);
        final String content;
        try {
            content = contentProvider.call();
        } catch (final ParsingException e) {
            throw e;
        } catch (final Exception e) {
            throw new ParsingException("Could not extract the embed id due to missing content", e);
        }

        Matcher matcher = pattern.matcher(content);
        String embedId = matcher.find() ? matcher.group(1) : null;
        if (embedId == null) {
            pattern = Pattern.compile("\\bRumble\\(\\s*[\"']play[\"']\\s*,\\s*\\{[^}]*"
                    + "[\"']?video[\"']?\\s*:\\s*[\"'](v[0-9a-z]+)[\"']");
            for (final Element script : Jsoup.parse(content).select("script")) {
                matcher = pattern.matcher(script.data());
                if (matcher.find()) {
                    embedId = matcher.group(1);
                    break;
                }
            }
        }
        if (embedId != null) {
            // Remove v (first character) from the id.
            final String result = embedId.substring(1);
            EMBED_VIDEO_IDS_CACHE.put(url, result);
            return result;
        }
        throw AttachException.createAttachException(
                "Could not extract the embed id due to missing context. URL: " + url,
                content,
                "internalVideoId",
                "(.{0,100}(<title>|embed|\"video\":).{0,100})"
        );
    }

    public static void checkIfContentIsAccessible(
            final Response response,
            final Document doc)
            throws ContentNotAvailableException, PvcCloudFlareChallengeException {
        final int code = response.responseCode();
        if (code == 403) {
            final String errMsg = code + " - " + getErrFromTitle(doc);
            if (errMsg.toLowerCase(Locale.ROOT).contains("private")) {
                throw new PrivateContentException(errMsg);
            } else if (doc.selectFirst("span#challenge-error-text") != null
                    || errMsg.toLowerCase(Locale.ROOT).contains("just a moment...")) {
                throw new PvcCloudFlareChallengeException(
                        errMsg + " for " + response.latestUrl());
            } else {
                throw new ContentNotAvailableException(errMsg);
            }

        } else if (code >= 400) {
            String errMsg = getErrFromTitle(doc);
            if (errMsg == null) {
                errMsg = "Rumble request failed";
            }
            throw new ContentNotAvailableException(code + " - " + errMsg);
        }
    }

    public static Document fetchParseValidate(
            final Downloader downloader,
            final String url)
            throws IOException, ReCaptchaException, ParsingException {
        return fetchParseValidate(downloader, url, null, null);
    }

    public static Document fetchParseValidate(
            final Downloader downloader,
            final String url,
            @Nullable final Map<String, List<String>> headers,
            @Nullable final Localization localization)
            throws IOException, ReCaptchaException, ParsingException {
        final Response response = fetchResponse(downloader, url, headers, localization);
        return Jsoup.parse(response.responseBody(), response.latestUrl());
    }

    public static Response fetchResponse(final Downloader downloader, final String url)
            throws IOException, ReCaptchaException, ParsingException {
        return fetchResponse(downloader, url, null, null);
    }

    public static Response fetchResponse(
            final Downloader downloader,
            final String url,
            @Nullable final Map<String, List<String>> headers,
            @Nullable final Localization localization)
            throws IOException, ReCaptchaException, ParsingException {
        final Response response = localization == null
                ? downloader.get(url, headers) : downloader.get(url, headers, localization);
        if (response.responseCode() >= 400) {
            checkIfContentIsAccessible(response,
                    Jsoup.parse(response.responseBody(), response.latestUrl()));
        }
        return response;
    }
}
