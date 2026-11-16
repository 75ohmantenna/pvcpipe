package org.schabi.newpipe.extractor.exceptions;

/**
 * A service refused an API request with a traffic/security response. This does not establish
 * the cause of the refusal, require sign-in, or provide a solvable challenge URL.
 */
public class RequestBlockedException extends ParsingException {
    public RequestBlockedException() {
        super("The service refused the API request with a traffic/security response");
    }
}
