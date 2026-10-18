package org.schabi.newpipe.extractor.services.kick.extractors;

import com.grack.nanojson.JsonObject;

import org.schabi.newpipe.extractor.Image;
import org.schabi.newpipe.extractor.StreamingService;
import org.schabi.newpipe.extractor.channel.ChannelExtractor;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.exceptions.ExtractionException;
import org.schabi.newpipe.extractor.exceptions.ParsingException;
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler;
import org.schabi.newpipe.extractor.services.kick.KickApi;
import org.schabi.newpipe.extractor.services.kick.KickLinkHandlerFactory;
import org.schabi.newpipe.extractor.services.kick.KickUrl;
import org.schabi.newpipe.extractor.utils.PvcPipeExtractorUtils;

import java.io.IOException;
import java.util.List;

import javax.annotation.Nonnull;

public final class KickChannelExtractor extends ChannelExtractor {
    private JsonObject channel;

    public KickChannelExtractor(final StreamingService service, final ListLinkHandler handler) {
        super(service, handler);
    }

    @Override
    public void onFetchPage(@Nonnull final Downloader downloader)
            throws IOException, ExtractionException {
        channel = KickApi.channel(getId());
    }

    @Nonnull
    @Override
    public String getId() throws ParsingException {
        return channel == null ? super.getId() : KickUrl.slug(channel.getString("slug"));
    }

    @Nonnull
    @Override
    public String getUrl() throws ParsingException {
        return KickLinkHandlerFactory.CHANNEL.getUrl(getId());
    }

    @Nonnull
    @Override
    public String getName() {
        return KickApi.name(channel);
    }

    @Nonnull
    @Override
    public List<Image> getAvatars() {
        return KickApi.images(KickApi.avatar(channel));
    }

    @Nonnull
    @Override
    public List<Image> getBanners() {
        return KickApi.images(KickApi.image(channel, "banner_image"));
    }

    @Override
    public String getFeedUrl() {
        return null;
    }

    @Override
    public long getSubscriberCount() {
        return KickApi.followers(channel);
    }

    @Override
    public String getDescription() {
        return KickApi.biography(channel);
    }

    @Override
    public String getParentChannelName() {
        return "";
    }

    @Override
    public String getParentChannelUrl() {
        return "";
    }

    @Nonnull
    @Override
    public List<Image> getParentChannelAvatars() {
        return List.of();
    }

    @Override
    public boolean isVerified() {
        return KickApi.verified(channel);
    }

    @Nonnull
    @Override
    public List<ListLinkHandler> getTabs() throws ParsingException {
        return PvcPipeExtractorUtils.generateTabsFromSuffixMap(KickUrl.BASE + "/" + getId(),
                getId(), KickLinkHandlerFactory.tabs(), channel);
    }
}
