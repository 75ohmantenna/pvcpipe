package org.schabi.newpipe.extractor.services.bitchute.extractor;

import com.github.pvcpipe.json2java4nanojson.bitchute.api.results.stream.videos.Videos;
import com.grack.nanojson.JsonArray;
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
import org.schabi.newpipe.extractor.services.bitchute.BitchuteParserHelper;
import org.schabi.newpipe.extractor.services.bitchute.misc.BitchuteHelpers;
import org.schabi.newpipe.extractor.stream.Description;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.StreamInfoItemsCollector;
import org.schabi.newpipe.extractor.utils.Utils;

import java.io.IOException;
import java.util.List;

import javax.annotation.Nonnull;

/** Public playlists with row-offset pagination; unavailable rows still consume an offset. */
public final class BitchutePlaylistExtractor extends PlaylistExtractor {
    private static final String API = "https://api.bitchute.com/api/beta/playlist";
    private JsonObject playlist;

    public BitchutePlaylistExtractor(final StreamingService service,
                                    final ListLinkHandler handler) {
        super(service, handler);
    }

    @Override
    public void onFetchPage(@Nonnull final Downloader downloader)
            throws IOException, ExtractionException {
        playlist = BitchuteParserHelper.callJsonApi(
                JsonObject.builder().value("playlist_id", getId()), API);
        if (!playlist.getBoolean("is_public") || playlist.getBoolean("is_restricted")) {
            throw new ContentNotAvailableException("This BitChute playlist is not public"
                    + " or is restricted");
        }
        if (Utils.isNullOrEmpty(playlist.getString("playlist_name"))) {
            throw new ParsingException("BitChute returned no playlist name");
        }
    }

    @Nonnull
    @Override
    public String getName() {
        return playlist.getString("playlist_name");
    }

    @Override
    public String getUploaderName() {
        return playlist.getString("profile_name", "");
    }

    @Override
    public String getUploaderUrl() {
        // Playlist owners are profiles, which are not interchangeable with video channels.
        return "";
    }

    @Nonnull
    @Override
    public List<Image> getUploaderAvatars() {
        return List.of();
    }

    @Override
    public boolean isUploaderVerified() {
        return false;
    }

    @Override
    public long getStreamCount() {
        return playlist.getLong("video_count", -1);
    }

    @Nonnull
    @Override
    public Description getDescription() {
        return new Description(playlist.getString("description", ""), Description.PLAIN_TEXT);
    }

    @Nonnull
    @Override
    public List<Image> getThumbnails() {
        final String url = playlist.getString("thumbnail_url");
        return Utils.isNullOrEmpty(url) ? List.of() : List.of(BitchuteHelpers.toImage(url));
    }

    @Nonnull
    @Override
    public InfoItemsPage<StreamInfoItem> getInitialPage() throws IOException, ExtractionException {
        assertPageFetched();
        return getPage(new Page(getUrl(), "0"));
    }

    @Override
    public InfoItemsPage<StreamInfoItem> getPage(final Page page)
            throws IOException, ExtractionException {
        final int offset;
        try {
            if (page == null || !getUrl().equals(page.getUrl())) {
                throw new IllegalArgumentException("Page belongs to another playlist");
            }
            offset = Integer.parseInt(page.getId());
            if (offset < 0) {
                throw new IllegalArgumentException("Negative playlist offset");
            }
        } catch (final RuntimeException e) {
            throw new ParsingException("Invalid BitChute playlist page", e);
        }
        final JsonObject response = BitchuteParserHelper.callJsonApi(JsonObject.builder()
                .value("playlist_id", getId()).value("offset", offset).value("limit", 50),
                API + "/videos");
        if (!(response.get("videos") instanceof JsonArray)) {
            throw new ParsingException("BitChute returned no playlist videos array");
        }
        final JsonArray rows = response.getArray("videos");
        final StreamInfoItemsCollector items = new StreamInfoItemsCollector(getServiceId());
        for (final Object row : rows) {
            if (row instanceof JsonObject) {
                final JsonObject video = ((JsonObject) row).getObject("video");
                if (!Utils.isNullOrEmpty(video.getString("video_id"))) {
                    items.commit(new BitchuteVideoInfoItemExtractor(new Videos(video)));
                }
            }
        }
        // The server can clamp the requested limit. Deleted rows still consume an offset.
        final long next = (long) offset + rows.size();
        final long total = response.getLong("video_count", -1);
        final Page nextPage = rows.isEmpty() || next > Integer.MAX_VALUE
                || (total >= 0 && next >= total) ? null : new Page(getUrl(), Long.toString(next));
        return new InfoItemsPage<>(items, nextPage);
    }
}
