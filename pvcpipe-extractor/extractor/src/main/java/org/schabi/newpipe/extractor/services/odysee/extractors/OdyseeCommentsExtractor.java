package org.schabi.newpipe.extractor.services.odysee.extractors;

import com.grack.nanojson.JsonObject;

import org.schabi.newpipe.extractor.Page;
import org.schabi.newpipe.extractor.StreamingService;
import org.schabi.newpipe.extractor.comments.CommentsExtractor;
import org.schabi.newpipe.extractor.comments.CommentsInfoItem;
import org.schabi.newpipe.extractor.comments.CommentsInfoItemsCollector;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.exceptions.ExtractionException;
import org.schabi.newpipe.extractor.exceptions.ParsingException;
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler;
import org.schabi.newpipe.extractor.services.odysee.OdyseeApi;
import org.schabi.newpipe.extractor.services.odysee.OdyseeClaim;
import org.schabi.newpipe.extractor.services.odysee.OdyseeUrl;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class OdyseeCommentsExtractor extends CommentsExtractor {

    static final String REPLIES_PAGE_PREFIX = "replies:";

    private static final int PAGE_SIZE = 20;
    // The orders the comment service knows by number.
    private static final int SORT_OLDEST = 1;
    private static final int SORT_BEST = 3;

    private String claimId;
    private int commentsCount = -1;

    public OdyseeCommentsExtractor(final StreamingService service,
                                   final ListLinkHandler linkHandler) {
        super(service, linkHandler);
    }

    @Override
    public void onFetchPage(@Nonnull final Downloader downloader)
            throws IOException, ExtractionException {
        // The video was resolved a moment ago when it was opened.
        claimId = OdyseeClaim.unwrapRepost(
                OdyseeApi.resolveCached(OdyseeUrl.toLbryUri(getId()))).getString("claim_id");
    }

    @Override
    public int getCommentsCount() {
        return commentsCount;
    }

    @Nonnull
    @Override
    public InfoItemsPage<CommentsInfoItem> getInitialPage()
            throws IOException, ExtractionException {
        return getPage(null, 1);
    }

    @Override
    public InfoItemsPage<CommentsInfoItem> getPage(final Page page)
            throws IOException, ExtractionException {
        if (page == null || page.getId() == null) {
            throw new ParsingException("Invalid Odysee comments page");
        }
        // A page id is a page number, with "replies:<comment id>:" in front for a reply thread.
        final String id = page.getId();
        try {
            if (id.startsWith(REPLIES_PAGE_PREFIX)) {
                final int separator = id.lastIndexOf(':');
                return getPage(id.substring(REPLIES_PAGE_PREFIX.length(), separator),
                        Integer.parseInt(id.substring(separator + 1)));
            }
            return getPage(null, Integer.parseInt(id));
        } catch (final NumberFormatException | IndexOutOfBoundsException e) {
            throw new ParsingException("Invalid Odysee comments page: " + id, e);
        }
    }

    private InfoItemsPage<CommentsInfoItem> getPage(@Nullable final String parentId,
                                                    final int pageNumber)
            throws IOException, ExtractionException {
        if (claimId == null) {
            fetchPage();
        }
        final JsonObject params = new JsonObject();
        params.put("claim_id", claimId);
        params.put("page", pageNumber);
        params.put("page_size", PAGE_SIZE);
        if (parentId == null) {
            params.put("top_level", true);
            params.put("sort_by", SORT_BEST);
        } else {
            params.put("parent_id", parentId);
            params.put("sort_by", SORT_OLDEST);
        }
        final JsonObject result = OdyseeApi.callComments("comment.List", params);
        final List<JsonObject> comments = OdyseeClaim.list(result.getArray("items"));
        if (parentId == null) {
            commentsCount = result.getInt("total_items", commentsCount);
        }

        final JsonObject reactions = fetchReactions(comments);
        final Map<String, JsonObject> authors = fetchAuthors(comments);
        final String url = getUrl();
        final CommentsInfoItemsCollector items = new CommentsInfoItemsCollector(getServiceId());
        for (final JsonObject comment : comments) {
            final String commentId = comment.getString("comment_id", "");
            items.commit(new OdyseeCommentsInfoItemExtractor(comment, url,
                    reactions == null ? null : reactions.getObject(commentId),
                    authors.get(comment.getString("channel_id"))));
        }

        final String nextId = (parentId == null ? "" : REPLIES_PAGE_PREFIX + parentId + ":")
                + (pageNumber + 1);
        return new InfoItemsPage<>(items, pageNumber < result.getInt("total_pages", 0)
                ? new Page(url, nextId) : null);
    }

    // Like counts and avatars come from two further requests. Comments are shown without them
    // if those fail.
    @Nullable
    private static JsonObject fetchReactions(final List<JsonObject> comments) {
        if (comments.isEmpty()) {
            return null;
        }
        final List<String> ids = new ArrayList<>();
        for (final JsonObject comment : comments) {
            ids.add(comment.getString("comment_id", ""));
        }
        final JsonObject params = new JsonObject();
        params.put("comment_ids", String.join(",", ids));
        try {
            return OdyseeApi.callComments("reaction.List", params).getObject("others_reactions");
        } catch (final IOException | ExtractionException | RuntimeException e) {
            return null;
        }
    }

    private static Map<String, JsonObject> fetchAuthors(final List<JsonObject> comments) {
        final Set<String> channelIds = new LinkedHashSet<>();
        for (final JsonObject comment : comments) {
            final String channelId = comment.getString("channel_id");
            if (channelId != null && !channelId.isEmpty()) {
                channelIds.add(channelId);
            }
        }
        final Map<String, JsonObject> authors = new HashMap<>();
        try {
            for (final JsonObject channel : OdyseeApi.claimsByIds(channelIds)) {
                authors.put(channel.getString("claim_id"), channel);
            }
        } catch (final IOException | ExtractionException | RuntimeException e) {
            return authors;
        }
        return authors;
    }
}
