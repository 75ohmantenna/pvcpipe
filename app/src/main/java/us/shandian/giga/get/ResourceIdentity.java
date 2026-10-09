package us.shandian.giga.get;

import java.io.IOException;
import java.io.Serializable;
import java.net.HttpURLConnection;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.regex.Pattern;

/** A strong HTTP validator bound to both the requested and the effective resource URI. */
final class ResourceIdentity implements Serializable {
    private static final long serialVersionUID = 1L;
    private static final Pattern STRONG_ETAG =
            Pattern.compile("\"[\\x21\\x23-\\x7E\\x80-\\xFF]*\"");

    final String condition;
    final String requestUrl;
    final String effectiveUrl;

    private ResourceIdentity(final String condition, final String requestUrl,
                             final String effectiveUrl) {
        this.condition = condition;
        this.requestUrl = requestUrl;
        this.effectiveUrl = effectiveUrl;
    }

    /**
     * Returns a validator tied to the request and effective resource.
     * An HTTP date is usable only without an ETag and with evidence that it is strong.
     *
     * @param response the initial HTTP response
     * @param requestUrl the requested resource URI
     * @return the identity, or null if no strong validator is available
     */
    static ResourceIdentity from(final HttpURLConnection response, final String requestUrl) {
        final String etag = response.getHeaderField("ETag");
        final String condition;
        if (etag != null) {
            final String trimmed = etag.trim();
            if (!STRONG_ETAG.matcher(trimmed).matches()) {
                return null;
            }
            condition = trimmed;
        } else {
            final String lastModified = response.getHeaderField("Last-Modified");
            final String date = response.getHeaderField("Date");
            if (lastModified == null || date == null) {
                return null;
            }
            try {
                final Instant modified = ZonedDateTime.parse(lastModified.trim(),
                        DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
                final Instant issued = ZonedDateTime.parse(date.trim(),
                        DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
                if (issued.isBefore(modified.plusSeconds(1))) {
                    return null;
                }
            } catch (final DateTimeException error) {
                return null;
            }
            condition = lastModified.trim();
        }
        return new ResourceIdentity(condition, requestUrl, response.getURL().toExternalForm());
    }

    void verify(final HttpURLConnection response, final String requestedUrl,
                final String sentIfRange) throws IOException {
        if (!this.requestUrl.equals(requestedUrl)
                || !effectiveUrl.equals(response.getURL().toExternalForm())) {
            throw new ValidatedRange.InvalidRangeException(
                    "Ranged response redirected to a different resource; restart required");
        }
        final String header = response.getHeaderField(condition.startsWith("\"")
                ? "ETag" : "Last-Modified");
        if (header == null ? !condition.equals(sentIfRange)
                : !condition.equals(header.trim())) {
            throw new ValidatedRange.InvalidRangeException(
                    "Ranged response has a different resource validator; restart required");
        }
    }
}
