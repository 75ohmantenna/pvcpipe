package org.schabi.newpipe.extractor.services.odysee.extractors;

import com.grack.nanojson.JsonObject;

import org.schabi.newpipe.extractor.Image;
import org.schabi.newpipe.extractor.localization.DateWrapper;
import org.schabi.newpipe.extractor.services.odysee.OdyseeClaim;
import org.schabi.newpipe.extractor.stream.ContentAvailability;
import org.schabi.newpipe.extractor.stream.StreamInfoItemExtractor;
import org.schabi.newpipe.extractor.stream.StreamType;

import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class OdyseeStreamInfoItemExtractor implements StreamInfoItemExtractor {

    private final JsonObject claim;
    private final long viewCount;

    /**
     * @param claim     a claim that a player can play
     * @param viewCount how often it was viewed, or for a livestream how many are watching;
     *                  -1 if that is not known
     */
    public OdyseeStreamInfoItemExtractor(@Nonnull final JsonObject claim, final long viewCount) {
        this.claim = claim;
        this.viewCount = viewCount;
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
        return OdyseeClaim.thumbnails(claim);
    }

    @Override
    public StreamType getStreamType() {
        return OdyseeClaim.streamType(claim);
    }

    @Override
    public boolean isAd() {
        return false;
    }

    @Override
    public long getDuration() {
        return OdyseeClaim.duration(claim);
    }

    @Override
    public long getViewCount() {
        return viewCount;
    }

    @Override
    public String getUploaderName() {
        return OdyseeClaim.uploaderName(claim);
    }

    @Override
    public String getUploaderUrl() {
        return OdyseeClaim.uploaderUrl(claim);
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

    @Nullable
    @Override
    public String getTextualUploadDate() {
        return OdyseeClaim.textualUploadDate(claim);
    }

    @Nullable
    @Override
    public DateWrapper getUploadDate() {
        return OdyseeClaim.uploadDate(claim);
    }

    @Override
    public boolean isShortFormContent() {
        return OdyseeClaim.isShort(claim);
    }

    @Nonnull
    @Override
    public ContentAvailability getContentAvailability() {
        return OdyseeClaim.availability(claim);
    }
}
