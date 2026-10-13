/*
 * SPDX-FileCopyrightText: 2026 PVCPipe contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.util;

import androidx.annotation.Nullable;

import java.io.File;
import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Set;

final class StateSaverFileCleanup {
    private StateSaverFileCleanup() {
    }

    static void deleteInactiveFiles(final File cacheDir, final Set<String> activeStateFiles) {
        final File[] files = cacheDir.listFiles();
        if (files == null) {
            return;
        }

        for (final File file : files) {
            if (!activeStateFiles.contains(file.getAbsolutePath())) {
                delete(file);
            }
        }
    }

    /**
     * Says whether a path names a file beneath a directory, after links and {@code ..} have
     * been resolved. It compares whole path elements, so {@code state_cache_other/x} is not
     * inside {@code state_cache}.
     *
     * @param directory the directory
     * @param path      any path, from a source that is not trusted
     * @return whether the path is a file inside the directory
     */
    static boolean isInside(final File directory, @Nullable final String path) {
        if (path == null || path.isEmpty()) {
            return false;
        }
        try {
            final Path canonicalDirectory = directory.getCanonicalFile().toPath();
            final Path canonicalFile = new File(path).getCanonicalFile().toPath();
            return canonicalFile.startsWith(canonicalDirectory)
                    && !canonicalFile.equals(canonicalDirectory);
        } catch (final IOException | InvalidPathException | SecurityException e) {
            return false;
        }
    }

    /**
     * Deletes a state file. A path outside the state directory is left alone.
     *
     * @param directory the state directory
     * @param path      the path a saved state names
     */
    static void deleteIfInside(final File directory, @Nullable final String path) {
        if (isInside(directory, path)) {
            delete(new File(path));
        }
    }

    /**
     * Removes a directory of state files with what is directly in it. State directories hold
     * files only.
     *
     * @param directory the directory to remove
     */
    static void deleteDirectory(final File directory) {
        final File[] files = directory.listFiles();
        if (files != null) {
            for (final File file : files) {
                delete(file);
            }
        }
        delete(directory);
    }

    static void delete(final File file) {
        try {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        } catch (final SecurityException ignored) {
            // Cache cleanup is best effort.
        }
    }
}
