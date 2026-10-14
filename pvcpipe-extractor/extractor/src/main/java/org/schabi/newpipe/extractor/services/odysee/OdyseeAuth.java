package org.schabi.newpipe.extractor.services.odysee;

import com.grack.nanojson.JsonObject;

import org.schabi.newpipe.extractor.exceptions.ExtractionException;
import org.schabi.newpipe.extractor.services.odysee.settings.OdyseeSettings;
import org.schabi.newpipe.extractor.utils.Utils;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.security.SecureRandom;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * The anonymous visitor token of Odysee. The website registers one for every visitor who is
 * not signed in, and Odysee answers a few questions, such as how often a video was viewed,
 * only to a client that has one. The token stands for no account and no person: registering
 * takes no name, address or password.
 * <p>
 * One token is registered when it is first needed and kept for as long as Odysee accepts it,
 * so that a client does not leave a new visitor behind every time it starts. Where it is kept
 * between runs is up to the {@link TokenStore}. Nothing is registered or kept while
 * {@link OdyseeSettings#SHOW_COUNTS} is off.
 * </p>
 */
public final class OdyseeAuth {

    /** Keeps the token between runs of the program. Its methods must not block for long. */
    public interface TokenStore {
        /**
         * @return the token that was saved, or {@code null} if there is none
         */
        @Nullable
        String load();

        /**
         * @param token the token to keep, or {@code null} to forget the one that was kept
         */
        void save(@Nullable String token);
    }

    // Odysee accepts an application id of 65 or 66 letters and digits.
    private static final int APP_ID_LENGTH = 66;
    private static final String APP_ID_CHARACTERS =
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    // After a registration that failed or gave a token Odysee then refused, the next one waits
    // this long, and twice as long after each further failure.
    private static final long RETRY_MILLIS = 10 * 60 * 1000L;
    private static final int MAX_RETRY_DOUBLINGS = 6;

    // Guards the fields below. Never held during a request.
    private static final Object STATE = new Object();
    // Held during a registration, so that callers at the same time register one visitor.
    private static final Object REGISTRATION = new Object();

    private static TokenStore store = new MemoryStore();
    private static boolean loaded;
    @Nullable
    private static String token;
    private static long registeredAt;
    private static long retryAt;
    private static int failures;
    // Changes when the token is forgotten or its store replaced, so that a registration that
    // was under way at that moment knows its token is not wanted.
    private static int generation;

    private OdyseeAuth() {
    }

    /**
     * @param tokenStore where to keep the token between runs, or {@code null} to keep it
     *                   only until the program ends
     */
    public static void setTokenStore(@Nullable final TokenStore tokenStore) {
        synchronized (STATE) {
            store = tokenStore == null ? new MemoryStore() : tokenStore;
            loaded = false;
            token = null;
            registeredAt = 0;
            retryAt = 0;
            failures = 0;
            generation++;
            OdyseeApi.clearCountCache();
        }
    }

    /**
     * Stops using the token and removes it from where it was kept. This is for the moment the
     * user switches the counts off.
     */
    public static void forget() {
        synchronized (STATE) {
            token = null;
            loaded = true;
            registeredAt = 0;
            generation++;
            OdyseeApi.clearCountCache();
            keep(null);
        }
    }

    /**
     * Returns the token, registering one if there is none yet. Callers that run at the same
     * time wait for one registration and share its token.
     *
     * @return the token, or {@code null} if the counts are off or none could be registered
     * just now
     */
    @Nullable
    static String token() {
        if (!wanted()) {
            return null;
        }
        synchronized (STATE) {
            if (!loaded) {
                token = loadKept();
                loaded = true;
            }
            if (token != null) {
                return token;
            }
        }
        synchronized (REGISTRATION) {
            final int startedIn;
            synchronized (STATE) {
                if (token != null) {
                    return token; // registered by the caller this one waited for
                }
                if (System.currentTimeMillis() < retryAt) {
                    return null;
                }
                startedIn = generation;
            }
            final String registered;
            try {
                registered = register();
            } catch (final IOException | ExtractionException | RuntimeException e) {
                // A caller that gave up, as when the user leaves the page, says nothing
                // about whether Odysee would have registered a visitor.
                if (!wasCancelled(e)) {
                    synchronized (STATE) {
                        waitBeforeRegisteringAgain();
                    }
                }
                return null;
            }
            synchronized (STATE) {
                if (generation != startedIn || !wanted()) {
                    return null;
                }
                token = registered;
                registeredAt = System.currentTimeMillis();
                keep(token);
                return token;
            }
        }
    }

    /** Notes that Odysee answered a request that carried the token. */
    static void accepted() {
        synchronized (STATE) {
            failures = 0;
        }
    }

    /**
     * Forgets a token that Odysee no longer accepts, so that the next call registers a new one.
     *
     * @param rejected the token that was refused
     */
    static void discard(@Nonnull final String rejected) {
        synchronized (STATE) {
            // Another caller may have replaced the token already.
            if (rejected.equals(token)) {
                token = null;
                keep(null);
                // A token that is refused right after it was registered will not be the last
                // one. Do not register visitor after visitor.
                if (System.currentTimeMillis() - registeredAt < RETRY_MILLIS) {
                    waitBeforeRegisteringAgain();
                }
            }
        }
    }

    private static boolean wanted() {
        return OdyseeSettings.getInstance().isSettingEnabled(OdyseeSettings.SHOW_COUNTS);
    }

    private static void waitBeforeRegisteringAgain() {
        retryAt = System.currentTimeMillis()
                + (RETRY_MILLIS << Math.min(failures, MAX_RETRY_DOUBLINGS));
        failures++;
    }

    static boolean wasCancelled(final Exception e) {
        return Thread.currentThread().isInterrupted()
                || (e instanceof InterruptedIOException && !(e instanceof SocketTimeoutException));
    }

    @Nonnull
    private static String register() throws IOException, ExtractionException {
        final SecureRandom random = new SecureRandom();
        final StringBuilder appId = new StringBuilder(APP_ID_LENGTH);
        for (int i = 0; i < APP_ID_LENGTH; i++) {
            appId.append(APP_ID_CHARACTERS.charAt(random.nextInt(APP_ID_CHARACTERS.length())));
        }
        final Object user = OdyseeApi.callAccountApi(OdyseeApi.REGISTER_URL,
                "auth_token=&app_id=" + appId + "&language=en");
        final String registered = user instanceof JsonObject
                ? emptyToNull(((JsonObject) user).getString("auth_token")) : null;
        if (registered == null) {
            throw new ExtractionException("Odysee registered a visitor without a token");
        }
        return registered;
    }

    // A store that fails must not take the page that asked for a count down with it. The
    // token then lives until the program ends.
    @Nullable
    private static String loadKept() {
        try {
            return emptyToNull(store.load());
        } catch (final RuntimeException e) {
            return null;
        }
    }

    private static void keep(@Nullable final String value) {
        try {
            store.save(value);
        } catch (final RuntimeException ignored) {
            // see loadKept
        }
    }

    @Nullable
    private static String emptyToNull(@Nullable final String value) {
        return Utils.isBlank(value) ? null : value;
    }

    private static final class MemoryStore implements TokenStore {
        @Nullable
        private String saved;

        @Nullable
        @Override
        public String load() {
            return saved;
        }

        @Override
        public void save(@Nullable final String value) {
            saved = value;
        }
    }
}
