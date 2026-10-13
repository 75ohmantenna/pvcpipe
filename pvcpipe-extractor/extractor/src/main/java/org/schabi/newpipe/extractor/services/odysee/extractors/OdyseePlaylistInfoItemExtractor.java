package org.schabi.newpipe.extractor.services.odysee.extractors;

import com.grack.nanojson.JsonObject;

import org.schabi.newpipe.extractor.Image;
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItemExtractor;
import org.schabi.newpipe.extractor.services.odysee.OdyseeClaim;

import java.util.List;

import javax.annotation.Nonnull;

public class OdyseePlaylistInfoItemExtractor implements PlaylistInfoItemExtractor {

    private final JsonObject claim;

    public OdyseePlaylistInfoItemExtractor(@Nonnull final JsonObject claim) {
        this.claim = claim;
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
    public String getUploaderName() {
        return OdyseeClaim.uploaderName(claim);
    }

    @Override
    public String getUploaderUrl() {
        return OdyseeClaim.uploaderUrl(claim);
    }

    @Override
    public boolean isUploaderVerified() {
        return false; // Odysee has no verification mark.
    }

    @Override
    public long getStreamCount() {
        return OdyseeClaim.value(claim).getArray("claims").size();
    }
}
