package org.schabi.newpipe.streams.io;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.UUID;

/**
 * Grants a single direct-download file without mapping its parent directory into FileProvider.
 * The opaque URI survives process death so a recipient can open it after the chooser closes.
 * SAF downloads use their document URI instead and never pass through this provider.
 */
public final class MissionFileProvider extends ContentProvider {
    private static final String AUTHORITY_SUFFIX = ".mission-files";
    private static final String PREFERENCES = "mission_file_grants";
    private static final String MIME_PREFIX = "mime_";

    @NonNull
    public static Uri register(@NonNull final Context context, @NonNull final File file,
                               @NonNull final String mimeType) throws IOException {
        // Resolve symlinks once, then only open that exact path when a recipient uses the grant.
        final File canonicalFile = file.getCanonicalFile();
        if (!canonicalFile.isFile()) {
            throw new FileNotFoundException(canonicalFile.toString());
        }
        // UUID.randomUUID uses a cryptographically strong random source.
        final String token = UUID.randomUUID().toString();
        final SharedPreferences preferences = context.getSharedPreferences(PREFERENCES,
                Context.MODE_PRIVATE);
        if (!preferences.edit().putString(token, canonicalFile.getPath())
                .putString(MIME_PREFIX + token, mimeType).commit()) {
            throw new IOException("Could not save mission file grant");
        }
        return new Uri.Builder().scheme("content")
                .authority(context.getPackageName() + AUTHORITY_SUFFIX)
                .appendPath(token).build();
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @NonNull
    private String token(@NonNull final Uri uri) throws FileNotFoundException {
        final Context context = getContext();
        if (context == null || !"content".equals(uri.getScheme())
                || !(context.getPackageName() + AUTHORITY_SUFFIX).equals(uri.getAuthority())
                || uri.getPathSegments().size() != 1 || uri.getQuery() != null
                || uri.getFragment() != null) {
            throw new FileNotFoundException("Invalid mission file URI");
        }
        return uri.getLastPathSegment();
    }

    @NonNull
    private File file(@NonNull final Uri uri) throws FileNotFoundException {
        final String path = getContext().getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .getString(token(uri), null);
        if (path == null) {
            throw new FileNotFoundException("Unknown mission file URI");
        }
        final File file = new File(path);
        try {
            if (!file.isFile() || !file.getCanonicalPath().equals(path)) {
                throw new FileNotFoundException("Mission file no longer exists");
            }
        } catch (final FileNotFoundException e) {
            throw e;
        } catch (final IOException e) {
            final FileNotFoundException notFound =
                    new FileNotFoundException("Could not resolve mission file");
            notFound.initCause(e);
            throw notFound;
        }
        return file;
    }

    @Nullable
    @Override
    public String getType(@NonNull final Uri uri) {
        try {
            final String token = token(uri);
            final SharedPreferences preferences = getContext().getSharedPreferences(PREFERENCES,
                    Context.MODE_PRIVATE);
            if (!preferences.contains(token)) {
                return null;
            }
            return preferences.getString(MIME_PREFIX + token, StoredFileHelper.DEFAULT_MIME);
        } catch (final FileNotFoundException e) {
            return null;
        }
    }

    @Nullable
    @Override
    public Cursor query(@NonNull final Uri uri, @Nullable final String[] projection,
                        @Nullable final String selection, @Nullable final String[] selectionArgs,
                        @Nullable final String sortOrder) {
        final File file;
        try {
            file = file(uri);
        } catch (final FileNotFoundException e) {
            return null;
        }
        final String[] columns = projection == null
                ? new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE} : projection;
        final Object[] values = new Object[columns.length];
        for (int i = 0; i < columns.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(columns[i])) {
                values[i] = file.getName();
            } else if (OpenableColumns.SIZE.equals(columns[i])) {
                values[i] = file.length();
            }
        }
        final MatrixCursor cursor = new MatrixCursor(columns, 1);
        cursor.addRow(values);
        return cursor;
    }

    @NonNull
    @Override
    public ParcelFileDescriptor openFile(@NonNull final Uri uri, @NonNull final String mode)
            throws FileNotFoundException {
        if (!"r".equals(mode)) {
            throw new FileNotFoundException("Mission files are read-only");
        }
        try {
            // The leaf cannot be replaced by a symlink after the canonical-path check.
            final FileDescriptor descriptor = Os.open(file(uri).getPath(),
                    OsConstants.O_RDONLY | OsConstants.O_NOFOLLOW, 0);
            try {
                return ParcelFileDescriptor.dup(descriptor);
            } finally {
                Os.close(descriptor);
            }
        } catch (final ErrnoException | IOException e) {
            final FileNotFoundException notFound =
                    new FileNotFoundException("Could not open mission file");
            notFound.initCause(e);
            throw notFound;
        }
    }

    @Nullable
    @Override
    public Uri insert(@NonNull final Uri uri, @Nullable final ContentValues values) {
        throw new UnsupportedOperationException("Mission files are read-only");
    }

    @Override
    public int delete(@NonNull final Uri uri, @Nullable final String selection,
                      @Nullable final String[] selectionArgs) {
        throw new UnsupportedOperationException("Mission files are read-only");
    }

    @Override
    public int update(@NonNull final Uri uri, @Nullable final ContentValues values,
                      @Nullable final String selection, @Nullable final String[] selectionArgs) {
        throw new UnsupportedOperationException("Mission files are read-only");
    }
}
