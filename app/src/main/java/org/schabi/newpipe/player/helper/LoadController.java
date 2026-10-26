package org.schabi.newpipe.player.helper;

import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.analytics.PlayerId;

@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public class LoadController extends DefaultLoadControl {

    public static final String TAG = "LoadController";
    private boolean preloadingEnabled = true;

    public LoadController() {
        // Keep the standalone engine's buffering thresholds for both network and local media.
        super(new androidx.media3.exoplayer.upstream.DefaultAllocator(true,
                androidx.media3.common.C.DEFAULT_BUFFER_SEGMENT_SIZE),
                50_000, 50_000, 50_000, 50_000, 2500, 2500, 5000, 5000,
                androidx.media3.common.C.LENGTH_UNSET, false, false, 0, false,
                java.util.Map.of());
    }

    @Override
    public void onPrepared(final PlayerId playerId) {
        preloadingEnabled = true;
        super.onPrepared(playerId);
    }

    @Override
    public void onStopped(final PlayerId playerId) {
        preloadingEnabled = true;
        super.onStopped(playerId);
    }

    @Override
    public void onReleased(final PlayerId playerId) {
        preloadingEnabled = true;
        super.onReleased(playerId);
    }

    @Override
    public boolean shouldContinueLoading(final Parameters parameters) {
        return preloadingEnabled && super.shouldContinueLoading(parameters);
    }

    public void disablePreloadingOfCurrentTrack() {
        preloadingEnabled = false;
    }
}
