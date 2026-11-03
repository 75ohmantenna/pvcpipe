package org.schabi.newpipe.extractor.services.rumble.extractors;

import com.grack.nanojson.JsonObject;
import com.grack.nanojson.JsonWriter;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import org.schabi.newpipe.extractor.Page;
import org.schabi.newpipe.extractor.StreamingService;
import org.schabi.newpipe.extractor.comments.CommentsExtractor;
import org.schabi.newpipe.extractor.comments.CommentsInfoItem;
import org.schabi.newpipe.extractor.comments.CommentsInfoItemsCollector;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException;
import org.schabi.newpipe.extractor.exceptions.ExtractionException;
import org.schabi.newpipe.extractor.exceptions.ParsingException;
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException;
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler;
import org.schabi.newpipe.extractor.services.rumble.RumbleMediaResolver;
import org.schabi.newpipe.extractor.services.rumble.RumbleApi;
import org.schabi.newpipe.extractor.services.rumble.linkHandler.RumbleStreamLinkHandlerFactory;
import org.schabi.newpipe.extractor.utils.JsonUtils;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@SuppressWarnings({"checkstyle:FinalLocalVariable", "checkstyle:FinalParameters"})
public class RumbleCommentsExtractor extends CommentsExtractor {
    private static final Pattern USER_IMAGE_PATTERN = Pattern.compile(
            "i\\.user-image--img--id-(\\w+)\\s*\\{\\s*"
                    + "background-image:\\s*url\\(([^)]+)\\)");
    private final int maxCommentsPerPage = 15;

    private Map<String, String> imageMap;

    private Document doc;
    private byte[] initialResponseBody;
    private String commentsUrl;
    private ExtractionException responseError;

    public RumbleCommentsExtractor(
            final StreamingService service,
            final ListLinkHandler uiHandler) {
        super(service, uiHandler);
    }

    @Nonnull
    @Override
    public String getUrl() throws ParsingException {
        if (commentsUrl != null) {
            return commentsUrl;
        }
        final String embedId;
        try {
            embedId = RumbleMediaResolver.resolveEmbedId(getDownloader(),
                    RumbleStreamLinkHandlerFactory.getInstance().fromUrl(getOriginalUrl()),
                    getExtractorLocalization());
        } catch (final IOException | ReCaptchaException e) {
            throw new ParsingException("Could not resolve Rumble comment video", e);
        }
        commentsUrl = "https://rumble.com/service.php?video=" + embedId.substring(1)
                + "&name=comment.list";
        return commentsUrl;
    }

    @Override
    public boolean isCommentsDisabled() throws ExtractionException {
        if (responseError != null) {
            throw responseError;
        }
        return doc == null;
    }

    @Nonnull
    @Override
    public InfoItemsPage<CommentsInfoItem> getInitialPage()
            throws IOException, ExtractionException {
        assertPageFetched();
        return getPage(new Page("1", initialResponseBody));
    }

    @Override
    public InfoItemsPage<CommentsInfoItem> getPage(final Page page)
            throws IOException, ExtractionException {
        if (page == null) {
            clearResponseState();
            throw responseError;
        }
        byte[] responseBody = page.getBody();
        loadFromResponseBody(responseBody);
        if (isCommentsDisabled()) {
            return new InfoItemsPage<>(Collections.emptyList(), null, Collections.emptyList());
        }
        int[] ids = stringToIntArray(page.getUrl());
        int startIndex = ids[ids.length - 1] - 1;
        int count = startIndex + maxCommentsPerPage + 1;
        Element next = null;
        final CommentsInfoItemsCollector collector = new CommentsInfoItemsCollector(
                getServiceId());
        for (; startIndex < count; startIndex++) {
            ids[ids.length - 1] = startIndex + 1;
            next = getComments(ids).first();
            if (next == null || startIndex == count - 1) {
                break;
            }
            collector.commit(new RumbleCommentsInfoItemExtractor(this, ids, responseBody));
        }
        return new InfoItemsPage<>(collector, next != null
                ? new Page(intArrayToString(ids), responseBody) : null);
    }

    @Override
    public void onFetchPage(@Nonnull final Downloader downloader)
            throws IOException, ExtractionException {
        clearResponseState();
        initialResponseBody = null;
        try {
            final JsonObject response = RumbleApi.fetchJson(
                    downloader, getUrl(), getExtractorLocalization());
            final byte[] body = JsonWriter.string(response).getBytes(StandardCharsets.UTF_8);
            loadFromResponseBody(body);
            initialResponseBody = body;
        } catch (final ExtractionException e) {
            responseError = e;
            throw e;
        } catch (final IOException e) {
            responseError = new ParsingException("Could not fetch Rumble comments", e);
            throw e;
        }
    }

    public Elements getComments(int[] id) {
        if (doc == null) {
            return null;
        }
        int level = 1;
        StringBuilder selection = new StringBuilder();
        for (int i : id) {
            if (level != 1) {
                selection.append(" > div.comment-replies > ");
            }
            selection.append("ul.comments-").append(level++).append(" > li.comment-item");
            if (i != 0) {
                selection.append(":nth-child(").append(i).append(")");
            }
        }
        return doc.select(selection.toString());
    }

    public String getImage(Element e) {
        Element element = e.selectFirst("i.user-image");
        if (element == null || imageMap == null) {
            return null;
        }
        String attr = element.className();
        String[] classes = attr.split(" ");
        for (String name : classes) {
            if (name.startsWith("user-image--img--id-")
                    && imageMap.containsKey(name)) {
                return imageMap.get(name);
            }
        }
        return null;
    }

    public static String intArrayToString(int[] intArray) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < intArray.length; i++) {
            sb.append(intArray[i]);
            if (i < intArray.length - 1) {
                sb.append(" ");
            }
        }
        return sb.toString();
    }

    private static int[] stringToIntArray(String str) {
        String[] stringArray = str.split(" ");
        int[] intArray = new int[stringArray.length];
        for (int i = 0; i < stringArray.length; i++) {
            intArray[i] = Integer.parseInt(stringArray[i]);
        }
        return intArray;
    }

    private void initImageMap(String css) {
        Matcher matcher = USER_IMAGE_PATTERN.matcher(css);
        imageMap = new HashMap<>();
        while (matcher.find()) {
            String key = "user-image--img--id-" + matcher.group(1);
            String value = matcher.group(2);
            imageMap.put(key, value);
        }
    }

    private void clearResponseState() {
        doc = null;
        imageMap = null;
        responseError = new ParsingException("Rumble comments response is unavailable");
    }

    private void loadFromResponseBody(final byte[] responseBody) throws ExtractionException {
        clearResponseState();
        try {
            if (responseBody == null) {
                throw new ParsingException("Missing Rumble comments response body");
            }
            final JsonObject info = JsonUtils.toJsonObject(
                    new String(responseBody, StandardCharsets.UTF_8));
            // Continuations carry a body rather than fetching a new network response.
            // Validate their envelope as well, even if an error also supplies HTML.
            validateContinuationEnvelope(info);
            final String html = JsonUtils.getString(info, "html");
            final Object stylesheets = JsonUtils.getValue(info, "css_libs");
            final String css;
            if (stylesheets instanceof String) {
                css = (String) stylesheets;
            } else if (stylesheets instanceof JsonObject) {
                final StringBuilder combined = new StringBuilder();
                for (final Object value : ((JsonObject) stylesheets).values()) {
                    if (!(value instanceof String)) {
                        throw new ParsingException("Invalid Rumble comment stylesheet value");
                    }
                    combined.append((String) value).append('\n');
                }
                css = combined.toString();
            } else {
                throw new ParsingException("Invalid Rumble comments css_libs");
            }
            final Document parsed = Jsoup.parse(html);
            if (parsed.selectFirst("ul.comments-1") != null) {
                parsed.select("li.comment-item.comment-item.comments-create").remove();
                initImageMap(css);
                doc = parsed;
            }
            responseError = null;
        } catch (final ExtractionException e) {
            responseError = e;
            throw e;
        }
    }

    private static void validateContinuationEnvelope(final JsonObject info)
            throws ParsingException {
        if (info.get("errors") != null && !JsonUtils.getArray(info, "errors").isEmpty()) {
            throw new ContentNotAvailableException(
                    "Rumble API error: " + JsonWriter.string(info.get("errors")));
        }
        final Object error = info.get("error");
        if (error != null
                && !(error instanceof String && ((String) error).trim().isEmpty())
                && !(error instanceof Map && ((Map<?, ?>) error).isEmpty())
                && !(error instanceof java.util.List && ((java.util.List<?>) error).isEmpty())) {
            throw new ContentNotAvailableException("Rumble API error: " + JsonWriter.string(error));
        }
        if (info.get("data") instanceof JsonObject) {
            final JsonObject data = JsonUtils.getObject(info, "data");
            if (data.get("success") != null && !JsonUtils.getBoolean(data, "success")) {
                throw new ContentNotAvailableException(
                        "Rumble API error: " + JsonWriter.string(data));
            }
        }
    }
}
