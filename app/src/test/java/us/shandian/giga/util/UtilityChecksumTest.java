package us.shandian.giga.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.Test;
import org.schabi.newpipe.R;
import org.schabi.newpipe.streams.io.SharpStream;
import org.schabi.newpipe.streams.io.StoredFileHelper;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

public class UtilityChecksumTest {
    @Test
    public void emptyFilesHaveStandardDigests() throws IOException {
        assertChecksum(new byte[0], R.id.md5, "d41d8cd98f00b204e9800998ecf8427e");
        assertChecksum(new byte[0], R.id.sha1, "da39a3ee5e6b4b0d3255bfef95601890afd80709");
    }

    @Test
    public void outputIsLowercaseAndRetainsLeadingZeroes() throws IOException {
        final byte[] content = "a".getBytes(StandardCharsets.US_ASCII);
        assertChecksum(content, R.id.md5, "0cc175b9c0f1b6a831c399e269772661");
        assertChecksum(content, R.id.sha1, "86f7e437faa5a7fce15d1ddcb9eaeaea377667b8");
    }

    @Test
    public void fragmentedBinaryReadsHashOnlyBytesReadAcrossBuffers() throws IOException {
        final byte[] content = new byte[20003];
        for (int i = 0; i < content.length; i++) {
            content[i] = (byte) (i % 251);
        }
        assertChecksum(content, R.id.md5, "fa3eef8108163c8bdf68321f06828138");
        assertChecksum(content, R.id.sha1, "f773d1f713700bfe6ec39d550913d69e5c053885");
    }

    @Test
    public void readFailurePropagatesAndClosesTheFile() throws IOException {
        final StoredFileHelper source = mock(StoredFileHelper.class);
        final SharpStream stream = mock(SharpStream.class);
        when(source.getStream()).thenReturn(stream);
        when(stream.canRead()).thenReturn(true);
        final IOException failure = new IOException("read failed");
        when(stream.read(any(byte[].class))).thenReturn(7).thenThrow(failure);

        assertSame(failure, assertThrows(IOException.class,
                () -> Utility.checksum(source, R.id.md5)));
        verify(stream).close();
    }

    @Test
    public void unsupportedAlgorithmIsNotTreatedAsAFileHexDump() {
        assertThrows(IllegalArgumentException.class,
                () -> Utility.checksum(mock(StoredFileHelper.class), -1));
    }

    private static void assertChecksum(final byte[] content, final int algorithm,
                                       final String expected) throws IOException {
        final StoredFileHelper source = mock(StoredFileHelper.class);
        final SharpStream stream = mock(SharpStream.class);
        final ByteArrayInputStream input = new ByteArrayInputStream(content);
        when(source.getStream()).thenReturn(stream);
        when(stream.canRead()).thenReturn(true);
        // A file/provider may return fewer bytes than requested, including a final partial buffer.
        when(stream.read(any(byte[].class))).thenAnswer(invocation -> {
            final byte[] buffer = invocation.getArgument(0);
            return input.read(buffer, 0, Math.min(buffer.length, 7));
        });
        when(stream.read(any(byte[].class), anyInt(), anyInt())).thenAnswer(invocation ->
                input.read(invocation.getArgument(0), invocation.getArgument(1),
                        Math.min((int) invocation.getArgument(2), 7)));

        assertEquals(expected, Utility.checksum(source, algorithm));
        verify(stream).close();
    }
}
