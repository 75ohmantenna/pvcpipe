package org.schabi.newpipe.util;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import android.app.Application;
import android.content.Context;
import android.content.Intent;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.StreamType;
import org.schabi.newpipe.player.Player;
import org.schabi.newpipe.player.PlayerIntentType;
import org.schabi.newpipe.player.PlayerService;
import org.schabi.newpipe.player.helper.PlayerHolder;
import org.schabi.newpipe.player.playqueue.PlayQueue;
import org.schabi.newpipe.player.playqueue.SinglePlayQueue;

import java.util.Optional;

@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, application = Application.class, sdk = 26)
public class NavigationHelperPlayerHandoffTest {
    @Test
    public void explicitHandoffSurvivesSerializationWithoutABoundPlayerHolder() {
        final Context context = RuntimeEnvironment.getApplication();
        final PlayerHolder holder = mock(PlayerHolder.class);
        final PlayQueue active = queue();
        final PlayQueue timestamp = queue();
        timestamp.setRecovery(0, 12_000);
        when(holder.getPlayer()).thenReturn(Optional.empty());
        try (var holders = mockStatic(PlayerHolder.class);
                var services = mockStatic(PlayerService.class)) {
            holders.when(PlayerHolder::getInstance).thenReturn(holder);
            services.when(() -> PlayerService.authenticateInternalIntent(
                    eq(context), any(Intent.class))).thenAnswer(call -> call.getArgument(1));

            final Intent handoff = NavigationHelper.getPlayerIntent(context, PlayerService.class,
                    active, PlayerIntentType.AllOthers, true);
            final Intent explicitTimestamp = NavigationHelper.getPlayerIntent(context,
                    PlayerService.class, timestamp, PlayerIntentType.AllOthers);

            assertTrue(handoff.getBooleanExtra(Player.PRESERVE_CURRENT_POSITION, false));
            assertFalse(explicitTimestamp.getBooleanExtra(Player.PRESERVE_CURRENT_POSITION, false));
            final PlayQueue transported = SerializedCache.getInstance().take(
                    handoff.getStringExtra(Player.PLAY_QUEUE_KEY), PlayQueue.class);
            assertNotSame(active, transported);
            assertTrue(active.equalStreamsAndIndex(transported));
        } finally {
            SerializedCache.getInstance().clear();
        }
    }

    private static PlayQueue queue() {
        final String url = "https://example.invalid/video";
        final StreamInfo info = new StreamInfo(0, url, url,
                StreamType.VIDEO_STREAM, "video", "video", 0);
        return new SinglePlayQueue(info);
    }
}
