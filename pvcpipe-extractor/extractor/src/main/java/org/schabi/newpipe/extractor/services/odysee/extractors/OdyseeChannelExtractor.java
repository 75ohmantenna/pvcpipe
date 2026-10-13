package org.schabi.newpipe.extractor.services.odysee.extractors;

import com.grack.nanojson.JsonObject;

import org.schabi.newpipe.extractor.Image;
import org.schabi.newpipe.extractor.StreamingService;
import org.schabi.newpipe.extractor.channel.ChannelExtractor;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.exceptions.ContentNotSupportedException;
import org.schabi.newpipe.extractor.exceptions.ExtractionException;
import org.schabi.newpipe.extractor.exceptions.ParsingException;
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler;
import org.schabi.newpipe.extractor.services.odysee.OdyseeApi;
import org.schabi.newpipe.extractor.services.odysee.OdyseeClaim;
import org.schabi.newpipe.extractor.services.odysee.OdyseeUrl;
import org.schabi.newpipe.extractor.services.odysee.linkHandler.OdyseeChannelLinkHandlerFactory;
import org.schabi.newpipe.extractor.services.odysee.linkHandler.OdyseeChannelTabLinkHandlerFactory;
import org.schabi.newpipe.extractor.utils.PvcPipeExtractorUtils;

import java.io.IOException;
import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class OdyseeChannelExtractor extends ChannelExtractor {

    private JsonObject claim;
    @Nullable
    private String canonicalId;
    private long subscriberCount = UNKNOWN_SUBSCRIBER_COUNT;

    public OdyseeChannelExtractor(final StreamingService service,
                                  final ListLinkHandler linkHandler) {
        super(service, linkHandler);
    }

    @Override
    public void onFetchPage(@Nonnull final Downloader downloader)
            throws IOException, ExtractionException {
        final JsonObject resolved = OdyseeApi.resolve(OdyseeUrl.toLbryUri(getId()));
        if (!OdyseeClaim.isChannel(resolved)) {
            throw new ContentNotSupportedException("This Odysee address is not a channel");
        }
        claim = resolved;
        try {
            canonicalId = OdyseeChannelLinkHandlerFactory.getInstance()
                    .getId(OdyseeClaim.webUrl(claim));
        } catch (final ParsingException e) {
            // An address this extractor cannot read back is not adopted: the channel keeps
            // the one it was opened with.
            canonicalId = null;
        }
        final String claimId = claim.getString("claim_id", "");
        subscriberCount = OdyseeApi.followerCounts(List.of(claimId))
                .getOrDefault(claimId, UNKNOWN_SUBSCRIBER_COUNT);
    }

    /**
     * One channel can be reached at several addresses: with a longer or shorter part of its
     * claim id, or with none. Once the channel is known, its id and address are the ones
     * Odysee itself uses, so that a subscription made from any of them is the same one.
     */
    @Nonnull
    @Override
    public String getId() throws ParsingException {
        return canonicalId == null ? super.getId() : canonicalId;
    }

    @Nonnull
    @Override
    public String getUrl() throws ParsingException {
        return canonicalId == null ? super.getUrl() : OdyseeUrl.toWebUrl(canonicalId);
    }

    @Nonnull
    @Override
    public String getName() {
        return OdyseeClaim.title(claim);
    }

    @Nonnull
    @Override
    public List<Image> getAvatars() {
        return OdyseeClaim.avatars(claim);
    }

    @Nonnull
    @Override
    public List<Image> getBanners() {
        return OdyseeClaim.banners(claim);
    }

    @Override
    public String getFeedUrl() {
        return null;
    }

    @Override
    public long getSubscriberCount() {
        return subscriberCount;
    }

    @Override
    public String getDescription() {
        return OdyseeClaim.value(claim).getString("description", "");
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
        return false; // Odysee has no verification mark.
    }

    @Nonnull
    @Override
    public List<ListLinkHandler> getTabs() throws ParsingException {
        // The tabs carry the channel claim so that opening one does not look it up again.
        return PvcPipeExtractorUtils.generateTabsFromSuffixMap(getUrl(), getId(),
                OdyseeChannelTabLinkHandlerFactory.getTab2UrlSuffixes(), claim);
    }

    @Nonnull
    @Override
    public List<String> getTags() {
        return OdyseeClaim.tags(claim);
    }
}
