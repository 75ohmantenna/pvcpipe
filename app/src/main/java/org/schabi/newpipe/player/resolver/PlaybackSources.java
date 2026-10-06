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
    @Nullable
    private SourceType lastVideoSourceType;

    private enum SourceType {
        LIVE_STREAM,
        VIDEO_WITH_SEPARATED_AUDIO,
        VIDEO_WITH_AUDIO_OR_AUDIO_ONLY
    }

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
        if (audioPlayer || (audioOnly && sourceType()
                == SourceType.VIDEO_WITH_AUDIO_OR_AUDIO_ONLY)) {
            return resolveAudio(info, selection);
        }
        return resolveVideo(info, selection);
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
                .map(info -> requiresReload(info, videoRendererAvailable)).orElse(true);
    }

    private boolean requiresReload(@NonNull final StreamInfo info,
                                   final boolean videoRendererAvailable) {
        final StreamType streamType = info.getStreamType();
        final boolean audio = StreamTypeUtil.isAudio(streamType);
        final SourceType sourceType = sourceType();
        if (!videoRendererAvailable && !audio) {
            return true;
        }
        if (audio || (streamType == StreamType.LIVE_STREAM
                && sourceType == SourceType.LIVE_STREAM)) {
            return false;
        }
        if (sourceType == SourceType.VIDEO_WITH_SEPARATED_AUDIO
                || (sourceType == SourceType.VIDEO_WITH_AUDIO_OR_AUDIO_ONLY
                && isNullOrEmpty(info.getAudioStreams()))) {
            return !StreamTypeUtil.isVideo(streamType);
        }
        return true;
    }

    private SourceType sourceType() {
        return lastVideoSourceType == null
                ? SourceType.VIDEO_WITH_AUDIO_OR_AUDIO_ONLY : lastVideoSourceType;
    }

    @Nullable
    private MediaSource resolveAudio(@NonNull final StreamInfo info,
                                     @NonNull final Settings selection) {
        final MediaSource liveSource = environment.liveSource(info);
        if (liveSource != null) {
            return liveSource;
        }
        final List<AudioStream> audioStreams = environment.audioStreams(info);
        final Stream stream;
        final MediaItemTag tag;
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
            return environment.streamSource(info, stream, tag);
        } catch (final ResolverException error) {
            environment.logError("Unable to create audio source", error);
            return null;
        }
    }

    @Nullable
    private MediaSource resolveVideo(@NonNull final StreamInfo info,
                                     @NonNull final Settings selection) {
        changeRumbleLiveQuality(info);
        final MediaSource liveSource = environment.liveSource(info);
        if (liveSource != null) {
            lastVideoSourceType = SourceType.LIVE_STREAM;
            return liveSource;
        }
        final List<MediaSource> sources = new ArrayList<>();
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
        final MediaItemTag tag = StreamInfoTag.of(info, videos, videoIndex, audios, audioIndex);
        final VideoStream video = tag.getMaybeQuality()
                .map(MediaItemTag.Quality::getSelectedVideoStream).orElse(null);
        final AudioStream audio = tag.getMaybeAudioTrack()
                .map(MediaItemTag.AudioTrack::getSelectedAudioStream).orElse(null);
        if (video != null) {
            try {
                sources.add(environment.streamSource(info, video, tag));
            } catch (final ResolverException error) {
                environment.logError("Unable to create video source", error);
                return null;
            }
        }
        if (audio != null && (video == null || video.isVideoOnly()
                || selection.audioTrack != null)) {
            try {
                sources.add(environment.streamSource(info, audio, tag));
                lastVideoSourceType = SourceType.VIDEO_WITH_SEPARATED_AUDIO;
            } catch (final ResolverException error) {
                environment.logError("Unable to create audio source", error);
                return null;
            }
        } else {
            lastVideoSourceType = SourceType.VIDEO_WITH_AUDIO_OR_AUDIO_ONLY;
        }
        if (sources.isEmpty()) {
            return null;
        }
        sources.addAll(environment.subtitleSources(info));
        return sources.size() == 1 ? sources.get(0) : environment.merge(sources);
    }

    private void changeRumbleLiveQuality(@NonNull final StreamInfo info) {
        if (info.getStreamType() == StreamType.LIVE_STREAM
                && info.getServiceId() == ServiceList.Rumble.getServiceId()) {
            final int index = qualityResolver.getDefaultResolutionIndex(info.getVideoStreams());
            final String manifest = info.getVideoStreams().get(index).getManifestUrl();
            if (manifest != null && !manifest.isBlank()) {
                info.setHlsUrl(manifest);
            } else {
                environment.logWarning("could not set set hls url according to select quality");
            }
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
        MediaSource liveSource(StreamInfo info);

        MediaSource streamSource(StreamInfo info, @Nullable Stream stream, MediaItemTag tag)
                throws ResolverException;

        List<MediaSource> subtitleSources(StreamInfo info);

        MediaSource merge(List<MediaSource> sources);

        void logError(String message, Exception error);

        void logWarning(String message);
    }
}
