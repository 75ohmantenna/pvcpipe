package org.schabi.newpipe.extractor.settings;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ServiceSettings {

    // Written by the client's settings screen while extractor threads read it.
    private final Map<Integer, Boolean> settings = new ConcurrentHashMap<>();

    public void setting(final boolean enable, final int setting) {
        if (enable) {
            settings.put(setting, true);
        } else {
            settings.remove(setting);
        }
    }

    public boolean isSettingEnabled(final int setting) {
        return settings.get(setting) != null;
    }
}
