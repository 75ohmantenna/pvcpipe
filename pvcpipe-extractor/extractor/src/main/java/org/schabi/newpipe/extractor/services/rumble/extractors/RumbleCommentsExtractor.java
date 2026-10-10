package org.schabi.newpipe.extractor.services.rumble.extractors;

import com.grack.nanojson.JsonArray;
import com.grack.nanojson.JsonObject;
import com.grack.nanojson.JsonParser;
import com.grack.nanojson.JsonParserException;

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
import org.schabi.newpipe.extractor.exceptions.ExtractionException;
import org.schabi.newpipe.extractor.exceptions.ParsingException;
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException;
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler;
import org.schabi.newpipe.extractor.services.rumble.RumbleParsingHelper;
import org.schabi.newpipe.extractor.utils.Utils;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.net.MalformedURLException;
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
        final String videoUrl = getOriginalUrl();
        final String path;
        try {
            path = Utils.stringToURL(videoUrl).getPath();
        } catch (final MalformedURLException e) {
            throw new ParsingException("Invalid Rumble video URL", e);
        }
        final String id;
        if (path.startsWith("/embed/")) {
            id = getId().substring(1);
        } else if (path.startsWith("/shorts/")) {
            id = getShortsVideoId(videoUrl);
        } else {
            id = RumbleParsingHelper.getEmbedVideoId(videoUrl,
                    () -> RumbleParsingHelper.fetchResponse(getDownloader(), videoUrl)
                            .responseBody());
        }
        commentsUrl = "https://rumble.com/service.php?video=" + id + "&name=comment.list";
        return commentsUrl;
    }

    private String getShortsVideoId(final String url) throws ParsingException {
        final Document shortsDoc;
        try {
            shortsDoc = RumbleParsingHelper.fetchParseValidate(getDownloader(), url);
        } catch (final IOException | ReCaptchaException e) {
            throw new ParsingException("Could not fetch Rumble shorts page", e);
        }
        final Element script = shortsDoc.selectFirst("rum-shorts script[type=application/json]");
        if (script == null) {
            throw new ParsingException("Rumble shorts JSON not found");
        }
        try {
            final JsonObject root = JsonParser.object().from(script.data());
            final JsonArray items = root.getArray("items");
            if (items == null) {
                throw new ParsingException("Rumble shorts items not found");
            }
            for (final Object item : items) {
                if (item instanceof JsonObject) {
                    final JsonObject video = (JsonObject) item;
                    if (getId().equals(video.getString("permalink_id"))
                            && video.getLong("id") > 0) {
                        return Long.toString(video.getLong("id"), 36);
                    }
                }
            }
        } catch (final JsonParserException e) {
            throw new ParsingException("Could not parse Rumble shorts JSON", e);
        }
        throw new ParsingException("Short video not found in JSON");
    }

    @Override
    public boolean isCommentsDisabled() throws ExtractionException {
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
        initialResponseBody = RumbleParsingHelper.fetchResponse(downloader, getUrl())
                .responseBody().getBytes(StandardCharsets.UTF_8);
        loadFromResponseBody(initialResponseBody);
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

    private void loadFromResponseBody(byte[] responseBody) throws ExtractionException {
        try {
            if (responseBody == null) {
                return;
            }
            JsonObject info = JsonParser.object().from(
                    new String(responseBody, StandardCharsets.UTF_8));
            if (info.has("html") && info.has("css_libs")) {
                doc = Jsoup.parse(info.get("html").toString());
                if (doc.selectFirst("ul.comments-1") == null) {
                    doc = null;
                    return;
                }
                Elements createComment = doc.select("li.comment-item.comment-item.comments-create");
                if (!createComment.isEmpty()) {
                    createComment.remove();
                }
                initImageMap(info.get("css_libs").toString());
            }
        } catch (final JsonParserException e) {
            e.printStackTrace();
            throw new ExtractionException("Could not read json from: " + getUrl());
        }
    }
}
