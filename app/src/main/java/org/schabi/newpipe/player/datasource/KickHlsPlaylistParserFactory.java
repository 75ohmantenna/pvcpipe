package org.schabi.newpipe.player.datasource;

import androidx.annotation.Nullable;
import androidx.media3.common.StreamKey;
import androidx.media3.exoplayer.hls.playlist.DefaultHlsPlaylistParserFactory;
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist;
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist;
import androidx.media3.exoplayer.hls.playlist.HlsPlaylist;
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParserFactory;
import androidx.media3.exoplayer.upstream.ParsingLoadable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Keeps a selected Kick video's native audio and subtitle rendition graph intact. */
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public final class KickHlsPlaylistParserFactory implements HlsPlaylistParserFactory {
    private static final String MISSING_RENDITION =
            "Selected Kick HLS rendition is no longer available";
    private final DefaultHlsPlaylistParserFactory delegate = new DefaultHlsPlaylistParserFactory();
    @Nullable
    private final String selectedVideoPlaylistUrl;
    @Nullable
    private final String selectedAudioPlaylistUrl;

    public KickHlsPlaylistParserFactory(@Nullable final String selectedVideoPlaylistUrl,
                                       @Nullable final String selectedAudioPlaylistUrl) {
        this.selectedVideoPlaylistUrl = selectedVideoPlaylistUrl;
        this.selectedAudioPlaylistUrl = selectedAudioPlaylistUrl;
    }

    @Override
    public ParsingLoadable.Parser<HlsPlaylist> createPlaylistParser() {
        return wrap(delegate.createPlaylistParser());
    }

    @Override
    public ParsingLoadable.Parser<HlsPlaylist> createPlaylistParser(
            final HlsMultivariantPlaylist multivariantPlaylist,
            @Nullable final HlsMediaPlaylist previousMediaPlaylist) {
        return wrap(delegate.createPlaylistParser(multivariantPlaylist, previousMediaPlaylist));
    }

    private ParsingLoadable.Parser<HlsPlaylist> wrap(
            final ParsingLoadable.Parser<HlsPlaylist> parser) {
        return (uri, input) -> {
            final HlsPlaylist playlist = parser.parse(uri, input);
            return playlist instanceof HlsMultivariantPlaylist
                    ? select((HlsMultivariantPlaylist) playlist) : playlist;
        };
    }

    private HlsMultivariantPlaylist select(final HlsMultivariantPlaylist playlist)
            throws IOException {
        final List<StreamKey> keys = new ArrayList<>();
        final Set<String> audioGroups = new HashSet<>();
        final Set<String> subtitleGroups = new HashSet<>();
        boolean videoFound = selectedVideoPlaylistUrl == null;
        for (int i = 0; i < playlist.variants.size(); i++) {
            final HlsMultivariantPlaylist.Variant variant = playlist.variants.get(i);
            if (selectedVideoPlaylistUrl == null
                    || selectedVideoPlaylistUrl.equals(variant.url.toString())) {
                videoFound = true;
                keys.add(new StreamKey(HlsMultivariantPlaylist.GROUP_INDEX_VARIANT, i));
                if (variant.audioGroupId != null) {
                    audioGroups.add(variant.audioGroupId);
                }
                if (variant.subtitleGroupId != null) {
                    subtitleGroups.add(variant.subtitleGroupId);
                }
            }
        }
        boolean audioFound = selectedAudioPlaylistUrl == null;
        for (int i = 0; i < playlist.audios.size(); i++) {
            final HlsMultivariantPlaylist.Rendition audio = playlist.audios.get(i);
            if (audioGroups.contains(audio.groupId) && (selectedAudioPlaylistUrl == null
                    || (audio.url != null
                    && selectedAudioPlaylistUrl.equals(audio.url.toString())))) {
                audioFound = true;
                keys.add(new StreamKey(HlsMultivariantPlaylist.GROUP_INDEX_AUDIO, i));
            }
        }
        if (!videoFound || !audioFound) {
            throw new IOException(MISSING_RENDITION);
        }
        for (int i = 0; i < playlist.subtitles.size(); i++) {
            if (subtitleGroups.contains(playlist.subtitles.get(i).groupId)) {
                keys.add(new StreamKey(HlsMultivariantPlaylist.GROUP_INDEX_SUBTITLE, i));
            }
        }
        return playlist.copy(keys);
    }
}
