package org.schabi.newpipe.player.error;

import static org.junit.Assert.assertEquals;
import static org.schabi.newpipe.player.error.PlaybackErrors.Recovery.RELOAD;
import static org.schabi.newpipe.player.error.PlaybackErrors.Recovery.RESTART_AT_LIVE_EDGE;
import static org.schabi.newpipe.player.error.PlaybackErrors.Recovery.SHUT_DOWN;
import static org.schabi.newpipe.player.error.PlaybackErrors.Recovery.SKIP_STREAM;

import androidx.media3.common.PlaybackException;

import org.junit.Test;

import java.util.List;

@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public class PlaybackErrorsTest {

    @Test
    public void fallingBehindALiveWindowRestartsAtTheLiveEdge() {
        assertRecovery(RESTART_AT_LIVE_EDGE, PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW);
    }

    @Test
    public void unreadableSourcesAreSkipped() {
        assertRecovery(SKIP_STREAM,
                PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE,
                PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
                PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
                PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
                PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED,
                PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
                PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
                PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
                PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
                PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED);
    }

    @Test
    public void timeoutsNetworkFailuresAndUnspecifiedErrorsReload() {
        assertRecovery(RELOAD,
                PlaybackException.ERROR_CODE_TIMEOUT,
                PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
                PlaybackException.ERROR_CODE_UNSPECIFIED);
    }

    @Test
    public void everyOtherErrorShutsThePlayerDown() {
        assertRecovery(SHUT_DOWN,
                PlaybackException.ERROR_CODE_REMOTE_ERROR,
                PlaybackException.ERROR_CODE_FAILED_RUNTIME_CHECK,
                PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
                PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
                PlaybackException.ERROR_CODE_DECODING_FAILED,
                PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
                PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
                PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
                PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED,
                PlaybackException.ERROR_CODE_DRM_UNSPECIFIED,
                PlaybackException.ERROR_CODE_DRM_LICENSE_ACQUISITION_FAILED,
                PlaybackException.CUSTOM_ERROR_CODE_BASE,
                -1);
    }

    @Test
    public void onlyTheLiveEdgeRestartIsHandledWithoutTellingTheUser() {
        assertEquals(List.of(RESTART_AT_LIVE_EDGE),
                List.of(PlaybackErrors.Recovery.values()).stream()
                        .filter(PlaybackErrors.Recovery::isCatchable).toList());
    }

    private static void assertRecovery(final PlaybackErrors.Recovery expected,
                                       final int... errorCodes) {
        for (final int errorCode : errorCodes) {
            assertEquals("error code " + errorCode, expected,
                    PlaybackErrors.recoveryFor(errorCode));
        }
    }
}
