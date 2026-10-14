package org.schabi.newpipe.extractor.services.bitchute.extractor;

import com.github.pvcpipe.json2java4nanojson.bitchute.api.results.stream.channel.videos.Videos;

import org.schabi.newpipe.extractor.Image;
import org.schabi.newpipe.extractor.pvc.misc.PvcParsingHelper;
import org.schabi.newpipe.extractor.exceptions.ParsingException;
import org.schabi.newpipe.extractor.localization.DateWrapper;
import org.schabi.newpipe.extractor.services.bitchute.BitchuteParserHelper;
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper;
import org.schabi.newpipe.extractor.stream.StreamInfoItemExtractor;
import org.schabi.newpipe.extractor.stream.StreamType;

import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public abstract class BitchuteChannelStreamInfoItemExtractor implements StreamInfoItemExtractor {

    private final Videos video;

    public BitchuteChannelStreamInfoItemExtractor(final Videos video) {
        this.video = video;
    }

    @Override
    public StreamType getStreamType() throws ParsingException {
        return "live".equals(video.getStateId())
                ? StreamType.LIVE_STREAM : StreamType.VIDEO_STREAM;
    }

    @Override
    public boolean isAd() throws ParsingException {
        return false;
    }

    @Override
    public long getDuration() throws ParsingException {
        return "live".equals(video.getStateId()) ? -1
                : YoutubeParsingHelper.parseDurationString(video.getDuration());
    }

    @Override
    public long getViewCount() throws ParsingException {
        return video.getViewCount();
    }

    @Nullable
    @Override
    public String getTextualUploadDate() throws ParsingException {
        return video.getDatePublished();
    }

    @Nullable
    @Override
    public DateWrapper getUploadDate() throws ParsingException {
        if (getTextualUploadDate() == null || getTextualUploadDate().isEmpty()) {
            return null;
        }
        final String date = getTextualUploadDate();
        return date == null || date.isEmpty() ? null
                : new DateWrapper(PvcParsingHelper.parseDateFrom(date));
    }

    @Override
    public String getName() throws ParsingException {
        return video.getVideoName();
    }

    @Override
    public String getUrl() throws ParsingException {
        return BitchuteParserHelper.prependBaseUrl(video.getVideoUrl());
    }

    @Nonnull
    @Override
    public List<Image> getThumbnails() throws ParsingException {
        return List.of(
                new Image(video.getThumbnailUrl(),
                        Image.HEIGHT_UNKNOWN,
                        Image.WIDTH_UNKNOWN,
                        Image.ResolutionLevel.UNKNOWN));
    }

}
