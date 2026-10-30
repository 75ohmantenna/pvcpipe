package org.schabi.newpipe;

import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;
import org.schabi.newpipe.player.LocalPlayer;
import org.schabi.newpipe.player.LocalPlayerListener;
import org.schabi.newpipe.player.helper.PlaybackParameterDialog;
import org.schabi.newpipe.util.ThemeHelper;
import org.schabi.newpipe.util.SponsorBlockSegment;

@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public class LocalPlayerActivity extends AppCompatActivity implements Player.Listener,
        LocalPlayerListener, PlaybackParameterDialog.Callback {
    private LocalPlayer localPlayer;
    private PlayerView playerView;
    public static final String TAG = "LocalPlayerActivity";

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        ThemeHelper.setTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_local_player);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.local_player_root),
                (view, insets) -> {
                    final var safe = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                            | WindowInsetsCompat.Type.displayCutout());
                    view.setPadding(safe.left, safe.top, safe.right, safe.bottom);
                    return insets;
                });

        hideSystemUi(isLandscape());

        final Intent intent = getIntent();

        final String uri = parseUriFromIntent(intent);
        final SponsorBlockSegment[] segments = parseSegmentsFromIntent(intent);

        localPlayer = new LocalPlayer(this);
        localPlayer.initialize(uri, segments);
        localPlayer.setListener(this);

        playerView = findViewById(R.id.player_view);
        playerView.setPlayer(localPlayer.getExoPlayer());

        playerView.getVideoSurfaceView().setOnLongClickListener(v -> {
            showPlaybackParameterDialog();
            return false;
        });

        playerView.getVideoSurfaceView().setOnClickListener(v -> playerView.performClick());
    }

    public void showPlaybackParameterDialog() {
        final PlaybackParameters playbackParameters =
                localPlayer.getExoPlayer().getPlaybackParameters();

        boolean skipSilence = false;

        skipSilence = localPlayer.getExoPlayer().getSkipSilenceEnabled();
        PlaybackParameterDialog.newInstance(playbackParameters.speed, playbackParameters.pitch,
                skipSilence, this)
                .show(getSupportFragmentManager(), TAG);
    }

    @Override
    protected void onDestroy() {
        playerView.setPlayer(null);
        localPlayer.destroy();
        super.onDestroy();
    }

    @Override
    public void onConfigurationChanged(@NonNull final Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

        hideSystemUi(isLandscape());
    }

    @Override
    public void onPlaybackParameterChanged(final float playbackTempo, final float playbackPitch,
                                           final boolean playbackSkipSilence) {
        localPlayer.setPlaybackParameters(playbackTempo, playbackPitch, playbackSkipSilence);
    }

    @Override
    public void onBlocked(final ExoPlayer player) {

    }

    @Override
    public void onPlaying(final ExoPlayer player) {
        setKeepScreenOn(true);
    }

    @Override
    public void onBuffering(final ExoPlayer player) {
        setKeepScreenOn(true);
    }

    @Override
    public void onPaused(final ExoPlayer player) {
        setKeepScreenOn(false);
    }

    @Override
    public void onPausedSeek(final ExoPlayer player) {

    }

    @Override
    public void onCompleted(final ExoPlayer player) {
        setKeepScreenOn(false);
    }

    private static String parseUriFromIntent(final Intent intent) {
        return intent.getDataString();
    }

    private static SponsorBlockSegment[] parseSegmentsFromIntent(final Intent intent) {
        return SponsorBlockSegment.fromJson(intent.getStringExtra("segments"));
    }

    private void setKeepScreenOn(final boolean keepScreenOn) {
        if (keepScreenOn) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        playerView.getRootView().setKeepScreenOn(keepScreenOn);
    }

    private void hideSystemUi(final boolean isLandscape) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            getWindow().getAttributes().layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }

        int visibility;

        if (isLandscape) {
            visibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
        } else {
            visibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
        }

        if (!isInMultiWindow()) {
            visibility |= View.SYSTEM_UI_FLAG_FULLSCREEN;
        }

        getWindow().getDecorView().setSystemUiVisibility(visibility);

        if (isInMultiWindow()) {
            getWindow().setStatusBarColor(Color.TRANSPARENT);
            getWindow().setNavigationBarColor(Color.TRANSPARENT);
        }
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
    }

    private boolean isInMultiWindow() {
        return isInMultiWindowMode();
    }

    boolean isLandscape() {
        final DisplayMetrics metrics = getResources().getDisplayMetrics();
        return metrics.heightPixels < metrics.widthPixels;
    }
}
