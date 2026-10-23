package us.shandian.giga.postprocessing;

import android.content.Context;

import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import okio.BufferedSink;
import okio.BufferedSource;
import okio.Okio;
import okio.Sink;
import us.shandian.giga.get.DownloadMission;

public class PvcFromHlsRemuxer extends Postprocessing {
    private static final AtomicLong WORK_DIR_COUNTER = new AtomicLong();
    // the directories of the conversions that are running in this process
    private static final Set<File> ACTIVE_WORK_DIRS = new HashSet<>();

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

    /**
     * Makes a directory for one conversion. Conversions of several missions can run at the
     * same time, so no two may share their segment files or their output file.
     *
     * @param parent           the directory all conversions work under
     * @param missionTimestamp the timestamp of the mission, for a name that can be recognised
     * @return a new, empty directory
     * @throws IOException if the directory cannot be created
     */
    @VisibleForTesting
    static File newWorkDir(final File parent, final long missionTimestamp) throws IOException {
        final File dir = new File(parent, missionTimestamp + "-" + System.nanoTime() + "-"
                + WORK_DIR_COUNTER.incrementAndGet());
        if (!dir.mkdirs()) {
            throw new IOException("Cannot create the conversion directory " + dir);
        }
        return dir;
    }

    /** What one conversion does in its directory. */
    @VisibleForTesting
    interface WorkDirAction {
        void run(File workDir) throws IOException, InterruptedException;
    }

    /**
     * Runs one conversion in a directory of its own and attempts to remove it afterwards,
     * also when conversion fails. Cleanup is best effort and does not replace a conversion failure.
     * <p>
     * When no other conversion is running, whatever is in the parent directory was left by a
     * conversion that never got to clean up: a build from before each conversion had its own
     * directory, or a process that was killed half way. Cleanup is attempted first.
     *
     * @param parent           the directory all conversions work under
     * @param missionTimestamp the timestamp of the mission
     * @param action           the conversion
     * @throws IOException          if the directory cannot be made or the conversion fails
     * @throws InterruptedException if the conversion is interrupted
     */
    @VisibleForTesting
    static void inWorkDir(final File parent, final long missionTimestamp,
                          final WorkDirAction action) throws IOException, InterruptedException {
        final File workDir;
        synchronized (ACTIVE_WORK_DIRS) {
            if (ACTIVE_WORK_DIRS.isEmpty()) {
                final File[] leftovers = parent.listFiles();
                if (leftovers != null) {
                    for (final File leftover : leftovers) {
                        deleteQuietly(leftover);
                    }
                }
            }
            workDir = newWorkDir(parent, missionTimestamp);
            ACTIVE_WORK_DIRS.add(workDir);
        }
        try {
            action.run(workDir);
        } finally {
            synchronized (ACTIVE_WORK_DIRS) {
                ACTIVE_WORK_DIRS.remove(workDir);
            }
            deleteQuietly(workDir);
        }
    }

    /**
     * Deletes a file or a directory with everything in it. Does not throw: it runs while an
     * exception of the conversion may be on its way out, and must not replace it.
     *
     * @param file what to delete
     */
    @VisibleForTesting
    static void deleteQuietly(@Nullable final File file) {
        if (file == null) {
            return;
        }
        try {
            final File[] children = file.listFiles();
            if (children != null) {
                for (final File child : children) {
                    deleteQuietly(child);
                }
            }
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        } catch (final RuntimeException ignored) {
            // what cannot be deleted stays in the app's own files directory
        }
    }

    private static class PvcHlsConverterToMp4 {

        private final File tempParent;
        private final DownloadMission mission;

        PvcHlsConverterToMp4(final DownloadMission mission) {
            this.mission = mission;
            final Context context = App.getInstance().getApplicationContext();
            final File external = context.getExternalFilesDir(null);
            this.tempParent = new File(external != null ? external : context.getCacheDir(),
                    "ffmpeg_temp");
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
            if (!(mission.pvcArbitraryData instanceof List<?>)) {
                throw new IOException("The segment list of this HLS download is missing");
            }
            @SuppressWarnings("unchecked") final List<HlsParser.Segment> segments =
                    (List<HlsParser.Segment>) mission.pvcArbitraryData;

            inWorkDir(tempParent, mission.timestamp, workDir -> {
                final File remuxedFile = new File(workDir, "output.mp4");
                final SegmentInputStreamProvider segmentInputStreamProvider =
                        new SegmentInputStreamProvider(segmentStreams);
                final HlsMediaProcessor hlsMediaProcessor = new HlsMediaProcessor(null,
                        workDir.getAbsolutePath(), remuxedFile.getAbsolutePath(),
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

                hlsMediaProcessor.setSegments(segments);
                hlsMediaProcessor.processSegments(segmentInputStreamProvider);
                hlsMediaProcessor.finalizeDownload();

                copyFileToStream(remuxedFile, outputStream);
            });
        }

        private void copyFileToStream(
                final File inFile,
                final SharpStream outStream) throws IOException {
            final SharpOutputStream sharpOutputStream = new SharpOutputStream(outStream);
            final Sink sink = Okio.sink(sharpOutputStream);
            final BufferedSink bufferedSink = Okio.buffer(sink);

            // Close only the source, also when the copy fails. The 'out' stream will be
            // properly closed by the Postprocessing framework in
            // CircularFileWriter.finalizeFile(). If we close it here the framework would
            // crash as it can no longer finalize it.
            try (BufferedSource bufferedSource = Okio.buffer(Okio.source(inFile))) {
                // Read all data from source to sink
                bufferedSource.readAll(bufferedSink);

                // Flush the buffered sink to ensure all data is pushed through
                bufferedSink.flush();
            }
        }
    }
}
