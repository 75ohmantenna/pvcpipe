package us.shandian.giga.io;

import androidx.annotation.NonNull;

import org.schabi.newpipe.streams.io.SharpStream;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Objects;

/**
 * A custom file writer with a two-tiered write strategy:
 * - The "out" file: the main file being written to.
 * - The "aux" file: a temporary overflow buffer to hold excess data
 *   until it can be safely flushed into the main file.
 *
 * This is useful when the final write position (or maximum offset)
 * isn't always known in advance (e.g., downloading media in chunks),
 * and prevents overwriting data until it's confirmed safe to do so.
 */
public class CircularFileWriter extends SharpStream {

    // 8 KiB internal per-buffer queue
    private static final int QUEUE_BUFFER_SIZE = 8 * 1024;
    // 128 KiB used when copying from aux to out
    private static final int COPY_BUFFER_SIZE = 128 * 1024;
    // 64 KiB progress notification threshold
    private static final int NOTIFY_BYTES_INTERVAL = 64 * 1024;
    private static final int THRESHOLD_AUX_LENGTH = 15 * 1024 * 1024; // 15 MiB aux flush threshold

    private final OffsetChecker callback;

    public ProgressReport onProgress;
    public WriteErrorHandle onWriteError;

    private long reportPosition;
    private long maxLengthKnown = -1;

    private BufferedFile out;
    private BufferedFile aux;

    public CircularFileWriter(final SharpStream target, final File temp,
                              final OffsetChecker checker) throws IOException {
        Objects.requireNonNull(checker);

        if (!temp.exists()) {
            if (!temp.createNewFile()) {
                throw new IOException("Cannot create temporary file");
            }
        }

        aux = new BufferedFile(temp);
        out = new BufferedFile(target);

        callback = checker;

        reportPosition = NOTIFY_BYTES_INTERVAL;
    }

    /**
     * Flushes a specified amount of data from the aux file into the main output file.
     *
     * This happens when the aux buffer has accumulated too much data or when finalizing.
     * It also handles the "underflow" condition (where aux has unread data and/or out
     * hasn't reached its full length yet), ensuring consistency of offsets and lengths.
     *
     * @param amount Amount of data (in bytes) to move from aux to out.
     * @throws IOException if an I/O error occurs
     */
    private void flushAuxiliar(final long amount) throws IOException {
        if (aux.length < 1) {
            return; // nothing to flush
        }

        out.flush();
        aux.flush();

        // Check for underflow condition:
        // underflow means either aux still has data not fully written
        // or out hasn't reached its expected length yet.
        final boolean underflow = aux.offset < aux.length || out.offset < out.length;
        final byte[] buffer = new byte[COPY_BUFFER_SIZE];

        aux.target.seek(0);
        out.target.seek(out.length);

        long remaining = amount;
        while (remaining > 0) {
            int read = (int) Math.min(remaining, buffer.length);
            read = aux.target.read(buffer, 0, read);

            if (read < 1) {
                break; // no more data to read
            }

            out.writeProof(buffer, read);
            remaining -= read;
        }

        // adjust offsets after flushing
        if (underflow) {
            if (out.offset >= out.length) {
                // calculate the aux underflow pointer
                if (aux.offset < amount) {
                    out.offset += aux.offset;
                    aux.offset = 0;
                    out.target.seek(out.offset);
                } else {
                    aux.offset -= amount;
                    out.offset = out.length + amount;
                }
            } else {
                aux.offset = 0;
            }
        } else {
            out.offset += amount;
            aux.offset -= amount;
        }

        out.length += amount;

        // update the max length seen so far
        if (out.length > maxLengthKnown) {
            maxLengthKnown = out.length;
        }

        if (amount < aux.length) {
            // move excess data in aux to the beginning of the aux file (compacting it).
            long readOffset = amount;
            long writeOffset = 0;

            aux.length -= amount;
            long toMove = aux.length;
            while (toMove > 0) {
                int read = (int) Math.min(toMove, buffer.length);
                read = aux.target.read(buffer, 0, read);

                aux.target.seek(writeOffset);
                aux.writeProof(buffer, read);

                writeOffset += read;
                readOffset += read;
                toMove -= read;

                aux.target.seek(readOffset);
            }

            aux.target.setLength(aux.length);
            return;
        }

        if (aux.length > THRESHOLD_AUX_LENGTH) {
            aux.target.setLength(THRESHOLD_AUX_LENGTH);
        }

        aux.reset();
    }

    /**
     * Flushes all buffers and finalizes the file.
     *
     * This should be called after all writes are complete.
     * Ensures that all data (including queues and auxiliary data)
     * are written into the target file and that the physical file size
     * matches the calculated final length.
     *
     * @return the final length of the file
     * @throws IOException if an I/O error occurs
     */
    public long finalizeFile() throws IOException {
        flushAuxiliar(aux.length);

        out.flush();

        // calculate final length: main + aux
        final long length = Math.max(maxLengthKnown, out.length + aux.length);

        // ensure file is set to correct final size
        if (length != out.target.length()) {
            out.target.setLength(length);
        }

        close();

        return length;
    }

    /**
     * Close the file without flushing any buffer.
     */
    @Override
    public void close() {
        if (out != null) {
            out.close();
            out = null;
        }
        if (aux != null) {
            aux.close();
            aux = null;
        }
    }

    @Override
    public void write(final byte b) throws IOException {
        write(new byte[]{b}, 0, 1);
    }

    @Override
    public void write(final byte[] b) throws IOException {
        write(b, 0, b.length);
    }

    @Override
    @SuppressWarnings("checkstyle:FinalParameters")
    public void write(final byte[] b, int off, int len) throws IOException {
        if (len == 0) {
            return;
        }

        long available;
        final long offsetOut = out.getOffset();
        final long offsetAux = aux.getOffset();
        final long end = callback.check();

        if (end == -1) {
            available = Integer.MAX_VALUE;
        } else if (end < offsetOut) {
            throw new IOException("The reported offset is invalid: " + end + "<" + offsetOut);
        } else {
            available = end - offsetOut;
        }

        final boolean usingAux = aux.length > 0 && offsetOut >= out.length;
        final boolean underflow = offsetAux < aux.length || offsetOut < out.length;

        if (usingAux) {
            // before continue calculate the final length of aux
            final long newAuxLength = underflow
                    ? Math.max(aux.length, offsetAux + len) : aux.length + len;
            // write to aux first
            aux.write(b, off, len);

            // flush if aux exceeds threshold
            if (newAuxLength >= THRESHOLD_AUX_LENGTH && newAuxLength <= available) {
                flushAuxiliar(available);
            }
        } else {
            if (underflow) {
                available = out.length - offsetOut;
            }

            final int writeLen = Math.min(len, (int) Math.min(Integer.MAX_VALUE, available));
            out.write(b, off, writeLen);

            len -= writeLen;
            off += writeLen;

            if (len > 0) {
                aux.write(b, off, len);
            }
        }

        // report progress
        if (onProgress != null) {
            final long absoluteOffset = out.getOffset() + aux.getOffset();
            if (absoluteOffset > reportPosition) {
                reportPosition = absoluteOffset + NOTIFY_BYTES_INTERVAL;
                onProgress.report(absoluteOffset);
            }
        }
    }

    @Override
    public void flush() throws IOException {
        aux.flush();
        out.flush();

        final long total = out.length + aux.length;
        if (total > maxLengthKnown) {
            // save the current file length in case the method {@code rewind()} is called
            maxLengthKnown = total;
        }
    }

    @Override
    public long skip(final long amount) throws IOException {
        seek(out.getOffset() + aux.getOffset() + amount);
        return amount;
    }

    @Override
    public void rewind() throws IOException {
        if (onProgress != null) {
            onProgress.report(0); // rollback the whole progress
        }

        seek(0);

        reportPosition = NOTIFY_BYTES_INTERVAL;
    }

    @Override
    public void seek(final long offset) throws IOException {
        final long total = out.length + aux.length;

        if (offset == total) {
            // do not ignore the seek offset if a underflow exists
            final long relativeOffset = out.getOffset() + aux.getOffset();
            if (relativeOffset == total) {
                return;
            }
        }

        // flush everything, avoid any underflow
        flush();

        if (offset < 0 || offset > total) {
            throw new IOException("desired offset is outside of range=0-" + total + " offset="
                    + offset);
        }

        if (offset > out.length) {
            out.seek(out.length);
            aux.seek(offset - out.length);
        } else {
            out.seek(offset);
            aux.seek(0);
        }
    }

    @Override
    public boolean isClosed() {
        return out == null;
    }

    @Override
    public boolean canRewind() {
        return true;
    }

    @Override
    public boolean canWrite() {
        return true;
    }

    @Override
    public boolean canSeek() {
        return true;
    }

    // <editor-fold defaultstate="collapsed" desc="stub read methods">
    @Override
    public boolean canRead() {
        return false;
    }

    @Override
    public int read() {
        throw new UnsupportedOperationException("write-only");
    }

    @Override
    public int read(final byte[] buffer) {
        throw new UnsupportedOperationException("write-only");
    }

    @Override
    public int read(final byte[] buffer, final int offset, final int count) {
        throw new UnsupportedOperationException("write-only");
    }

    @Override
    public long available() {
        throw new UnsupportedOperationException("write-only");
    }
    //</editor-fold>

    public interface OffsetChecker {

        /**
         * Checks the allowed maximum offset for writes.
         *
         * @return Absolute offset beyond which no more data should be written,
         * or -1 if no limit applies (the whole file will be used).
         */
        long check();
    }

    public interface WriteErrorHandle {

        /**
         * Attempts to handle a I/O exception.
         *
         * @param err the cause
         * @return {@code true} to retry and continue, otherwise, {@code false}
         * and throw the exception
         */
        boolean handle(Exception err);
    }

    class BufferedFile {

        final SharpStream target;

        private long offset;
        long length;

        private byte[] queue = new byte[QUEUE_BUFFER_SIZE];
        private int queueSize;

        BufferedFile(final File file) throws FileNotFoundException {
            this.target = new FileStream(file);
        }

        BufferedFile(final SharpStream target) {
            this.target = target;
        }

        long getOffset() {
            return offset + queueSize; // absolute offset in the file
        }

        void close() {
            queue = null;
            target.close();
        }

        @SuppressWarnings("checkstyle:FinalParameters")
        void write(final byte[] b, int off, int len) throws IOException {
            while (len > 0) {
                // if the queue is full, the method available() will flush the queue
                final int read = Math.min(available(), len);

                // enqueue incoming buffer
                System.arraycopy(b, off, queue, queueSize, read);
                queueSize += read;

                len -= read;
                off += read;
            }

            final long total = offset + queueSize;
            if (total > length) {
                length = total; // save length
            }
        }

        void flush() throws IOException {
            writeProof(queue, queueSize);
            offset += queueSize;
            queueSize = 0;
        }

        protected void rewind() throws IOException {
            offset = 0;
            target.seek(0);
        }

        int available() throws IOException {
            if (queueSize >= queue.length) {
                flush();
                return queue.length;
            }

            return queue.length - queueSize;
        }

        void reset() throws IOException {
            offset = 0;
            length = 0;
            target.seek(0);
        }

        void seek(final long absoluteOffset) throws IOException {
            if (absoluteOffset == offset) {
                return; // nothing to do
            }
            offset = absoluteOffset;
            target.seek(absoluteOffset);
        }

        @SuppressWarnings("checkstyle:HiddenField")
        void writeProof(final byte[] buffer, final int length) throws IOException {
            if (onWriteError == null) {
                target.write(buffer, 0, length);
                return;
            }

            while (true) {
                try {
                    target.write(buffer, 0, length);
                    return;
                } catch (Exception e) {
                    if (!onWriteError.handle(e)) {
                        throw e; // give up
                    }
                }
            }
        }

        @NonNull
        @Override
        public String toString() {
            String absLength;

            try {
                absLength = Long.toString(target.length());
            } catch (IOException e) {
                absLength = "[" + e.getLocalizedMessage() + "]";
            }

            return String.format(
                    "offset=%s  length=%s  queue=%s  absLength=%s",
                    offset, length, queueSize, absLength
            );
        }
    }
}
