/*
 * SPDX-FileCopyrightText: 2026 PVCPipe contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.util;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.util.Set;

public class StateSaverFileCleanupTest {
    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void deleteInactiveFilesPreservesActiveState() throws IOException {
        final File cacheDir = temporaryFolder.newFolder("state_cache");
        final File staleState = new File(cacheDir, "stale.cache");
        final File activeState = new File(cacheDir, "active.cache");
        assertTrue(staleState.createNewFile());
        assertTrue(activeState.createNewFile());

        StateSaverFileCleanup.deleteInactiveFiles(
                cacheDir, Set.of(activeState.getAbsolutePath()));

        assertFalse(staleState.exists());
        assertTrue(activeState.exists());
    }

    @Test
    public void onlyAFileBeneathTheStateDirectoryIsInsideIt() throws IOException {
        final File stateDir = temporaryFolder.newFolder("cache", "state_cache");
        final File sibling = temporaryFolder.newFolder("cache", "state_cache_other");
        final File outside = temporaryFolder.newFolder("shared");

        assertTrue(StateSaverFileCleanup.isInside(stateDir,
                new File(stateDir, "1234.cache").getPath()));
        // a file that does not exist yet is still inside
        assertTrue(StateSaverFileCleanup.isInside(stateDir,
                new File(stateDir, "never-written").getPath()));

        // a sibling whose name starts the same way
        assertFalse(StateSaverFileCleanup.isInside(stateDir,
                new File(sibling, "1234.cache").getPath()));
        // a path that leaves the directory again
        assertFalse(StateSaverFileCleanup.isInside(stateDir,
                stateDir.getPath() + "/../state_cache_other/1234.cache"));
        assertFalse(StateSaverFileCleanup.isInside(stateDir,
                stateDir.getPath() + "/../../shared/1234.cache"));
        assertFalse(StateSaverFileCleanup.isInside(stateDir,
                new File(outside, "1234.cache").getPath()));
        // the directory is not a state file
        assertFalse(StateSaverFileCleanup.isInside(stateDir, stateDir.getPath()));
        assertFalse(StateSaverFileCleanup.isInside(stateDir, stateDir.getPath() + "/."));
        assertFalse(StateSaverFileCleanup.isInside(stateDir, ""));
        assertFalse(StateSaverFileCleanup.isInside(stateDir, null));
        assertFalse(StateSaverFileCleanup.isInside(stateDir, "relative.cache"));
    }

    @Test
    public void aPathThatLeavesTheStateDirectoryThroughALinkIsOutside() throws IOException {
        final File stateDir = temporaryFolder.newFolder("cache", "state_cache");
        final File outside = temporaryFolder.newFolder("shared");
        final File link = new File(stateDir, "link");
        java.nio.file.Files.createSymbolicLink(link.toPath(), outside.toPath());

        assertFalse(StateSaverFileCleanup.isInside(stateDir,
                new File(link, "1234.cache").getPath()));
    }

    @Test
    public void aStateFileOutsideTheStateDirectoryIsNotDeleted() throws IOException {
        final File stateDir = temporaryFolder.newFolder("cache", "state_cache");
        final File outside = temporaryFolder.newFolder("shared");
        final File own = new File(stateDir, "1234.cache");
        final File foreign = new File(outside, "1234.cache");
        assertTrue(own.createNewFile());
        assertTrue(foreign.createNewFile());

        StateSaverFileCleanup.deleteIfInside(stateDir, foreign.getPath());
        StateSaverFileCleanup.deleteIfInside(stateDir,
                stateDir.getPath() + "/../../shared/1234.cache");
        assertTrue(foreign.exists());

        StateSaverFileCleanup.deleteIfInside(stateDir, own.getPath());
        assertFalse(own.exists());
        assertTrue(stateDir.isDirectory());
    }

    @Test
    public void theOldStateDirectoryIsRemovedWithItsFiles() throws IOException {
        final File legacy = temporaryFolder.newFolder("external", "state_cache");
        assertTrue(new File(legacy, "1.cache").createNewFile());
        assertTrue(new File(legacy, "2.cache").createNewFile());

        StateSaverFileCleanup.deleteDirectory(legacy);
        assertFalse(legacy.exists());
        assertTrue(legacy.getParentFile().isDirectory());

        // nothing to remove is not an error
        StateSaverFileCleanup.deleteDirectory(legacy);
    }

    @Test
    public void deleteInactiveFilesAcceptsMissingDirectory() {
        final File missingCacheDir = new File(temporaryFolder.getRoot(), "missing");

        StateSaverFileCleanup.deleteInactiveFiles(missingCacheDir, Set.of());

        assertFalse(missingCacheDir.exists());
    }
}
