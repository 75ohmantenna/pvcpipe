package org.schabi.newpipe;

import android.content.Context;
import android.content.SharedPreferences;

import org.schabi.newpipe.pvc.feature.challenge.PvcCfChallenge403Interceptor;

import com.github.evermindzz.challengefloatsaway.manager.ChallengeManagerInterface;
import com.github.evermindzz.challengefloatsaway.manager.ChallengeServiceManager;

import org.schabi.newpipe.extractor.downloader.PvcCookieManager;
import org.schabi.newpipe.util.PermissionHelper;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;
import okhttp3.Dns;
import okhttp3.Interceptor;
import okhttp3.JavaNetCookieJar;
import okhttp3.OkHttpClient;

import static org.schabi.newpipe.DownloaderImpl.USER_AGENT;

/**
 * Used for code that only exists in PVCPipe and is used
 * within the {@link DownloaderImpl}.
 */
public final class PvcDownloaderImplUtils {
    public static final Config CONFIG = new Config();

    private PvcDownloaderImplUtils() {
    }

    // some servers eg rumble do not allow HEAD requests anymore (discovered 202300203)
    public static long getContentLengthViaGet(final String url) throws IOException {
        final okhttp3.Request request = new okhttp3.Request.Builder().url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "*/*")
                .header("Accept-Encoding", "*")
                .build();
        try (okhttp3.Response response = DownloaderImpl.getInstance().getClient()
                .newCall(request).execute()) {
            return Long.parseLong(response.header("Content-Length"));
        }
    }

    public static void addOrRemoveInterceptors(final OkHttpClient.Builder builder) {
        final Context context = App.getInstance().getApplicationContext();
        final SharedPreferences settings = PreferenceManager.getDefaultSharedPreferences(context);

        addOrRemoveHostInterceptor(builder, context, settings);
        addOrRemoveRumbleCloudflareInterceptor(builder, context, settings);
        addOrRemoveTimeoutInterceptor(builder, context, settings);
        if (builder.interceptors().stream().noneMatch(LocalNetworkInterceptor.class::isInstance)) {
            builder.dns(new LocalNetworkDns(context));
            builder.addInterceptor(new LocalNetworkInterceptor(context));
        }
        if (builder.networkInterceptors().stream()
                .noneMatch(LocalNetworkInterceptor.class::isInstance)) {
            builder.addNetworkInterceptor(new LocalNetworkInterceptor(context));
        }
    }

    /**
     * Resolves whether a custom endpoint host targets the local network.
     * Callers must run this off the main thread.
     *
     * @param host the endpoint host to resolve
     * @return true when the host resolves to a local network address
     */
    public static boolean requiresLocalNetwork(final String host) throws UnknownHostException {
        if (isLocalHost(host)) {
            return true;
        }
        for (final InetAddress address : Dns.SYSTEM.lookup(host)) {
            if (isLocalAddress(address)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isLocalHost(final String host) {
        final String lower = host.toLowerCase(Locale.ROOT);
        final String normalized = lower.endsWith(".")
                ? lower.substring(0, lower.length() - 1) : lower;
        if (normalized.equals("localhost") || normalized.endsWith(".localhost")
                || normalized.equals("local") || normalized.endsWith(".local")) {
            // mDNS itself needs permission, so do not resolve .local before the prompt.
            return true;
        }
        final String literal = normalized.startsWith("[") && normalized.endsWith("]")
                ? normalized.substring(1, normalized.length() - 1) : normalized;
        final InetAddress address = parseNumericAddress(literal);
        return address != null && isLocalAddress(address);
    }

    @Nullable
    private static InetAddress parseNumericAddress(final String literal) {
        final boolean isIpv4 = literal.matches("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}");
        final boolean isIpv6 = literal.indexOf(':') >= 0;
        if (!isIpv4 && !isIpv6) {
            return null;
        }
        try {
            return InetAddress.getByName(literal);
        } catch (final UnknownHostException ignored) {
            return null;
        }
    }

    private static boolean isLocalAddress(final InetAddress address) {
        final byte[] bytes = address.getAddress();
        return address.isAnyLocalAddress() || address.isLoopbackAddress()
                || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                || address.isMulticastAddress()
                // InetAddress.isSiteLocalAddress does not include IPv6 unique-local addresses.
                || (bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc);
    }

    private static void requireLocalNetworkPermission(final Context context)
            throws UnknownHostException {
        if (!PermissionHelper.hasLocalNetworkPermission(context)) {
            throw new UnknownHostException(context.getString(
                    R.string.local_network_permission_required));
        }
    }

    private static final class LocalNetworkDns implements Dns {
        private final Context context;

        private LocalNetworkDns(final Context context) {
            this.context = context;
        }

        @NonNull
        @Override
        public List<InetAddress> lookup(@NonNull final String hostname)
                throws UnknownHostException {
            if (isLocalHost(hostname)) {
                requireLocalNetworkPermission(context);
            }
            final List<InetAddress> addresses = Dns.SYSTEM.lookup(hostname);
            for (final InetAddress address : addresses) {
                if (isLocalAddress(address)) {
                    requireLocalNetworkPermission(context);
                }
            }
            return addresses;
        }
    }

    private static final class LocalNetworkInterceptor implements Interceptor {
        private final Context context;

        private LocalNetworkInterceptor(final Context context) {
            this.context = context;
        }

        @NonNull
        @Override
        public okhttp3.Response intercept(@NonNull final Chain chain) throws IOException {
            // Literals bypass OkHttp DNS; pooled connections must also detect revocation.
            if (isLocalHost(chain.request().url().host())
                    || (chain.connection() != null
                    && isLocalAddress(chain.connection().socket().getInetAddress()))) {
                requireLocalNetworkPermission(context);
            }
            return chain.proceed(chain.request());
        }
    }

    private static void addOrRemoveRumbleCloudflareInterceptor(
            final OkHttpClient.Builder builder,
            final Context context,
            final SharedPreferences settings) {

        final Optional<Interceptor> rumbleInterceptor =
                PvcCfChallenge403Interceptor.getInterceptor(builder);
        final boolean isHandleCloudflareChallengeEnabled = settings.getBoolean(context.getString(
                R.string.pvc_settings_handle_cloudflare_challenge_enable_key), false);

        if (isHandleCloudflareChallengeEnabled) {
            if (rumbleInterceptor.isEmpty()) {
                final ChallengeManagerInterface manager = new ChallengeServiceManager(
                        App.getInstance().getApplicationContext());
                builder.addInterceptor(new PvcCfChallenge403Interceptor(manager));
            }
        } else {
            rumbleInterceptor.ifPresent(interceptor -> {
                if (interceptor instanceof PvcCfChallenge403Interceptor) {
                    ((PvcCfChallenge403Interceptor) interceptor).cleanupBeforeDestroy();
                }
                builder.interceptors().remove(interceptor);

            });
        }
    }

    public static void addOrRemoveHostInterceptor(
            final OkHttpClient.Builder builder,
            final Context context,
            final SharedPreferences settings) {

        final Set<String> selectedHosts = settings.getStringSet(
                context.getString(R.string.pvc_settings_host_replace_key),
                Collections.emptySet());

        final Optional<Interceptor> hostInterceptor = PvcHostInterceptor.getInterceptor(builder);
        if (selectedHosts.isEmpty()) {
            hostInterceptor.ifPresent(interceptor -> builder.interceptors().remove(interceptor));
        } else {
            final Map<String, String> replaceHosts = new HashMap<>();
            for (final String oldAndNewHost : selectedHosts) {
                final String[] result = oldAndNewHost.split(":");
                replaceHosts.put(result[0], result[1]);
            }

            if (hostInterceptor.isPresent()) {
                ((PvcHostInterceptor) hostInterceptor.get()).setHosts(replaceHosts);
            } else {
                builder.addInterceptor(new PvcHostInterceptor(replaceHosts));
            }
        }
    }

    private static void addOrRemoveTimeoutInterceptor(
            final OkHttpClient.Builder builder,
            final Context context,
            final SharedPreferences settings) {

        final boolean isClientForSponsorblockingOrReturnDislikesEnabled = (settings.getBoolean(
                context.getString(R.string.sponsor_block_enable_key), false)
                || settings.getBoolean(
                context.getString(R.string.enable_return_youtube_dislike_key), false));

        final Optional<Interceptor> timeoutInterceptor =
                PvcTimeoutInterceptor.getInterceptor(builder);
        if (isClientForSponsorblockingOrReturnDislikesEnabled) {
            if (timeoutInterceptor.isEmpty()) {
                builder.addInterceptor(new PvcTimeoutInterceptor());
            }
        } else {
            timeoutInterceptor.ifPresent(interceptor -> builder.interceptors().remove(interceptor));
        }
    }

    /**
     * Rumble needs to handle cookies to correctly redirect.
     *
     * It was reported in
     * <a href="https://github.com/75ohmantenna/pvcpipe-extractor/issues/123">issue#123</a>
     * even though it seems it was only temporary Rumble glitch this functionality is added here.
     *
     * @param theBuilder the builder
     */
    public static void addCookieManager(final OkHttpClient.Builder theBuilder) {
        final PvcCookieManager cookieManager = new PvcCookieManager();
        theBuilder.cookieJar(new JavaNetCookieJar(cookieManager));
    }

    /**
     * Listen to the SharedPreferences and handle if replacing hosts or sponsorblock are enabled.
     */
    public static class Config implements SharedPreferences.OnSharedPreferenceChangeListener {

        public void registerOnChanged(@NonNull final Context context) {
            PreferenceManager.getDefaultSharedPreferences(context)
                    .registerOnSharedPreferenceChangeListener(this);
        }

        @Override
        public void onSharedPreferenceChanged(
                final SharedPreferences settings, final String configOption) {

            if (configOption == null) {
                return;
            }

            final Context context = App.getInstance().getApplicationContext();
            if (configOption.equals(
                    context.getString(R.string.pvc_settings_host_replace_key))
                    || configOption.equals(context.getString(
                    R.string.pvc_settings_handle_cloudflare_challenge_enable_key))
                    || configOption.equals(
                    context.getString(R.string.sponsor_block_enable_key))
                    || configOption.equals(
                    context.getString(R.string.enable_return_youtube_dislike_key))) {

                DownloaderImpl.getInstance().reInitInterceptors();
            }
        }

        public void unregisterOnChanged(@NonNull final Context context) {
            PreferenceManager.getDefaultSharedPreferences(context)
                    .unregisterOnSharedPreferenceChangeListener(this);
        }
    }
}
