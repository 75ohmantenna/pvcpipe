package org.schabi.newpipe.extractor.downloader;

import java.io.IOException;
import java.net.CookieManager;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Stores response cookies only from {@code rumble.com} and its subdomains.
 * <p>
 * Installed through {@code JavaNetCookieJar} in the app and live-test HTTP clients so cookies
 * can participate in redirect handling before a response reaches the extractor. The inherited
 * {@link CookieManager} handles request-cookie selection from its store.
 * <p>
 * A Rumble redirect problem was reported on 2024-03-02, referenced in the pre-rebrand source as
 * <a href="https://github.com/bravenewpipe/NewPipeExtractor/issues/123">issue #123</a>.
 * That historical report does not establish that every Rumble request needs cookies.
 */
public class PvcCookieManager extends CookieManager {

    @Override
    public void put(
            final URI uri,
            final Map<String, List<String>> responseHeaders) throws IOException {
        final String host = uri.getHost();
        if (host != null && (host.equalsIgnoreCase("rumble.com")
                || host.toLowerCase(Locale.ROOT).endsWith(".rumble.com"))) {
            super.put(uri, responseHeaders);
        }
    }
}
