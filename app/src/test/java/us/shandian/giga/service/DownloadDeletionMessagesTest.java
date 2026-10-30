/*
 * SPDX-FileCopyrightText: 2026 PVCPipe contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package us.shandian.giga.service;

import android.app.Application;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;

import androidx.collection.SparseArrayCompat;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

import us.shandian.giga.get.DownloadMission;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Real service message dispatch with controlled manager and failed-notification cache entries. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, application = Application.class, sdk = 26)
public class DownloadDeletionMessagesTest {
    private DownloadManagerService service() {
        final DownloadManagerService service = new DownloadManagerService();
        ReflectionHelpers.setField(service, "mHandler", new Handler(Looper.getMainLooper()));
        return service;
    }

    private void dispatch(final DownloadManagerService service, final int what,
                          final DownloadMission mission) {
        final Message message = Message.obtain();
        message.what = what;
        message.obj = mission;
        ReflectionHelpers.callInstanceMethod(service, "handleMessage",
                ClassParameter.from(Message.class, message));
    }

    @Test
    public void deletingAMissionRemovesItsCacheEntryByIndexWithoutRemovingOthers() {
        final DownloadManagerService service = service();
        final DownloadMission other = mock(DownloadMission.class);
        final DownloadMission removed = mock(DownloadMission.class);
        final SparseArrayCompat<DownloadMission> failed =
                ReflectionHelpers.getField(service, "mFailedDownloads");
        failed.put(9001, other);
        failed.put(9005, removed);
        dispatch(service, DownloadManagerService.MESSAGE_DELETED, removed);
        assertEquals(1, failed.size());
        assertSame(other, failed.get(9001));
        dispatch(service, DownloadManagerService.MESSAGE_DELETED, removed);
        assertEquals(1, failed.size());
    }

    @Test
    public void lateFinishForADeletionPendingMissionDoesNotPublishOrNotifyCompletion() {
        final DownloadManagerService service = service();
        final DownloadManager manager = mock(DownloadManager.class);
        ReflectionHelpers.setField(service, "mManager", manager);
        final DownloadMission mission = mock(DownloadMission.class);
        when(mission.isDeletionPending()).thenReturn(true);
        dispatch(service, DownloadManagerService.MESSAGE_FINISHED, mission);
        verifyNoInteractions(manager);
    }
}
