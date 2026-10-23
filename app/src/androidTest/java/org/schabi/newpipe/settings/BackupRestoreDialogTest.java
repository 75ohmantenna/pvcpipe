package org.schabi.newpipe.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.net.Uri;
import android.os.SystemClock;
import android.text.Layout;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import androidx.activity.result.ActivityResult;
import androidx.appcompat.app.AlertDialog;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.schabi.newpipe.R;
import org.schabi.newpipe.settings.export.BackupFileLocator;
import org.schabi.newpipe.settings.export.ImportExportManager;
import org.schabi.newpipe.settings.export.PendingDatabaseRestore;
import org.schabi.newpipe.streams.io.StoredFileHelper;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import io.reactivex.rxjava3.schedulers.Schedulers;
import io.reactivex.rxjava3.schedulers.TestScheduler;

/** Real fragment actions with isolated files/preferences and a counting restart callback. */
@RunWith(AndroidJUnit4.class)
public class BackupRestoreDialogTest {
    private Instrumentation instrumentation;
    private Context context;
    private Context filesContext;
    private SettingsActivity activity;
    private BackupRestoreSettingsFragment fragment;
    private Configuration originalConfiguration;
    private Path directory;
    private Path database;
    private String preferenceName;
    private SharedPreferences preferences;
    private BackupRestore backups;
    private BackupRestore.Platform platform;
    private int restarts;
    private int documents;
    private Throwable restoreFailure;

    @Before
    public void setup() throws Exception {
        instrumentation = InstrumentationRegistry.getInstrumentation();
        context = instrumentation.getTargetContext();
        directory = Files.createTempDirectory(context.getCacheDir().toPath(),
                "backup-dialog-test-");
        database = directory.resolve("newpipe.db");
        Files.writeString(database, "live database");
        Files.writeString(directory.resolve("newpipe.db-wal"), "live WAL");
        preferenceName = "backup-dialog-test-" + UUID.randomUUID();
        preferences = context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE);
        assertTrue(preferences.edit().putString("original", "settings").commit());
        filesContext = new ContextWrapper(context) {
            @Override
            public File getDatabasePath(final String name) {
                return directory.resolve(name).toFile();
            }
        };
        platform = new BackupRestore.Platform() {
            @Override
            public StoredFileHelper document(final Uri uri) {
                documents++;
                return new StoredFileHelper(context, uri, "application/zip");
            }
            @Override
            public void checkpoint() { }
            @Override
            public void normalizeImportedPreferences(final Map<String, Object> values) { }
            @Override
            public void restart() {
                restarts++;
            }
            @Override
            public void reportRestoreFailure(final Throwable error) {
                restoreFailure = error;
            }
        };
        backups = new BackupRestore(new BackupOperations(Schedulers.trampoline()),
                new ImportExportManager(new BackupFileLocator(filesContext)), preferences,
                "location", platform, Schedulers.trampoline());
        activity = (SettingsActivity) instrumentation.startActivitySync(
                new Intent(context, SettingsActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        instrumentation.waitForIdleSync();
        onMain(() -> {
            originalConfiguration = new Configuration(activity.getResources().getConfiguration());
            activity.getSupportFragmentManager().executePendingTransactions();
            fragment = new BackupRestoreSettingsFragment();
            activity.getSupportFragmentManager().beginTransaction()
                    .replace(R.id.settings_fragment_holder, fragment).commitNow();
            final Field field = BackupRestoreSettingsFragment.class.getDeclaredField("backups");
            field.setAccessible(true);
            field.set(fragment, backups);
            return null;
        });
    }

    @After
    @SuppressWarnings("deprecation")
    public void cleanup() throws Exception {
        try {
            if (activity != null) {
                onMain(() -> {
                    if (originalConfiguration != null) {
                        activity.getResources().updateConfiguration(originalConfiguration,
                                activity.getResources().getDisplayMetrics());
                    }
                    activity.finish();
                    return null;
                });
                instrumentation.waitForIdleSync();
            }
        } finally {
            if (preferenceName != null) {
                context.deleteSharedPreferences(preferenceName);
            }
            if (directory != null) {
                try (var paths = Files.walk(directory)) {
                    for (final Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                        Files.deleteIfExists(path);
                    }
                }
            }
        }
    }

    @Test
    public void jsonWithoutSettingsStagesDatabaseAndPreservesSettings() throws Exception {
        assertRestore(BackupRestore.PreferenceFormat.JSON, AlertDialog.BUTTON_NEUTRAL, false);
    }

    @Test
    public void jsonWithSettingsStagesDatabaseAndReplacesSettings() throws Exception {
        assertRestore(BackupRestore.PreferenceFormat.JSON, AlertDialog.BUTTON_POSITIVE, true);
    }

    @Test
    public void legacyWithoutSettingsStagesDatabaseAndPreservesSettings() throws Exception {
        assertRestore(BackupRestore.PreferenceFormat.LEGACY_SERIALIZED,
                AlertDialog.BUTTON_NEUTRAL, false);
    }

    @Test
    public void legacyWithSettingsStagesDatabaseAndReplacesSettings() throws Exception {
        assertRestore(BackupRestore.PreferenceFormat.LEGACY_SERIALIZED,
                AlertDialog.BUTTON_POSITIVE, true);
    }

    @Test
    public void cancelButtonNeverRestoresEitherSettingsFormat() throws Exception {
        assertCancellation(Dismissal.CANCEL);
    }

    @Test
    public void backKeyNeverRestoresEitherSettingsFormat() throws Exception {
        assertCancellation(Dismissal.BACK);
    }

    @Test
    public void outsideTouchNeverRestoresEitherSettingsFormat() throws Exception {
        assertCancellation(Dismissal.OUTSIDE);
    }

    @Test
    public void destroyingFragmentViewDismissesWithoutRestoring() throws Exception {
        final AlertDialog dialog = showChoice(archive(BackupRestore.PreferenceFormat.JSON));
        onMain(() -> {
            activity.getSupportFragmentManager().beginTransaction().remove(fragment).commitNow();
            assertFalse(dialog.isShowing());
            assertEquals(null, fragment.getView());
            return null;
        });
        instrumentation.waitForIdleSync();
        assertUnchanged();
    }

    @Test
    public void destroyedViewDoesNotShowChoiceAfterPendingInspection() throws Exception {
        assertDestroyedDuringInspection(BackupRestore.PreferenceFormat.JSON);
    }

    @Test
    public void destroyedViewDoesNotRestoreSettingsFreeBackupAfterPendingInspection()
            throws Exception {
        assertDestroyedDuringInspection(BackupRestore.PreferenceFormat.NONE);
    }

    @Test
    public void cancellingOverwriteDoesNotEvenInspectArchive() throws Exception {
        final Uri uri = archive(BackupRestore.PreferenceFormat.JSON);
        onMain(() -> {
            invoke("requestImportPathResult", ActivityResult.class,
                    new ActivityResult(Activity.RESULT_OK, new Intent().setData(uri)));
            assertEquals(context.getString(R.string.override_current_data),
                    ((TextView) dialog().findViewById(android.R.id.message)).getText().toString());
            assertTrue(dialog().getButton(AlertDialog.BUTTON_NEGATIVE).performClick());
            return null;
        });
        instrumentation.waitForIdleSync();
        assertEquals(0, documents);
        assertUnchanged();
    }

    @Test
    public void confirmingOverwriteReachesChoiceAndCancellingStillDoesNothing() throws Exception {
        final Uri uri = archive(BackupRestore.PreferenceFormat.JSON);
        onMain(() -> {
            invoke("requestImportPathResult", ActivityResult.class,
                    new ActivityResult(Activity.RESULT_OK, new Intent().setData(uri)));
            assertTrue(dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick());
            return null;
        });
        instrumentation.waitForIdleSync();
        onMain(() -> {
            assertLabelsAndMessage(dialog(), BackupRestore.PreferenceFormat.JSON);
            assertTrue(dialog().getButton(AlertDialog.BUTTON_NEGATIVE).performClick());
            return null;
        });
        instrumentation.waitForIdleSync();
        assertEquals(1, documents);
        assertUnchanged();
    }

    @Test
    public void settingsFreeArchiveRestoresDatabaseWithoutOfferingSettings() throws Exception {
        final Uri uri = archive(BackupRestore.PreferenceFormat.NONE);
        importArchive(uri);
        onMain(() -> {
            assertEquals(null, dialog());
            return null;
        });
        assertStaged(uri, false);
    }

    @Test
    public void narrowPortraitButtonsAreReadableAtNormalFont() throws Exception {
        assertNarrowDialog(1.0f);
    }

    @Test
    public void narrowPortraitButtonsAreReadableAtLargeFont() throws Exception {
        assertNarrowDialog(1.3f);
    }

    @Test
    public void narrowPortraitButtonsAreReadableAtLargestFont() throws Exception {
        assertNarrowDialog(2.0f);
    }

    private void assertRestore(final BackupRestore.PreferenceFormat format, final int button,
                               final boolean withSettings) throws Exception {
        final Uri uri = archive(format);
        final AlertDialog dialog = showChoice(uri);
        assertUnchanged();
        onMain(() -> {
            assertLabelsAndMessage(dialog, format);
            assertTrue(dialog.getButton(button).performClick());
            return null;
        });
        instrumentation.waitForIdleSync();
        assertFalse(onMain(dialog::isShowing));
        assertStaged(uri, withSettings);
    }

    private void assertDestroyedDuringInspection(final BackupRestore.PreferenceFormat format)
            throws Exception {
        final TestScheduler io = new TestScheduler();
        final BackupRestore delayed = new BackupRestore(new BackupOperations(io),
                new ImportExportManager(new BackupFileLocator(filesContext)), preferences,
                "location", platform, Schedulers.trampoline());
        final Uri uri = archive(format);
        onMain(() -> {
            final Field field = BackupRestoreSettingsFragment.class.getDeclaredField("backups");
            field.setAccessible(true);
            field.set(fragment, delayed);
            invoke("importDatabase", Uri.class, uri);
            assertEquals(0, documents);
            assertEquals(null, dialog());
            activity.getSupportFragmentManager().beginTransaction().remove(fragment).commitNow();
            return null;
        });
        // Accepted inspection may finish, but its disposed fragment observer must not act on it.
        io.triggerActions();
        instrumentation.waitForIdleSync();
        assertEquals(1, documents);
        onMain(() -> {
            assertEquals(null, fragment.getView());
            assertEquals(null, dialog());
            return null;
        });
        assertUnchanged();
    }

    private void assertCancellation(final Dismissal dismissal) throws Exception {
        for (final BackupRestore.PreferenceFormat format : new BackupRestore.PreferenceFormat[]{
                BackupRestore.PreferenceFormat.JSON,
                BackupRestore.PreferenceFormat.LEGACY_SERIALIZED}) {
            // Label assertions live in the action/layout tests so the old negative-button wiring
            // fails here on restore effects, rather than failing before Cancel is actually clicked.
            final AlertDialog dialog = showChoice(archive(format));
            onMain(() -> {
                if (dismissal == Dismissal.CANCEL) {
                    // This is deliberately BUTTON_NEGATIVE: the old wiring restored the database.
                    assertTrue(dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick());
                } else if (dismissal == Dismissal.BACK) {
                    dialog.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,
                            KeyEvent.KEYCODE_BACK));
                    dialog.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP,
                            KeyEvent.KEYCODE_BACK));
                } else {
                    final long time = SystemClock.uptimeMillis();
                    // Coordinates are outside the actual decor, not a shortcut to cancel().
                    final MotionEvent down = MotionEvent.obtain(time, time,
                            MotionEvent.ACTION_DOWN, -1000, -1000, 0);
                    final MotionEvent up = MotionEvent.obtain(time, time + 1,
                            MotionEvent.ACTION_UP, -1000, -1000, 0);
                    try {
                        dialog.dispatchTouchEvent(down);
                        dialog.dispatchTouchEvent(up);
                    } finally {
                        down.recycle();
                        up.recycle();
                    }
                }
                return null;
            });
            instrumentation.waitForIdleSync();
            assertFalse(onMain(dialog::isShowing));
            assertUnchanged();
        }
    }

    @SuppressWarnings("deprecation")
    private void assertNarrowDialog(final float fontScale) throws Exception {
        onMain(() -> {
            final Configuration configuration = new Configuration(originalConfiguration);
            configuration.fontScale = fontScale;
            activity.getResources().updateConfiguration(configuration,
                    activity.getResources().getDisplayMetrics());
            return null;
        });
        for (final BackupRestore.PreferenceFormat format : new BackupRestore.PreferenceFormat[]{
                BackupRestore.PreferenceFormat.JSON,
                BackupRestore.PreferenceFormat.LEGACY_SERIALIZED}) {
            final AlertDialog dialog = showChoice(archive(format));
            onMain(() -> {
                assertNotNull(dialog.getWindow());
                final int width = Math.round(320 * activity.getResources()
                        .getDisplayMetrics().density);
                dialog.getWindow().setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
                return null;
            });
            instrumentation.waitForIdleSync();
            // Main-thread idleness alone can precede the dialog's window enter animation.
            instrumentation.getUiAutomation().waitForIdle(500, 5000);
            instrumentation.waitForIdleSync();
            saveScreenshot("font-" + fontScale + "-"
                    + format.name().toLowerCase(java.util.Locale.US));
            onMain(() -> {
                assertLabelsAndMessage(dialog, format);
                return null;
            });
            final int[] buttons = {AlertDialog.BUTTON_NEUTRAL, AlertDialog.BUTTON_NEGATIVE,
                    AlertDialog.BUTTON_POSITIVE};
            final Rect[] bounds = new Rect[buttons.length];
            for (int index = 0; index < buttons.length; index++) {
                final int button = buttons[index];
                bounds[index] = onMain(() -> assertReadableButton(dialog.getButton(button)));
                instrumentation.getUiAutomation().waitForIdle(500, 5000);
                saveScreenshot("font-" + fontScale + "-"
                        + format.name().toLowerCase(java.util.Locale.US) + "-button-" + button);
            }
            onMain(() -> {
                final Rect neutralBounds = bounds[0];
                final Rect negativeBounds = bounds[1];
                final Rect positiveBounds = bounds[2];
                assertFalse(Rect.intersects(neutralBounds, negativeBounds));
                assertFalse(Rect.intersects(neutralBounds, positiveBounds));
                assertFalse(Rect.intersects(negativeBounds, positiveBounds));
                if (neutralBounds.top == positiveBounds.top) {
                    assertTrue(neutralBounds.right <= negativeBounds.left);
                    assertTrue(negativeBounds.right <= positiveBounds.left);
                } else {
                    // AppCompat reverses its button order when the button bar stacks vertically.
                    assertTrue(positiveBounds.bottom <= negativeBounds.top);
                    assertTrue(negativeBounds.bottom <= neutralBounds.top);
                }
                assertTrue(dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick());
                return null;
            });
            instrumentation.waitForIdleSync();
            assertUnchanged();
        }
    }

    private static Rect assertReadableButton(final Button button) {
        assertTrue(button.isShown());
        // AppCompat's button panel scrolls when large fonts exhaust the available height.
        button.requestRectangleOnScreen(new Rect(0, 0, button.getWidth(), button.getHeight()),
                true);
        final Rect visible = new Rect();
        final android.graphics.Point offset = new android.graphics.Point();
        assertTrue("Restore action must be reachable by scrolling",
                button.getGlobalVisibleRect(visible, offset));
        assertEquals("Restore action width must be visible", button.getWidth(), visible.width());
        final Layout layout = button.getLayout();
        assertNotNull(layout);
        assertTrue(layout.getLineCount() > 0);
        final int available = button.getWidth() - button.getCompoundPaddingLeft()
                - button.getCompoundPaddingRight();
        for (int line = 0; line < layout.getLineCount(); line++) {
            assertEquals("Restore action must not be ellipsized", 0, layout.getEllipsisCount(line));
            assertTrue("Restore action must not be clipped horizontally",
                    layout.getLineWidth(line) <= available + 1);
        }
        assertTrue("Restore action must not be clipped vertically", layout.getHeight()
                <= button.getHeight() - button.getCompoundPaddingTop()
                - button.getCompoundPaddingBottom());
        final int textTop = offset.y + button.getBaseline() - layout.getLineBaseline(0);
        assertTrue("Label range " + textTop + ".." + (textTop + layout.getHeight())
                        + " must fit visible bounds " + visible,
                visible.top <= textTop && visible.bottom >= textTop + layout.getHeight());
        final int minimumTarget = Math.round(48 * button.getResources()
                .getDisplayMetrics().density);
        assertTrue("Visible action target must remain at least 48dp",
                visible.height() >= minimumTarget);
        final var node = button.createAccessibilityNodeInfo();
        try {
            assertTrue("Restore action must be accessible and clickable", node.isClickable());
            assertTrue(node.isEnabled());
            assertNotNull(node.getText());
            assertTrue("Accessible action name must match its visible label",
                    node.getText().toString().equalsIgnoreCase(button.getText().toString()));
        } finally {
            node.recycle();
        }
        // Compare sibling geometry independently of the panel's current scroll position.
        return new Rect(button.getLeft(), button.getTop(), button.getRight(), button.getBottom());
    }

    private void assertLabelsAndMessage(final AlertDialog dialog,
                                        final BackupRestore.PreferenceFormat format) {
        assertTrue(dialog.isShowing());
        assertEquals(context.getString(R.string.cancel),
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).getText().toString());
        assertEquals(context.getString(R.string.restore_without_settings),
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).getText().toString());
        assertEquals(context.getString(R.string.restore_with_settings),
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).getText().toString());
        final TextView title = dialog.findViewById(androidx.appcompat.R.id.alertTitle);
        assertNotNull(title);
        assertEquals(context.getString(R.string.restore_backup_title), title.getText().toString());
        final TextView message = dialog.findViewById(android.R.id.message);
        assertNotNull(message);
        final String expected = context.getString(R.string.restore_settings_message)
                + (format == BackupRestore.PreferenceFormat.LEGACY_SERIALIZED
                ? "\n\n" + context.getString(R.string.import_settings_vulnerable_format) : "");
        assertEquals(expected, message.getText().toString());
        for (final int button : new int[]{AlertDialog.BUTTON_NEGATIVE,
                AlertDialog.BUTTON_NEUTRAL, AlertDialog.BUTTON_POSITIVE}) {
            assertEquals(View.VISIBLE, dialog.getButton(button).getVisibility());
        }
    }

    private AlertDialog showChoice(final Uri uri) throws Exception {
        importArchive(uri);
        return onMain(() -> {
            final AlertDialog dialog = dialog();
            assertNotNull(dialog);
            assertTrue(dialog.isShowing());
            return dialog;
        });
    }

    private void importArchive(final Uri uri) throws Exception {
        onMain(() -> {
            invoke("importDatabase", Uri.class, uri);
            return null;
        });
        instrumentation.waitForIdleSync();
    }

    private AlertDialog dialog() throws ReflectiveOperationException {
        final Field field = BackupRestoreSettingsFragment.class
                .getDeclaredField("importSettingsDialog");
        field.setAccessible(true);
        return (AlertDialog) field.get(fragment);
    }

    private void invoke(final String name, final Class<?> parameter, final Object value)
            throws ReflectiveOperationException {
        final Method method = BackupRestoreSettingsFragment.class
                .getDeclaredMethod(name, parameter);
        method.setAccessible(true);
        method.invoke(fragment, value);
    }

    private Uri archive(final BackupRestore.PreferenceFormat format) throws IOException {
        final Path archive = directory.resolve(format.name() + ".zip");
        try (var zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            zip.putNextEntry(new ZipEntry("newpipe.db"));
            zip.write("restored database".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            if (format == BackupRestore.PreferenceFormat.JSON) {
                zip.putNextEntry(new ZipEntry("preferences.json"));
                zip.write("{\"restored\":true}".getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            } else if (format == BackupRestore.PreferenceFormat.LEGACY_SERIALIZED) {
                final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                try (var output = new ObjectOutputStream(bytes)) {
                    final Map<String, Object> values = new HashMap<>();
                    values.put("restored", true);
                    output.writeObject(values);
                }
                zip.putNextEntry(new ZipEntry("newpipe.settings"));
                zip.write(bytes.toByteArray());
                zip.closeEntry();
            }
        }
        return Uri.fromFile(archive.toFile());
    }

    private void assertUnchanged() throws IOException {
        assertEquals(Map.of("original", "settings"), preferences.getAll());
        assertEquals("live database", Files.readString(database));
        assertEquals("live WAL", Files.readString(directory.resolve("newpipe.db-wal")));
        assertFalse(PendingDatabaseRestore.hasPending(database));
        assertEquals(0, restarts);
        assertEquals(null, restoreFailure);
    }

    private void assertStaged(final Uri uri, final boolean withSettings) throws IOException {
        final Map<String, Object> expected = new HashMap<>();
        expected.put(withSettings ? "restored" : "original", withSettings ? true : "settings");
        expected.put("location", uri.toString());
        assertEquals(expected, preferences.getAll());
        assertTrue(PendingDatabaseRestore.hasPending(database));
        assertEquals("restored database",
                Files.readString(directory.resolve("newpipe.db.restore")));
        assertEquals("live database", Files.readString(database));
        assertEquals("live WAL", Files.readString(directory.resolve("newpipe.db-wal")));
        assertEquals(1, restarts);
        assertEquals(null, restoreFailure);
    }

    private void saveScreenshot(final String name) throws IOException {
        final Path screenshots = context.getCacheDir().toPath()
                .resolve("backup-restore-dialog-screenshots");
        Files.createDirectories(screenshots);
        final Bitmap screenshot = instrumentation.getUiAutomation().takeScreenshot();
        assertNotNull("UiAutomation must capture the visible dialog", screenshot);
        try (var output = Files.newOutputStream(screenshots.resolve(name + ".png"))) {
            assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, output));
        } finally {
            screenshot.recycle();
        }
    }

    private <T> T onMain(final Callable<T> action) throws Exception {
        final FutureTask<T> result = new FutureTask<>(action);
        instrumentation.runOnMainSync(result);
        return result.get(10, TimeUnit.SECONDS);
    }

    private enum Dismissal { CANCEL, BACK, OUTSIDE }
}
