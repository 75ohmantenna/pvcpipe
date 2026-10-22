package org.schabi.newpipe.player.error;

import static com.google.android.exoplayer2.PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW;
import static com.google.android.exoplayer2.PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS;
import static com.google.android.exoplayer2.PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED;
import static com.google.android.exoplayer2.PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND;
import static com.google.android.exoplayer2.PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE;
import static com.google.android.exoplayer2.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED;
import static com.google.android.exoplayer2.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT;
import static com.google.android.exoplayer2.PlaybackException.ERROR_CODE_IO_NO_PERMISSION;
import static com.google.android.exoplayer2.PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE;
import static com.google.android.exoplayer2.PlaybackException.ERROR_CODE_IO_UNSPECIFIED;
import static com.google.android.exoplayer2.PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED;
import static com.google.android.exoplayer2.PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED;
import static com.google.android.exoplayer2.PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED;
import static com.google.android.exoplayer2.PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED;
import static com.google.android.exoplayer2.PlaybackException.ERROR_CODE_TIMEOUT;
import static com.google.android.exoplayer2.PlaybackException.ERROR_CODE_UNSPECIFIED;

import androidx.annotation.NonNull;

import com.google.android.exoplayer2.PlaybackException;

/**
 * Sorts the errors {@link com.google.android.exoplayer2.ExoPlayer ExoPlayer} reports by how the
 * player recovers from them.
 * <p>
 * It only reads the error code, so it can be tested without a player.
 */
public final class PlaybackErrors {

    /** What the player does about an error. */
    public enum Recovery {
        /**
         * Playback of a livestream lagged too far behind the playable window
         * ({@link PlaybackException#ERROR_CODE_BEHIND_LIVE_WINDOW BEHIND_LIVE_WINDOW}): seek to
         * the latest timestamp and restart. This is the only error the user is not told about.
         */
        RESTART_AT_LIVE_EDGE,
        /**
         * The extractor accepted the stream source but the player cannot read it, from
         * {@link PlaybackException#ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE BAD_IO} to
         * {@link PlaybackException#ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED UNSUPPORTED_FORMATS}:
         * signal an error on the play queue and move on.
         */
        SKIP_STREAM,
        /**
         * Timeouts, network failures and unspecified errors, such as
         * {@link PlaybackException#ERROR_CODE_TIMEOUT PLAYER_TIMEOUT},
         * {@link PlaybackException#ERROR_CODE_IO_UNSPECIFIED MEDIA_SOURCE_RESOLVER_TIMEOUT} and
         * {@link PlaybackException#ERROR_CODE_IO_NETWORK_CONNECTION_FAILED NO_NETWORK}: record
         * where playback was and reload the play queue, which keeps the player in its current
         * state until it can play again.
         */
        RELOAD,
        /**
         * Everything else. These errors are either unrelated to what this app plays, such as
         * DRM, or cannot be recovered from, such as
         * {@link PlaybackException#ERROR_CODE_DECODER_INIT_FAILED DECODER_ERROR}: stop playback.
         */
        SHUT_DOWN;

        /**
         * Indicates whether this recovery handles the error without a user notification.
         *
         * @return whether the player handles the error by itself, so that the user does not
         * need an error notification
         */
        public boolean isCatchable() {
            return this == RESTART_AT_LIVE_EDGE;
        }
    }

    private PlaybackErrors() {
    }

    /**
     * Chooses a recovery strategy for the reported playback error.
     *
     * @param errorCode the {@link PlaybackException#errorCode} of the reported error
     * @return what the player does about it
     */
    @SuppressWarnings("SwitchIntDef")
    @NonNull
    public static Recovery recoveryFor(@PlaybackException.ErrorCode final int errorCode) {
        switch (errorCode) {
            case ERROR_CODE_BEHIND_LIVE_WINDOW:
                return Recovery.RESTART_AT_LIVE_EDGE;
            case ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE:
            case ERROR_CODE_IO_BAD_HTTP_STATUS:
            case ERROR_CODE_IO_FILE_NOT_FOUND:
            case ERROR_CODE_IO_NO_PERMISSION:
            case ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED:
            case ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE:
            case ERROR_CODE_PARSING_CONTAINER_MALFORMED:
            case ERROR_CODE_PARSING_MANIFEST_MALFORMED:
            case ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED:
            case ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED:
                return Recovery.SKIP_STREAM;
            case ERROR_CODE_TIMEOUT:
            case ERROR_CODE_IO_UNSPECIFIED:
            case ERROR_CODE_IO_NETWORK_CONNECTION_FAILED:
            case ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT:
            case ERROR_CODE_UNSPECIFIED:
                return Recovery.RELOAD;
            default:
                // API, remote and renderer errors belong here:
                return Recovery.SHUT_DOWN;
        }
    }
}
