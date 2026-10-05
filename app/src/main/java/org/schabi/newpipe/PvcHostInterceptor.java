package org.schabi.newpipe;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;

import androidx.annotation.NonNull;
import okhttp3.HttpUrl;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Request;

/**
 * This interceptor allows to replace a hostname with another on the fly.
 * Useful to get around stupid censorship.
 */
public class PvcHostInterceptor implements Interceptor {
    private volatile Map<String, String> replaceHosts;

    public PvcHostInterceptor(final Map<String, String> hosts) {
        setHosts(hosts);
    }

    public static Optional<Interceptor> getInterceptor(
            final OkHttpClient.Builder builder) {
        return builder.interceptors().stream().filter(
                PvcHostInterceptor.class::isInstance).findFirst();
    }

    public void setHosts(final Map<String, String> hosts) {
        this.replaceHosts = hosts;
    }

    @NonNull
    @Override
    public okhttp3.Response intercept(final Chain chain) throws IOException {
        final Request request = chain.request();
        final String newHostName = replaceHosts.get(request.url().host());

        if (newHostName != null) {
            final HttpUrl newUrl = request.url().newBuilder()
                    .host(newHostName)
                    .build();
            final Request newRequest = request.newBuilder()
                    .url(newUrl)
                    .build();
            return chain.proceed(newRequest);
        }

        return chain.proceed(request);
    }
}
