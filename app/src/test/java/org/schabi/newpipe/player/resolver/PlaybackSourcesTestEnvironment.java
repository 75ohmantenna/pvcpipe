package org.schabi.newpipe.player.resolver;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.net.Uri;

import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.source.MediaSource;

import org.schabi.newpipe.extractor.MediaFormat;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.DeliveryMethod;
import org.schabi.newpipe.extractor.stream.Stream;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.StreamType;
import org.schabi.newpipe.extractor.stream.VideoStream;
import org.schabi.newpipe.player.mediaitem.MediaItemTag;
import org.schabi.newpipe.player.mediaitem.StreamInfoTag;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Scripts preference decisions and ExoPlayer construction, retaining real selection logic. */
final class PlaybackSourcesTestEnvironment implements PlaybackSources.Environment {
    final List<Stream> streams = new ArrayList<>();
    final List<MediaItemTag> streamTags = new ArrayList<>();
    final List<String> requestedAudioTracks = new ArrayList<>();
    final List<List<MediaSource>> merges = new ArrayList<>();
    final List<MediaSource> subtitles = new ArrayList<>();
    final List<String> errors = new ArrayList<>();
    final List<String> warnings = new ArrayList<>();
    final Set<StreamInfo> liveInfos = new HashSet<>();
    final Set<Stream> failedStreams = new HashSet<>();
    int selectedAudioIndex;
    int fallbackVideoIndex;
    int subtitleRequests;

    @Override
    public List<VideoStream> sortedVideoStreams(final StreamInfo info) {
        final List<VideoStream> videos = new ArrayList<>(info.getVideoStreams());
        videos.addAll(info.getVideoOnlyStreams());
        return videos;
    }

    @Override
    public List<VideoStream> audioFallbackStreams(final StreamInfo info) {
        return info.getVideoStreams();
    }

    @Override
    public List<AudioStream> audioStreams(final StreamInfo info) {
        return info.getAudioStreams();
    }

    @Override
    public int audioIndex(final List<AudioStream> audioStreams, final String audioTrack) {
        requestedAudioTracks.add(audioTrack);
        return selectedAudioIndex;
    }

    @Override
    public int audioFallbackIndex(final List<VideoStream> videoStreams) {
        return fallbackVideoIndex;
    }

    @Override
    public MediaSource liveSource(final StreamInfo info) {
        return liveInfos.contains(info) ? source(StreamInfoTag.of(info)) : null;
    }

    @Override
    public MediaSource streamSource(final StreamInfo info, final Stream stream,
                                   final MediaItemTag tag)
            throws PlaybackResolver.ResolverException {
        streams.add(stream);
        streamTags.add(tag);
        if (failedStreams.contains(stream)) {
            throw new PlaybackResolver.ResolverException("scripted construction failure");
        }
        return source(tag);
    }

    @Override
    public List<MediaSource> subtitleSources(final StreamInfo info) {
        subtitleRequests++;
        return subtitles;
    }

    @Override
    public MediaSource merge(final List<MediaSource> sources) {
        merges.add(new ArrayList<>(sources));
        return source(tag(sources.get(0)));
    }

    @Override
    public void logError(final String message, final Exception exception) {
        errors.add(message);
    }

    @Override
    public void logWarning(final String message) {
        warnings.add(message);
    }

    static MediaSource source(final MediaItemTag tag) {
        final MediaSource source = mock(MediaSource.class);
        final MediaItem item = new MediaItem.Builder().setUri(mock(Uri.class)).setTag(tag).build();
        when(source.getMediaItem()).thenReturn(item);
        return source;
    }

    static MediaItemTag tag(final MediaSource source) {
        return MediaItemTag.from(source.getMediaItem()).orElseThrow();
    }

    static StreamInfo info(final String id, final StreamType type) {
        final String url = "https://example.com/watch/" + id;
        return new StreamInfo(0, url, url, type, id, id, 0);
    }

    static VideoStream video(final String id, final String resolution, final boolean videoOnly) {
        return new VideoStream.Builder().setId(id)
                .setContent("https://example.com/video/" + id, true)
                .setMediaFormat(MediaFormat.MPEG_4)
                .setDeliveryMethod(DeliveryMethod.PROGRESSIVE_HTTP)
                .setResolution(resolution).setIsVideoOnly(videoOnly).build();
    }

    static AudioStream audio(final String id) {
        return new AudioStream.Builder().setId(id)
                .setContent("https://example.com/audio/" + id, true)
                .setMediaFormat(MediaFormat.M4A)
                .setDeliveryMethod(DeliveryMethod.PROGRESSIVE_HTTP)
                .setAverageBitrate(128).setAudioTrackId(id).build();
    }

    static final class Quality implements PlaybackSources.QualityResolver {
        int defaultIndex;
        int overrideIndex;
        final List<String> requestedOverrides = new ArrayList<>();

        @Override
        public int getDefaultResolutionIndex(final List<VideoStream> sortedVideos) {
            return defaultIndex;
        }

        @Override
        public int getOverrideResolutionIndex(final List<VideoStream> sortedVideos,
                                              final String playbackQuality) {
            requestedOverrides.add(playbackQuality);
            return overrideIndex;
        }
    }
}
