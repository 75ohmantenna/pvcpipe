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
 * Read/write adapter for a Storage Access Framework descriptor opened in {@code rw} mode.
 * <p>
 * Seeking and truncation use the output stream's {@link FileChannel}; actual support depends
 * on the document provider. The capability methods report the adapter's operations, not a
 * successful probe of the provider. {@code setLength} truncates and does not extend a file.
 * Reads and writes share the descriptor position. Concurrent access requires caller ordering.
 * Closing attempts every wrapper and the owning descriptor, logging I/O failures. The closed
 * flag records a close attempt, not proof that every resource released successfully.
 */
public class FileStreamSAF extends SharpStream {

    private final FileInputStream in;
    private final FileOutputStream out;
    private final FileChannel channel;
    private final ParcelFileDescriptor file;

    private boolean disposed;

    public FileStreamSAF(@NonNull final ContentResolver contentResolver, final Uri fileUri)
            throws IOException {
        // The provider must supply a descriptor usable for the requested read/write operations.
        file = contentResolver.openFileDescriptor(fileUri, "rw");

        if (file == null) {
            throw new IOException("Cannot get the ParcelFileDescriptor for " + fileUri);
        }

        // These wrappers borrow the descriptor; the ParcelFileDescriptor owns it.
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
        disposed = true;
        // Reverse declaration order keeps the owning descriptor last, even after failure.
        try (file; out; in) {
            channel.close();
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
