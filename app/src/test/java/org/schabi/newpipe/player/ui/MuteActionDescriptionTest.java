package org.schabi.newpipe.player.ui;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.app.Application;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;

import androidx.preference.PreferenceManager;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;
import org.schabi.newpipe.R;
import org.schabi.newpipe.databinding.PlayerBinding;
import org.schabi.newpipe.player.Player;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 26)
public class MuteActionDescriptionTest {
    @Test
    public void initialBindingTransitionsAndStateResyncDescribeTheNextAction() {
        final var context = new ContextThemeWrapper(RuntimeEnvironment.getApplication(),
                R.style.DarkTheme);
        final PlayerBinding binding = PlayerBinding.inflate(LayoutInflater.from(context));
        final Player player = mock(Player.class);
        when(player.getPrefs()).thenReturn(PreferenceManager.getDefaultSharedPreferences(context));
        final VideoPlayerUi ui = mock(VideoPlayerUi.class, CALLS_REAL_METHODS);
        ReflectionHelpers.setField(ui, "context", context);
        ReflectionHelpers.setField(ui, "player", player);
        ReflectionHelpers.setField(ui, "binding", binding);
        org.mockito.Mockito.doNothing().when(ui)
                .setBlockSponsorsButton(binding.switchSponsorBlocking);
        when(player.isMuted()).thenReturn(true);
        ui.setupElementsVisibility();
        assertEquals(context.getString(R.string.unmute),
                binding.switchMute.getContentDescription());
        ui.onMuteUnmuteChanged(false);
        assertEquals(context.getString(R.string.mute), binding.switchMute.getContentDescription());
        ui.onMuteUnmuteChanged(true);
        assertEquals(context.getString(R.string.unmute),
                binding.switchMute.getContentDescription());
        when(player.isMuted()).thenReturn(false);
        ui.setupElementsVisibility();
        assertEquals(context.getString(R.string.mute), binding.switchMute.getContentDescription());
    }
}
