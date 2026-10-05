package org.schabi.newpipe.downloader;

import org.schabi.newpipe.extractor.downloader.PvcCookieManager;


import okhttp3.JavaNetCookieJar;
import okhttp3.OkHttpClient;

final class PvcDownloaderTestImplUtils {

    private PvcDownloaderTestImplUtils() {
    }

    /**
     * Rumble needs to handle cookies to correctly redirect. It was
     *
     * Reported in
     * <a href="https://github.com/pvcpipe/NewPipeExtractor/issues/123">issue#123</a>
     * even though it seems it was only temporary Rumble glitch this functionality is added here.
     *
     * Note: this code is duplicated from NewPipe#PvcDownloaderImplUtils class
     *
     * @param theBuilder the builder
     */
    public static void addCookieManager(final OkHttpClient.Builder theBuilder) {
        final PvcCookieManager cookieManager = new PvcCookieManager();
        theBuilder.cookieJar(new JavaNetCookieJar(cookieManager));
    }
}
