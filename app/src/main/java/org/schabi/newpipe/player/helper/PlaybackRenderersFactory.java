package org.schabi.newpipe.player.helper;

import android.content.Context;
import android.os.Looper;

import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.Renderer;
import androidx.media3.exoplayer.text.TextOutput;
import androidx.media3.exoplayer.text.TextRenderer;

import java.util.ArrayList;

/** Keeps optional single-sample sidecars lazy and non-fatal with Media3's text decoder. */
@androidx.annotation.OptIn(markerClass = {androidx.media3.common.util.UnstableApi.class,
        androidx.media3.common.util.ExperimentalApi.class})
public class PlaybackRenderersFactory extends DefaultRenderersFactory {
    public PlaybackRenderersFactory(final Context context) {
        super(context);
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void buildTextRenderers(final Context context, final TextOutput output,
                                      final Looper outputLooper,
                                      @ExtensionRendererMode final int extensionRendererMode,
                                      final ArrayList<Renderer> out) {
        super.buildTextRenderers(context, output, outputLooper, extensionRendererMode, out);
        for (final Renderer renderer : out) {
            if (renderer instanceof TextRenderer) {
                ((TextRenderer) renderer).experimentalSetLegacyDecodingEnabled(true);
            }
        }
    }
}
