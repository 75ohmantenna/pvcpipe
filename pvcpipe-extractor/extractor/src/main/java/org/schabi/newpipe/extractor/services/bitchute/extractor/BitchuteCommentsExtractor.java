package org.schabi.newpipe.extractor.services.bitchute.extractor;

import com.grack.nanojson.JsonArray;
import com.grack.nanojson.JsonObject;
import com.grack.nanojson.JsonParser;
import com.grack.nanojson.JsonParserException;

import org.schabi.newpipe.extractor.Page;
import org.schabi.newpipe.extractor.StreamingService;
import org.schabi.newpipe.extractor.comments.CommentsExtractor;
import org.schabi.newpipe.extractor.comments.CommentsInfoItem;
import org.schabi.newpipe.extractor.comments.CommentsInfoItemsCollector;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.exceptions.ExtractionException;
import org.schabi.newpipe.extractor.exceptions.ParsingException;
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler;
import org.schabi.newpipe.extractor.services.bitchute.BitchuteParserHelper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

import javax.annotation.Nonnull;

public class BitchuteCommentsExtractor extends CommentsExtractor {
    private JsonArray comments;

    public BitchuteCommentsExtractor(final StreamingService service,
                                     final ListLinkHandler uiHandler) {
        super(service, uiHandler);
    }

    @Nonnull
    @Override
    public InfoItemsPage<CommentsInfoItem> getInitialPage() throws ExtractionException,
            IOException {

        if (comments == null) {
            fetchPage();
        }
        return collectComments(comments, null);
    }

    @Override
    public InfoItemsPage<CommentsInfoItem> getPage(final Page page) throws ExtractionException,
            IOException {
        if (page == null || page.getBody() == null || page.getId() == null) {
            throw new ParsingException("Invalid BitChute replies page");
        }
        try {
            return collectComments(JsonParser.array().from(
                    new String(page.getBody(), StandardCharsets.UTF_8)), page.getId());
        } catch (final JsonParserException e) {
            throw new ParsingException("Could not parse BitChute replies", e);
        }
    }

    @Override
    public void onFetchPage(@Nonnull final Downloader downloader)
            throws ExtractionException, IOException {
        comments = BitchuteParserHelper.getComments(getId(), getUrl(), 0);
    }

    private InfoItemsPage<CommentsInfoItem> collectComments(final JsonArray snapshot,
                                                           final String parentId)
            throws ParsingException {
        final CommentsInfoItemsCollector collector = new CommentsInfoItemsCollector(
                getServiceId());
        final Set<String> ids = new HashSet<>();
        for (final Object entry : snapshot) {
            ids.add(((JsonObject) entry).getString("id"));
        }
        final String url = getUrl();
        for (final Object entry : snapshot) {
            final JsonObject comment = (JsonObject) entry;
            final String parent = comment.getString("parent");
            if (parentId == null ? parent == null || !ids.contains(parent)
                    : parentId.equals(parent)) {
                collector.commit(new BitchuteCommentsInfoItemExtractor(comment, url, snapshot));
            }
        }
        return new InfoItemsPage<>(collector, null);
    }
}
