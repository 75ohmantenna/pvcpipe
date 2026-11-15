package org.schabi.newpipe.fragments.list.channel;

import android.app.Application;
import android.os.Bundle;
import android.os.Parcel;

import org.junit.Test;
import org.junit.Before;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Robolectric;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.annotation.Config;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo;
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabs;
import org.schabi.newpipe.extractor.linkhandler.ReadyChannelTabListLinkHandler;
import org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeChannelTabPlaylistExtractor;
import org.schabi.newpipe.info_list.InfoListAdapter;

import java.util.ArrayList;
import java.util.LinkedList;
import java.io.ObjectInputStream;
import java.io.ObjectStreamClass;
import java.nio.file.Files;
import org.schabi.newpipe.util.StateSaver;
import org.schabi.newpipe.util.SavedState;
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler;
import org.schabi.newpipe.extractor.Page;
import org.schabi.newpipe.extractor.services.kick.KickClipFilters;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Real fragment state and post-result routing, with UI-only calls suppressed. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, application = Application.class, sdk = 26)
public class ChannelTabStateTest {
    private static final String ID = "UC1234567890123456789012";
    private static final String URL = "https://www.youtube.com/channel/" + ID + "/videos";

    @Before
    public void initializeExtractor() {
        NewPipe.init(mock(Downloader.class));
    }

    @Test
    public void inFlightPrefetchedPayloadDoesNotEnterBundle() {
        final Harness fragment = new Harness();
        fragment.tabHandler = new ReadyChannelTabListLinkHandler(URL, ID, ChannelTabs.VIDEOS,
                new LegacyChannelTabBuilder(1_200_000), plain());
        final Bundle bundle = new Bundle();
        fragment.saveFragmentState(bundle);
        final Parcel parcel = Parcel.obtain();
        try {
            parcel.writeBundle(bundle);
            assertTrue("Bundle bytes: " + parcel.dataSize(), parcel.dataSize() < 64_000);
        } finally {
            parcel.recycle();
        }
    }

    @Test
    public void completedAgeRestrictedTabPreservesPlaylistRouting() throws Exception {
        final Harness fragment = new Harness();
        fragment.tabHandler = new ReadyChannelTabListLinkHandler(URL, ID, ChannelTabs.VIDEOS,
                YoutubeChannelTabPlaylistExtractor::new);
        fragment.handleResult(new ChannelTabInfo(0, fragment.tabHandler));
        assertTrue(ServiceList.YouTube.getChannelTabExtractor(fragment.tabHandler)
                instanceof YoutubeChannelTabPlaylistExtractor);
    }


    @Test
    public void realYoutubePrefetchSurvivesWarmRestoreWithoutAnotherRequest() throws Exception {
        final var channel = spy(ServiceList.YouTube.getChannelExtractor(
                "https://www.youtube.com/channel/" + ID));
        doReturn("Fixture").when(channel).getName();
        doReturn(ID).when(channel).getId();
        doReturn("https://www.youtube.com/channel/" + ID).when(channel).getUrl();
        ReflectionHelpers.setField(channel, "channelId", ID);
        ReflectionHelpers.setField(channel, "jsonResponse", com.grack.nanojson.JsonParser.object()
                .from("{\"contents\":{\"twoColumnBrowseResultsRenderer\":{\"tabs\":["
                        + "{\"tabRenderer\":{\"endpoint\":{\"commandMetadata\":{"
                        + "\"webCommandMetadata\":{\"url\":\"" + URL + "\"}}},"
                        + "\"content\":{},\"fixturePadding\":\"" + "x".repeat(1_200_000)
                        + "\"}}]}}}"));
        final java.util.List<ListLinkHandler> tabs = ReflectionHelpers.callInstanceMethod(channel,
                "getTabsForNonAgeRestrictedChannels");
        final ReadyChannelTabListLinkHandler ready = (ReadyChannelTabListLinkHandler) tabs.get(0);
        assertFalse(ready.getRestorationHandler() instanceof ReadyChannelTabListLinkHandler);
        assertEquals(URL, ready.getRestorationHandler().getUrl());
        final Harness original = new Harness();
        original.tabHandler = ready;
        final Bundle bundle = new Bundle();
        original.saveFragmentState(bundle);
        final Parcel parcel = Parcel.obtain();
        try {
            parcel.writeBundle(bundle);
            assertTrue(parcel.dataSize() < 64_000);
        } finally {
            parcel.recycle();
        }
        StateSaver.init(RuntimeEnvironment.getApplication());
        final SavedState state = StateSaver.tryToSave(true, null, bundle, original);
        final Harness restored = new Harness();
        restored.restoreThroughOnCreate(bundle);
        assertSame(ready, restored.tabHandler);
        ChannelTabInfo.getInfo(ServiceList.YouTube, restored.tabHandler);
        verifyNoInteractions(NewPipe.getDownloader());
        StateSaver.onDestroy(state);
    }

    @Test
    public void baselineSerializedHandlerRetainsUidAndSpecialStrategy() throws Exception {
        assertEquals(-4117307732798877956L,
                ObjectStreamClass.lookup(ReadyChannelTabListLinkHandler.class)
                        .getSerialVersionUID());
        try (ObjectInputStream input = new ObjectInputStream(getClass().getResourceAsStream(
                "/channel-state/legacy-ready.ser"))) {
            final ReadyChannelTabListLinkHandler legacy =
                    (ReadyChannelTabListLinkHandler) input.readObject();
            assertSame(legacy, legacy.getRestorationHandler());
            assertTrue(ServiceList.YouTube.getChannelTabExtractor(legacy)
                    instanceof YoutubeChannelTabPlaylistExtractor);
        }
    }

    @Test
    public void unknownLegacyBuilderRetainsItsPayloadRatherThanLosingStrategy() {
        final ReadyChannelTabListLinkHandler legacy = new ReadyChannelTabListLinkHandler(
                URL, ID, ChannelTabs.VIDEOS, new LegacyChannelTabBuilder(1_200_000));
        final Harness fragment = new Harness();
        fragment.tabHandler = legacy;
        fragment.handleResult(new ChannelTabInfo(0, legacy));
        assertSame(legacy, fragment.tabHandler);
        assertSame(legacy, legacy.getRestorationHandler());
    }

    @Test
    public void warmAndDiskCacheRestoreFullHandlerAfterBundleFallback() throws Exception {
        StateSaver.init(RuntimeEnvironment.getApplication());
        for (final boolean memory : new boolean[]{true, false}) {
            final Harness original = new Harness();
            original.tabHandler = new ReadyChannelTabListLinkHandler(URL, ID, ChannelTabs.VIDEOS,
                    new LegacyChannelTabBuilder(100_000), plain());
            final Page next = new Page("https://fixture.invalid/next");
            ReflectionHelpers.setField(original, "currentNextPage", next);
            ReflectionHelpers.setField(original, "currentInfo",
                    new ChannelTabInfo(0, plain()));
            final Bundle bundle = new Bundle();
            original.saveFragmentState(bundle);
            final SavedState state = StateSaver.tryToSave(memory, null, bundle, original);
            final Harness restored = new Harness();
            restored.restoreThroughOnCreate(bundle);
            assertTrue(restored.tabHandler instanceof ReadyChannelTabListLinkHandler);
            assertEquals(100_000, ((LegacyChannelTabBuilder) ReflectionHelpers.getField(
                    restored.tabHandler, "extractorBuilder")).payloadSize());
            assertEquals(next.getUrl(), ((Page) ReflectionHelpers.getField(
                    restored, "currentNextPage")).getUrl());
            assertTrue(ReflectionHelpers.getField(restored, "currentInfo") != null);
            StateSaver.onDestroy(state);
        }
    }

    @Test
    public void missingAndCorruptCacheKeepBoundedFallback() throws Exception {
        StateSaver.init(RuntimeEnvironment.getApplication());
        for (final boolean corrupt : new boolean[]{false, true}) {
            final Harness original = new Harness();
            original.tabHandler = new ReadyChannelTabListLinkHandler(URL, ID, ChannelTabs.VIDEOS,
                    new LegacyChannelTabBuilder(100_000), plain());
            final Bundle bundle = new Bundle();
            original.saveFragmentState(bundle);
            final SavedState state = StateSaver.tryToSave(false, null, bundle, original);
            final java.nio.file.Path path = java.nio.file.Path.of(state.getPathFileSaved());
            if (corrupt) {
                Files.write(path, new byte[]{1, 2, 3});
            } else {
                Files.delete(path);
            }
            final Harness restored = new Harness();
            restored.restoreThroughOnCreate(bundle);
            assertFalse(restored.tabHandler instanceof ReadyChannelTabListLinkHandler);
            assertEquals(URL, restored.tabHandler.getUrl());
            assertEquals(ID, restored.tabHandler.getId());
            assertEquals(plain().getContentFilters(), restored.tabHandler.getContentFilters());
            StateSaver.onDestroy(state);
        }
    }

    @Test
    public void oldQueueAndLegacyBundleDoNotOverwriteHandler() throws Exception {
        final Harness original = new Harness();
        final ReadyChannelTabListLinkHandler special = new ReadyChannelTabListLinkHandler(
                URL, ID, ChannelTabs.VIDEOS, YoutubeChannelTabPlaylistExtractor::new);
        original.tabHandler = special;
        final Bundle bundle = new Bundle();
        original.saveFragmentState(bundle);
        final LinkedList<Object> oldQueue = new LinkedList<>();
        original.writeTo(oldQueue);
        oldQueue.removeLast();
        final Harness restored = new Harness();
        restored.restoreFragmentState(bundle);
        restored.readFrom(oldQueue);
        assertSame(special, restored.tabHandler);
        restored.handleResult(new ChannelTabInfo(0, special));
        assertTrue(ServiceList.YouTube.getChannelTabExtractor(restored.tabHandler)
                instanceof YoutubeChannelTabPlaylistExtractor);
    }

    @Test
    public void kickFiltersSurviveBundleWithoutCache() throws Exception {
        final Harness original = new Harness();
        original.tabHandler = ServiceList.Kick.getChannelTabLHFactory().fromUrl(
                "https://kick.com/example/clips?sort=views&range=week");
        final Bundle bundle = new Bundle();
        original.saveFragmentState(bundle);
        final Harness restored = new Harness();
        restored.restoreThroughOnCreate(bundle);
        assertEquals(original.tabHandler.getUrl(), restored.tabHandler.getUrl());
        assertEquals("views", KickClipFilters.sort(restored.tabHandler.getSortFilter()));
        assertEquals("week", KickClipFilters.time(restored.tabHandler.getSortFilter()));
    }

    @Test
    public void ageStrategySurvivesMarshalledBundleWithoutCache() throws Exception {
        for (final var tab : java.util.List.of(ChannelTabs.VIDEOS, ChannelTabs.SHORTS,
                ChannelTabs.LIVESTREAMS)) {
            final Harness original = new Harness();
            original.tabHandler = new ReadyChannelTabListLinkHandler(URL, ID, tab,
                    YoutubeChannelTabPlaylistExtractor::new);
            original.handleResult(new ChannelTabInfo(0, original.tabHandler));
            final Bundle saved = new Bundle();
            original.saveFragmentState(saved);
            final Parcel output = Parcel.obtain();
            final Parcel input = Parcel.obtain();
            try {
                output.writeBundle(saved);
                assertTrue(output.dataSize() < 64_000);
                final byte[] bytes = output.marshall();
                input.unmarshall(bytes, 0, bytes.length);
                input.setDataPosition(0);
                final Harness restored = new Harness();
                restored.restoreThroughOnCreate(input.readBundle(getClass().getClassLoader()));
                assertTrue(ServiceList.YouTube.getChannelTabExtractor(restored.tabHandler)
                        instanceof YoutubeChannelTabPlaylistExtractor);
                assertEquals(tab, restored.tabHandler.getContentFilters().get(0));
            } finally {
                output.recycle();
                input.recycle();
            }
        }
    }

    private static org.schabi.newpipe.extractor.linkhandler.ListLinkHandler plain() {
        return new org.schabi.newpipe.extractor.linkhandler.ListLinkHandler(URL, URL, ID,
                java.util.List.of(ChannelTabs.VIDEOS), java.util.List.of());
    }

    public static final class Harness extends ChannelTabFragment {
        public Harness() {
            serviceId = 0;
            channelName = "Fixture";
            infoListAdapter = mock(InfoListAdapter.class);
            final ArrayList<org.schabi.newpipe.extractor.InfoItem> items = new ArrayList<>();
            items.add(null); // Avoid list rendering; no network/content needed for routing.
            when(infoListAdapter.getItemsList()).thenReturn(items);
        }
        private Bundle restorationInput;

        void restoreThroughOnCreate(final Bundle saved) {
            restorationInput = saved;
            final var controller = Robolectric.buildActivity(
                    androidx.appcompat.app.AppCompatActivity.class);
            controller.get().setTheme(androidx.appcompat.R.style.Theme_AppCompat);
            controller.setup();
            controller.get().getSupportFragmentManager().beginTransaction()
                    .add(this, "fixture").commitNow();
        }

        @Override
        public void onCreate(final Bundle saved) {
            super.onCreate(restorationInput == null ? saved : restorationInput);
        }

        @Override
        public android.view.View onCreateView(final android.view.LayoutInflater inflater,
                                               final android.view.ViewGroup container,
                                               final Bundle state) {
            return null; // Lifecycle/restore are real; omit rendering and automatic loading.
        }
        @Override public void hideLoading() { }
        @Override public void setTitle(final String title) { }
    }
}
