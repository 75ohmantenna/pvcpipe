/*
 * SPDX-FileCopyrightText: 2026 PVCPipe contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package us.shandian.giga.io;

import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;

import androidx.core.content.FileProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/** Real local FileProvider descriptors; opt-in disposable-emulator checks, not cloud SAF tests. */
@RunWith(AndroidJUnit4.class)
public class FileStreamSAFEmulatorTest {
    private final List<ParcelFileDescriptor> owners = new ArrayList<>();
    private Context context;
    private File directory;

    @Before
    public void setup() throws IOException {
        final Bundle args = InstrumentationRegistry.getArguments();
        assumeTrue("Explicit disposable emulator required",
                "true".equals(args.getString("safDisposable")));
        assertTrue("Physical devices are refused",
                "ranchu".equals(Build.HARDWARE) || "goldfish".equals(Build.HARDWARE));
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertEquals("org.seventyfiveohmantenna.pvcpipe.debug", context.getPackageName());
        final String nonce = args.getString("safNonce");
        assertTrue(nonce != null && nonce.matches("[a-f0-9]{32}"));
        assertEquals(nonce, new String(Files.readAllBytes(
                new File(context.getFilesDir(), "saf-emulator-allow").toPath()),
                StandardCharsets.UTF_8));
        directory = new File(context.getCacheDir(), "share_preview/saf-close-" + UUID.randomUUID());
        assertTrue(directory.mkdirs());
    }

    @After
    public void cleanup() throws IOException {
        // Explicit owner cleanup also runs when an assertion fails before adapter close.
        try {
            for (final ParcelFileDescriptor owner : owners) {
                owner.close();
            }
        } finally {
            if (directory != null) {
                final File[] files = directory.listFiles();
                if (files != null) {
                    for (final File file : files) {
                        Files.deleteIfExists(file.toPath());
                    }
                }
                Files.deleteIfExists(directory.toPath());
            }
        }
    }

    @Test
    public void realSharedDescriptorClosesWithoutChangingFileBytes() throws Exception {
        exercise(false);
    }

    @Test
    public void inputCloseFailureStillReleasesRealDescriptor() throws Exception {
        exercise(true);
    }

    private void exercise(final boolean failInput) throws Exception {
        final File target = new File(directory, "target.bin");
        Files.write(target.toPath(), new byte[0]);
        final Uri uri = FileProvider.getUriForFile(context,
                context.getPackageName() + ".provider", target);
        try (FileStreamSAF stream = new FileStreamSAF(context.getContentResolver(), uri)) {
            final ParcelFileDescriptor owner = (ParcelFileDescriptor) field("file").get(stream);
            owners.add(owner);
            final FileDescriptor descriptor = owner.getFileDescriptor();
            final FileInputStream original = (FileInputStream) field("in").get(stream);
            final FailingInput replacement;
            if (failInput) {
                replacement = new FailingInput(descriptor);
                field("in").set(stream, replacement);
                original.close();
                assertTrue("Borrowed input must not close the owner", descriptor.valid());
            } else {
                replacement = null;
            }
            stream.write(new byte[]{10, 20, 30, 40});
            assertEquals(4, stream.length());
            stream.seek(1);
            assertEquals(20, stream.read());
            stream.setLength(3);
            stream.rewind();
            final byte[] bytes = new byte[3];
            assertEquals(3, stream.read(bytes));
            assertArrayEquals(new byte[]{10, 20, 30}, bytes);

            stream.close();

            assertTrue(stream.isClosed());
            assertFalse("Owning descriptor must be released", descriptor.valid());
            assertArrayEquals(bytes, Files.readAllBytes(target.toPath()));
            if (replacement != null) {
                assertEquals(1, replacement.closeAttempts);
            }
            final File witness = new File(directory, "witness.bin");
            try (ParcelFileDescriptor next = ParcelFileDescriptor.open(witness,
                    ParcelFileDescriptor.MODE_CREATE | ParcelFileDescriptor.MODE_READ_WRITE)) {
                stream.close();
                assertTrue("Repeated close must not close another descriptor",
                        next.getFileDescriptor().valid());
            }
        }
    }

    private static Field field(final String name) throws NoSuchFieldException {
        final Field field = FileStreamSAF.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static final class FailingInput extends FileInputStream {
        private int closeAttempts;

        private FailingInput(final FileDescriptor descriptor) {
            super(descriptor);
        }

        @Override
        public void close() throws IOException {
            closeAttempts++;
            throw new IOException("controlled input close failure");
        }
    }
}
