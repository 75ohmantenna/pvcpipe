package us.shandian.giga.preprocessing;

import android.net.Uri;

import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.media3.common.util.UriUtil;
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist;
import androidx.media3.exoplayer.hls.playlist.HlsPlaylist;
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParser;

import com.github.evermindzz.hlsdownloader.common.Fetcher;
import com.github.evermindzz.hlsdownloader.parser.HlsParser;

import org.schabi.newpipe.DownloaderImpl;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import us.shandian.giga.get.DownloadMission;
import us.shandian.giga.get.MissionRecoveryInfo;

@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public class PvcHlsPreProcessor {
    private static final Pattern KEY_ATTRIBUTE =
            Pattern.compile("(?:^|,)\\s*([A-Z0-9-]+)=(\"[^\"]*\"|[^,]*)");
    private static final Pattern HEX_IV = Pattern.compile("[0-9a-fA-F]+");
    private static final String ZERO_IV = "00000000000000000000000000000000";

    private final FetchWithDownloaderImpl fetcher;

    public PvcHlsPreProcessor() {
        this(null);
    }

    @VisibleForTesting
    PvcHlsPreProcessor(@Nullable final OkHttpClient client) {
        fetcher = new FetchWithDownloaderImpl(client);
    }

    public void modifyMission(final DownloadMission mission) {
        final LinkedHashMap<String, Integer> resources = new LinkedHashMap<>();
        final HlsDownloadPlan plan;
        try {
            plan = readPlan(URI.create(mission.urls[0]), resources);
        } catch (final IOException | IllegalArgumentException e) {
            throw new RuntimeException("Could not prepare HLS download", e);
        }

        // Publish only after parsing, encryption validation and key fetching all succeed.
        // Workers retain their existing whole-resource download/checkpoint architecture.
        final String[] urls = resources.keySet().toArray(new String[0]);
        final MissionRecoveryInfo[] recovery = new MissionRecoveryInfo[urls.length];
        for (int i = 0; i < recovery.length; i++) {
            recovery[i] = mission.recoveryInfo[0];
        }
        mission.urls = urls;
        mission.offsets = new long[urls.length];
        mission.recoveryInfo = recovery;
        mission.pvcArbitraryData = plan;
    }

    private HlsDownloadPlan readPlan(final URI uri,
                                     final LinkedHashMap<String, Integer> resources)
            throws IOException {
        final HlsMediaPlaylist playlist;
        final String base;
        try (Response response = fetcher.open(uri)) {
            requireSuccessful(response, uri);
            base = response.request().url().toString();
            final HlsPlaylist parsed = new HlsPlaylistParser().parse(
                    Uri.parse(base), response.body().byteStream());
            if (!(parsed instanceof HlsMediaPlaylist)) {
                throw new IOException("Select an HLS media playlist before downloading");
            }
            playlist = (HlsMediaPlaylist) parsed;
        }
        validateEncryption(playlist);
        if (playlist.segments.isEmpty()) {
            throw new IOException("HLS media playlist has no segments");
        }
        final Map<URI, byte[]> keys = new HashMap<>();
        final Map<HlsMediaPlaylist.Segment, HlsDownloadPlan.Initialization> initializations =
                new IdentityHashMap<>();
        final List<HlsDownloadPlan.Segment> segments =
                new ArrayList<>(playlist.segments.size());
        for (final HlsMediaPlaylist.Segment segment : playlist.segments) {
            final HlsDownloadPlan.Initialization initialization;
            if (segment.initializationSegment == null) {
                initialization = null;
            } else {
                final HlsMediaPlaylist.Segment map = segment.initializationSegment;
                HlsDownloadPlan.Initialization cached = initializations.get(map);
                if (cached == null) {
                    final HlsParser.Segment media = media(base, map, keys);
                    cached = new HlsDownloadPlan.Initialization(media,
                            window(media.getUri(), map, resources));
                    initializations.put(map, cached);
                }
                initialization = cached;
            }
            final HlsParser.Segment media = media(base, segment, keys);
            segments.add(new HlsDownloadPlan.Segment(media,
                    window(media.getUri(), segment, resources), initialization));
        }
        return new HlsDownloadPlan(segments);
    }

    private static void validateEncryption(final HlsMediaPlaylist playlist) throws IOException {
        // Media3 retains KEY tags even when identity SAMPLE-AES has neither full-segment
        // encryption nor DRM data. Do not silently treat that unsupported media as clear.
        for (final String tag : playlist.tags) {
            if (!tag.startsWith("#EXT-X-KEY:")) {
                continue;
            }
            final String attributes = tag.substring("#EXT-X-KEY:".length());
            final Matcher attribute = KEY_ATTRIBUTE.matcher(attributes);
            String method = null;
            String format = null;
            while (attribute.find()) {
                if ("METHOD".equals(attribute.group(1))) {
                    if (method != null) {
                        throw new IOException("Ambiguous HLS encryption method");
                    }
                    method = attribute.group(2);
                } else if ("KEYFORMAT".equals(attribute.group(1))) {
                    if (format != null) {
                        throw new IOException("Ambiguous HLS encryption format");
                    }
                    format = attribute.group(2);
                }
            }
            if (!("NONE".equals(method) || "AES-128".equals(method))
                    || (format != null && !"\"identity\"".equals(format)
                    && !"identity".equals(format))) {
                throw new IOException("Unsupported HLS encryption");
            }
        }
        for (final HlsMediaPlaylist.Segment segment : playlist.segments) {
            if (segment.drmInitData != null || (segment.initializationSegment != null
                    && segment.initializationSegment.drmInitData != null)) {
                throw new IOException("Unsupported HLS DRM");
            }
        }
    }

    private HlsParser.Segment media(final String base, final HlsMediaPlaylist.Segment segment,
                                    final Map<URI, byte[]> keys) throws IOException {
        final HlsParser.EncryptionInfo encryption;
        if (segment.fullSegmentEncryptionKeyUri == null) {
            encryption = null;
        } else {
            final URI keyUri = resolve(base, segment.fullSegmentEncryptionKeyUri);
            encryption = new HlsParser.EncryptionInfo("AES-128", keyUri,
                    normalizeIv(segment.encryptionIV));
            byte[] key = keys.get(keyUri);
            if (key == null) {
                key = fetcher.fetchBytes(keyUri);
                if (key.length != 16) {
                    throw new IOException("HLS AES-128 key must contain exactly 16 bytes");
                }
                keys.put(keyUri, key);
            }
            encryption.setKey(key);
        }
        return new HlsParser.Segment(resolve(base, segment.url),
                segment.durationUs / 1_000_000.0, segment.title, encryption);
    }

    private static String normalizeIv(@Nullable final String iv) throws IOException {
        if (iv == null) {
            throw new IOException("HLS AES-128 segment has no IV");
        }
        final String hex = iv.startsWith("0x") || iv.startsWith("0X") ? iv.substring(2) : iv;
        if (hex.isEmpty() || hex.length() > 32 || !HEX_IV.matcher(hex).matches()) {
            throw new IOException("Invalid HLS AES-128 IV");
        }
        return hex.length() == 32 ? hex : ZERO_IV.substring(hex.length()) + hex;
    }

    private static URI resolve(final String base, final String reference) throws IOException {
        try {
            return URI.create(UriUtil.resolve(base, reference));
        } catch (final IllegalArgumentException e) {
            throw new IOException("Invalid HLS resource URL", e);
        }
    }

    private static HlsDownloadPlan.Window window(
            final URI uri, final HlsMediaPlaylist.Segment segment,
            final LinkedHashMap<String, Integer> resources) throws IOException {
        final String url = uri.toString();
        Integer index = resources.get(url);
        if (index == null) {
            index = resources.size();
            resources.put(url, index);
        }
        try {
            return new HlsDownloadPlan.Window(index,
                    segment.byteRangeOffset, segment.byteRangeLength);
        } catch (final IllegalArgumentException e) {
            throw new IOException("Invalid HLS byte window", e);
        }
    }

    private static void requireSuccessful(final Response response, final URI uri)
            throws IOException {
        if (!response.isSuccessful() || response.body() == null) {
            throw new IOException("HTTP " + response.code() + " for " + uri);
        }
    }

    /**
     * Fetches encryption keys with the app's HTTP client. It hands on the bytes the server
     * sent: a key is 16 binary bytes, which the text of {@link DownloaderImpl}'s answers
     * cannot carry.
     */
    public static class FetchWithDownloaderImpl implements Fetcher {
        @Nullable
        private final OkHttpClient client;

        public FetchWithDownloaderImpl() {
            this(null);
        }

        @VisibleForTesting
        FetchWithDownloaderImpl(@Nullable final OkHttpClient client) {
            this.client = client;
        }

        @Override
        public InputStream fetchContent(final URI uri) throws IOException {
            return new ByteArrayInputStream(fetchBytes(uri));
        }

        private byte[] fetchBytes(final URI uri) throws IOException {
            try (Response response = open(uri)) {
                requireSuccessful(response, uri);
                return response.body().bytes();
            }
        }

        private Response open(final URI uri) throws IOException {
            final String url = uri.toString();
            final Request.Builder request = new Request.Builder().url(url)
                    .header("User-Agent", DownloaderImpl.getMediaUserAgent(url));
            final String referer = DownloaderImpl.getMediaReferer(url);
            if (referer != null) {
                request.header("Referer", referer);
            }
            final OkHttpClient theClient = client != null
                    ? client : DownloaderImpl.getInstance().getClient();
            return theClient.newCall(request.build()).execute();
        }
    }
}
