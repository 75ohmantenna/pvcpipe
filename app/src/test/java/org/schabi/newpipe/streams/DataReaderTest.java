package org.schabi.newpipe.streams;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.Test;
import org.schabi.newpipe.streams.io.SharpStream;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
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

    @Test
    public void readsMixedPrimitiveWidthsAndUnsignedInts() throws IOException {
        final ByteBuffer bytes = ByteBuffer.allocate(26);
        bytes.putLong(Long.MIN_VALUE).putShort(Short.MIN_VALUE).putInt(Integer.MIN_VALUE)
                .putShort(Short.MAX_VALUE).putInt(-1).putInt(Integer.MAX_VALUE)
                .putShort((short) -1);
        final DataReader reader = reader(bytes.array());

        assertEquals(Long.MIN_VALUE, reader.readLong());
        assertEquals(Short.MIN_VALUE, reader.readShort());
        assertEquals(0x80000000L, reader.readUnsignedInt());
        assertEquals(Short.MAX_VALUE, reader.readShort());
        assertEquals(0xffffffffL, reader.readUnsignedInt());
        assertEquals(Integer.MAX_VALUE, reader.readInt());
        assertEquals(-1, reader.readShort());
        assertEquals(bytes.capacity(), reader.position());
    }

    @Test
    public void readsPrimitiveSplitAcrossTheReadBufferBoundary() throws IOException {
        final int bufferSize = 128 * 1024;
        final ByteBuffer bytes = ByteBuffer.allocate(bufferSize + 7);
        bytes.position(bufferSize - 1);
        bytes.putLong(0x8765432180000000L);
        final DataReader reader = reader(bytes.array());

        assertEquals(bufferSize - 1, reader.read(new byte[bufferSize - 1]));
        assertEquals(0x8765432180000000L, reader.readLong());
        assertEquals(bytes.capacity(), reader.position());
    }

    @Test
    public void reportsTruncationAfterACompletePrimitive() throws IOException {
        final ByteBuffer bytes = ByteBuffer.allocate(11);
        bytes.putLong(-1).put(new byte[]{1, 2, 3});
        final DataReader reader = reader(bytes.array());

        assertEquals(-1, reader.readLong());
        final EOFException exception = assertThrows(EOFException.class, reader::readInt);
        assertEquals("Truncated stream, missing 1 bytes", exception.getMessage());
        assertEquals(bytes.capacity(), reader.position());
    }

    @Test
    public void zeroBytesCountTowardsTheViewLimit() throws IOException {
        final DataReader reader = reader(new byte[]{0, 42});
        final InputStream view = reader.getView(1);
        assertEquals(0, view.read());
        assertEquals(-1, view.read());
        assertEquals(0, view.available());
        assertEquals(1, reader.position());
        assertEquals(42, reader.read());
    }

    @Test
    public void bulkViewReadsStopAtTheLimit() throws IOException {
        final DataReader reader = reader(new byte[]{0, 1, 2, 3});
        final InputStream view = reader.getView(3);
        final byte[] buffer = new byte[5];
        assertEquals(3, view.read(buffer, 1, 4));
        assertArrayEquals(new byte[]{0, 0, 1, 2, 0}, buffer);
        assertEquals(-1, view.read(buffer));
        assertEquals(3, reader.read());
    }

    @Test
    public void truncatedViewsReturnEofWithoutGrowingTheirLimit() throws IOException {
        final DataReader reader = reader(new byte[]{0, 1});
        final InputStream view = reader.getView(5);
        final byte[] buffer = new byte[8];
        assertEquals(2, view.read(buffer));
        assertEquals(-1, view.read(buffer));
        assertEquals(-1, view.read());
        assertEquals(0, view.available());
        assertEquals(2, reader.position());
    }

    @Test
    public void emptySourcesReturnEofForBothViewReadForms() throws IOException {
        final DataReader reader = reader(new byte[0]);
        assertEquals(-1, reader.getView(1).read(new byte[1]));
        assertEquals(-1, reader.getView(1).read());
    }

    @Test
    public void zeroLengthViewReadsReturnZeroEvenAtEof() throws IOException {
        final InputStream view = reader(new byte[0]).getView(0);
        assertEquals(0, view.read(new byte[0]));
        assertEquals(0, view.read(new byte[1], 1, 0));
        assertEquals(-1, view.read());
    }

    @Test
    public void viewReadArgumentsAreValidatedEvenAtEof() throws IOException {
        final InputStream view = reader(new byte[0]).getView(0);
        assertThrows(NullPointerException.class, () -> view.read(null, 0, 0));
        assertThrows(IndexOutOfBoundsException.class, () -> view.read(new byte[1], -1, 0));
        assertThrows(IndexOutOfBoundsException.class, () -> view.read(new byte[1], 0, -1));
        assertThrows(IndexOutOfBoundsException.class, () -> view.read(new byte[1], 1, 1));
        assertThrows(IndexOutOfBoundsException.class,
                () -> view.read(new byte[1], Integer.MAX_VALUE, Integer.MAX_VALUE));
    }

    @Test
    public void viewSkipsIgnoreNegativeAmountsAndRespectTheLimit() throws IOException {
        final DataReader reader = reader(new byte[]{0, 1, 2, 3});
        final InputStream view = reader.getView(3);
        assertEquals(0, view.read());
        assertEquals(0, view.skip(-1));
        assertEquals(0, view.skip(Long.MIN_VALUE));
        assertEquals(1, reader.position());
        assertEquals(2, view.skip(Long.MAX_VALUE));
        assertEquals(-1, view.read());
        assertEquals(3, reader.read());
    }

    @Test
    public void shortUnderlyingReadsAreNotMistakenForEof() throws IOException {
        final byte[] bytes = {0, 1, 2, 3, 4, 5, 6, 7, 8};
        final DataReader reader = reader(bytes, 2);
        final InputStream view = reader.getView(bytes.length);
        for (final byte value : bytes) {
            assertEquals(value, view.read());
        }
        assertEquals(-1, view.read());
        assertEquals(bytes.length, reader.position());
    }

    @Test
    public void primitivesCanSpanMultipleShortUnderlyingReads() throws IOException {
        final ByteBuffer bytes = ByteBuffer.allocate(8).putLong(0x8765432180000000L);
        assertEquals(0x8765432180000000L, reader(bytes.array(), 2).readLong());
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
        return reader(bytes, Integer.MAX_VALUE);
    }

    private static DataReader reader(final byte[] bytes, final int maxRead) throws IOException {
        final ByteArrayInputStream input = new ByteArrayInputStream(bytes);
        final SharpStream stream = mock(SharpStream.class);
        when(stream.read(any(byte[].class))).thenAnswer(invocation -> {
            final byte[] buffer = invocation.getArgument(0);
            return input.read(buffer, 0, Math.min(buffer.length, maxRead));
        });
        when(stream.read(any(byte[].class), anyInt(), anyInt())).thenAnswer(invocation ->
                input.read(invocation.getArgument(0), invocation.getArgument(1),
                        Math.min(invocation.getArgument(2), maxRead)));
        when(stream.skip(anyLong())).thenAnswer(invocation ->
                input.skip(invocation.getArgument(0)));
        return new DataReader(stream);
    }
}
