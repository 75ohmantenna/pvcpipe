package org.schabi.newpipe.extractor.exceptions;

import java.io.IOException;

/**
 * An unsuccessful HTTP response. Only the status is retained for diagnostics.
 */
public final class HttpResponseException extends IOException {
    private final int responseCode;

    public HttpResponseException(final int responseCode) {
        super("HTTP " + responseCode + " while loading data");
        this.responseCode = responseCode;
    }

    public int responseCode() {
        return responseCode;
    }
}
