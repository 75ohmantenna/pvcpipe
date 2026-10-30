package org.schabi.newpipe.extractor.services.kick.extractors;

import com.grack.nanojson.JsonObject;

import org.schabi.newpipe.extractor.Image;
import org.schabi.newpipe.extractor.MediaFormat;
import org.schabi.newpipe.extractor.StreamingService;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException;
import org.schabi.newpipe.extractor.exceptions.ExtractionException;
import org.schabi.newpipe.extractor.exceptions.ParsingException;
import org.schabi.newpipe.extractor.linkhandler.LinkHandler;
import org.schabi.newpipe.extractor.localization.DateWrapper;
import org.schabi.newpipe.extractor.services.kick.KickApi;
import org.schabi.newpipe.extractor.services.kick.KickHls;
import org.schabi.newpipe.extractor.services.kick.KickUrl;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.ContentAvailability;
import org.schabi.newpipe.extractor.stream.DeliveryMethod;
import org.schabi.newpipe.extractor.stream.Description;
import org.schabi.newpipe.extractor.stream.StreamExtractor;
import org.schabi.newpipe.extractor.stream.StreamInfoItemsCollector;
import org.schabi.newpipe.extractor.stream.StreamType;
import org.schabi.newpipe.extractor.stream.SubtitlesStream;
import org.schabi.newpipe.extractor.stream.VideoStream;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Locale;

import javax.annotation.Nonnull;

public final class KickClipExtractor extends StreamExtractor {
    private JsonObject clip;
    private JsonObject channel;
    private KickClipInfoItemExtractor item;
    private String hls = "";
    private List<VideoStream> videoStreams = List.of();
    private KickHls.Manifest manifest;

    public KickClipExtractor(final StreamingService service, final LinkHandler handler) {
        super(service, handler);
    }

    @Override
    public void onFetchPage(@Nonnull final Downloader downloader)
            throws IOException, ExtractionException {
        final String[] identity = getId().split("/", -1);
        final String slug = KickUrl.slug(identity[0]);
        clip = KickApi.clip(KickUrl.clipId(identity[2]));
        final JsonObject owner = KickApi.child(clip, "channel");
        if (!slug.equals(KickUrl.slug(owner.getString("slug", "")))) {
            throw new ParsingException("Kick clip belongs to a different channel");
        }
        KickApi.requirePublicPlayback(clip, "clip");
        if (owner.getBoolean("is_banned")) {
            throw new ContentNotAvailableException("This Kick clip is unavailable");
        }
        String media = "";
        for (final String field : List.of("playback_url", "clip_url", "video_url")) {
            final String candidate = clip.getString(field, "");
            if (!candidate.isBlank()) {
                media = candidate;
                break;
            }
        }
        if (media.isEmpty()) {
            throw new ContentNotAvailableException("This Kick clip has no playable media");
        }
        if (!KickUrl.isHttps(media)) {
            throw new ParsingException("Kick clip media URLs must use HTTPS");
        }
        final String path = URI.create(media).getPath().toLowerCase(Locale.ROOT);
        if (!path.endsWith(".m3u8") && !path.endsWith(".mp4")) {
            throw new ParsingException("Unsupported Kick clip media type");
        }
        channel = KickApi.channel(slug);
        if (!slug.equals(KickUrl.slug(channel.getString("slug", "")))
                || owner.getLong("id", 0) != channel.getLong("id", 0)) {
            throw new ParsingException("Kick returned a different clip channel");
        }
        item = new KickClipInfoItemExtractor(clip, channel);
        if (path.endsWith(".m3u8")) {
            hls = media;
            manifest = KickHls.parse(hls, KickApi.manifest(hls));
        } else {
            hls = "";
            manifest = null;
            videoStreams = List.of(new VideoStream.Builder().setId("mp4")
                    .setContent(media, true).setMediaFormat(MediaFormat.MPEG_4)
                    .setDeliveryMethod(DeliveryMethod.PROGRESSIVE_HTTP)
                    .setIsVideoOnly(false).setResolution(VideoStream.RESOLUTION_UNKNOWN).build());
        }
    }

    @Nonnull
    @Override
    public String getUrl() throws ParsingException {
        return item == null ? super.getUrl() : item.getUrl();
    }

    @Nonnull
    @Override
    public String getName() {
        return item.getName();
    }

    @Nonnull
    @Override
    public List<Image> getThumbnails() {
        return item.getThumbnails();
    }

    @Nonnull
    @Override
    public Description getDescription() {
        return new Description(KickApi.biography(channel), Description.PLAIN_TEXT);
    }

    @Override
    public long getLength() {
        return item.getDuration();
    }

    @Override
    public long getViewCount() {
        return item.getViewCount();
    }

    @Override
    public long getLikeCount() {
        return clip.getLong("likes_count", clip.getLong("likes", -1));
    }

    @Override
    public int getAgeLimit() {
        return clip.getBoolean("is_mature") ? 18 : NO_AGE_LIMIT;
    }

    @Nonnull
    @Override
    public String getCategory() {
        return KickApi.category(clip);
    }

    @Nonnull
    @Override
    public String getUploaderUrl() throws ParsingException {
        return item.getUploaderUrl();
    }

    @Nonnull
    @Override
    public String getUploaderName() {
        return item.getUploaderName();
    }

    @Nonnull
    @Override
    public List<Image> getUploaderAvatars() {
        return item.getUploaderAvatars();
    }

    @Override
    public boolean isUploaderVerified() {
        return item.isUploaderVerified();
    }

    @Override
    public long getUploaderSubscriberCount() {
        return KickApi.followers(channel);
    }

    @Override
    public String getTextualUploadDate() {
        return item.getTextualUploadDate();
    }

    @Override
    public DateWrapper getUploadDate() throws ParsingException {
        return item.getUploadDate();
    }

    @Nonnull
    @Override
    public String getHlsUrl() {
        return hls;
    }

    @Override
    public List<AudioStream> getAudioStreams() {
        return manifest == null ? List.of() : manifest.getAudioStreams();
    }

    @Override
    public List<VideoStream> getVideoStreams() {
        return manifest == null ? videoStreams : manifest.getVideoStreams();
    }

    @Override
    public List<VideoStream> getVideoOnlyStreams() {
        return manifest == null ? List.of() : manifest.getVideoOnlyStreams();
    }

    @Nonnull
    @Override
    public List<SubtitlesStream> getSubtitlesDefault() {
        return manifest == null ? List.of() : manifest.getSubtitles();
    }

    @Nonnull
    @Override
    public List<SubtitlesStream> getSubtitles(final MediaFormat format) {
        return format == MediaFormat.VTT ? getSubtitlesDefault() : List.of();
    }

    @Override
    public List<String> getTags() {
        return KickApi.tags(clip);
    }

    @Override
    public Locale getLanguageInfo() {
        return KickApi.language(clip);
    }

    @Override
    public ContentAvailability getContentAvailability() {
        return KickApi.availability(clip);
    }

    @Override
    public StreamInfoItemsCollector getRelatedItems() throws IOException, ExtractionException {
        final StreamInfoItemsCollector collector = new StreamInfoItemsCollector(getServiceId());
        final JsonObject data = KickApi.clips(channel.getLong("id", 0), "date", "all", null);
        for (final Object value : data.getArray("clips")) {
            if (value instanceof JsonObject) {
                final JsonObject related = (JsonObject) value;
                final JsonObject owner = KickApi.child(related, "channel");
                if (KickApi.listable(related) && KickApi.listable(owner)
                        && !clip.getString("id").equals(related.getString("id", ""))) {
                    collector.commit(new KickClipInfoItemExtractor(related, channel));
                }
            }
        }
        return collector;
    }

    @Override
    public StreamType getStreamType() {
        return StreamType.VIDEO_STREAM;
    }
}
