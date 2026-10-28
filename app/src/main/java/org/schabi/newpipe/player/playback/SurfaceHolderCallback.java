package org.schabi.newpipe.player.playback;

import android.content.Context;
import android.view.SurfaceHolder;

import androidx.media3.common.Player;
import androidx.media3.exoplayer.video.PlaceholderSurface;

/**
 * Keeps a video output attached while the view's surface is destroyed and recreated.
 * <p>
 * Rotation or backgrounding can destroy the view's surface. A lazily created Media3
 * {@link PlaceholderSurface} replaces it until {@link #surfaceCreated(SurfaceHolder)} attaches
 * the new surface. This is a workaround for surface-lifecycle failures, not a guarantee that
 * every codec or device can switch surfaces without interruption.
 * <p>
 * This implementation is based on:
 * 'ExoPlayer stuck in buffering after re-adding the surface view a few time #2703'
 * <p>
 * -> exoplayer fix suggestion link
 * https://github.com/google/ExoPlayer/issues/2703#issuecomment-300599981
 */
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public final class SurfaceHolderCallback implements SurfaceHolder.Callback {

    private final Context context;
    private final Player player;
    private PlaceholderSurface placeholderSurface;

    public SurfaceHolderCallback(final Context context, final Player player) {
        this.context = context;
        this.player = player;
    }

    @Override
    public void surfaceCreated(final SurfaceHolder holder) {
        player.setVideoSurface(holder.getSurface());
    }

    @Override
    public void surfaceChanged(final SurfaceHolder holder,
                               final int format,
                               final int width,
                               final int height) {
    }

    @Override
    public void surfaceDestroyed(final SurfaceHolder holder) {
        if (placeholderSurface == null) {
            placeholderSurface = PlaceholderSurface.newInstanceV17(context, false);
        }
        player.setVideoSurface(placeholderSurface);
    }

    public void release() {
        if (placeholderSurface != null) {
            placeholderSurface.release();
            placeholderSurface = null;
        }
    }
}
