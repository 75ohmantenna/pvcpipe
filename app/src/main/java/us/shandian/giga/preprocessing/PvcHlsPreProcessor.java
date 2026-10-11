package us.shandian.giga.preprocessing;

import com.github.evermindzz.hlsdownloader.HlsMediaProcessor;
import com.github.evermindzz.hlsdownloader.common.Fetcher;
import com.github.evermindzz.hlsdownloader.parser.HlsParser;

import org.schabi.newpipe.DownloaderImpl;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

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
                // for now have the same RecoveryInfo for every segment
                // -> it just works but it might be not correct.
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
     * Use {@link DownloaderImpl} to fetch the playlist etc.
     */
    public static class FetchWithDownloaderImpl implements Fetcher {
        public FetchWithDownloaderImpl() {
        }

        private InputStream stringToInputStream(final String input) {
            final byte[] bytes = input.getBytes(StandardCharsets.UTF_8);
            return new ByteArrayInputStream(bytes);
        }

        public InputStream fetchContent(final URI uri) throws IOException {
            try {
                final Response response = DownloaderImpl.getInstance().get(uri.toString());
                return stringToInputStream(response.responseBody());
            } catch (ReCaptchaException e) {
                throw new IOException(e);
            }
        }
    }
}
