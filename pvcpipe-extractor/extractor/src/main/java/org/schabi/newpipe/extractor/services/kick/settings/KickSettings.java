package org.schabi.newpipe.extractor.services.kick.settings;

import org.schabi.newpipe.extractor.settings.ServiceSettings;

public final class KickSettings extends ServiceSettings {
    public static final int HIDE_PAID_STREAMS = 1;
    private static final KickSettings INSTANCE = new KickSettings();

    private KickSettings() {
    }

    public static KickSettings getInstance() {
        return INSTANCE;
    }
}
