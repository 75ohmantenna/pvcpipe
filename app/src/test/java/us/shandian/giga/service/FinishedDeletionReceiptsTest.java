/*
 * SPDX-FileCopyrightText: 2026 PVCPipe contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package us.shandian.giga.service;

import android.app.Application;
import android.net.Uri;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.schabi.newpipe.streams.io.StoredFileHelper;

import java.io.File;

import us.shandian.giga.get.FinishedMission;
import us.shandian.giga.util.Utility;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Real private receipt writes/readback; no provider or finished-database doubles. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, application = Application.class, sdk = 26)
public class FinishedDeletionReceiptsTest {
    @Rule public final TemporaryFolder folder = new TemporaryFolder();

    private FinishedMission mission(final long timestamp, final String filename) {
        final FinishedMission mission = new FinishedMission();
        mission.timestamp = timestamp;
        mission.storage = new StoredFileHelper(null,
                Uri.fromFile(new File(folder.getRoot(), filename)), "application/octet-stream");
        return mission;
    }

    @Test
    public void receiptSurvivesReloadAndBelongsOnlyToItsTimestampAndUri() {
        final FinishedMission mission = mission(1234, "first.bin");
        final FinishedDeletionReceipts receipts = new FinishedDeletionReceipts(folder.getRoot());
        assertFalse(receipts.contains(mission));
        assertTrue(receipts.save(mission));
        final FinishedDeletionReceipts reloaded = new FinishedDeletionReceipts(folder.getRoot());
        assertTrue(reloaded.contains(mission));
        assertFalse(reloaded.contains(mission(1235, "first.bin")));
        assertFalse(reloaded.contains(mission(1234, "second.bin")));
        reloaded.remove(mission);
        assertFalse(receipts.exists(mission));
    }

    @Test
    public void mismatchedContentsAreNotAcceptedAsADeletionReceipt() {
        final FinishedMission mission = mission(1234, "first.bin");
        final FinishedDeletionReceipts receipts = new FinishedDeletionReceipts(folder.getRoot());
        assertTrue(receipts.save(mission));
        final File[] files = new File(folder.getRoot(), "finished_download_deletions").listFiles();
        assertNotNull(files);
        assertTrue(Utility.writeToFile(files[0], "not this mission"));
        assertFalse(receipts.contains(mission));
    }

    @Test
    public void anUnavailableReceiptDirectoryReportsFailure() throws Exception {
        assertTrue(new File(folder.getRoot(), "finished_download_deletions").createNewFile());
        assertFalse(new FinishedDeletionReceipts(folder.getRoot()).save(mission(1234, "file.bin")));
    }
}
