/*
 * Copyright 2017 Mauricio Colli <mauriciocolli@outlook.com>
 * StateSaver.java is part of NewPipe
 *
 * License: GPL-3.0+
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package org.schabi.newpipe.util;


import android.content.Context;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.os.BundleCompat;

import org.schabi.newpipe.BuildConfig;
import org.schabi.newpipe.MainActivity;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.LinkedList;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A way to save state to disk or in a in-memory map
 * if it's just changing configurations (i.e. rotating the phone).
 */
public final class StateSaver {
    public static final String KEY_SAVED_STATE = "key_saved_state";
    private static final ConcurrentHashMap<String, Queue<Object>> STATE_OBJECTS_HOLDER =
            new ConcurrentHashMap<>();
    // Cleanup tasks consult this live set so that state created after cleanup was requested is not
    // deleted when the task eventually runs.
    private static final Set<String> ACTIVE_STATE_FILES = ConcurrentHashMap.newKeySet();
    private static final ExecutorService FILE_DELETE_EXECUTOR =
            Executors.newSingleThreadExecutor(runnable -> {
                final Thread thread = new Thread(runnable, "state-saver-file-cleanup");
                thread.setDaemon(true);
                return thread;
            });
    private static final String TAG = "StateSaver";
    private static final String CACHE_DIR_NAME = "state_cache";
    private static String cacheDirPath;

    private StateSaver() {
        //no instance
    }

    /**
     * Initialize the StateSaver, usually you want to call this in the Application class.
     *
     * @param context used to get the available cache dir
     */
    public static void init(final Context context) {
        // State files are serialized objects and are read back with ObjectInputStream. They
        // belong in internal storage, which no other app can write to. Older builds used the
        // external cache, which other apps could reach on Android 8 to 10.
        cacheDirPath = context.getCacheDir().getAbsolutePath();

        final File externalCacheDir = context.getExternalCacheDir();
        if (externalCacheDir != null) {
            final File legacyStateDir = new File(externalCacheDir, CACHE_DIR_NAME);
            FILE_DELETE_EXECUTOR.execute(() ->
                    StateSaverFileCleanup.deleteDirectory(legacyStateDir));
        }
    }

    private static File stateDir() {
        return new File(cacheDirPath, CACHE_DIR_NAME);
    }

    /**
     * Restores the saved state referenced by the bundle from memory or disk.
     *
     * @param outState
     * @param writeRead
     * @return the saved state
     * @see #tryToRestore(SavedState, WriteRead)
     */
    public static SavedState tryToRestore(final Bundle outState, final WriteRead writeRead) {
        if (outState == null || writeRead == null) {
            return null;
        }

        final SavedState savedState = BundleCompat.getParcelable(
                outState, KEY_SAVED_STATE, SavedState.class);
        if (savedState == null) {
            return null;
        }

        return tryToRestore(savedState, writeRead);
    }

    /**
     * Try to restore the state from memory and disk,
     * using the {@link StateSaver.WriteRead#readFrom(Queue)} from the writeRead.
     *
     * @param savedState
     * @param writeRead
     * @return the saved state
     */
    @Nullable
    private static SavedState tryToRestore(@NonNull final SavedState savedState,
                                           @NonNull final WriteRead writeRead) {
        if (MainActivity.DEBUG) {
            Log.d(TAG, "tryToRestore() called with: savedState = [" + savedState + "], "
                    + "writeRead = [" + writeRead + "]");
        }

        try {
            Queue<Object> savedObjects =
                    STATE_OBJECTS_HOLDER.remove(savedState.getPrefixFileSaved());
            if (savedObjects != null) {
                writeRead.readFrom(savedObjects);
                if (MainActivity.DEBUG) {
                    Log.d(TAG, "tryToSave: reading objects from holder > " + savedObjects
                            + ", stateObjectsHolder > " + STATE_OBJECTS_HOLDER);
                }
                return savedState;
            }

            // The path comes from a saved bundle. Only a file of our own state directory is
            // read: a bundle written by an older build may name a file in shared storage.
            if (!StateSaverFileCleanup.isInside(stateDir(), savedState.getPathFileSaved())) {
                return null;
            }

            final File file = new File(savedState.getPathFileSaved());
            ACTIVE_STATE_FILES.add(file.getAbsolutePath());
            if (!file.exists()) {
                ACTIVE_STATE_FILES.remove(file.getAbsolutePath());
                if (MainActivity.DEBUG) {
                    Log.d(TAG, "Cache file doesn't exist: " + file.getAbsolutePath());
                }
                return null;
            }

            try (FileInputStream fileInputStream = new FileInputStream(file);
                 ObjectInputStream inputStream = new ObjectInputStream(fileInputStream)) {
                //noinspection unchecked
                savedObjects = (Queue<Object>) inputStream.readObject();
            }

            if (savedObjects != null) {
                writeRead.readFrom(savedObjects);
            }

            return savedState;
        } catch (final Exception e) {
            ACTIVE_STATE_FILES.remove(new File(savedState.getPathFileSaved()).getAbsolutePath());
            Log.e(TAG, "Failed to restore state", e);
        }
        return null;
    }

    /**
     * Saves the state and stores its reference in the output bundle.
     *
     * @param isChangingConfig
     * @param savedState
     * @param outState
     * @param writeRead
     * @return the saved state or {@code null}
     * @see #tryToSave(boolean, String, String, WriteRead)
     */
    @Nullable
    public static SavedState tryToSave(final boolean isChangingConfig,
                                       @Nullable final SavedState savedState, final Bundle outState,
                                       final WriteRead writeRead) {
        @NonNull final String currentSavedPrefix;
        if (savedState == null || TextUtils.isEmpty(savedState.getPrefixFileSaved())) {
            // Generate unique prefix
            currentSavedPrefix = System.nanoTime() - writeRead.hashCode() + "";
        } else {
            // Reuse prefix
            currentSavedPrefix = savedState.getPrefixFileSaved();
        }

        final SavedState newSavedState = tryToSave(isChangingConfig, currentSavedPrefix,
                writeRead.generateSuffix(), writeRead);
        if (newSavedState != null) {
            outState.putParcelable(StateSaver.KEY_SAVED_STATE, newSavedState);
            return newSavedState;
        }

        return null;
    }

    /**
     * If it's not changing configuration (i.e. rotating screen),
     * try to write the state from {@link StateSaver.WriteRead#writeTo(Queue)}
     * to the file with the name of prefixFileName + suffixFileName,
     * in a cache folder got from the {@link #init(Context)}.
     * <p>
     * It checks if the file already exists and if it does, just return the path,
     * so a good way to save is:
     * </p>
     * <ul>
     * <li>A fixed prefix for the file</li>
     * <li>A changing suffix</li>
     * </ul>
     *
     * @param isChangingConfig
     * @param prefixFileName
     * @param suffixFileName
     * @param writeRead
     * @return the saved state or {@code null}
     */
    @Nullable
    private static SavedState tryToSave(final boolean isChangingConfig, final String prefixFileName,
                                        final String suffixFileName, final WriteRead writeRead) {
        if (MainActivity.DEBUG) {
            Log.d(TAG, "tryToSave() called with: "
                    + "isChangingConfig = [" + isChangingConfig + "], "
                    + "prefixFileName = [" + prefixFileName + "], "
                    + "suffixFileName = [" + suffixFileName + "], "
                    + "writeRead = [" + writeRead + "]");
        }

        final LinkedList<Object> savedObjects = new LinkedList<>();
        writeRead.writeTo(savedObjects);

        if (isChangingConfig) {
            if (savedObjects.size() > 0) {
                STATE_OBJECTS_HOLDER.put(prefixFileName, savedObjects);
                return new SavedState(prefixFileName, "");
            } else {
                if (MainActivity.DEBUG) {
                    Log.d(TAG, "Nothing to save");
                }
                return null;
            }
        }

        File stateFile = null;
        try {
            File cacheDir = new File(cacheDirPath);
            if (!cacheDir.exists()) {
                throw new RuntimeException("Cache dir does not exist > " + cacheDirPath);
            }
            cacheDir = new File(cacheDir, CACHE_DIR_NAME);
            if (!cacheDir.exists()) {
                if (!cacheDir.mkdir()) {
                    if (BuildConfig.DEBUG) {
                        Log.e(TAG,
                                "Failed to create cache directory " + cacheDir.getAbsolutePath());
                    }
                    return null;
                }
            }

            stateFile = new File(cacheDir, prefixFileName
                    + (TextUtils.isEmpty(suffixFileName) ? ".cache" : suffixFileName));
            ACTIVE_STATE_FILES.add(stateFile.getAbsolutePath());
            if (stateFile.exists() && stateFile.length() > 0) {
                // If the file already exists, just return it
                return new SavedState(prefixFileName, stateFile.getAbsolutePath());
            } else {
                // Delete any file that contains the prefix
                final File[] files = cacheDir.listFiles((dir, name) ->
                        name.contains(prefixFileName));
                for (final File fileToDelete : files) {
                    fileToDelete.delete();
                }
            }

            try (FileOutputStream fileOutputStream = new FileOutputStream(stateFile);
                 ObjectOutputStream outputStream = new ObjectOutputStream(fileOutputStream)) {
                outputStream.writeObject(savedObjects);
            }

            return new SavedState(prefixFileName, stateFile.getAbsolutePath());
        } catch (final Exception e) {
            if (stateFile != null) {
                ACTIVE_STATE_FILES.remove(stateFile.getAbsolutePath());
            }
            Log.e(TAG, "Failed to save state", e);
        }
        return null;
    }

    /**
     * Schedule deletion of the cache file contained in the savedState.
     * Also remove any possible-existing value in the memory-cache.
     *
     * @param savedState the saved state to delete
     */
    public static void onDestroy(final SavedState savedState) {
        if (MainActivity.DEBUG) {
            Log.d(TAG, "onDestroy() called with: savedState = [" + savedState + "]");
        }

        if (savedState != null && !savedState.getPathFileSaved().isEmpty()) {
            STATE_OBJECTS_HOLDER.remove(savedState.getPrefixFileSaved());
            final String path = savedState.getPathFileSaved();
            ACTIVE_STATE_FILES.remove(new File(path).getAbsolutePath());
            final File stateDir = stateDir();
            FILE_DELETE_EXECUTOR.execute(() ->
                    StateSaverFileCleanup.deleteIfInside(stateDir, path));
        }
    }

    /**
     * Clear the in-memory cache immediately and schedule deletion of inactive disk cache files.
     */
    public static void clearStateFiles() {
        if (MainActivity.DEBUG) {
            Log.d(TAG, "clearStateFiles() called");
        }

        STATE_OBJECTS_HOLDER.clear();
        ACTIVE_STATE_FILES.clear();
        final File cacheDir = stateDir();
        FILE_DELETE_EXECUTOR.execute(() ->
                StateSaverFileCleanup.deleteInactiveFiles(cacheDir, ACTIVE_STATE_FILES));
    }

    /**
     * Used for describing how to save/read the objects.
     * <p>
     * Queue was chosen by its FIFO property.
     */
    public interface WriteRead {
        /**
         * Generate a changing suffix that will name the cache file,
         * and be used to identify if it changed (thus reducing useless reading/saving).
         *
         * @return a unique value
         */
        String generateSuffix();

        /**
         * Add to this queue objects that you want to save.
         *
         * @param objectsToSave the objects to save
         */
        void writeTo(Queue<Object> objectsToSave);

        /**
         * Poll saved objects from the queue in the order they were written.
         *
         * @param savedObjects queue of objects returned by {@link #writeTo(Queue)}
         */
        void readFrom(@NonNull Queue<Object> savedObjects) throws Exception;
    }
}
