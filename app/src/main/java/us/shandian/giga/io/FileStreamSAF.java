package us.shandian.giga.io;

import android.content.ContentResolver;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import androidx.annotation.NonNull;

import org.schabi.newpipe.streams.io.SharpStream;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;

/**
 * FileStreamSAF.
 * <p>
 * Robust implementation of a read/write interface for files
 * opened via the Storage Access Framework (SAF).
 * <p>
 * Supports:
 * - Random access (seek)
 * - Truncate (setLength)
 * - Reading & writing
 * <p>
 * Notes:
 * - File must exist and be accessible by the app.
 * - Not guaranteed to work with virtual cloud files (e.g., Google Drive, Dropbox)
 *   due to SAF limitations, as some providers may not support random access or
 *   truncate operations. Test thoroughly with specific cloud providers.
 * - Not thread-safe. Use ReentrantReadWriteLock for synchronization
 *   if multi-threaded access is required.
 */
public class FileStreamSAF extends SharpStream {

    private final FileInputStream in;
    private final FileOutputStream out;
    private final FileChannel channel;
    private final ParcelFileDescriptor file;

    private boolean disposed;

    public FileStreamSAF(@NonNull final ContentResolver contentResolver, final Uri fileUri)
            throws IOException {
        // Open file in read-write mode (SAF ensures positioning)
        file = contentResolver.openFileDescriptor(fileUri, "rw");

        if (file == null) {
            throw new IOException("Cannot get the ParcelFileDescriptor for " + fileUri);
        }

        // Separate streams for reading and writing (bidirectional)
        in = new FileInputStream(file.getFileDescriptor());
        out = new FileOutputStream(file.getFileDescriptor());
        channel = out.getChannel(); // Channel for seek & truncate
    }

    @Override
    public int read() throws IOException {
        return in.read();
    }

    @Override
    public int read(final byte[] buffer) throws IOException {
        return in.read(buffer);
    }

    @Override
    public int read(final byte[] buffer, final int offset, final int count) throws IOException {
        return in.read(buffer, offset, count);
    }

    @Override
    public long skip(final long amount) throws IOException {
        final long currentPos = channel.position();
        final long fileSize = channel.size();
        final long newPos = currentPos + amount;

        // Check if backward skip is supported
        if (amount < 0 && newPos < 0) {
            try {
                channel.position(0);
                return -currentPos; // Skipped backward to start of file
            } catch (IOException e) {
                throw new IOException("Backward skip not supported at current position", e);
            }
        }

        // For forward or backward skips within bounds or beyond EOF
        try {
            channel.position(newPos);
            return amount; // Return requested amount, even if past EOF
        } catch (IOException e) {
            // If positioning fails (e.g., due to SAF limitations), try partial skip
            if (amount > 0 && newPos > fileSize) {
                channel.position(fileSize);
                return fileSize - currentPos; // Skipped to EOF
            }
            throw new IOException("Skip operation failed", e);
        }
    }

    @Override
    public long available() {
        try {
            return channel.size() - channel.position();
        } catch (IOException e) {
            Log.e("FileStreamSAF", "Error calculating available bytes", e);
            return 0;
        }
    }

    @Override
    public void rewind() throws IOException {
        seek(0);
    }

    @Override
    public void close() {
        try {
            disposed = true;

            channel.close();
            in.close();
            out.close();
            file.close();
        } catch (IOException e) {
            Log.e("FileStreamSAF", "close() error", e);
        }
    }

    @Override
    public boolean isClosed() {
        return disposed;
    }

    @Override
    public boolean canRewind() {
        return true;
    }

    @Override
    public boolean canRead() {
        return true;
    }

    @Override
    public boolean canWrite() {
        return true;
    }

    @Override
    public boolean canSetLength() {
        return true;
    }

    @Override
    public boolean canSeek() {
        return true;
    }

    @Override
    public void write(final byte value) throws IOException {
        out.write(value);
    }

    @Override
    public void write(final byte[] buffer) throws IOException {
        out.write(buffer);
    }

    @Override
    public void write(final byte[] buffer, final int offset, final int count) throws IOException {
        out.write(buffer, offset, count);
    }

    @Override
    public void setLength(final long length) throws IOException {
        channel.truncate(length);
    }

    @Override
    public void seek(final long offset) throws IOException {
        channel.position(offset);
    }

    @Override
    public long length() throws IOException {
        return channel.size();
    }
}
