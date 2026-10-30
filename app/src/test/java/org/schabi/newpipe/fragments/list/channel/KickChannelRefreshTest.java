package org.schabi.newpipe.fragments.list.channel;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;

import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;

import com.google.android.material.tabs.TabLayout;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;
import org.schabi.newpipe.databinding.FragmentChannelBinding;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.channel.ChannelInfo;
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabs;
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler;
import org.schabi.newpipe.extractor.services.kick.KickClipFilters;
import org.schabi.newpipe.fragments.detail.TabAdapter;
import org.schabi.newpipe.util.ExtractorHelper;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;

import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.disposables.Disposable;

/** Real parent refresh/tab selection with controlled metadata and child destruction. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, application = Application.class, sdk = 26)
public class KickChannelRefreshTest {
    private static final int SERVICE = ServiceList.Kick.getServiceId();
    private static final String URL = "https://kick.com/example/videos";

    @Test
    public void parentRefreshRetainsFiltersAfterDestroyingOldChildren() throws Exception {
        final Harness harness = new Harness();
        try (MockedStatic<ExtractorHelper> extraction = mockStatic(ExtractorHelper.class)) {
            extraction.when(() -> ExtractorHelper.getChannelInfo(SERVICE, URL, true))
                    .thenReturn(Single.never());
            harness.fragment.startLoading(true);
            assertEquals(0, harness.children.size());
            harness.completeMetadataLoad();
            assertFilters(harness.clips());
        } finally {
            final Disposable worker = ReflectionHelpers.getField(harness.fragment, "currentWorker");
            if (worker != null) {
                worker.dispose();
            }
        }
    }

    @Test
    public void parentSavedStateRestoresFiltersWithoutOldChildren() throws Exception {
        final Harness original = new Harness();
        final Bundle saved = new Bundle();
        original.fragment.saveFragmentState(saved);
        final Harness restored = new Harness();
        restored.children.clear();
        restored.fragment.restoreFragmentState(saved);
        restored.completeMetadataLoad();
        assertFilters(restored.clips());
    }

    private static void assertFilters(final ListLinkHandler handler) throws Exception {
        assertEquals("views", KickClipFilters.sort(handler.getSortFilter()));
        assertEquals("week", KickClipFilters.time(handler.getSortFilter()));
        assertEquals("https://kick.com/example/clips?sort=views&range=week", handler.getUrl());
    }

    private static final class Harness {
        private final ChannelFragment fragment = spy(ChannelFragment.getInstance(
                SERVICE, URL, "Example"));
        private final List<Fragment> children = new ArrayList<>();
        private final TabAdapter adapter;

        Harness() throws Exception {
            final FragmentManager manager = mock(FragmentManager.class);
            doReturn(manager).when(fragment).getChildFragmentManager();
            final Context context = mock(Context.class);
            when(context.getPackageName()).thenReturn("org.schabi.newpipe.test");
            when(context.getString(anyInt())).thenAnswer(call -> "resource-" + call.getArgument(0));
            final SharedPreferences preferences = mock(SharedPreferences.class);
            when(preferences.getStringSet(anyString(), isNull())).thenReturn(null);
            when(context.getSharedPreferences(anyString(), anyInt())).thenReturn(preferences);
            doReturn(context).when(fragment).getContext();
            doNothing().when(fragment).showLoading();
            when(manager.getFragments()).thenReturn(children);
            adapter = spy(new TabAdapter(manager));
            doAnswer(call -> {
                children.clear();
                return null;
            }).when(adapter).notifyDataSetUpdate();
            final TabLayout tabs = mock(TabLayout.class);
            when(tabs.getSelectedTabPosition()).thenReturn(2);
            when(tabs.getTabAt(anyInt())).thenReturn(mock(TabLayout.Tab.class));
            final Constructor<?> constructor = FragmentChannelBinding.class
                    .getDeclaredConstructors()[0];
            constructor.setAccessible(true);
            final Object[] arguments = new Object[constructor.getParameterCount()];
            for (int i = 0; i < arguments.length; i++) {
                if (constructor.getParameterTypes()[i] == TabLayout.class) {
                    arguments[i] = tabs;
                }
            }
            ReflectionHelpers.setField(fragment, "binding", constructor.newInstance(arguments));
            ReflectionHelpers.setField(fragment, "tabAdapter", adapter);
            ReflectionHelpers.setField(fragment, "initialKickTabApplied", true);
            ReflectionHelpers.setField(fragment, "currentInfo", metadata());
            children.add(ChannelTabFragment.getInstance(SERVICE,
                    ServiceList.Kick.getChannelTabLHFactory().fromQuery("example",
                            List.of(ChannelTabs.CLIPS),
                            KickClipFilters.fromValues("views", "week")),
                    "Example"));
        }

        private void completeMetadataLoad() throws Exception {
            ReflectionHelpers.setField(fragment, "currentInfo", metadata());
            ReflectionHelpers.callInstanceMethod(fragment, "updateTabs");
        }

        private ListLinkHandler clips() {
            for (int i = 0; i < adapter.getCount(); i++) {
                if (adapter.getItem(i) instanceof ChannelTabFragment) {
                    final ListLinkHandler handler = ((ChannelTabFragment) adapter.getItem(i))
                            .tabHandler;
                    if (handler.getContentFilters().contains(ChannelTabs.CLIPS)) {
                        return handler;
                    }
                }
            }
            throw new AssertionError("Missing Clips tab");
        }

        private static ChannelInfo metadata() throws Exception {
            final ChannelInfo info = mock(ChannelInfo.class);
            when(info.getId()).thenReturn("example");
            when(info.getOriginalUrl()).thenReturn(URL);
            final List<ListLinkHandler> handlers = new ArrayList<>();
            for (final var tab : List.of(ChannelTabs.VIDEOS, ChannelTabs.LIVESTREAMS,
                    ChannelTabs.CLIPS)) {
                handlers.add(ServiceList.Kick.getChannelTabLHFactory().fromQuery("example",
                        List.of(tab), List.of()));
            }
            when(info.getTabs()).thenReturn(handlers);
            return info;
        }
    }
}
