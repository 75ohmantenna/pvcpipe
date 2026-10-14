package org.schabi.newpipe.pvc.feature.challenge;

import android.util.Log;

import com.github.evermindzz.challengefloatsaway.ChallengeResult;
import com.github.evermindzz.challengefloatsaway.manager.ChallengeManagerInterface;

import java.io.IOException;
import java.util.Optional;

import androidx.annotation.NonNull;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Opens WebView recovery for identified Cloudflare challenges on Rumble GET requests.
 * Only successful, nonblank pages without challenge markers replace the original response.
 */
public class PvcCfChallenge403Interceptor implements Interceptor {

    private static final MediaType HTML = MediaType.get("text/html; charset=utf-8");
    private static final String TAG = PvcCfChallenge403Interceptor.class.getSimpleName();
    private final ChallengeManagerInterface bypassManager;

    public PvcCfChallenge403Interceptor(final ChallengeManagerInterface manager) {
        this.bypassManager = manager;
    }

    public static Optional<Interceptor> getInterceptor(
            final OkHttpClient.Builder builder) {
        return builder.interceptors().stream().filter(
                PvcCfChallenge403Interceptor.class::isInstance).findFirst();
    }

    @NonNull
    @Override
    public Response intercept(final Chain chain) throws IOException {
        final Request request = chain.request();

        if (!isRumbleHost(request.url().host())) {
            return chain.proceed(request);
        }

        final Response response = chain.proceed(request);

        if (response.code() == 200) {
            debugMessage("CF_DBG Ic 1.0", response.code());
            return response;
        }

        if ("GET".equals(request.method()) && "GET".equals(response.request().method())
                && response.code() == 403 && isRumbleHost(response.request().url().host())
                && isChallenge(response)) {
            final ChallengeResult bypassResult;
            try {
                bypassResult = bypassManager.fetchContentViaWebView(
                        request.url().toString(), 30000);
            } catch (final RuntimeException exception) {
                response.close();
                throw exception;
            }

            debugMessage("CF_DBG Ic 2.0", response.code());

            if (bypassResult.success && bypassResult.content != null
                    && !bypassResult.content.trim().isEmpty()
                    && !isChallengePage(bypassResult.content)) {
                response.close();

                debugMessage("CF_DBG Ic 2.1 webview success", 200);

                // reuse the webView's content as a proper okHttp response.
                final Request newRequest = request.newBuilder().build();
                return new Response.Builder()
                        .request(newRequest)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(ResponseBody.create(HTML, bypassResult.content))
                        .build();
            }
        }

        debugMessage("CF_DBG Ic 3.0", response.code());

        return response;
    }

    private static boolean isRumbleHost(final String host) {
        return host.equals("rumble.com") || host.endsWith(".rumble.com");
    }

    private static boolean isChallenge(final Response response) throws IOException {
        if ("challenge".equalsIgnoreCase(response.header("cf-mitigated"))) {
            return true;
        }
        try {
            return isChallengePage(response.peekBody(8192).string());
        } catch (final IOException e) {
            response.close();
            throw e;
        }
    }

    private static boolean isChallengePage(final String body) {
        return body.contains("challenge-platform") && body.contains("Just a moment");
    }

    private void debugMessage(
            final String prefix,
            final int code
    ) {
        Log.d(TAG, prefix + " code " + code);
    }

    public void cleanupBeforeDestroy() {
        bypassManager.destroy();
    }
}
