package org.schabi.newpipe.fragments.detail;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import android.app.Application;
import android.os.Looper;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;

import androidx.appcompat.app.AppCompatActivity;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.util.ReflectionHelpers;
import org.schabi.newpipe.R;
import org.schabi.newpipe.databinding.FragmentVideoDetailBinding;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.util.ExtractorHelper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import io.reactivex.rxjava3.core.Single;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 26)
@LooperMode(LooperMode.Mode.PAUSED)
public class DetailDelayedRenderTest {
    @Test
    public void newerNavigationRetiresThePendingRenderEvenBeforeLoading() {
        final Probe fragment = new Probe();
        final StreamInfo a = info("A", "same title");
        fragment.schedule(a, 200);
        fragment.fragment.setInitialData(0, "B", "same title", null);
        idle(200);
        assertEquals(List.of(), fragment.rendered);
        assertEquals("B", fragment.fragment.url);
    }

    @Test
    public void loadingCannotBeOverwrittenByPendingMetadata() {
        final Probe fragment = new Probe();
        fragment.schedule(info("A", "A"), 200);
        try (var extractor = mockStatic(ExtractorHelper.class)) {
            extractor.when(() -> ExtractorHelper.getStreamInfo(0, "A", false))
                    .thenReturn(Single.never());
            fragment.fragment.startLoading(false);
            idle(200);
            assertEquals(List.of(), fragment.rendered);
            assertNull(ReflectionHelpers.getField(fragment.fragment, "currentInfo"));
        }
    }

    @Test
    public void failedNewRequestCannotBeOverwrittenByPendingMetadata() {
        final Probe fragment = new Probe();
        fragment.schedule(info("A", "A"), 200);
        fragment.fragment.setInitialData(0, "B", "B", null);
        io.reactivex.rxjava3.plugins.RxJavaPlugins.setIoSchedulerHandler(
                scheduler -> io.reactivex.rxjava3.schedulers.Schedulers.trampoline());
        try (var extractor = mockStatic(ExtractorHelper.class);
                var errors = org.mockito.Mockito.mockConstruction(
                        org.schabi.newpipe.error.ErrorInfo.class)) {
            extractor.when(() -> ExtractorHelper.getStreamInfo(0, "B", false))
                    .thenReturn(Single.error(new java.io.IOException("controlled failure")));
            fragment.fragment.startLoading(false);
            idle(200);
            assertEquals(1, fragment.errors);
            assertEquals(List.of(), fragment.rendered);
            assertEquals("B", fragment.fragment.url);
        } finally {
            io.reactivex.rxjava3.plugins.RxJavaPlugins.reset();
        }
    }

    @Test
    public void replacementMetadataWithSameTitleAndUrlRendersOnlyNewestOnce() {
        final Probe fragment = new Probe();
        final StreamInfo old = info("A", "same title");
        final StreamInfo current = info("A", "same title");
        fragment.binding().detailVideoTitleView.setText("same title");
        fragment.schedule(old, 200);
        idle(100);
        fragment.schedule(current, 200);
        idle(199);
        assertEquals(List.of(), fragment.rendered);
        idle(1);
        assertEquals(List.of(current), fragment.rendered);
        idle(1000);
        assertEquals(List.of(current), fragment.rendered);
        assertSame(current, ReflectionHelpers.getField(fragment.fragment, "currentInfo"));
    }

    @Test
    public void destroyedViewCancelsAndRecreatedViewStillRendersAtFiftyMilliseconds() {
        final Probe fragment = new Probe();
        final StreamInfo current = info("A", "A");
        fragment.schedule(current, 200);
        fragment.fragment.onDestroyView();
        idle(200);
        assertEquals(List.of(), fragment.rendered);
        fragment.newBinding();
        fragment.fragment.doInitialLoadLogic();
        idle(49);
        assertEquals(List.of(), fragment.rendered);
        idle(1);
        assertEquals(List.of(current), fragment.rendered);
    }

    @Test
    public void replacedBindingWithoutTeardownCannotReceiveAnOldCallback() {
        final Probe fragment = new Probe();
        fragment.schedule(info("A", "A"), 200);
        fragment.newBinding();
        idle(200);
        assertEquals(List.of(), fragment.rendered);
    }

    private static StreamInfo info(final String url, final String title) {
        final StreamInfo info = mock(StreamInfo.class);
        when(info.getUrl()).thenReturn(url);
        when(info.getOriginalUrl()).thenReturn(url);
        when(info.getName()).thenReturn(title);
        return info;
    }

    private static void idle(final long millis) {
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis));
    }

    private static final class Probe {
        private final VideoDetailFragment fragment = org.mockito.Mockito.spy(
                new VideoDetailFragment());
        private final List<StreamInfo> rendered = new ArrayList<>();
        private int errors;

        Probe() {
            final AppCompatActivity activity = mock(AppCompatActivity.class);
            when(activity.getApplicationContext()).thenReturn(RuntimeEnvironment.getApplication());
            when(activity.getPackageName()).thenReturn("org.schabi.newpipe.test");
            when(activity.getSharedPreferences(org.mockito.ArgumentMatchers.anyString(),
                    org.mockito.ArgumentMatchers.anyInt())).thenAnswer(call ->
                    RuntimeEnvironment.getApplication().getSharedPreferences(
                            call.getArgument(0), call.getArgument(1)));
            ReflectionHelpers.setField(fragment, "activity", activity);
            ReflectionHelpers.setField(fragment, "content", mock(DetailContentCoordinator.class));
            org.mockito.Mockito.doNothing().when(fragment).showLoading();
            org.mockito.Mockito.doAnswer(call -> {
                errors++;
                return null;
            }).when(fragment).showError(org.mockito.ArgumentMatchers.any());
            org.mockito.Mockito.doAnswer(call -> {
                final StreamInfo info = call.getArgument(0);
                rendered.add(info);
                ReflectionHelpers.setField(fragment, "currentInfo", info);
                fragment.setInitialData(info.getServiceId(), info.getOriginalUrl(),
                        info.getName(), null);
                return null;
            }).when(fragment).handleResult(org.mockito.ArgumentMatchers.any());
            newBinding();
        }

        void newBinding() {
            final var context = new ContextThemeWrapper(RuntimeEnvironment.getApplication(),
                    R.style.DarkTheme);
            ReflectionHelpers.setField(fragment, "binding",
                    FragmentVideoDetailBinding.inflate(LayoutInflater.from(context)));
        }

        FragmentVideoDetailBinding binding() {
            return ReflectionHelpers.getField(fragment, "binding");
        }

        void schedule(final StreamInfo info, final long delay) {
            fragment.setInitialData(0, info.getUrl(), info.getName(), null);
            ReflectionHelpers.setField(fragment, "currentInfo", info);
            ReflectionHelpers.callInstanceMethod(fragment, "prepareAndHandleInfoIfNeededAfterDelay",
                    ReflectionHelpers.ClassParameter.from(StreamInfo.class, info),
                    ReflectionHelpers.ClassParameter.from(boolean.class, true),
                    ReflectionHelpers.ClassParameter.from(long.class, delay));
        }
    }
}
