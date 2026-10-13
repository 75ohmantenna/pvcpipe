package org.schabi.newpipe.extractor.services.odysee.extractors;

import com.grack.nanojson.JsonObject;

import org.schabi.newpipe.extractor.Image;
import org.schabi.newpipe.extractor.channel.ChannelInfoItemExtractor;
import org.schabi.newpipe.extractor.services.odysee.OdyseeClaim;

import java.util.List;

import javax.annotation.Nonnull;

public class OdyseeChannelInfoItemExtractor implements ChannelInfoItemExtractor {

    private final JsonObject claim;
    private final long subscriberCount;

    /**
     * @param claim           a channel claim
     * @param subscriberCount how many followers it has, or -1 if that is not known
     */
    public OdyseeChannelInfoItemExtractor(@Nonnull final JsonObject claim,
                                          final long subscriberCount) {
        this.claim = claim;
        this.subscriberCount = subscriberCount;
    }

    @Override
    public String getName() {
        return OdyseeClaim.title(claim);
    }

    @Override
    public String getUrl() {
        return OdyseeClaim.webUrl(claim);
    }

    @Nonnull
    @Override
    public List<Image> getThumbnails() {
        return OdyseeClaim.avatars(claim);
    }

    @Override
    public String getDescription() {
        return OdyseeClaim.value(claim).getString("description", "");
    }

    @Override
    public long getSubscriberCount() {
        return subscriberCount;
    }

    @Override
    public long getStreamCount() {
        return claim.getObject("meta").getLong("claims_in_channel", -1);
    }

    @Override
    public boolean isVerified() {
        return false; // Odysee has no verification mark.
    }
}
