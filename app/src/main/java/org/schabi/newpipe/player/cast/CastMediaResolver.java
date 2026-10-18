package org.schabi.newpipe.player.cast;

import android.content.Context;

import androidx.annotation.Nullable;

import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.services.youtube.ItagItem;
import org.schabi.newpipe.extractor.services.youtube.dashmanifestcreators.YoutubeOtfDashManifestCreator;
import org.schabi.newpipe.extractor.services.youtube.dashmanifestcreators.YoutubePostLiveStreamDvrDashManifestCreator;
import org.schabi.newpipe.extractor.services.youtube.dashmanifestcreators.YoutubeProgressiveDashManifestCreator;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.DeliveryMethod;
import org.schabi.newpipe.extractor.stream.Stream;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.StreamType;
import org.schabi.newpipe.extractor.stream.VideoStream;
import org.schabi.newpipe.player.mediaitem.MediaItemTag;
import org.schabi.newpipe.util.ListHelper;
import org.schabi.newpipe.util.StreamTypeUtil;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetAddress;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Blocking preparation for one receiver load. Resources persist for replay and close on
 * replacement, disconnect, error, or shutdown rather than natural playback completion.
 */
public final class CastMediaResolver implements Closeable {
    private final Context context;
    private CastMediaServer server;
    private boolean closed;
    private boolean resolved;

    public CastMediaResolver(final Context context) {
        this.context = context.getApplicationContext();
    }

    public CastMedia resolve(final StreamInfo info, @Nullable final MediaItemTag tag,
                             final InetAddress localAddress) throws Exception {
        final CastMediaServer mediaServer;
        synchronized (this) {
            if (closed || resolved || Thread.currentThread().isInterrupted()) {
                throw new IOException("Cast resolver is closed or already used");
            }
            if (localAddress.isAnyLocalAddress() || localAddress.isLoopbackAddress()
                    || localAddress.isMulticastAddress()) {
                throw new IOException("A reachable LAN address is required for casting");
            }
            resolved = true;
            mediaServer = new CastMediaServer(localAddress);
            server = mediaServer;
        }
        try {
            final CastMedia media = resolve(info, tag, mediaServer);
            synchronized (this) {
                if (closed || Thread.currentThread().isInterrupted()) {
                    throw new IOException("Cast preparation was cancelled");
                }
                mediaServer.finishPreparation();
            }
            return media;
        } catch (final Exception error) {
            close();
            throw error;
        }
    }

    // Prepare sources without transferring lifetime ownership or publishing the receiver load.
    CastMedia resolve(final StreamInfo info, @Nullable final MediaItemTag tag,
                      final CastMediaServer mediaServer) throws Exception {
        if (StreamTypeUtil.isLiveStream(info.getStreamType())) {
            if (info.getDashMpdUrl() != null && !info.getDashMpdUrl().isBlank()) {
                return remoteManifest(mediaServer, info.getDashMpdUrl(), CastManifest.DASH);
            }
            if (info.getHlsUrl() != null && !info.getHlsUrl().isBlank()) {
                return remoteManifest(mediaServer, info.getHlsUrl(), CastManifest.HLS);
            }
        }
        VideoStream video = tag == null ? null : tag.getMaybeQuality()
                .map(MediaItemTag.Quality::getSelectedVideoStream).orElse(null);
        AudioStream audio = tag == null ? null : tag.getMaybeAudioTrack()
                .map(MediaItemTag.AudioTrack::getSelectedAudioStream).orElse(null);
        if (video == null && !StreamTypeUtil.isAudio(info.getStreamType())) {
            final List<VideoStream> videos = ListHelper.getSortedStreamVideosList(context,
                    ListHelper.getPlayableStreams(info.getVideoStreams(), info.getServiceId()),
                    ListHelper.getPlayableStreams(info.getVideoOnlyStreams(), info.getServiceId()),
                    false, true);
            final int index = ListHelper.getDefaultResolutionIndex(context, videos);
            if (index >= 0 && index < videos.size()) {
                video = videos.get(index);
            }
        }
        final boolean separate = video == null || video.isVideoOnly()
                || (tag != null && tag.getMaybeSourceType().orElse(null)
                == MediaItemTag.SourceType.VIDEO_WITH_SEPARATED_AUDIO);
        if (separate && audio == null) {
            final List<AudioStream> audios = ListHelper.getFilteredAudioStreams(context,
                    info.getAudioStreams());
            final int index = ListHelper.getAudioFormatIndex(context, audios, null);
            if (index >= 0 && index < audios.size()) {
                audio = audios.get(index);
            }
        }
        if (video == null && audio == null) {
            throw new IOException("This item has no castable extracted media");
        }
        if (video != null && video.isVideoOnly() && audio == null) {
            throw new IOException("The selected video has no audio stream to cast with it");
        }
        if (!separate || video == null || audio == null) {
            return single(info, video == null ? audio : video, mediaServer);
        }
        if (!video.isVideoOnly()) {
            throw new IOException("Replacing audio in a muxed video requires remuxing; "
                    + "select a video-only quality to cast this audio track");
        }
        if (video.getDeliveryMethod() == DeliveryMethod.HLS
                && audio.getDeliveryMethod() == DeliveryMethod.HLS) {
            final String videoUrl = hlsUrl(video, mediaServer);
            final String audioUrl = hlsUrl(audio, mediaServer);
            final String master = "#EXTM3U\n#EXT-X-VERSION:6\n"
                    + "#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"audio\",NAME=\"Selected audio\","
                    + "DEFAULT=YES,AUTOSELECT=YES,URI=\"" + audioUrl + "\"\n"
                    + "#EXT-X-STREAM-INF:BANDWIDTH=20000000,AUDIO=\"audio\"\n" + videoUrl + "\n";
            return localManifest(mediaServer, master, CastManifest.HLS);
        }
        final String videoDash = dash(info, video, mediaServer);
        final String audioDash = dash(info, audio, mediaServer);
        return localManifest(mediaServer, CastManifest.mergeDash(videoDash, audioDash),
                CastManifest.DASH);
    }

    private CastMedia single(final StreamInfo info, final Stream stream,
                             final CastMediaServer mediaServer) throws Exception {
        if (stream.getDeliveryMethod() == DeliveryMethod.HLS) {
            return new CastMedia(CastManifest.HLS, hlsUrl(stream, mediaServer), null, Map.of());
        }
        if (stream.getDeliveryMethod() == DeliveryMethod.DASH
                || (info.getServiceId() == ServiceList.YouTube.getServiceId()
                && (stream instanceof AudioStream
                || stream instanceof VideoStream video && video.isVideoOnly()))) {
            return localManifest(mediaServer, dash(info, stream, mediaServer), CastManifest.DASH);
        }
        if (stream.getDeliveryMethod() != DeliveryMethod.PROGRESSIVE_HTTP || !stream.isUrl()) {
            throw new IOException("Unsupported extracted cast delivery method");
        }
        if (stream.getFormat() == null) {
            throw new IOException("The extracted media has no MIME type");
        }
        final String mime = stream.getFormat().getMimeType();
        return new CastMedia(mime, mediaServer.register(stream.getContent(), mime,
                contentLength(stream)), null, Map.of());
    }

    private String dash(final StreamInfo info, final Stream stream,
                        final CastMediaServer mediaServer) throws Exception {
        final boolean youtube = info.getServiceId() == ServiceList.YouTube.getServiceId();
        final String manifest;
        String base = stream.getManifestUrl();
        if (youtube && stream.getDeliveryMethod() != DeliveryMethod.HLS) {
            final ItagItem itag = Objects.requireNonNull(stream.getItagItem(),
                    "YouTube stream is missing its index metadata");
            // Seed the byte length before rewriting the generated BaseURL. The streaming proxy
            // can then answer suffix/open-ended receiver ranges without an extra size probe.
            mediaServer.register(stream.getContent(), "", itag.getContentLength());
            base = stream.getContent();
            if (info.getStreamType() == StreamType.POST_LIVE_STREAM) {
                manifest = YoutubePostLiveStreamDvrDashManifestCreator
                        .fromPostLiveStreamDvrStreamingUrl(stream.getContent(), itag,
                                itag.getTargetDurationSec(), info.getDuration());
            } else if (stream.getDeliveryMethod() == DeliveryMethod.PROGRESSIVE_HTTP) {
                manifest = YoutubeProgressiveDashManifestCreator.fromProgressiveStreamingUrl(
                        stream.getContent(), itag, info.getDuration());
            } else if (stream.getDeliveryMethod() == DeliveryMethod.DASH && !stream.isUrl()) {
                manifest = YoutubeOtfDashManifestCreator.fromOtfStreamingUrl(stream.getContent(),
                        itag, info.getDuration());
            } else {
                return mediaServer.manifest(stream.getContent(), CastManifest.DASH);
            }
        } else if (stream.getDeliveryMethod() == DeliveryMethod.DASH) {
            if (stream.isUrl()) {
                return mediaServer.manifest(stream.getContent(), CastManifest.DASH);
            }
            manifest = stream.getContent();
        } else {
            throw new IOException("Separate tracks require DASH index metadata or two HLS "
                    + "playlists; this source would need remuxing on the phone");
        }
        return CastManifest.dash(manifest, base, mediaServer);
    }

    private String hlsUrl(final Stream stream, final CastMediaServer mediaServer) throws Exception {
        if (stream.isUrl()) {
            mediaServer.manifest(stream.getContent(), CastManifest.HLS);
            return mediaServer.register(stream.getContent(), CastManifest.HLS);
        }
        final String base = stream.getManifestUrl();
        return mediaServer.content(CastManifest.hls(stream.getContent(), base, mediaServer),
                CastManifest.HLS);
    }

    private CastMedia remoteManifest(final CastMediaServer mediaServer, final String url,
                                     final String mime) throws Exception {
        mediaServer.manifest(url, mime);
        return new CastMedia(mime, mediaServer.register(url, mime), null, Map.of());
    }

    private CastMedia localManifest(final CastMediaServer mediaServer, final String manifest,
                                    final String mime) throws IOException {
        return new CastMedia(mime, mediaServer.content(manifest, mime), null, Map.of());
    }

    private static long contentLength(final Stream stream) {
        return stream.getItagItem() == null ? -1 : stream.getItagItem().getContentLength();
    }

    @Override
    public void close() {
        final CastMediaServer previous;
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            previous = server;
            server = null;
        }
        if (previous != null) {
            previous.close();
        }
    }
}
