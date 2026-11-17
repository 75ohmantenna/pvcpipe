package org.schabi.newpipe.player.error;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;
import static org.schabi.newpipe.player.error.PlaybackErrors.Recovery.RELOAD;
import static org.schabi.newpipe.player.error.PlaybackErrors.Recovery.RESTART_AT_LIVE_EDGE;
import static org.schabi.newpipe.player.error.PlaybackErrors.Recovery.SHUT_DOWN;
import static org.schabi.newpipe.player.error.PlaybackErrors.Recovery.SKIP_STREAM;

import android.net.Uri;
import android.os.SystemClock;

import androidx.media3.common.PlaybackException;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.HttpDataSource;

import org.junit.Test;

import java.util.List;
import java.util.Map;

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

    @Test
    public void aRefusalMustBelongToAnHttpsYoutubeMediaRequest() {
        assertTrue(PlaybackErrors.isYoutubeMediaRefusal(mediaError(403, "https",
                "r1.example.googlevideo.com", "/videoplayback")));
        assertTrue(PlaybackErrors.isYoutubeMediaRefusal(mediaError(410, "https",
                "MANIFEST.GOOGLEVIDEO.COM", "/api/manifest/hls_playlist")));
        assertFalse(PlaybackErrors.isYoutubeMediaRefusal(mediaError(404, "https",
                "googlevideo.com", "/videoplayback")));
        assertFalse(PlaybackErrors.isYoutubeMediaRefusal(mediaError(403, "http",
                "googlevideo.com", "/videoplayback")));
        assertFalse(PlaybackErrors.isYoutubeMediaRefusal(mediaError(403, "https",
                "googlevideo.com.example.test", "/videoplayback")));
        assertFalse(PlaybackErrors.isYoutubeMediaRefusal(mediaError(403, "https",
                "fakegooglevideo.com", "/videoplayback")));
        assertFalse(PlaybackErrors.isYoutubeMediaRefusal(mediaError(403, "https",
                "googlevideo.com", "/videoplayback-lookalike")));
        assertFalse(PlaybackErrors.isYoutubeMediaRefusal(mediaError(403, "https",
                "www.youtube.com", "/youtubei/v1/player")));
    }

    private static PlaybackException mediaError(final int response, final String scheme,
                                                 final String host, final String path) {
        final Uri uri = mock(Uri.class);
        when(uri.getScheme()).thenReturn(scheme);
        when(uri.getHost()).thenReturn(host);
        when(uri.getEncodedPath()).thenReturn(path);
        final DataSpec dataSpec = new DataSpec.Builder().setUri(uri).build();
        final var refusal = new HttpDataSource.InvalidResponseCodeException(
                response, null, null, Map.of(), dataSpec, new byte[0]);
        try (var clock = mockStatic(SystemClock.class)) {
            return new PlaybackException("fixture", new IllegalStateException(refusal),
                    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS);
        }
    }

    private static void assertRecovery(final PlaybackErrors.Recovery expected,
                                       final int... errorCodes) {
        for (final int errorCode : errorCodes) {
            assertEquals("error code " + errorCode, expected,
                    PlaybackErrors.recoveryFor(errorCode));
        }
    }
}
