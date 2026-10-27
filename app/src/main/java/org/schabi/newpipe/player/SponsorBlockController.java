package org.schabi.newpipe.player;

import android.util.Log;
import android.widget.Toast;

import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.SeekParameters;

import org.schabi.newpipe.player.playqueue.PlayQueueItem;
import org.schabi.newpipe.util.SponsorBlockMode;
import org.schabi.newpipe.util.SponsorBlockPlaybackController;
import org.schabi.newpipe.util.SponsorBlockSegment;
import org.schabi.newpipe.util.SponsorBlockSettings;

/** Keeps skip policy and repeated-target state independent of playback UI type. */
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
final class SponsorBlockController {
    private SponsorBlockMode mode = SponsorBlockMode.DISABLED;
    private final SponsorBlockPlaybackController playback =
            new SponsorBlockPlaybackController();
    private PlayQueueItem lastItem;

    SponsorBlockMode getMode() {
        return mode;
    }

    void setMode(final SponsorBlockMode newMode) {
        if (mode != newMode) {
            playback.reset();
        }
        mode = newMode;
    }

    void resetPlayback() {
        playback.reset();
        lastItem = null;
    }

    void skip(final Player player, final int progress, final boolean rewind) {
        if (mode != SponsorBlockMode.ENABLED) {
            return;
        }
        final PlayQueueItem item = player.getCurrentItem();
        if (item != lastItem) {
            playback.reset();
            lastItem = item;
        }
        final SponsorBlockSegment[] segments =
                item == null ? null : item.getSponsorBlockSegments();
        final SponsorBlockSegment segment = SponsorBlockSegment.at(segments, progress);
        final long target = playback.target(segments, progress, rewind);
        if (segment == null || target < 0) {
            return;
        }

        final ExoPlayer exoPlayer = player.getExoPlayer();
        final SeekParameters previous = exoPlayer.getSeekParameters();
        exoPlayer.setSeekParameters(SeekParameters.EXACT);
        player.seekTo(target);
        exoPlayer.setSeekParameters(previous);

        final var context = player.getContext();
        if (new SponsorBlockSettings(context, player.getPrefs()).notificationsEnabled()) {
            Toast.makeText(context, segment.category().skipToast(context),
                    Toast.LENGTH_SHORT).show();
        }
        if (Player.DEBUG) {
            Log.d("SPONSOR_BLOCK", "Skipped segment: currentProgress = ["
                    + progress + "], skipped to = [" + target + "]");
        }
    }
}
