package org.schabi.newpipe.downloader;

import org.schabi.newpipe.extractor.downloader.PvcCookieManager;


import okhttp3.JavaNetCookieJar;
import okhttp3.OkHttpClient;

final class PvcDownloaderTestImplUtils {

    private PvcDownloaderTestImplUtils() {
    }

    /**
     * Installs the same Rumble response-cookie policy as the app's
     * {@code PvcDownloaderImplUtils.addCookieManager}, including during redirects.
     *
     * @param theBuilder the builder
     */
    public static void addCookieManager(final OkHttpClient.Builder theBuilder) {
        final PvcCookieManager cookieManager = new PvcCookieManager();
        theBuilder.cookieJar(new JavaNetCookieJar(cookieManager));
    }
}
