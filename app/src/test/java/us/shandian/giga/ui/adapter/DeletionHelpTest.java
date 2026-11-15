/*
 * SPDX-FileCopyrightText: 2026 PVCPipe contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package us.shandian.giga.ui.adapter;

import android.app.Activity;
import android.app.Application;
import android.os.Looper;
import android.view.MenuItem;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.util.ReflectionHelpers;
import org.schabi.newpipe.R;
import org.schabi.newpipe.streams.io.StoredFileHelper;

import us.shandian.giga.get.DownloadMission;
import us.shandian.giga.get.FinishedMission;
import us.shandian.giga.get.Mission;
import us.shandian.giga.service.DownloadManager;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.robolectric.util.ReflectionHelpers.ClassParameter.from;
import static org.robolectric.Shadows.shadowOf;

/** Actual adapter/menu/dialog resources; manager and storage are controlled, untouched doubles. */
@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 26)
public class DeletionHelpTest {
    private Activity activity;
    private MissionAdapter adapter;
    private MissionAdapter.ViewHolderItem holder;
    private DownloadManager manager;
    private StoredFileHelper storage;

    @Before
    public void setup() {
        activity = Robolectric.buildActivity(Activity.class).setup().get();
        activity.setTheme(androidx.appcompat.R.style.Theme_AppCompat);
        manager = mock(DownloadManager.class);
        when(manager.getIterator()).thenReturn(mock(DownloadManager.MissionIterator.class));
        adapter = new MissionAdapter(activity, manager, new View(activity), new View(activity));
        final FrameLayout root = new FrameLayout(activity);
        activity.setContentView(root);
        holder = (MissionAdapter.ViewHolderItem) adapter.onCreateViewHolder(root, 0);
        root.addView(holder.itemView);
        storage = mock(StoredFileHelper.class);
        clearInvocations(manager);
    }

    private DownloadMission pending() {
        final DownloadMission mission = new DownloadMission(
                new String[]{"https://fixture.invalid/file"}, storage, 'v', null);
        ReflectionHelpers.setField(mission, "deletionPending", true);
        mission.enqueued = false;
        bind(mission);
        return mission;
    }

    private void bind(final Mission mission) {
        holder.item = ReflectionHelpers.callConstructor(DownloadManager.MissionItem.class,
                from(int.class, 0), from(Mission.class, mission));
    }

    private void click(final int id) {
        ReflectionHelpers.callInstanceMethod(adapter, "handlePopupItem",
                from(MissionAdapter.ViewHolderItem.class, holder),
                from(MenuItem.class, holder.popupMenu.getMenu().findItem(id)));
    }

    @Test
    public void pendingMenuOffersReadOnlyHelpAndDismissalKeepsCancellation() {
        final DownloadMission mission = pending();
        holder.itemView.findViewById(R.id.item_more).performClick();
        assertTrue(holder.deletionHelp.isVisible());
        assertTrue(holder.delete.isVisible());
        assertFalse(holder.start.isVisible());
        assertFalse(holder.retry.isVisible());
        assertFalse(holder.deleteEntry.isVisible());
        click(R.id.deletion_help);
        final AlertDialog dialog = (AlertDialog) ShadowDialog.getLatestDialog();
        assertTrue(dialog.isShowing());
        final String guidance = ((TextView) dialog.findViewById(android.R.id.message))
                .getText().toString();
        assertTrue(guidance.contains("may already be gone"));
        assertTrue(guidance.contains("If deletion was not recorded"));
        assertTrue(guidance.contains("choose Delete again"));
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        shadowOf(Looper.getMainLooper()).idle();
        assertFalse(dialog.isShowing());
        click(R.id.deletion_help);
        ShadowDialog.getLatestDialog().cancel(); // Back/outside cancellation path.
        assertTrue(mission.isDeletionPending());
        assertFalse(mission.mediaDeleted);
        assertFalse(mission.running);
        verifyNoInteractions(manager, storage);
    }

    @Test
    public void staleMenuCannotResumeQueueOrRecoverButCanExplainDeletion() {
        final DownloadMission mission = pending();
        for (final int id : new int[]{R.id.start, R.id.queue, R.id.retry, R.id.cancel}) {
            click(id);
        }
        click(R.id.deletion_help);
        assertTrue(ShadowDialog.getLatestDialog().isShowing());
        ShadowDialog.getLatestDialog().dismiss();
        assertTrue(mission.isDeletionPending());
        assertFalse(mission.enqueued);
        verifyNoInteractions(manager, storage);
    }

    @Test
    public void recycledNormalRowRejectsStaleHelpAndResetsVisibility() {
        pending();
        holder.itemView.findViewById(R.id.item_more).performClick();
        assertTrue(holder.deletionHelp.isVisible());
        final FinishedMission normal = new FinishedMission();
        normal.storage = storage;
        bind(normal);
        holder.itemView.findViewById(R.id.item_more).performClick();
        assertFalse(holder.deletionHelp.isVisible());
        click(R.id.deletion_help);
        assertNull(ShadowDialog.getLatestDialog());
        verifyNoInteractions(manager, storage);
    }

    @Test
    public void finishedReceiptOffersHelpWithoutChangingIt() {
        final FinishedMission mission = new FinishedMission();
        mission.storage = storage;
        mission.mediaDeleted = true;
        bind(mission);
        holder.itemView.findViewById(R.id.item_more).performClick();
        assertTrue(holder.deletionHelp.isVisible());
        click(R.id.deletion_help);
        ShadowDialog.getLatestDialog().cancel();
        assertTrue(mission.mediaDeleted);
        verifyNoInteractions(manager, storage);
    }
}
