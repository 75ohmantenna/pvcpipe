package org.schabi.newpipe.streams;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

public class WebMWriterTest {
    @Test
    public void encodesKnownEbmlValues() throws Exception {
        assertArrayEquals(new byte[]{(byte) 0x80}, encode(0, false));
        assertArrayEquals(new byte[]{(byte) 0x81, 0}, encode(0, true));
        assertArrayEquals(new byte[]{(byte) 0xfe}, encode(126, false));
        assertArrayEquals(new byte[]{0x40, 0x7f}, encode(127, false));
        assertArrayEquals(new byte[]{(byte) 0x82, 0, 0x7f}, encode(127, true));
        assertArrayEquals(new byte[]{0x40, (byte) 0x80}, encode(128, false));
    }

    @Test
    public void preservesValuesAcrossEveryEncodingLengthBoundary() throws Exception {
        // Each EBML length contributes seven data bits; the all-ones value is reserved.
        final long[] boundaries = {128, 16384, 2097152, 268435456,
                34359738368L, 4398046511104L, 562949953421312L};
        for (int index = 0; index < boundaries.length; index++) {
            final long boundary = boundaries[index];
            assertEncoding(boundary - 2, index + 1);
            assertEncoding(boundary - 1, index + 2);
            if (index < boundaries.length - 1) {
                assertEncoding(boundary, index + 2);
                assertEncoding(boundary + 1, index + 2);
            }
        }
    }

    @Test
    public void rejectsValuesAtAndAboveSevenByteLimit() {
        for (final long value : new long[]{562949953421312L, Long.MAX_VALUE}) {
            for (final boolean withLength : new boolean[]{false, true}) {
                final InvocationTargetException error = assertThrows(
                        InvocationTargetException.class, () -> encode(value, withLength));
                assertTrue(error.getCause() instanceof ArithmeticException);
            }
        }
    }

    @Test
    public void preservesExistingNegativeValueEncoding() throws Exception {
        assertArrayEquals(new byte[]{(byte) 0xff}, encode(-1, false));
        assertArrayEquals(new byte[]{(byte) 0x81, (byte) 0xff}, encode(-1, true));
        assertArrayEquals(new byte[]{(byte) 0x80}, encode(Long.MIN_VALUE, false));
        assertArrayEquals(new byte[]{(byte) 0x81, 0}, encode(Long.MIN_VALUE, true));
    }

    private static void assertEncoding(final long value, final int length) throws Exception {
        final byte[] variableLength = encode(value, false);
        final byte[] withLength = encode(value, true);
        assertEquals(length, variableLength.length);
        assertEquals(length + 1, withLength.length);
        assertEquals(0x80 | length, withLength[0] & 0xff);

        // EBML stores the length as one plus the leading zero bits in the first byte.
        final int leadingZeroBits = Integer.numberOfLeadingZeros(variableLength[0] & 0xff)
                - (Integer.SIZE - Byte.SIZE);
        assertEquals(length, leadingZeroBits + 1);

        long decodedVariableLength = variableLength[0] & (0xff >>> length);
        long decodedWithLength = 0;
        for (int index = 1; index < variableLength.length; index++) {
            decodedVariableLength = (decodedVariableLength << 8) | (variableLength[index] & 0xff);
        }
        for (int index = 1; index < withLength.length; index++) {
            decodedWithLength = (decodedWithLength << 8) | (withLength[index] & 0xff);
        }
        assertEquals(value, decodedVariableLength);
        assertEquals(value, decodedWithLength);
    }

    private static byte[] encode(final long value, final boolean withLength) throws Exception {
        // Exercise the byte encoding directly without requiring media input or changing visibility.
        final Method method = WebMWriter.class
                .getDeclaredMethod("encode", long.class, boolean.class);
        method.setAccessible(true);
        return (byte[]) method.invoke(new WebMWriter(), value, withLength);
    }
}
