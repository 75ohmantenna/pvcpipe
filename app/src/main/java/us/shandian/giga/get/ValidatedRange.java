package us.shandian.giga.get;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The bytes a 206 response promises to deliver, checked against the requested destination. */
final class ValidatedRange {
    private static final Pattern CONTENT_RANGE =
            Pattern.compile("bytes ([0-9]+)-([0-9]+)/([0-9]+|\\*)");

    final long end;
    final long total;

    private ValidatedRange(final long end, final long total) {
        this.end = end;
        this.total = total;
    }

    static void requireIdentityEncoding(final HttpURLConnection connection)
            throws InvalidRangeException {
        final String encoding = connection.getHeaderField("Content-Encoding");
        if (encoding != null && !encoding.trim().equalsIgnoreCase("identity")) {
            throw new InvalidRangeException(
                    "Encoded media response cannot be written at byte offsets: " + encoding);
        }
    }

    static ValidatedRange from(final HttpURLConnection connection, final long requestedStart,
                               final long requestedEnd, final long expectedTotal)
            throws IOException {
        final String header = connection.getHeaderField("Content-Range");
        final Matcher match = header == null ? null : CONTENT_RANGE.matcher(header);
        if (match == null || !match.matches()) {
            throw new InvalidRangeException("Missing or malformed Content-Range: " + header);
        }
        final long start;
        final long end;
        final long total;
        try {
            start = Long.parseLong(match.group(1));
            end = Long.parseLong(match.group(2));
            total = "*".equals(match.group(3)) ? -1 : Long.parseLong(match.group(3));
        } catch (final NumberFormatException error) {
            throw new InvalidRangeException("Invalid Content-Range number: " + header);
        }
        if (requestedStart < 0 || start != requestedStart || end < start
                || end == Long.MAX_VALUE || (requestedEnd >= 0 && end > requestedEnd)
                || (total >= 0 && (total == 0 || end >= total))
                || (expectedTotal > 0 && total != expectedTotal)) {
            throw new InvalidRangeException("Content-Range does not match request: " + header);
        }
        final long contentLength = connection.getContentLengthLong();
        if (contentLength >= 0 && contentLength != end - start + 1) {
            throw new InvalidRangeException(
                    "Content-Length disagrees with Content-Range: " + header);
        }
        return new ValidatedRange(end, total);
    }

    static final class InvalidRangeException extends IOException {
        InvalidRangeException(final String message) {
            super(message);
        }
    }
}
