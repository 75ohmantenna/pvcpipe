/*
 * SPDX-FileCopyrightText: 2026 PVCPipe contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package us.shandian.giga.service;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.provider.DocumentsContract;
import android.provider.DocumentsProvider;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Test-APK-only provider serving bounded, newly created fixtures on disposable emulators. */
public class DeletionDocumentsProvider extends DocumentsProvider {
    public static final byte[] BYTES = "controlled deletion fixture\n"
            .getBytes(StandardCharsets.UTF_8);

    static void requireEmulator() {
        if (!"ranchu".equals(Build.HARDWARE) && !"goldfish".equals(Build.HARDWARE)) {
            throw new SecurityException("Disposable emulator required");
        }
    }

    static void requireId(final String id) {
        if (id == null || !id.matches("[a-f0-9]{32}-(finished|pending)")) {
            throw new SecurityException("Invalid fixture ID");
        }
    }

    private File directory(final String id) {
        requireEmulator();
        requireId(id);
        return new File(getContext().getFilesDir(), "download-delete/" + id);
    }

    @Override public boolean onCreate() {
        return true;
    }

    @Override public Bundle call(final String method, final String arg, final Bundle extras) {
        if (!"create-fixture".equals(method) && !"set-policy".equals(method)) {
            return super.call(method, arg, extras);
        }
        final File dir = directory(arg);
        try {
            if ("create-fixture".equals(method)) {
                if (dir.exists() || !dir.mkdirs()) {
                    throw new IllegalStateException("Refusing to replace fixture");
                }
                Files.write(new File(dir, "media.bin").toPath(), BYTES);
                Files.write(new File(dir, "policy").toPath(),
                        "refuse".getBytes(StandardCharsets.UTF_8));
            } else if ("set-policy".equals(method)) {
                final String policy = extras.getString("policy");
                if (!dir.isDirectory() || !("allow".equals(policy)
                        || "refuse".equals(policy) || "unavailable".equals(policy)
                        || "hold-before".equals(policy) || "hold-after".equals(policy))) {
                    throw new IllegalArgumentException("Invalid fixture policy");
                }
                Files.write(new File(dir, "policy").toPath(),
                        policy.getBytes(StandardCharsets.UTF_8));
            } else {
                return super.call(method, arg, extras);
            }
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
        return new Bundle();
    }

    private String policy(final String id) throws FileNotFoundException {
        try {
            return new String(Files.readAllBytes(new File(directory(id), "policy").toPath()),
                    StandardCharsets.UTF_8);
        } catch (final IOException e) {
            throw new FileNotFoundException("Unknown fixture");
        }
    }

    @Override public Cursor queryDocument(final String id, final String[] projection)
            throws FileNotFoundException {
        if ("unavailable".equals(policy(id))) {
            throw new SecurityException("Controlled metadata refusal");
        }
        final String[] columns = projection == null ? new String[]{
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_FLAGS} : projection;
        final MatrixCursor cursor = new MatrixCursor(columns);
        final File file = new File(directory(id), "media.bin");
        if (file.isFile()) {
            final MatrixCursor.RowBuilder row = cursor.newRow();
            for (final String column : columns) {
                switch (column) {
                    case DocumentsContract.Document.COLUMN_DOCUMENT_ID:
                        row.add(id);
                        break;
                    case DocumentsContract.Document.COLUMN_DISPLAY_NAME:
                        row.add("controlled-" + id + ".bin");
                        break;
                    case DocumentsContract.Document.COLUMN_MIME_TYPE:
                        row.add("application/octet-stream");
                        break;
                    case DocumentsContract.Document.COLUMN_SIZE:
                        row.add(file.length());
                        break;
                    case DocumentsContract.Document.COLUMN_FLAGS:
                        row.add(DocumentsContract.Document.FLAG_SUPPORTS_DELETE);
                        break;
                    default:
                        row.add(null);
                }
            }
        }
        return cursor;
    }

    @Override public void deleteDocument(final String id) throws FileNotFoundException {
        final String mode = policy(id);
        if ("hold-before".equals(mode)) {
            barrier(id);
            // This case models a provider aborting the outstanding request after caller death.
            throw new SecurityException("Controlled abort before media removal");
        }
        if (!"allow".equals(mode) && !"hold-after".equals(mode)) {
            throw new SecurityException("Controlled deletion refusal");
        }
        try {
            Files.delete(new File(directory(id), "media.bin").toPath());
        } catch (final IOException e) {
            throw new FileNotFoundException("Fixture already absent");
        }
        if ("hold-after".equals(mode)) {
            barrier(id);
        }
    }

    private void barrier(final String id) throws FileNotFoundException {
        final File dir = directory(id);
        try {
            Files.write(new File(dir, "entered").toPath(), BYTES);
            final long deadline = SystemClock.uptimeMillis() + 120_000;
            while (!new File(dir, "release").isFile()) {
                if (SystemClock.uptimeMillis() >= deadline) {
                    throw new FileNotFoundException("Host did not release provider barrier");
                }
                SystemClock.sleep(50);
            }
        } catch (final IOException e) {
            throw new FileNotFoundException("Provider barrier failed: " + e);
        }
    }

    @Override public ParcelFileDescriptor openDocument(final String id, final String mode,
                                                       final CancellationSignal signal)
            throws FileNotFoundException {
        if (!"r".equals(mode)) {
            throw new FileNotFoundException("Read-only fixture");
        }
        return ParcelFileDescriptor.open(new File(directory(id), "media.bin"),
                ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public Cursor queryRoots(final String[] projection) {
        return new MatrixCursor(new String[]{DocumentsContract.Root.COLUMN_ROOT_ID});
    }

    @Override public Cursor queryChildDocuments(final String parentId, final String[] projection,
                                                final String sortOrder) {
        return new MatrixCursor(new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID});
    }
}
