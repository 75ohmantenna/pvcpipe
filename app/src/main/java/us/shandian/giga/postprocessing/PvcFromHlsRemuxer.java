package us.shandian.giga.postprocessing;

import android.content.Context;

import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import com.github.evermindzz.hlsdownloader.FFmpegSegmentCombiner;
import com.github.evermindzz.hlsdownloader.HlsMediaProcessor;
import com.github.evermindzz.hlsdownloader.parser.HlsParser;
import com.github.evermindzz.slimhls.converter.RunFFmpeg;

import org.schabi.newpipe.App;
import org.schabi.newpipe.streams.io.SharpOutputStream;
import org.schabi.newpipe.streams.io.SharpStream;

import java.io.EOFException;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

import okio.BufferedSink;
import okio.BufferedSource;
import okio.Okio;
import okio.Sink;
import us.shandian.giga.get.DownloadMission;
import us.shandian.giga.preprocessing.HlsDownloadPlan;

public class PvcFromHlsRemuxer extends Postprocessing {
    // Keep existing mission checkpoints readable after adding the typed-plan conversion path.
    private static final long serialVersionUID = 2374249465661711693L;
    private static final AtomicLong WORK_DIR_COUNTER = new AtomicLong();
    private static final Pattern EXPLICIT_IV = Pattern.compile("(?:0x)?[0-9a-fA-F]{32}");
    // the directories of the conversions that are running in this process
    private static final Set<File> ACTIVE_WORK_DIRS = new HashSet<>();

    @Nullable
    private final File tempParent;

    PvcFromHlsRemuxer() {
        this(null);
    }

    @VisibleForTesting
    PvcFromHlsRemuxer(@Nullable final File tempParent) {
        super(false, true, ALGORITHM_PVC_HLS_REMUXER);
        this.tempParent = tempParent;
    }

    @Override
    int process(final SharpStream out, final SharpStream... sources) throws IOException {
        final Context context = App.getInstance().getApplicationContext();
        final File external = context.getExternalFilesDir(null);
        final File parent = tempParent != null ? tempParent
                : new File(external != null ? external : context.getCacheDir(), "ffmpeg_temp");
        final RunFFmpeg runFFmpeg = new RunFFmpeg(context);
        try {
            convert(mission, sources, out, parent, runFFmpeg::execute);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("HLS conversion interrupted", e);
        }
        return OK_RESULT;
    }

    /**
     * A logical window never owns the physical source. In particular, a cipher may close its
     * input without closing a resource that a later segment or initialization map reuses.
     */
    private static final class WindowInputStream extends InputStream {
        private final SharpStream source;
        private long remaining;
        private boolean closed;

        WindowInputStream(final SharpStream[] sources, final HlsDownloadPlan.Window window)
                throws IOException {
            final int index = window.getResourceIndex();
            final long offset = window.getOffset();
            final long length = window.getLength();
            if (index < 0 || index >= sources.length || sources[index] == null
                    || offset < 0 || length < -1 || (length == -1 && offset != 0)
                    || (length >= 0 && offset > Long.MAX_VALUE - length)) {
                throw new IOException("Invalid HLS resource window");
            }
            source = sources[index];
            if (source.isClosed() || !source.canRead() || !source.canRewind()) {
                throw new IOException("HLS resource cannot be rewound");
            }
            source.rewind();
            final long available = source.available();
            if (offset > available || (length >= 0 && length > available - offset)) {
                throw new EOFException("HLS resource window exceeds physical resource");
            }
            long toSkip = offset;
            while (toSkip > 0) {
                final long skipped = source.skip(Math.min(toSkip, Integer.MAX_VALUE));
                if (skipped < 0 || skipped > toSkip) {
                    throw new IOException("Invalid HLS resource skip");
                }
                if (skipped == 0) {
                    if (source.read() < 0) {
                        throw new EOFException("Truncated HLS resource before window");
                    }
                    toSkip--;
                } else {
                    toSkip -= skipped;
                }
            }
            remaining = length == -1 ? available : length;
        }

        @Override
        public int read() throws IOException {
            ensureOpen();
            if (remaining == 0) {
                return -1;
            }
            final int result = source.read();
            if (result < 0) {
                throw new EOFException("Truncated HLS resource window");
            }
            remaining--;
            return result;
        }

        @Override
        public int read(final byte[] bytes, final int offset, final int length) throws IOException {
            if (offset < 0 || length < 0 || offset > bytes.length - length) {
                throw new IndexOutOfBoundsException();
            }
            ensureOpen();
            if (length == 0) {
                return 0;
            }
            if (remaining == 0) {
                return -1;
            }
            final int count = source.read(bytes, offset, (int) Math.min(remaining, length));
            if (count < 0) {
                throw new EOFException("Truncated HLS resource window");
            }
            if (count == 0) {
                bytes[offset] = (byte) read();
                return 1;
            }
            remaining -= count;
            return count;
        }

        private void ensureOpen() throws IOException {
            if (closed) {
                throw new IOException("HLS window is closed");
            }
        }

        @Override
        public void close() {
            closed = true;
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

    @VisibleForTesting
    interface FfmpegExecutor {
        int execute(String[] arguments) throws IOException, InterruptedException;
    }

    private static final class FfmpegFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;

        FfmpegFailure(final Exception cause) {
            super(cause);
        }
    }

    /*
     * Stage every logical input before starting the in-place output copy. Both host proof and
     * production use this path; only the executor and workspace location are injectable.
     */
    @VisibleForTesting
    static void convert(final DownloadMission mission, final SharpStream[] sources,
                        final SharpStream output, final File tempParent,
                        final FfmpegExecutor executor) throws IOException, InterruptedException {
        checkInterrupted();
        final HlsDownloadPlan plan;
        if (mission.pvcArbitraryData instanceof HlsDownloadPlan) {
            plan = (HlsDownloadPlan) mission.pvcArbitraryData;
        } else if (mission.pvcArbitraryData instanceof List<?>) {
            plan = HlsDownloadPlan.fromLegacy((List<?>) mission.pvcArbitraryData);
            // Preserve the old physical-resource/checkpoint layout; only metadata is migrated.
            mission.pvcArbitraryData = plan;
        } else {
            throw new IOException("The segment plan of this HLS download is missing");
        }
        if (plan.getSegments().isEmpty()) {
            throw new IOException("The HLS download has no segments");
        }
        inWorkDir(tempParent, mission.timestamp, workDir -> {
            final File remuxedFile = new File(workDir, "output.mp4");
            final HlsMediaProcessor processor = new HlsMediaProcessor(null,
                    workDir.getAbsolutePath(), remuxedFile.getAbsolutePath(), null, null,
                    1, null, null, null, null, true);
            final List<File> inputs = new ArrayList<>(plan.getSegments().size());
            int index = 0;
            final byte[] buffer = new byte[64 * 1024];
            for (final HlsDownloadPlan.Segment segment : plan.getSegments()) {
                checkInterrupted();
                final File input = new File(workDir, "segment_" + index + ".ts");
                try (OutputStream staged = new FileOutputStream(input)) {
                    final HlsDownloadPlan.Initialization initialization =
                            segment.getInitialization();
                    if (initialization != null) {
                        writeWindow(processor, initialization.getMedia(),
                                initialization.getWindow(), index, sources, staged, buffer);
                    }
                    writeWindow(processor, segment.getMedia(), segment.getMediaWindow(),
                            index, sources, staged, buffer);
                }
                inputs.add(input);
                index++;
            }
            checkInterrupted();
            final FFmpegSegmentCombiner combiner = new FFmpegSegmentCombiner(arguments -> {
                try {
                    checkInterrupted();
                    applySegmentDurations(arguments, plan.getSegments());
                    final int status = executor.execute(arguments);
                    if (status != 0) {
                        throw new IOException("FFmpeg exited with status " + status);
                    }
                    return status;
                } catch (final IOException | InterruptedException e) {
                    throw new FfmpegFailure(e);
                }
            });
            try {
                combiner.combineSegments(inputs, workDir.getAbsolutePath(),
                        remuxedFile.getAbsolutePath());
            } catch (final FfmpegFailure failure) {
                if (failure.getCause() instanceof InterruptedException) {
                    throw (InterruptedException) failure.getCause();
                }
                throw (IOException) failure.getCause();
            }
            checkInterrupted();
            if (!remuxedFile.isFile() || remuxedFile.length() == 0) {
                throw new IOException("FFmpeg produced no HLS output");
            }
            copyFileToStream(remuxedFile, output);
        });
    }

    private static void applySegmentDurations(final String[] arguments,
                                              final List<HlsDownloadPlan.Segment> segments)
            throws IOException {
        Path concat = null;
        for (int i = 0; i + 1 < arguments.length; i++) {
            if ("-i".equals(arguments[i])) {
                concat = Path.of(arguments[i + 1]);
                break;
            }
        }
        if (concat == null) {
            throw new IOException("FFmpeg concat input is missing");
        }
        // A self-contained fMP4 fragment retains its absolute decode time. The concat
        // demuxer's inferred duration can include that prior timeline, adding gaps.
        // Preserve the dependency's escaped file paths, but use authoritative EXTINF timing.
        final StringBuilder timed = new StringBuilder();
        int index = 0;
        for (final String line : Files.readAllLines(concat)) {
            timed.append(line).append('\n');
            if (line.startsWith("file ")) {
                if (index >= segments.size()) {
                    throw new IOException("FFmpeg concat input has unexpected segments");
                }
                final double duration = segments.get(index++).getMedia().getDuration();
                if (!Double.isFinite(duration) || duration <= 0) {
                    throw new IOException("HLS segment has no finite positive duration");
                }
                timed.append("duration ").append(duration).append('\n');
            }
        }
        if (index != segments.size()) {
            throw new IOException("FFmpeg concat input is missing segments");
        }
        Files.writeString(concat, timed);
    }

    private static void writeWindow(final HlsMediaProcessor processor,
                                    final HlsParser.Segment media,
                                    final HlsDownloadPlan.Window window, final int index,
                                    final SharpStream[] sources, final OutputStream output,
                                    final byte[] buffer)
            throws IOException, InterruptedException {
        checkInterrupted();
        final HlsParser.EncryptionInfo encryption = media.getEncryptionInfo();
        if (encryption != null) {
            final String iv = encryption.getIv();
            if (iv == null || !EXPLICIT_IV.matcher(iv).matches()) {
                // Legacy lists did not retain media sequence. Never invent an IV for them.
                throw new IOException("HLS encryption requires an explicit 128-bit IV");
            }
            if (!"AES-128".equals(encryption.getMethod()) || encryption.getKey() == null
                    || encryption.getKey().length != 16) {
                throw new IOException("Invalid HLS AES-128 encryption metadata");
            }
        }
        // The provider slices ciphertext. Decryption must never see adjacent window bytes.
        try (InputStream bounded = new WindowInputStream(sources, window);
             InputStream decrypted = processor.processSegment(media, index,
                     (uri, ignoredIndex) -> bounded)) {
            int count;
            while ((count = decrypted.read(buffer)) != -1) {
                checkInterrupted();
                output.write(buffer, 0, count);
            }
        } catch (final InterruptedIOException e) {
            // processSegment consumes Thread.interrupted() when detecting cancellation.
            Thread.currentThread().interrupt();
            throw e;
        }
    }

    private static void checkInterrupted() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("HLS conversion interrupted");
        }
    }

    private static void copyFileToStream(final File inFile, final SharpStream outStream)
            throws IOException {
        final SharpOutputStream sharpOutputStream = new SharpOutputStream(outStream);
        final Sink sink = Okio.sink(sharpOutputStream);
        final BufferedSink bufferedSink = Okio.buffer(sink);
        // The framework owns the output and finalizes its in-place writer after this returns.
        try (BufferedSource bufferedSource = Okio.buffer(Okio.source(inFile))) {
            bufferedSource.readAll(bufferedSink);
            bufferedSink.flush();
        }
    }
}
