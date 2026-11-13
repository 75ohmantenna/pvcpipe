package org.schabi.newpipe.util.external_communication;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.FileProvider;
import androidx.lifecycle.LifecycleOwner;

import org.schabi.newpipe.BuildConfig;
import org.schabi.newpipe.R;
import org.schabi.newpipe.RouterActivity;
import org.schabi.newpipe.extractor.Image;
import org.schabi.newpipe.util.image.ImageStrategy;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

public final class ShareUtils {
    private static final String TAG = ShareUtils.class.getSimpleName();

    private static final SharePreviewCoordinator PREVIEWS = new SharePreviewCoordinator(
            Executors.newSingleThreadExecutor(runnable -> {
                final Thread thread = new Thread(runnable, "share-preview");
                thread.setDaemon(true);
                return thread;
            }), runnable -> new Handler(Looper.getMainLooper()).post(runnable));

    private ShareUtils() {
    }


    /**
     * Open the url with the system default browser. If no browser is installed, falls back to
     * {@link #openAppChooser(Context, Intent, boolean)} (for displaying that no apps are available
     * to handle the action, or possible OEM-related edge cases).
     * <p>
     * This function selects the package to open based on which apps respond to the {@code http://}
     * schema alone, which should exclude special non-browser apps that are can handle the url (e.g.
     * the official YouTube app).
     * <p>
     * Therefore <b>please prefer {@link #openUrlInApp(Context, String)}</b>, that handles package
     * resolution in a standard way, unless this is the action of an explicit "Open in browser"
     * button.
     *
     * @param context the context to use
     * @param url     the url to browse
     **/
    public static void openUrlInBrowser(@NonNull final Context context, final String url) {
        // Target a generic http://, so we are sure to get a browser and not e.g. the yt app.
        // Note that this requires the `http` schema to be added to `<queries>` in the manifest.
        final Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("http://"));

        final Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        // See https://stackoverflow.com/a/58801285 and `setSelector` documentation
        intent.setSelector(browserIntent);
        try {
            context.startActivity(intent);
        } catch (final ActivityNotFoundException e) {
            // No browser is available. This should, in the end, yield a nice AOSP error message
            // indicating that no app is available to handle this action.
            //
            // Note: there are some situations where modified OEM ROMs have apps that appear
            // to be browsers but are actually app choosers. If starting the Activity fails
            // related to this, opening the system app chooser is still the correct behavior.
            intent.setSelector(null);
            openAppChooser(context, intent, true);
        }
    }

    /**
     * Open a url with the system default app using {@link Intent#ACTION_VIEW}, showing a toast in
     * case of failure.
     *
     * @param context the context to use
     * @param url     the url to open
     */
    public static void openUrlInApp(@NonNull final Context context, final String url) {
        openIntentInApp(context, new Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    /**
     * Open an intent with the system default app.
     * <p>
     * Use {@link #openIntentInApp(Context, Intent)} to show a toast in case of failure.
     *
     * @param context the context to use
     * @param intent  the intent to open
     * @return true if the intent could be opened successfully, false otherwise
     */
    public static boolean tryOpenIntentInApp(@NonNull final Context context,
                                             @NonNull final Intent intent) {
        try {
            context.startActivity(intent);
        } catch (final ActivityNotFoundException e) {
            return false;
        }
        return true;
    }

    /**
     * Open an intent with the system default app, showing a toast in case of failure.
     * <p>
     * Use {@link #tryOpenIntentInApp(Context, Intent)} if you don't want the toast. Use {@link
     * #openUrlInApp(Context, String)} as a shorthand for {@link Intent#ACTION_VIEW} with urls.
     *
     * @param context the context to use
     * @param intent  the intent to
     */
    public static void openIntentInApp(@NonNull final Context context,
                                       @NonNull final Intent intent) {
        if (!tryOpenIntentInApp(context, intent)) {
            Toast.makeText(context, R.string.no_app_to_open_intent, Toast.LENGTH_LONG)
                    .show();
        }
    }

    /**
     * Open the system chooser to launch an intent.
     * <p>
     * This method opens an {@link android.content.Intent#ACTION_CHOOSER} of the intent putted
     * as the intent param. If the setTitleChooser boolean is true, the string "Open with" will be
     * set as the title of the system chooser.
     * For Android P and higher, title for {@link android.content.Intent#ACTION_SEND} system
     * choosers must be set on this intent, not on the
     * {@link android.content.Intent#ACTION_CHOOSER} intent.
     *
     * @param context         the context to use
     * @param intent          the intent to open
     * @param setTitleChooser set the title "Open with" to the chooser if true, else not
     */
    private static void openAppChooser(@NonNull final Context context,
                                       @NonNull final Intent intent,
                                       final boolean setTitleChooser) {
        final Intent chooserIntent = new Intent(Intent.ACTION_CHOOSER);
        chooserIntent.putExtra(Intent.EXTRA_INTENT, intent);
        chooserIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (setTitleChooser) {
            chooserIntent.putExtra(Intent.EXTRA_TITLE, context.getString(R.string.open_with));
        }

        // Avoid opening in NewPipe
        // (Implementation note: if the URL is one for which NewPipe itself
        // is set as handler on Android >= 12, we actually remove the only eligible app
        // for this link, and browsers will not be offered to the user. For that, use
        // `openUrlInBrowser`.)
        chooserIntent.putExtra(
                Intent.EXTRA_EXCLUDE_COMPONENTS,
                new ComponentName[]{new ComponentName(context, RouterActivity.class)}
        );

        // Migrate any clip data and flags from the original intent.
        final int permFlags = intent.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        if (permFlags != 0) {
            ClipData targetClipData = intent.getClipData();
            if (targetClipData == null && intent.getData() != null) {
                final ClipData.Item item = new ClipData.Item(intent.getData());
                final String[] mimeTypes;
                if (intent.getType() != null) {
                    mimeTypes = new String[] {intent.getType()};
                } else {
                    mimeTypes = new String[] {};
                }
                targetClipData = new ClipData(null, mimeTypes, item);
            }
            if (targetClipData != null) {
                chooserIntent.setClipData(targetClipData);
                chooserIntent.addFlags(permFlags);
            }
        }

        try {
            context.startActivity(chooserIntent);
        } catch (final ActivityNotFoundException e) {
            Toast.makeText(context, R.string.no_app_to_open_intent, Toast.LENGTH_LONG).show();
        }
    }

    /**
     * Open the android share sheet to share a content.
     *
     * <p>
     * For Android 10+ users, a content preview is shown, which includes the title of the shared
     * content and an image preview the content, if its URL is not null or empty and its
     * corresponding image is in the image cache.
     * </p>
     *
     * @param context         the context to use
     * @param title           the title of the content
     * @param content         the content to share
     * @param imagePreviewUrl the image of the subject
     */
    public static void shareText(@NonNull final Context context,
                                 @NonNull final String title,
                                 final String content,
                                 final String imagePreviewUrl) {
        shareText(context, title, content, imagePreviewUrl, lifecycleOwner(context), () -> true);
    }

    /**
     * Preview sharing with explicit source ownership (also supports service/player contexts).
     * @param context chooser context
     * @param title shared content title
     * @param content shared text or URL
     * @param images cached preview candidates
     * @param owner optional UI lifecycle; service contexts may omit it
     * @param stillValid main-thread check that the source content is still current
     */
    public static void shareText(@NonNull final Context context,
                                 @NonNull final String title,
                                 final String content,
                                 final List<Image> images,
                                 @Nullable final LifecycleOwner owner,
                                 @NonNull final BooleanSupplier stillValid) {
        shareText(context, title, content, ImageStrategy.choosePreferredImage(images),
                owner, stillValid);
    }

    private static void shareText(final Context context, final String title, final String content,
                                  final String imagePreviewUrl, final LifecycleOwner owner,
                                  final BooleanSupplier stillValid) {
        PREVIEWS.cancel();
        final Intent shareIntent = new Intent(Intent.ACTION_SEND);
        shareIntent.setType("text/plain");
        shareIntent.putExtra(Intent.EXTRA_TEXT, content);
        if (!TextUtils.isEmpty(title)) {
            shareIntent.putExtra(Intent.EXTRA_TITLE, title);
            shareIntent.putExtra(Intent.EXTRA_SUBJECT, title);
        }
        final BooleanSupplier valid = () -> isContextActive(context) && stillValid.getAsBoolean();
        if (!valid.getAsBoolean()) {
            return;
        }
        if (TextUtils.isEmpty(imagePreviewUrl) || !ImageStrategy.shouldLoadImages()) {
            openAppChooser(context, shareIntent, false);
            return;
        }
        final Context application = context.getApplicationContext();
        final AtomicReference<ClipData> preview = new AtomicReference<>();
        PREVIEWS.submit(owner != null ? owner : lifecycleOwner(context), valid, active -> {
            final var file = SharePreviewStore.prepare(application, imagePreviewUrl, active);
            if (file != null) {
                try {
                    // URI canonicalization/provider MIME lookup also stays off the main thread.
                    preview.set(ClipData.newUri(application.getContentResolver(), "",
                            FileProvider.getUriForFile(application,
                                    BuildConfig.APPLICATION_ID + ".provider", file)));
                } catch (final RuntimeException error) {
                    Log.w(TAG, "Cannot attach share preview", error);
                    file.delete();
                    return null;
                }
            }
            return file;
        }, file -> {
            if (file != null && preview.get() != null) {
                shareIntent.setClipData(preview.get());
                shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            }
            openAppChooser(context, shareIntent, false);
        });
    }

    @Nullable
    private static LifecycleOwner lifecycleOwner(final Context context) {
        final Context unwrapped = unwrapContext(context);
        return unwrapped instanceof LifecycleOwner ? (LifecycleOwner) unwrapped : null;
    }

    private static boolean isContextActive(final Context context) {
        final Context unwrapped = unwrapContext(context);
        return !(unwrapped instanceof Activity) || (!((Activity) unwrapped).isFinishing()
                && !((Activity) unwrapped).isDestroyed());
    }

    private static Context unwrapContext(final Context context) {
        Context current = context;
        while (current instanceof ContextWrapper && !(current instanceof Activity)) {
            final Context base = ((ContextWrapper) current).getBaseContext();
            if (base == current || base == null) {
                break;
            }
            current = base;
        }
        return current;
    }

    /**
     * Open the android share sheet to share a content.
     *
     * <p>
     * For Android 10+ users, a content preview is shown, which includes the title of the shared
     * content and an image preview the content, if the preferred image chosen by {@link
     * ImageStrategy#choosePreferredImage(List)} is in the image cache.
     * </p>
     *
     * @param context the context to use
     * @param title   the title of the content
     * @param content the content to share
     * @param images  a set of possible {@link Image}s of the subject, among which to choose with
     *                {@link ImageStrategy#choosePreferredImage(List)} since that's likely to
     *                provide an image that is in Coil's cache
     */
    public static void shareText(@NonNull final Context context,
                                 @NonNull final String title,
                                 final String content,
                                 final List<Image> images) {
        shareText(context, title, content, ImageStrategy.choosePreferredImage(images));
    }

    /**
     * Open the android share sheet to share a content.
     *
     * <p>
     * This calls {@link #shareText(Context, String, String, String)} with an empty string for the
     * {@code imagePreviewUrl} parameter. This method should be used when the shared content has no
     * preview thumbnail.
     * </p>
     *
     * @param context the context to use
     * @param title   the title of the content
     * @param content the content to share
     */
    public static void shareText(@NonNull final Context context,
                                 @NonNull final String title,
                                 final String content) {
        shareText(context, title, content, "");
    }

    /**
     * Copy the text to clipboard. The system indicates success; failures are shown using a Toast.
     *
     * @param context the context to use
     * @param text    the text to copy
     */
    public static void copyToClipboard(@NonNull final Context context, final String text) {
        final ClipboardManager clipboardManager =
                context.getSystemService(ClipboardManager.class);

        if (clipboardManager == null) {
            Toast.makeText(context, R.string.permission_denied, Toast.LENGTH_LONG).show();
            return;
        }

        try {
            clipboardManager.setPrimaryClip(ClipData.newPlainText(null, text));
        } catch (final Exception e) {
            Log.e(TAG, "Error when trying to copy text to clipboard", e);
            Toast.makeText(context, R.string.msg_failed_to_copy, Toast.LENGTH_SHORT).show();
        }
    }

}
