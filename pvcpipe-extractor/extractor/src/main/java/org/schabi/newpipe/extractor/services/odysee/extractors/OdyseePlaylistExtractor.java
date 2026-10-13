package org.schabi.newpipe.extractor.services.odysee.extractors;

import com.grack.nanojson.JsonObject;

import org.schabi.newpipe.extractor.Image;
import org.schabi.newpipe.extractor.Page;
import org.schabi.newpipe.extractor.StreamingService;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException;
import org.schabi.newpipe.extractor.exceptions.ExtractionException;
import org.schabi.newpipe.extractor.exceptions.ParsingException;
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler;
import org.schabi.newpipe.extractor.playlist.PlaylistExtractor;
import org.schabi.newpipe.extractor.services.odysee.OdyseeApi;
import org.schabi.newpipe.extractor.services.odysee.OdyseeClaim;
import org.schabi.newpipe.extractor.stream.Description;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.StreamInfoItemsCollector;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nonnull;

public class OdyseePlaylistExtractor extends PlaylistExtractor {

    private JsonObject claim;
    private List<String> claimIds = List.of();

    public OdyseePlaylistExtractor(final StreamingService service,
                                   final ListLinkHandler linkHandler) {
        super(service, linkHandler);
    }

    @Override
    public void onFetchPage(@Nonnull final Downloader downloader)
            throws IOException, ExtractionException {
        final List<JsonObject> found = OdyseeApi.claimsByIds(List.of(getId()));
        if (found.isEmpty() || !OdyseeClaim.isPlaylist(found.get(0))) {
            throw new ContentNotAvailableException("Odysee has no playlist " + getId());
        }
        claim = found.get(0);
        final List<String> ids = new ArrayList<>();
        for (final Object id : OdyseeClaim.value(claim).getArray("claims")) {
            if (id instanceof String) {
                ids.add((String) id);
            }
        }
        claimIds = ids;
    }

    @Nonnull
    @Override
    public String getName() {
        return OdyseeClaim.title(claim);
    }

    @Nonnull
    @Override
    public List<Image> getThumbnails() {
        return OdyseeClaim.thumbnails(claim);
    }

    @Override
    public String getUploaderUrl() {
        return OdyseeClaim.uploaderUrl(claim);
    }

    @Override
    public String getUploaderName() {
        return OdyseeClaim.uploaderName(claim);
    }

    @Nonnull
    @Override
    public List<Image> getUploaderAvatars() {
        return OdyseeClaim.uploaderAvatars(claim);
    }

    @Override
    public boolean isUploaderVerified() {
        return false; // Odysee has no verification mark.
    }

    @Override
    public long getStreamCount() {
        return claimIds.size();
    }

    @Nonnull
    @Override
    public Description getDescription() {
        return new Description(OdyseeClaim.value(claim).getString("description", ""),
                Description.PLAIN_TEXT);
    }

    @Nonnull
    @Override
    public InfoItemsPage<StreamInfoItem> getInitialPage() throws IOException, ExtractionException {
        return getPage(0);
    }

    @Override
    public InfoItemsPage<StreamInfoItem> getPage(final Page page)
            throws IOException, ExtractionException {
        if (page == null || page.getId() == null) {
            throw new ParsingException("Invalid Odysee playlist page");
        }
        try {
            return getPage(Integer.parseInt(page.getId()));
        } catch (final NumberFormatException e) {
            throw new ParsingException("Invalid Odysee playlist page: " + page.getId(), e);
        }
    }

    // A playlist is a list of claim ids, which are looked up a page at a time.
    private InfoItemsPage<StreamInfoItem> getPage(final int offset)
            throws IOException, ExtractionException {
        if (claim == null) {
            fetchPage();
        }
        final int end = Math.min(claimIds.size(), offset + OdyseeApi.MAX_PAGE_SIZE);
        final StreamInfoItemsCollector items = new StreamInfoItemsCollector(getServiceId());
        if (offset < end) {
            OdyseeItems.commitStreams(items,
                    OdyseeApi.claimsByIds(claimIds.subList(offset, end)));
        }
        return new InfoItemsPage<>(items,
                end < claimIds.size() ? new Page(getUrl(), String.valueOf(end)) : null);
    }
}
