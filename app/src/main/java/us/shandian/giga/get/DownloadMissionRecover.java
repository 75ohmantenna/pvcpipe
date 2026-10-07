package us.shandian.giga.get;

import android.util.Log;

import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.StreamingService;
import org.schabi.newpipe.extractor.exceptions.ExtractionException;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.DeliveryMethod;
import org.schabi.newpipe.extractor.stream.StreamExtractor;
import org.schabi.newpipe.extractor.stream.SubtitlesStream;
import org.schabi.newpipe.extractor.stream.VideoStream;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.HttpURLConnection;
import java.nio.channels.ClosedByInterruptException;
import java.util.List;

import us.shandian.giga.get.DownloadMission.HttpError;

import static us.shandian.giga.get.DownloadMission.ERROR_RESOURCE_GONE;

public class DownloadMissionRecover extends Thread {
    private static final String TAG = "DownloadMissionRecover";
    static final int mID = -3;

    private final DownloadMission mMission;
    private final boolean mNotInitialized;

    private final int mErrCode;

    private HttpURLConnection mConn;
    private MissionRecoveryInfo mRecovery;
    private StreamExtractor mExtractor;

    DownloadMissionRecover(DownloadMission mission, int errCode) {
        mMission = mission;
        mNotInitialized = mission.blocks == null && mission.current == 0;
        mErrCode = errCode;
    }

    @Override
    public void run() {
        if (mMission.source == null) {
            mMission.notifyError(mErrCode, null);
            return;
        }

        Exception err = null;
        int attempt = 0;

        while (attempt++ < mMission.maxRetry) {
            try {
                tryRecover();
                return;
            } catch (InterruptedIOException | ClosedByInterruptException e) {
                return;
            } catch (ValidatedRange.InvalidRangeException e) {
                mMission.notifyError(e);
                return;
            } catch (Exception e) {
                if (!mMission.running || super.isInterrupted()) return;
                err = e;
            }
        }

        // give up
        mMission.notifyError(mErrCode, err);
    }

    private void tryRecover() throws ExtractionException, IOException, HttpError {
        if (mExtractor == null) {
            try {
                StreamingService svr = NewPipe.getServiceByUrl(mMission.source);
                mExtractor = svr.getStreamExtractor(mMission.source);
                mExtractor.fetchPage();
            } catch (ExtractionException e) {
                mExtractor = null;
                throw e;
            }
        }

        // maybe the following check is redundant
        if (!mMission.running || super.isInterrupted()) return;

        if (!mNotInitialized) {
            // set the current download url to null in case if the recovery
            // process is canceled. Next time start() method is called the
            // recovery will be executed, saving time
            mMission.urls[mMission.current] = null;

            mRecovery = mMission.recoveryInfo[mMission.current];
            resolveStream();
            return;
        }

        Log.w(TAG, "mission is not fully initialized, this will take a while");

        try {
            for (; mMission.current < mMission.urls.length; mMission.current++) {
                mRecovery = mMission.recoveryInfo[mMission.current];

                if (test()) continue;
                if (!mMission.running) return;

                resolveStream();
                if (!mMission.running) return;

                // before continue, check if the current stream was resolved
                if (mMission.urls[mMission.current] == null) {
                    break;
                }
            }
        } finally {
            mMission.current = 0;
        }

        mMission.writeThisToFile();

        if (!mMission.running || super.isInterrupted()) return;

        mMission.recoveryFinished();
    }

    private void resolveStream() throws IOException, ExtractionException, HttpError {
        // FIXME: this getErrorMessage() always returns "video is unavailable"
        /*if (mExtractor.getErrorMessage() != null) {
            mMission.notifyError(mErrCode, new ExtractionException(mExtractor.getErrorMessage()));
            return;
        }*/

        String url = null;

        switch (mRecovery.getKind()) {
            case 'a':
                for (final AudioStream audio : mExtractor.getAudioStreams()) {
                    if (audio.getAverageBitrate() == mRecovery.getDesiredBitrate()
                            && audio.getFormat() == mRecovery.getFormat()
                            && audio.getDeliveryMethod() == DeliveryMethod.PROGRESSIVE_HTTP) {
                        url = audio.getContent();
                        break;
                    }
                }
                break;
            case 'v':
                final List<VideoStream> videoStreams;
                if (mRecovery.isDesired2())
                    videoStreams = mExtractor.getVideoOnlyStreams();
                else
                    videoStreams = mExtractor.getVideoStreams();
                for (final VideoStream video : videoStreams) {
                    if (video.getResolution().equals(mRecovery.getDesired())
                            && video.getFormat() == mRecovery.getFormat()
                            && video.getDeliveryMethod() == DeliveryMethod.PROGRESSIVE_HTTP) {
                        url = video.getContent();
                        break;
                    }
                }
                break;
            case 's':
                for (final SubtitlesStream subtitles : mExtractor.getSubtitles(mRecovery
                        .getFormat())) {
                    String tag = subtitles.getLanguageTag();
                    if (tag.equals(mRecovery.getDesired())
                            && subtitles.isAutoGenerated() == mRecovery.isDesired2()
                            && subtitles.getDeliveryMethod() == DeliveryMethod.PROGRESSIVE_HTTP) {
                        url = subtitles.getContent();
                        break;
                    }
                }
                break;
            default:
                throw new RuntimeException("Unknown stream type");
        }

        resolve(url);
    }

    private void resolve(String url) throws IOException, HttpError {
        final ResourceIdentity identity = mMission.resourceIdentity;
        // An ETag is scoped to one resource URI; equal tags on different signed URLs
        // do not prove equal bytes. Restart only after the previous workers have stopped.
        if (identity != null && !identity.requestUrl.equals(url)) {
            recover(url, true);
            return;
        }
        if (mMission.unknownLength || identity == null) {
            recover(url, false);
            return;
        }

        ///////////////////////////////////////////////////////////////////////
        ////// Validate the http resource doing a range request
        /////////////////////
        try {
            // Range is defined for GET, not HEAD (RFC 9110 section 14.2).
            mConn = mMission.openConnection(url, false,
                    Math.max(0, mMission.length - 10), mMission.length - 1);
            final String sentIfRange = mConn.getRequestProperty("If-Range");
            mMission.establishConnection(mID, mConn);

            int code = mConn.getResponseCode();

            switch (code) {
                case 200:
                case 413:
                    // stale
                    recover(url, true);
                    return;
                case 206:
                    identity.verify(mConn, url, sentIfRange);
                    final ValidatedRange range = ValidatedRange.from(mConn,
                            Math.max(0, mMission.length - 10), mMission.length - 1, -1);
                    // If-Range may return a new representation. Only the existing mission's
                    // initializer may reset its file, after the previous workers have stopped.
                    recover(url, range.total < 0 || range.total != mMission.length);
                    return;
            }

            throw new HttpError(code);
        } finally {
            disconnect();
        }
    }

    private void recover(String url, boolean stale) {
        Log.i(TAG,
                String.format("recover()  name=%s  isStale=%s  url=%s", mMission.storage.getName(), stale, url)
        );

        mMission.urls[mMission.current] = url;

        if (url == null) {
            mMission.urls = new String[0];
            mMission.notifyError(ERROR_RESOURCE_GONE, null);
            return;
        }

        if (mNotInitialized) return;

        if (stale) {
            mMission.resetState(false, false, DownloadMission.ERROR_NOTHING);
        }

        mMission.writeThisToFile();

        if (!mMission.running || super.isInterrupted()) return;

        mMission.recoveryFinished();
    }


    private boolean test() {
        if (mMission.urls[mMission.current] == null) return false;

        try {
            mConn = mMission.openConnection(mMission.urls[mMission.current], true, -1, -1);
            mMission.establishConnection(mID, mConn);

            if (mConn.getResponseCode() == 200) return true;
        } catch (Exception e) {
            // nothing to do
        } finally {
            disconnect();
        }

        return false;
    }

    private void disconnect() {
        // A GET probe only needs the response headers. Opening its body just to close
        // it can drain an ignored-range 200, which may be the entire media resource.
        final HttpURLConnection conn = mConn;
        mConn = null;
        if (conn != null) conn.disconnect();
    }

    @Override
    public void interrupt() {
        super.interrupt();
        if (mConn != null) disconnect();
    }
}
