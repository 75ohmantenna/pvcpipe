/*
 * SPDX-FileCopyrightText: 2026 PVCPipe contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package us.shandian.giga.ui.common;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

import com.google.android.material.snackbar.Snackbar;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;
import org.schabi.newpipe.R;
import org.schabi.newpipe.streams.io.StoredFileHelper;

import java.time.Duration;
import java.util.ArrayList;

import us.shandian.giga.get.FinishedMission;
import us.shandian.giga.get.Mission;
import us.shandian.giga.service.DownloadManager;
import us.shandian.giga.ui.adapter.MissionAdapter;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.robolectric.Shadows.shadowOf;

/** Real timers/loops with controlled manager, iterator, Snackbar, reporting and labels. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, application = Application.class, sdk = 26)
public class DeletionPresentationTest {
    private DownloadManager manager;
    private DownloadManager.MissionIterator iterator;
    private MissionAdapter adapter;
    private Context context;
    private View view;
    private Snackbar snackbar;
    private MockedStatic<Snackbar> snackbars;
    private Deleter deleter;

    @Before
    public void setup() {
        manager = mock(DownloadManager.class);
        iterator = mock(DownloadManager.MissionIterator.class);
        adapter = mock(MissionAdapter.class);
        context = mock(Context.class);
        view = new View(RuntimeEnvironment.getApplication());
        snackbar = mock(Snackbar.class, RETURNS_SELF);
        snackbars = mockStatic(Snackbar.class);
        snackbars.when(() -> Snackbar.make(eq(view), any(CharSequence.class), anyInt()))
                .thenReturn(snackbar);
        when(context.getString(R.string.file_deleted, "fixture.bin")).thenReturn("Marked");
        when(context.getString(R.string.entry_deleted)).thenReturn("Entry removed");
        deleter = new Deleter(view, context, adapter, manager, iterator,
                new Handler(Looper.getMainLooper()));
    }

    @After
    public void cleanup() {
        deleter.pause();
        snackbars.close();
    }

    private FinishedMission mission() {
        final FinishedMission mission = new FinishedMission();
        mission.storage = mock(StoredFileHelper.class);
        when(mission.storage.getName()).thenReturn("fixture.bin");
        when(mission.storage.getUri()).thenReturn(Uri.parse("content://fixture/document/file"));
        return mission;
    }

    @Test
    public void failedSingleDeletionUnhidesRefreshesAndReports() {
        final Mission mission = mission();
        deleter.append(mission, true);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(6));
        verify(manager).deleteMission(mission, true);
        verify(iterator).unHide(mission);
        verify(adapter).applyChanges();
        verify(adapter).reportDeletionFailures(1);
        verify(context, never()).sendBroadcast(any(Intent.class));
    }

    @Test
    public void successfulSingleDeletionScansOnlyOnce() {
        final Mission mission = mission();
        when(manager.deleteMission(mission, true)).thenReturn(true);
        deleter.append(mission, true);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(12));
        verify(manager).deleteMission(mission, true);
        verify(context).sendBroadcast(any(Intent.class));
        verify(adapter, never()).reportDeletionFailures(1);
    }

    @Test
    public void undoNeverAttemptsDeletion() {
        final Mission mission = mission();
        deleter.append(mission, true);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(350));
        final ArgumentCaptor<View.OnClickListener> action =
                ArgumentCaptor.forClass(View.OnClickListener.class);
        verify(snackbar).setAction(eq(R.string.undo), action.capture());
        action.getValue().onClick(view);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(6));
        verify(manager, never()).deleteMission(any(Mission.class), eq(true));
        verify(iterator).unHide(mission);
    }

    @Test
    public void entryOnlyRemovalDoesNotScanOrDeleteAFile() {
        final Mission mission = mission();
        when(manager.deleteMission(mission, false)).thenReturn(true);
        deleter.append(mission, false);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(6));
        verify(manager).deleteMission(mission, false);
        verify(context, never()).sendBroadcast(any(Intent.class));
    }

    @Test
    public void disposalCommitsOnceAndUnhidesFailedEntries() {
        final Mission mission = mission();
        deleter.append(mission, true);
        deleter.dispose();
        deleter.dispose();
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(6));
        verify(manager).deleteMission(mission, true);
        verify(iterator).unHide(mission);
        verify(adapter).reportDeletionFailures(1);
    }

    @Test
    public void bulkDeletionContinuesAfterAFailureAndUnhidesEveryEntry() {
        final MissionAdapter real = bulkAdapter();
        final Mission failed = mission();
        final Mission succeeded = mission();
        when(manager.deleteMission(succeeded, true)).thenReturn(true);
        final ArrayList<Mission> hidden = ReflectionHelpers.getField(real, "mHidden");
        hidden.add(failed);
        hidden.add(succeeded);
        ReflectionHelpers.callInstanceMethod(real, "deleteFinishedDownloads");
        verify(manager).deleteMission(failed, true);
        verify(manager).deleteMission(succeeded, true);
        verify(iterator).unHide(failed);
        verify(iterator).unHide(succeeded);
        verify(real).applyChanges();
        verify(real).reportDeletionFailures(1);
    }

    @Test
    public void screenDestructionFlushesBulkDeletionWithoutALaterDuplicate() {
        final MissionAdapter real = bulkAdapter();
        final Mission failed = mission();
        final ArrayList<Mission> hidden = ReflectionHelpers.getField(real, "mHidden");
        hidden.add(failed);
        real.onDestroy();
        real.onDestroy();
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(6));
        verify(manager).deleteMission(failed, true);
        verify(iterator).unHide(failed);
        verify(real, times(1)).reportDeletionFailures(1);
    }

    private MissionAdapter bulkAdapter() {
        when(manager.getIterator()).thenReturn(iterator);
        final Context application = RuntimeEnvironment.getApplication();
        final MissionAdapter real = spy(new MissionAdapter(application, manager,
                new View(application), new View(application)));
        doNothing().when(real).applyChanges();
        doNothing().when(real).reportDeletionFailures(anyInt());
        return real;
    }
}
