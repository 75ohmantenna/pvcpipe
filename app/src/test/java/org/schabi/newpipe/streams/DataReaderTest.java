package org.schabi.newpipe.streams;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.Test;
import org.schabi.newpipe.streams.io.SharpStream;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Random;

public class DataReaderTest {
    @Test
    public void readsSignedLongsWithoutExtendingTheLowWord() throws IOException {
        final long[] values = {0, 1, -1, Integer.MAX_VALUE, 0x80000000L, 0xffffffffL,
                0x100000000L, Long.MIN_VALUE, Long.MAX_VALUE, 0x1234567880000000L,
                0x8765432180000000L};
        assertReads(values);
    }

    @Test
    public void matchesByteBufferAcrossTheReadBufferBoundary() throws IOException {
        final Random random = new Random(75);
        final long[] values = new long[16_385];
        for (int i = 0; i < values.length; i++) {
            values[i] = random.nextLong();
        }
        assertReads(values);
    }

    @Test(expected = EOFException.class)
    public void rejectsTruncatedLongs() throws IOException {
        reader(new byte[7]).readLong();
    }

    private static void assertReads(final long[] values) throws IOException {
        final ByteBuffer bytes = ByteBuffer.allocate(values.length * Long.BYTES);
        for (final long value : values) {
            bytes.putLong(value);
        }
        final DataReader reader = reader(bytes.array());
        for (final long value : values) {
            assertEquals(value, reader.readLong());
        }
        assertEquals(bytes.capacity(), reader.position());
    }

    private static DataReader reader(final byte[] bytes) throws IOException {
        final ByteArrayInputStream input = new ByteArrayInputStream(bytes);
        final SharpStream stream = mock(SharpStream.class);
        when(stream.read(any(byte[].class))).thenAnswer(invocation -> {
            final byte[] buffer = invocation.getArgument(0);
            return input.read(buffer, 0, buffer.length);
        });
        return new DataReader(stream);
    }
}
