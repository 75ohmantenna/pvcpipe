package us.shandian.giga.preprocessing;

import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import com.github.evermindzz.hlsdownloader.HlsMediaProcessor;
import com.github.evermindzz.hlsdownloader.common.Fetcher;
import com.github.evermindzz.hlsdownloader.parser.HlsParser;

import org.schabi.newpipe.DownloaderImpl;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import us.shandian.giga.get.DownloadMission;
import us.shandian.giga.get.MissionRecoveryInfo;

public class PvcHlsPreProcessor {
    private final HlsMediaProcessor segmentsDataDownloader;
    private List<HlsParser.Segment> segments;

    public PvcHlsPreProcessor() {
        segmentsDataDownloader = setupHlsMediaProcessor();
    }

    public void adjustMissionForSegmentUrls(
            final DownloadMission mission) {
        try {
            segments = segmentsDataDownloader.getPlayListSegmentData(URI.create(mission.urls[0]));
            segmentsDataDownloader.retrieveEncryptionKeys(segments);
        } catch (final IOException e) {
            throw new RuntimeException(e);
        }


        final List<String> segmentsUrls = new ArrayList<>();
        final List<MissionRecoveryInfo> missionRecoveryInfos = new ArrayList<>();
        if (segments != null) {
            for (final HlsParser.Segment segment : segments) {
                segmentsUrls.add(segment.getUri().toString());
                // Retain the original rendition metadata for each segment. HLS missions bypass
                // progressive-URL recovery in DownloadMission.doRecover; these are not segment URLs.
                missionRecoveryInfos.add(mission.recoveryInfo[0]);
            }
        }

        // update values for segment download
        mission.urls = segmentsUrls.toArray(new String[0]);
        mission.offsets = new long[mission.urls.length];
        mission.recoveryInfo = missionRecoveryInfos.toArray(new MissionRecoveryInfo[0]);
    }

    // setup a HlsMediaProcessor that does not download any segments at all.
    // It is just used to retrieve the playlist and retrieve the urls and
    // if necessary also retrieve encryption keys.
    private HlsMediaProcessor setupHlsMediaProcessor() {
        final Fetcher fetcher = new FetchWithDownloaderImpl();
        final HlsParser parser = new HlsParser(
                variants -> {
                    throw new RuntimeException("HLS master playlist variants are not supported.");
                },
                fetcher,
                false
        );

        // outputDir and outputFile is not needed here so we null it.
        final HlsMediaProcessor hlsMediaProcessor = new HlsMediaProcessor(parser, null, null,
                fetcher,
                null,
                1, null,
                null,
                (progress, total) -> {
                },
                (state, message) -> {
                },
                false);

        return hlsMediaProcessor;
    }

    public void modifyMission(
            final DownloadMission mission) {
        adjustMissionForSegmentUrls(mission);

        // attach segment data for later use in post processing
        mission.pvcArbitraryData = this.segments;
    }

    /**
     * Fetches playlists and encryption keys with the app's HTTP client. It hands on the bytes
     * the server sent: a key is 16 bytes of binary data, which the text of
     * {@link DownloaderImpl}'s answers cannot carry.
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

        public InputStream fetchContent(final URI uri) throws IOException {
            final String url = uri.toString();
            final Request.Builder request = new Request.Builder().url(url)
                    .header("User-Agent", DownloaderImpl.getMediaUserAgent(url));
            final String referer = DownloaderImpl.getMediaReferer(url);
            if (referer != null) {
                request.header("Referer", referer);
            }
            final OkHttpClient theClient = client != null
                    ? client : DownloaderImpl.getInstance().getClient();
            try (Response response = theClient.newCall(request.build()).execute()) {
                if (!response.isSuccessful()) {
                    // an error page is neither a playlist nor a key
                    throw new IOException("HTTP " + response.code() + " for " + url);
                }
                return new ByteArrayInputStream(response.body().bytes());
            }
        }
    }
}
