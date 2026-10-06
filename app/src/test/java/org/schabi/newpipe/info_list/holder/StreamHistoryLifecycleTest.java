package org.schabi.newpipe.info_list.holder;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.Test;
import org.schabi.newpipe.database.stream.model.StreamStateEntity;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.StreamType;
import org.schabi.newpipe.local.history.HistoryRecordManager;
import org.schabi.newpipe.util.DependentPreferenceHelper;
import org.schabi.newpipe.views.AnimatedProgressBar;

import java.lang.reflect.Field;

import io.reactivex.rxjava3.android.plugins.RxAndroidPlugins;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.disposables.SerialDisposable;
import io.reactivex.rxjava3.schedulers.Schedulers;
import io.reactivex.rxjava3.subjects.SingleSubject;

public class StreamHistoryLifecycleTest {
    @Test
    public void historyLookupReturnsBeforeResultAndCannotUpdateARecycledHolder() throws Exception {
        final var immediate = Schedulers.trampoline();
        final var previous = RxAndroidPlugins.getInitMainThreadSchedulerHandler();
        RxAndroidPlugins.setInitMainThreadSchedulerHandler(ignored -> immediate);
        try (var android = mockStatic(AndroidSchedulers.class);
             var prefs = mockStatic(DependentPreferenceHelper.class)) {
            android.when(AndroidSchedulers::mainThread).thenReturn(immediate);
            prefs.when(() -> DependentPreferenceHelper.getPositionsInListsEnabled(any()))
                    .thenReturn(true);
            final StreamMiniInfoItemHolder holder =
                    mock(StreamMiniInfoItemHolder.class, CALLS_REAL_METHODS);
            final AnimatedProgressBar progress = mock(AnimatedProgressBar.class);
            final HistoryRecordManager history = mock(HistoryRecordManager.class);
            final StreamInfoItem oldItem = item();
            final StreamInfoItem newItem = item();
            final SingleSubject<StreamStateEntity[]> oldResult = SingleSubject.create();
            final SingleSubject<StreamStateEntity[]> newResult = SingleSubject.create();
            when(history.loadStreamState(oldItem)).thenReturn(oldResult);
            when(history.loadStreamState(newItem)).thenReturn(newResult);
            field(holder, "stateSubscription", new SerialDisposable());
            field(holder, "itemProgressView", progress);
            field(holder, "boundItem", oldItem);
            holder.updateState(oldItem, history);
            assertTrue(oldResult.hasObservers());
            holder.recycle();
            assertFalse(oldResult.hasObservers());
            field(holder, "boundItem", newItem);
            holder.updateState(newItem, history);
            oldResult.onSuccess(new StreamStateEntity[]{new StreamStateEntity(1, 11_000)});
            newResult.onSuccess(new StreamStateEntity[]{new StreamStateEntity(2, 22_000)});
            verify(progress, never()).setProgressAnimated(11);
            verify(progress).setProgressAnimated(22);
        } finally {
            RxAndroidPlugins.setInitMainThreadSchedulerHandler(previous);
        }
    }

    private static StreamInfoItem item() {
        final StreamInfoItem item = mock(StreamInfoItem.class);
        when(item.getDuration()).thenReturn(120L);
        when(item.getStreamType()).thenReturn(StreamType.VIDEO_STREAM);
        return item;
    }

    private static void field(final Object holder, final String name, final Object value)
            throws Exception {
        final Field field = StreamMiniInfoItemHolder.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(holder, value);
    }
}
