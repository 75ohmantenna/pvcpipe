package org.schabi.newpipe.settings.migration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.SharedPreferences;

import org.junit.Test;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.settings.tabs.Tab;

import java.util.ArrayList;
import java.util.List;

public class SettingMigrationsTest {
    private static final int MEDIA_CCC_ID = 2;
    private static final int PEERTUBE_ID = 3;
    private static final int BANDCAMP_ID = 4;

    @Test
    public void onlyRetiredYoutubeTrendingTabsAreDropped() {
        final List<Tab> retained = List.of(
                new Tab.DefaultKioskTab(),
                new Tab.KioskTab(ServiceList.Odysee.getServiceId(), "Trending"),
                new Tab.KioskTab(ServiceList.YouTube.getServiceId(), "live"),
                new Tab.SubscriptionsTab(),
                new Tab.ChannelTab(ServiceList.YouTube.getServiceId(),
                        "https://www.youtube.com/@YouTube", "YouTube"));
        final List<Tab> tabs = new ArrayList<>(retained);
        tabs.add(0, new Tab.KioskTab(ServiceList.YouTube.getServiceId(), "Trending"));
        tabs.add(3, new Tab.KioskTab(ServiceList.YouTube.getServiceId(), "Trending"));

        assertEquals(retained, SettingMigrations.withoutYoutubeTrending(tabs));
    }

    @Test
    public void aRetiredYoutubeTrendingTabCanLeaveNoCustomTabs() {
        assertTrue(SettingMigrations.withoutYoutubeTrending(List.of(
                new Tab.KioskTab(ServiceList.YouTube.getServiceId(), "Trending"))).isEmpty());
    }

    @Test
    public void tabsOfRemovedServicesAreDropped() {
        final List<Tab> kept = SettingMigrations.withoutRemovedServices(List.of(
                new Tab.KioskTab(MEDIA_CCC_ID, "conferences"),
                new Tab.ChannelTab(PEERTUBE_ID, "https://example.org/c/a", "A"),
                new Tab.PlaylistTab(BANDCAMP_ID, "https://example.org/album/b", "B")));

        assertTrue(kept.isEmpty());
    }

    @Test
    public void otherTabsKeepTheirOrder() {
        final List<Tab> tabs = List.of(
                new Tab.DefaultKioskTab(),
                new Tab.KioskTab(ServiceList.YouTube.getServiceId(), "live"),
                new Tab.SubscriptionsTab(),
                new Tab.ChannelTab(ServiceList.Rumble.getServiceId(), "https://example.org/c", "C"),
                new Tab.PlaylistTab(1L, "Local playlist"),
                new Tab.PlaylistTab(ServiceList.SoundCloud.getServiceId(),
                        "https://example.org/sets/d", "D"),
                new Tab.KioskTab(ServiceList.Bitchute.getServiceId(), "Trending"));

        assertEquals(tabs, SettingMigrations.withoutRemovedServices(tabs));
    }

    @Test
    public void onlyTabsOfRemovedServicesAreDroppedFromAMixedList() {
        final Tab feed = new Tab.FeedTab();
        final Tab youtubeChannel = new Tab.ChannelTab(
                ServiceList.YouTube.getServiceId(), "https://example.org/channel/e", "E");

        final List<Tab> kept = SettingMigrations.withoutRemovedServices(List.of(
                feed,
                new Tab.KioskTab(PEERTUBE_ID, "Local"),
                youtubeChannel,
                new Tab.KioskTab(BANDCAMP_ID, "Radio")));

        assertEquals(List.of(feed, youtubeChannel), kept);
    }

    @Test
    public void instancePreferencesAreRemovedAndAKeptServiceStaysSelected() {
        final SharedPreferences preferences = mock(SharedPreferences.class);
        final SharedPreferences.Editor editor =
                mock(SharedPreferences.Editor.class, RETURNS_SELF);
        when(preferences.edit()).thenReturn(editor);
        when(preferences.getString("service", "")).thenReturn("Rumble");

        assertFalse(SettingMigrations.removeServicePreferences(preferences, "service"));

        verify(editor).remove("peertube_selected_instance");
        verify(editor).remove("peertube_instance_list");
        verify(editor, never()).remove("service");
        verify(editor).apply();
    }

    @Test
    public void aRemovedSelectedServiceIsCleared() {
        for (final String name : List.of("media.ccc.de", "PeerTube", "Bandcamp")) {
            final SharedPreferences preferences = mock(SharedPreferences.class);
            final SharedPreferences.Editor editor =
                    mock(SharedPreferences.Editor.class, RETURNS_SELF);
            when(preferences.edit()).thenReturn(editor);
            when(preferences.getString("service", "")).thenReturn(name);

            assertTrue(SettingMigrations.removeServicePreferences(preferences, "service"));

            verify(editor).remove("service");
            verify(editor).apply();
        }
    }

    @Test
    public void aMissingSelectedServiceIsLeftAlone() {
        final SharedPreferences preferences = mock(SharedPreferences.class);
        final SharedPreferences.Editor editor =
                mock(SharedPreferences.Editor.class, RETURNS_SELF);
        when(preferences.edit()).thenReturn(editor);
        when(preferences.getString("service", "")).thenReturn("");

        assertFalse(SettingMigrations.removeServicePreferences(preferences, "service"));

        verify(editor, never()).remove("service");
    }

    @Test
    public void noRegisteredServiceUsesARemovedId() {
        for (final var service : ServiceList.all()) {
            final Tab tab = new Tab.KioskTab(service.getServiceId(), "any");
            assertEquals(List.of(tab), SettingMigrations.withoutRemovedServices(List.of(tab)));
        }
    }
}
