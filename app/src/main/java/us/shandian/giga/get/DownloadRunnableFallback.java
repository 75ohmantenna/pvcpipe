package us.shandian.giga.get;

import android.util.Log;

import androidx.annotation.NonNull;

import org.schabi.newpipe.streams.io.SharpStream;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.nio.channels.ClosedByInterruptException;

import us.shandian.giga.get.DownloadMission.HttpError;
import us.shandian.giga.util.Utility;

import static org.schabi.newpipe.BuildConfig.DEBUG;
import static us.shandian.giga.get.DownloadMission.ERROR_HTTP_FORBIDDEN;

/**
 * Single-threaded fallback mode
 */
public class DownloadRunnableFallback extends Thread {
    private static final String TAG = "DLRunnableFallback";

    private final DownloadMission mMission;

    private int mRetryCount = 0;
    private InputStream mIs;
    private SharpStream mF;
    private HttpURLConnection mConn;

    DownloadRunnableFallback(@NonNull DownloadMission mission) {
        mMission = mission;
    }

    private void dispose() {
        try {
            if (mIs != null) mIs.close();
        } catch (IOException ignored) {
            // Closing the connection below also stops an in-flight request.
        } finally {
            mIs = null;
            if (mConn != null) mConn.disconnect();
            mConn = null;
            if (mF != null) mF.close();
            mF = null;
        }
    }

    @Override
    public void run() {
        long start = mMission.fallbackResumeOffset;

        if (DEBUG && !mMission.unknownLength && start > 0) {
            Log.i(TAG, "Resuming a single-thread download at " + start);
        }

        while (mMission.running) {
            try {
                final long rangeStart = mMission.unknownLength || start < 1 ? -1 : start;
                mConn = mMission.openConnection(false, rangeStart, -1);
                if (mRetryCount == 0 && rangeStart == -1) {
                    // workaround: bypass android connection pool
                    mConn.setRequestProperty("Range", "bytes=0-");
                }
                mMission.applyIfRange(mConn, mMission.urls[mMission.current]);
                final String sentIfRange = mConn.getRequestProperty("If-Range");
                mMission.establishConnection(1, mConn);
                final int status = mConn.getResponseCode();

                if (status == 416) {
                    if (start > 0) {
                        mMission.notifyProgress(-start);
                        start = 0;
                        mMission.fallbackResumeOffset = 0;
                        dispose();
                        continue;
                    }
                    throw new HttpError(416);
                }

                final ValidatedRange range;
                if (status == 206) {
                    range = ValidatedRange.from(mConn, rangeStart < 0 ? 0 : rangeStart,
                            -1, mMission.unknownLength ? -1 : mMission.length);
                    mMission.validatePartialRepresentation(mConn, sentIfRange);
                    if (range.total < 0) {
                        throw new ValidatedRange.InvalidRangeException(
                                "Partial response has no resource length");
                    }
                    if (mMission.unknownLength || mMission.length < 1) {
                        mMission.length = range.total;
                    }
                    mMission.unknownLength = false;
                } else if (status == 200) {
                    range = null;
                    final long responseLength = Utility.getContentLength(mConn);
                    if (responseLength == 0) {
                        throw new IOException("Empty full response");
                    }
                    if (!mMission.unknownLength && mMission.length > 0
                            && responseLength >= 0 && responseLength != mMission.length) {
                        throw new IOException("Full response length changed while downloading");
                    }
                    if (responseLength >= 0) {
                        mMission.length = responseLength;
                        mMission.unknownLength = false;
                    } else if (mMission.unknownLength || mMission.length < 1) {
                        // An unknown-length mission counts downloaded bytes in length.
                        // Retried full responses must start counting again from zero.
                        mMission.length = 0;
                        mMission.unknownLength = true;
                    }
                    // A full response safely replaces a partial one only in this single-worker
                    // fallback mode. Drop any old tail before accepting its bytes.
                } else {
                    throw new HttpError(status);
                }

                final boolean restart = status == 200 || rangeStart < 0;
                if (restart) {
                    mMission.done = mMission.offsets[mMission.current] - mMission.offsets[0];
                    start = 0;
                }
                if (status == 200) {
                    mMission.discardCurrentIdentity();
                }
                mF = mMission.storage.getStream();
                if (restart) {
                    mF.setLength(mMission.offsets[mMission.current]);
                }
                mF.seek(mMission.offsets[mMission.current] + start);
                if (status == 200) {
                    mMission.beginFullRepresentation(mConn);
                }
                mIs = mConn.getInputStream();

                final byte[] buf = new byte[DownloadMission.BUFFER_SIZE];
                final long endExclusive = range == null ? mMission.length : range.end + 1;
                while (mMission.running) {
                    final long remaining = endExclusive > 0 ? endExclusive - start : buf.length;
                    if (endExclusive > 0 && remaining == 0) break;
                    final int limit = (int) Math.min(buf.length, remaining);
                    final int len = mIs.read(buf, 0, limit);
                    if (len < 0) {
                        if (range != null || (mMission.length > 0 && start < mMission.length)) {
                            throw new EOFException("Truncated response at byte " + start);
                        }
                        break;
                    }
                    if (len == 0) throw new IOException("No progress reading media");
                    mF.write(buf, 0, len);
                    start += len;
                    mMission.notifyProgress(len);
                }
                if (mMission.running && range == null && endExclusive > 0
                        && mIs.read() != -1) {
                    throw new IOException("Full response exceeds expected length");
                }
                dispose();
                mMission.fallbackResumeOffset = start;
                if (!mMission.running) return;
                if (range != null && start < range.total) {
                    // Servers may return a valid range shorter than requested.
                    continue;
                }
                mMission.notifyFinished();
                return;
            } catch (Exception e) {
                dispose();
                mMission.fallbackResumeOffset = start;
                if (!mMission.running || e instanceof ClosedByInterruptException) return;
                if (e instanceof HttpError && ((HttpError) e).statusCode == ERROR_HTTP_FORBIDDEN) {
                    mMission.doRecover(ERROR_HTTP_FORBIDDEN);
                    return;
                }
                if (e instanceof ValidatedRange.InvalidRangeException
                        || mRetryCount++ >= mMission.maxRetry) {
                    mMission.notifyError(e);
                    return;
                }
                if (DEBUG) Log.e(TAG, "got exception, retrying...", e);
            }
        }
    }

    @Override
    public void interrupt() {
        super.interrupt();

        if (mConn != null) {
            try {
                mConn.disconnect();
            } catch (Exception e) {
                // nothing to do
            }

        }
    }
}
