package org.schabi.newpipe.extractor.services.odysee.settings;

import org.schabi.newpipe.extractor.settings.ServiceSettings;

public final class OdyseeSettings extends ServiceSettings {
    /** Leave videos that have to be bought, rented or need a membership out of lists. */
    public static final int HIDE_PAID_STREAMS = 1;
    /**
     * Show view counts and follower counts. Odysee gives these only to a client that has
     * registered an anonymous visitor token, so switching this on makes the extractor register
     * one. See {@link org.schabi.newpipe.extractor.services.odysee.OdyseeAuth}.
     */
    public static final int SHOW_COUNTS = 2;

    private static final OdyseeSettings INSTANCE = new OdyseeSettings();

    private OdyseeSettings() {
    }

    public static OdyseeSettings getInstance() {
        return INSTANCE;
    }
}
