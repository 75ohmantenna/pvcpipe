package us.shandian.giga.postprocessing;

import android.content.Context;

import com.github.evermindzz.hlsdownloader.FFmpegSegmentCombiner;
import com.github.evermindzz.hlsdownloader.HlsMediaProcessor;
import com.github.evermindzz.hlsdownloader.parser.HlsParser;
import com.github.evermindzz.slimhls.converter.RunFFmpeg;

import org.schabi.newpipe.App;
import org.schabi.newpipe.streams.io.SharpInputStream;
import org.schabi.newpipe.streams.io.SharpOutputStream;
import org.schabi.newpipe.streams.io.SharpStream;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.List;

import okio.BufferedSink;
import okio.BufferedSource;
import okio.Okio;
import okio.Sink;
import okio.Source;
import us.shandian.giga.get.DownloadMission;

public class PvcFromHlsRemuxer extends Postprocessing {

    PvcFromHlsRemuxer() {
        super(false, true, ALGORITHM_PVC_HLS_REMUXER);
    }


    @Override
    int process(final SharpStream out, final SharpStream... sources) throws IOException {
        try {
            final PvcHlsConverterToMp4 converterToMp4 = new PvcHlsConverterToMp4(this.mission);
            converterToMp4.convert(sources, out);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        return OK_RESULT;
    }

    /**
     * Provide the hls segment based on the index.
     */
    static final class SegmentInputStreamProvider implements HlsMediaProcessor.InputStreamProvider {
        final SharpStream[] segmentStreams;

        private SegmentInputStreamProvider(final SharpStream[] segmentStreams) {
            this.segmentStreams = segmentStreams;
        }

        @Override
        public InputStream get(final URI uri, final int i) throws IOException {
            if (segmentStreams.length > i) {
                return new SharpInputStream(segmentStreams[i]);
            }
            throw new RuntimeException("there is not stream for index: " + i);
        }
    }

    private static class PvcHlsConverterToMp4 {

        private final File tempDir;
        private final DownloadMission mission;

        PvcHlsConverterToMp4(final DownloadMission mission) {
            this.mission = mission;
            final Context context = App.getInstance().getApplicationContext();
            this.tempDir = new File(context.getExternalFilesDir(null), "ffmpeg_temp");
        }

        private FFmpegSegmentCombiner createCombiner() {
            final RunFFmpeg runFFmpeg = new RunFFmpeg(App.getInstance().getApplicationContext());
            return new FFmpegSegmentCombiner(args -> {
                try {
                    return runFFmpeg.execute(args);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }

        /**
         * Actually convert the hls stream to the output stream.
         *
         * @param segmentStreams        the hls input segment streams
         * @param outputStream          the result of the conversion will be written there
         * @throws IOException          on I/O problems. eg writing to disk etc.
         * @throws InterruptedException could happen from {@link HlsMediaProcessor} but
         *                              not likely here as we do not interrupt it.
         */
        public void convert(
                final SharpStream[] segmentStreams,
                final SharpStream outputStream)
                throws IOException, InterruptedException {
            createTempDir();

            final File remuxedFile = new File(tempDir, "output.mp4");
            final SegmentInputStreamProvider segmentInputStreamProvider =
                    new SegmentInputStreamProvider(segmentStreams);
            final HlsMediaProcessor hlsMediaProcessor = new HlsMediaProcessor(null,
                    tempDir.getAbsolutePath(), remuxedFile.getAbsolutePath(),
                    null,
                    null,
                    1, null,
                    createCombiner(),
                    (progress, total) -> {
                    },
                    (state, message) -> {
                    },
                    true
            );

            if (mission.pvcArbitraryData instanceof List<?>) {
                @SuppressWarnings("unchecked") final List<HlsParser.Segment> segments =
                        (List<HlsParser.Segment>) mission.pvcArbitraryData;
                hlsMediaProcessor.setSegments(segments);
                hlsMediaProcessor.processSegments(segmentInputStreamProvider);
                hlsMediaProcessor.finalizeDownload();

                copyFileToStream(remuxedFile, outputStream);
            }

            cleanupTempFiles(remuxedFile);
        }

        private void copyFileToStream(
                final File inFile,
                final SharpStream outStream) throws IOException {
            final Source source = Okio.source(inFile);
            final SharpOutputStream sharpOutputStream = new SharpOutputStream(outStream);
            final Sink sink = Okio.sink(sharpOutputStream);

            final BufferedSource bufferedSource = Okio.buffer(source);
            final BufferedSink bufferedSink = Okio.buffer(sink);

            // Read all data from source to sink
            bufferedSource.readAll(bufferedSink);

            // Flush the buffered sink to ensure all data is pushed through
            bufferedSink.flush();

            // close only the source. The 'out' stream will be properly closed by the
            // Postprocessing framework in CircularFileWriter.finalizeFile().
            // If we close it here the framework would crash as it can no longer finalize it
            bufferedSource.close();
        }

        private void createTempDir() {
            if (!tempDir.exists()) {
                tempDir.mkdirs();
            }
        }

        private void cleanupTempFiles(
                final File outputFile) {
            outputFile.delete();
            tempDir.delete();
        }
    }
}
