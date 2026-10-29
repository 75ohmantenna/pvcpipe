/*
 * SPDX-FileCopyrightText: 2026 PVCPipe contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package us.shandian.giga.io;

import android.os.ParcelFileDescriptor;
import android.util.Log;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.io.Closeable;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

/** Close-fault tests with controlled resources, not Android descriptor/provider evidence. */
public class FileStreamSAFCloseTest {
    private FileStreamSAF stream;
    private Closeable[] resources;
    private IOException[] failures;

    @Before
    public void setup() throws Exception {
        // Exercise the real close method without changing production construction for injection.
        stream = mock(FileStreamSAF.class, CALLS_REAL_METHODS);
        resources = new Closeable[]{mock(FileChannel.class), mock(FileInputStream.class),
                mock(FileOutputStream.class), mock(ParcelFileDescriptor.class)};
        failures = new IOException[]{new IOException("channel"), new IOException("input"),
                new IOException("output"), new IOException("descriptor")};
        final String[] names = {"channel", "in", "out", "file"};
        for (int i = 0; i < names.length; i++) {
            final Field field = FileStreamSAF.class.getDeclaredField(names[i]);
            field.setAccessible(true);
            field.set(stream, resources[i]);
        }
        assertFalse(stream.isClosed());
    }

    @Test
    public void normalCloseReleasesAllResourcesAndCanBeRepeated() throws Exception {
        closeWithFailures(0);
        clearInvocations((Object[]) resources);
        stream.close();
        verifyAllCloses();
    }

    @Test
    public void channelFailureDoesNotSkipTheOwningDescriptor() throws Exception {
        closeWithFailures(1);
    }

    @Test
    public void inputFailureDoesNotSkipOutputOrTheOwningDescriptor() throws Exception {
        closeWithFailures(2);
    }

    @Test
    public void outputFailureDoesNotSkipTheOwningDescriptor() throws Exception {
        closeWithFailures(4);
    }

    @Test
    public void descriptorFailureIsLoggedWithoutEscapingClose() throws Exception {
        closeWithFailures(8);
    }

    @Test
    public void allFailuresAreReportedWithTheFirstFailurePrimary() throws Exception {
        closeWithFailures(15);
    }

    @Test
    public void laterFailuresAreSuppressedWhenTheChannelClosesSuccessfully() throws Exception {
        closeWithFailures(14);
    }

    @Test
    public void uncheckedFailureStillClosesTheOwnerAndPropagates() throws Exception {
        final IllegalStateException unexpected = new IllegalStateException("channel failure");
        for (int i = 0; i < resources.length; i++) {
            final int index = i;
            doAnswer(call -> {
                assertTrue(stream.isClosed());
                if (index == 0) {
                    throw unexpected;
                }
                throw failures[index];
            }).when(resources[i]).close();
        }
        try (var logs = mockStatic(Log.class)) {
            assertSame(unexpected, assertThrows(IllegalStateException.class, stream::close));
            verifyAllCloses();
            assertArrayEquals(new Throwable[]{failures[1], failures[2], failures[3]},
                    unexpected.getSuppressed());
            logs.verifyNoInteractions();
        }
    }

    private void closeWithFailures(final int mask) throws Exception {
        for (int i = 0; i < resources.length; i++) {
            final int index = i;
            doAnswer(call -> {
                assertTrue(stream.isClosed());
                if ((mask & (1 << index)) != 0) {
                    throw failures[index];
                }
                return null;
            }).when(resources[i]).close();
        }
        try (var logs = mockStatic(Log.class)) {
            stream.close();
            verifyAllCloses();
            assertTrue(stream.isClosed());
            if (mask == 0) {
                logs.verifyNoInteractions();
            } else {
                final int first = Integer.numberOfTrailingZeros(mask);
                final ArgumentCaptor<Throwable> logged = ArgumentCaptor.forClass(Throwable.class);
                logs.verify(() -> Log.e(eq("FileStreamSAF"), eq("close() error"),
                        logged.capture()));
                assertSame(failures[first], logged.getValue());
                final List<Throwable> suppressed = new ArrayList<>();
                for (int i = first + 1; i < resources.length; i++) {
                    if ((mask & (1 << i)) != 0) {
                        suppressed.add(failures[i]);
                    }
                }
                assertArrayEquals(suppressed.toArray(new Throwable[0]),
                        logged.getValue().getSuppressed());
                logs.verifyNoMoreInteractions();
            }
        }
    }

    private void verifyAllCloses() throws IOException {
        final InOrder order = inOrder((Object[]) resources);
        for (final Closeable resource : resources) {
            order.verify(resource).close();
        }
        order.verifyNoMoreInteractions();
    }
}
