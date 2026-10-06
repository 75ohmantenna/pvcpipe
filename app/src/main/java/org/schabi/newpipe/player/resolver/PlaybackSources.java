package org.schabi.newpipe.player.resolver;

import static org.schabi.newpipe.extractor.utils.Utils.isNullOrEmpty;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.exoplayer2.source.MediaSource;

import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.Stream;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.StreamType;
import org.schabi.newpipe.extractor.stream.VideoStream;
import org.schabi.newpipe.player.helper.PlayerDataSource;
import org.schabi.newpipe.player.mediaitem.MediaItemTag;
import org.schabi.newpipe.player.mediaitem.MediaItemTag.SourceType;
import org.schabi.newpipe.player.mediaitem.StreamInfoTag;
import org.schabi.newpipe.player.resolver.PlaybackResolver.ResolverException;
import org.schabi.newpipe.util.StreamTypeUtil;

import java.util.ArrayList;
import java.util.List;

/** Selects playback sources and owns the settings and policy shared by player modes. */
public final class PlaybackSources {
    @NonNull
    private final Environment environment;
    @NonNull
    private final QualityResolver qualityResolver;
    @NonNull
    private volatile Settings settings = new Settings(null, null);

    public PlaybackSources(@NonNull final Context context,
                           @NonNull final PlayerDataSource dataSource,
                           @NonNull final QualityResolver qualityResolver) {
        this(new AndroidPlaybackEnvironment(context, dataSource), qualityResolver);
    }

    PlaybackSources(@NonNull final Environment environment,
                    @NonNull final QualityResolver qualityResolver) {
        this.environment = environment;
        this.qualityResolver = qualityResolver;
    }

    /**
     * Resolves a queue entry using the current playback mode and one selection-settings snapshot.
     * The queue manager retains ownership of preloading, expiration and playback position.
     *
     * @param info extracted information for the incoming stream
     * @param audioPlayer whether the dedicated audio player is selected
     * @param audioOnly whether video rendering is disabled
     * @return the constructed source, or null when no playable source can be built
     */
    @Nullable
    public MediaSource resolve(@NonNull final StreamInfo info,
                               final boolean audioPlayer,
                               final boolean audioOnly) {
        final Settings selection = settings;
        if (audioPlayer) {
            return resolveAudio(info, selection);
        }
        final MediaSource live = resolveLiveVideo(info);
        if (live != null) {
            return live;
        }
        final VideoSelection video = selectVideo(info, selection);
        return audioOnly && !video.separateAudio
                ? buildAudio(info, selection) : buildVideo(info, video);
    }

    public synchronized void setPlaybackQuality(@Nullable final String quality) {
        settings = new Settings(quality, settings.audioTrack);
    }

    public synchronized void setAudioTrack(@Nullable final String audioTrack) {
        settings = new Settings(settings.quality, audioTrack);
    }

    /**
     * Returns whether toggling video needs to reconstruct the current playback source.
     *
     * @param currentTag metadata of the currently playing source, if available
     * @param videoRendererAvailable whether the current player has a video renderer
     * @return whether source reconstruction is needed
     */
    public boolean requiresReload(@Nullable final MediaItemTag currentTag,
                                  final boolean videoRendererAvailable) {
        return currentTag == null || currentTag.getMaybeStreamInfo()
                .map(info -> requiresReload(info, currentTag, videoRendererAvailable)).orElse(true);
    }

    private boolean requiresReload(@NonNull final StreamInfo info,
                                   @NonNull final MediaItemTag currentTag,
                                   final boolean videoRendererAvailable) {
        final StreamType streamType = info.getStreamType();
        if (StreamTypeUtil.isAudio(streamType)) {
            return false;
        }
        if (!videoRendererAvailable) {
            return true;
        }
        final SourceType type = currentTag.getMaybeSourceType().orElse(null);
        if (type == null) {
            return true;
        }
        if (streamType == StreamType.LIVE_STREAM && type == SourceType.LIVE_STREAM) {
            return false;
        }
        if (type == SourceType.VIDEO_WITH_SEPARATED_AUDIO
                || (type == SourceType.VIDEO_WITH_AUDIO_OR_AUDIO_ONLY
                && isNullOrEmpty(info.getAudioStreams()))) {
            return !StreamTypeUtil.isVideo(streamType);
        }
        return true;
    }

    @Nullable
    private MediaSource resolveAudio(@NonNull final StreamInfo info,
                                     @NonNull final Settings selection) {
        final MediaSource liveSource = environment.liveSource(info, null,
                StreamInfoTag.of(info).withSourceType(SourceType.LIVE_STREAM));
        return liveSource == null ? buildAudio(info, selection) : liveSource;
    }

    @Nullable
    private MediaSource buildAudio(@NonNull final StreamInfo info,
                                   @NonNull final Settings selection) {
        final List<AudioStream> audioStreams = environment.audioStreams(info);
        final Stream stream;
        final StreamInfoTag tag;
        if (!audioStreams.isEmpty()) {
            final int index = environment.audioIndex(audioStreams, selection.audioTrack);
            stream = streamForIndex(index, audioStreams);
            tag = StreamInfoTag.of(info, audioStreams, index);
        } else {
            final List<VideoStream> videoStreams = environment.audioFallbackStreams(info);
            if (videoStreams.isEmpty()) {
                return null;
            }
            stream = streamForIndex(environment.audioFallbackIndex(videoStreams), videoStreams);
            tag = StreamInfoTag.of(info);
        }
        try {
            return environment.streamSource(info, stream,
                    tag.withSourceType(SourceType.VIDEO_WITH_AUDIO_OR_AUDIO_ONLY));
        } catch (final ResolverException error) {
            environment.logError("Unable to create audio source", error);
            return null;
        }
    }

    @Nullable
    private MediaSource resolveLiveVideo(@NonNull final StreamInfo info) {
        return environment.liveSource(info, rumbleLiveManifest(info),
                StreamInfoTag.of(info).withSourceType(SourceType.LIVE_STREAM));
    }

    private VideoSelection selectVideo(@NonNull final StreamInfo info,
                                       @NonNull final Settings selection) {
        final List<VideoStream> videos = environment.sortedVideoStreams(info);
        final List<AudioStream> audios = environment.audioStreams(info);
        final int videoIndex;
        if (videos.isEmpty()) {
            videoIndex = -1;
        } else if (selection.quality == null) {
            videoIndex = qualityResolver.getDefaultResolutionIndex(videos);
        } else {
            videoIndex = qualityResolver.getOverrideResolutionIndex(videos, selection.quality);
        }
        final int audioIndex = environment.audioIndex(audios, selection.audioTrack);
        final StreamInfoTag tag = StreamInfoTag.of(info, videos, videoIndex, audios, audioIndex);
        final VideoStream video = tag.getMaybeQuality()
                .map(MediaItemTag.Quality::getSelectedVideoStream).orElse(null);
        final AudioStream audio = tag.getMaybeAudioTrack()
                .map(MediaItemTag.AudioTrack::getSelectedAudioStream).orElse(null);
        final boolean separateAudio = audio != null && (video == null || video.isVideoOnly()
                || selection.audioTrack != null);
        return new VideoSelection(video, audio, separateAudio, tag.withSourceType(separateAudio
                ? SourceType.VIDEO_WITH_SEPARATED_AUDIO
                : SourceType.VIDEO_WITH_AUDIO_OR_AUDIO_ONLY));
    }

    @Nullable
    private MediaSource buildVideo(@NonNull final StreamInfo info,
                                   @NonNull final VideoSelection selection) {
        final List<MediaSource> sources = new ArrayList<>();
        if (selection.video != null) {
            try {
                sources.add(environment.streamSource(info, selection.video, selection.tag));
            } catch (final ResolverException error) {
                environment.logError("Unable to create video source", error);
                return null;
            }
        }
        if (selection.separateAudio) {
            try {
                sources.add(environment.streamSource(info, selection.audio, selection.tag));
            } catch (final ResolverException error) {
                environment.logError("Unable to create audio source", error);
                return null;
            }
        }
        if (sources.isEmpty()) {
            return null;
        }
        sources.addAll(environment.subtitleSources(info));
        return sources.size() == 1 ? sources.get(0) : environment.merge(sources);
    }

    @Nullable
    private String rumbleLiveManifest(@NonNull final StreamInfo info) {
        if (info.getStreamType() != StreamType.LIVE_STREAM
                || info.getServiceId() != ServiceList.Rumble.getServiceId()) {
            return null;
        }
        final List<VideoStream> videos = info.getVideoStreams();
        if (videos.isEmpty()) {
            return null;
        }
        final int index = qualityResolver.getDefaultResolutionIndex(videos);
        if (index < 0 || index >= videos.size()) {
            return null;
        }
        final String manifest = videos.get(index).getManifestUrl();
        if (manifest != null && !manifest.isBlank()) {
            return manifest;
        }
        environment.logWarning("could not set set hls url according to select quality");
        return null;
    }

    private static final class VideoSelection {
        @Nullable
        private final VideoStream video;
        @Nullable
        private final AudioStream audio;
        private final boolean separateAudio;
        @NonNull
        private final StreamInfoTag tag;

        private VideoSelection(@Nullable final VideoStream video,
                               @Nullable final AudioStream audio,
                               final boolean separateAudio,
                               @NonNull final StreamInfoTag tag) {
            this.video = video;
            this.audio = audio;
            this.separateAudio = separateAudio;
            this.tag = tag;
        }
    }

    @Nullable
    private static Stream streamForIndex(final int index,
                                        @NonNull final List<? extends Stream> streams) {
        return index >= 0 && index < streams.size() ? streams.get(index) : null;
    }

    private static final class Settings {
        @Nullable
        private final String quality;
        @Nullable
        private final String audioTrack;

        private Settings(@Nullable final String quality, @Nullable final String audioTrack) {
            this.quality = quality;
            this.audioTrack = audioTrack;
        }
    }

    public interface QualityResolver {
        int getDefaultResolutionIndex(List<VideoStream> sortedVideos);

        int getOverrideResolutionIndex(List<VideoStream> sortedVideos, String playbackQuality);
    }

    interface Environment {
        List<VideoStream> sortedVideoStreams(StreamInfo info);

        List<VideoStream> audioFallbackStreams(StreamInfo info);

        List<AudioStream> audioStreams(StreamInfo info);

        int audioIndex(List<AudioStream> streams, @Nullable String audioTrack);

        int audioFallbackIndex(List<VideoStream> streams);

        @Nullable
        MediaSource liveSource(StreamInfo info, @Nullable String hlsOverride, MediaItemTag tag);

        MediaSource streamSource(StreamInfo info, @Nullable Stream stream, MediaItemTag tag)
                throws ResolverException;

        List<MediaSource> subtitleSources(StreamInfo info);

        MediaSource merge(List<MediaSource> sources);

        void logError(String message, Exception error);

        void logWarning(String message);
    }
}
