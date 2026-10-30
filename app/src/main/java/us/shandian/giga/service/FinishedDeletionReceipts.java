/*
 * SPDX-FileCopyrightText: 2026 PVCPipe contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package us.shandian.giga.service;

import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import us.shandian.giga.get.Mission;
import us.shandian.giga.util.Utility;

/** Private receipts bridge successful media deletion and a retryable finished-database removal. */
final class FinishedDeletionReceipts {
    private static final String TAG = "FinishedDeletionReceipts";
    private final File directory;

    FinishedDeletionReceipts(final File files) {
        directory = new File(files, "finished_download_deletions");
    }

    private String key(final Mission mission) {
        final String target = mission.storage == null ? "unassigned"
                : mission.storage.isInvalid() ? mission.storage.getName()
                : mission.storage.getUri().toString();
        return mission.timestamp + "\n" + target;
    }

    private File file(final Mission mission) {
        try {
            final byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(key(mission).getBytes(StandardCharsets.UTF_8));
            final StringBuilder name = new StringBuilder();
            for (final byte value : digest) {
                name.append(Character.forDigit((value >>> 4) & 15, 16));
                name.append(Character.forDigit(value & 15, 16));
            }
            return new File(directory, name + ".receipt");
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    boolean exists(final Mission mission) {
        final File path = file(mission);
        return path.exists() || new File(path + ".bak").exists()
                || new File(path + ".new").exists();
    }

    boolean contains(final Mission mission) {
        if (!exists(mission)) {
            return false;
        }
        return key(mission).equals(Utility.readFromFile(file(mission)));
    }

    boolean save(final Mission mission) {
        return (directory.isDirectory() || directory.mkdirs())
                && Utility.writeToFile(file(mission), key(mission));
    }

    void remove(final Mission mission) {
        final File path = file(mission);
        try {
            Files.deleteIfExists(new File(path + ".new").toPath());
            Files.deleteIfExists(new File(path + ".bak").toPath());
            Files.deleteIfExists(path.toPath());
        } catch (final IOException | SecurityException e) {
            // The entry and media are already gone; new missions avoid this orphan's key.
            Log.w(TAG, "Unable to remove committed deletion receipt", e);
        }
    }
}
