package org.schabi.newpipe.streams.io;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.documentfile.provider.DocumentFile;

import org.schabi.newpipe.MainActivity;

import java.io.IOException;
import java.io.Serializable;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;

import us.shandian.giga.io.FileStream;
import us.shandian.giga.io.FileStreamSAF;

public class StoredFileHelper implements Serializable {
    private static final boolean DEBUG = MainActivity.DEBUG;
    private static final String TAG = StoredFileHelper.class.getSimpleName();

    private static final long serialVersionUID = 0L;
    public static final String DEFAULT_MIME = "application/octet-stream";

    private transient DocumentFile docFile;
    private transient DocumentFile docTree;
    private transient Path ioPath;
    private transient Context context;

    protected String source;
    private String sourceTree;

    protected String tag;

    private String srcName;
    private String srcType;

    public StoredFileHelper(final Context context, final Uri uri, final String mime) {
        if (ContentResolver.SCHEME_FILE.equals(uri.getScheme())) {
            ioPath = Paths.get(URI.create(uri.toString()));
        } else {
            docFile = DocumentFile.fromSingleUri(context, uri);
        }
        source = uri.toString();

        this.context = context;
        this.srcType = mime;
        this.srcName = uri.getLastPathSegment();
    }

    public StoredFileHelper(@Nullable final Uri parent, final String filename, final String mime,
                            final String tag) {
        this.source = null; // this instance will be "invalid" see invalidate()/isInvalid() methods

        this.srcName = filename;
        this.srcType = mime == null ? DEFAULT_MIME : mime;
        if (parent != null) {
            this.sourceTree = parent.toString();
        }

        this.tag = tag;
    }

    StoredFileHelper(@Nullable final Context context, final DocumentFile tree,
                     final String filename, final String mime, final boolean safe)
            throws IOException {
        this.docTree = tree;
        this.context = context;

        final DocumentFile res;

        if (safe) {
            // no conflicts (the filename is not in use)
            res = this.docTree.createFile(mime, filename);
            if (res == null) {
                throw new IOException("Cannot create the file");
            }
        } else {
            res = createSAF(context, mime, filename);
        }

        this.docFile = res;

        this.source = docFile.getUri().toString();
        this.sourceTree = docTree.getUri().toString();

        this.srcName = this.docFile.getName();
        this.srcType = this.docFile.getType();
    }

    /**
     * Download names come from titles and settings. A name must be one path component, so that
     * creating it cannot replace or reveal a file outside the folder it is created in.
     *
     * @param filename the name to check
     * @return whether the name is a single ordinary file name
     */
    public static boolean isPlainFileName(@Nullable final String filename) {
        return filename != null && !filename.isEmpty()
                && !".".equals(filename) && !"..".equals(filename)
                && filename.indexOf('/') < 0 && filename.indexOf('\\') < 0
                && filename.indexOf('\0') < 0;
    }

    StoredFileHelper(final Path location, final String filename, final String mime)
            throws IOException {
        if (!isPlainFileName(filename)) {
            throw new IOException("Not a plain file name: " + filename);
        }
        ioPath = location.resolve(filename);
        if (!location.equals(ioPath.getParent())) {
            throw new IOException("File name leaves its folder: " + filename);
        }

        Files.deleteIfExists(ioPath);
        Files.createFile(ioPath);

        source = Uri.fromFile(ioPath.toFile()).toString();
        sourceTree = Uri.fromFile(location.toFile()).toString();

        srcName = ioPath.getFileName().toString();
        srcType = mime;
    }

    public StoredFileHelper(final Context context, @Nullable final Uri parent,
                            @NonNull final Uri path, final String tag) throws IOException {
        this.tag = tag;
        this.source = path.toString();

        if (path.getScheme() == null
                || path.getScheme().equalsIgnoreCase(ContentResolver.SCHEME_FILE)) {
            this.ioPath = Paths.get(URI.create(this.source));
        } else {
            final DocumentFile file = DocumentFile.fromSingleUri(context, path);

            if (file == null) {
                throw new IOException("SAF not available");
            }

            this.context = context;

            if (file.getName() == null) {
                this.source = null;
                return;
            } else {
                this.docFile = file;
                takePermissionSAF();
            }
        }

        if (parent != null) {
            if (!ContentResolver.SCHEME_FILE.equals(parent.getScheme())) {
                this.docTree = DocumentFile.fromTreeUri(context, parent);
            }

            this.sourceTree = parent.toString();
        }

        this.srcName = getName();
        this.srcType = getType();
    }


    public static StoredFileHelper deserialize(@NonNull final StoredFileHelper storage,
                                               final Context context) throws IOException {
        final Uri treeUri = storage.sourceTree == null ? null : Uri.parse(storage.sourceTree);

        if (storage.isInvalid()) {
            return new StoredFileHelper(treeUri, storage.srcName, storage.srcType, storage.tag);
        }

        final StoredFileHelper instance = new StoredFileHelper(context, treeUri,
                Uri.parse(storage.source), storage.tag);

        // under SAF, if the target document is deleted, conserve the filename and mime
        if (instance.srcName == null) {
            instance.srcName = storage.srcName;
        }
        if (instance.srcType == null) {
            instance.srcType = storage.srcType;
        }

        return instance;
    }

    /**
     * Restores a deletion target without discarding its URI when provider metadata is unavailable.
     * This does not acquire permissions or prepare the target for writing.
     *
     * @param storage the saved deletion target
     * @param context the resolver context
     * @return the restored target, retaining its saved name and URI
     */
    public static StoredFileHelper deserializeForDeletion(@NonNull final StoredFileHelper storage,
                                                          final Context context) {
        if (storage.isInvalid()) {
            return storage;
        }
        final StoredFileHelper restored = new StoredFileHelper(context,
                Uri.parse(storage.source), storage.srcType);
        if (storage.srcName != null) {
            restored.srcName = storage.srcName;
        }
        restored.sourceTree = storage.sourceTree;
        restored.tag = storage.tag;
        return restored;
    }

    public SharpStream getStream() throws IOException {
        assertValid();

        if (docFile == null) {
            return new FileStream(ioPath.toFile());
        } else {
            return new FileStreamSAF(context.getContentResolver(), docFile.getUri());
        }
    }

    public SharpStream openAndTruncateStream() throws IOException {
        final SharpStream sharpStream = getStream();
        try {
            sharpStream.setLength(0);
        } catch (final Throwable e) {
            // we can't use try-with-resources here, since we only want to close the stream if an
            // exception occurs, but leave it open if everything goes well
            sharpStream.close();
            throw e;
        }
        return sharpStream;
    }

    /**
     * Indicates whether it's using the {@code java.io} API.
     *
     * @return {@code true} for Java I/O API, otherwise, {@code false} for Storage Access Framework
     */
    public boolean isDirect() {
        assertValid();

        return docFile == null;
    }

    public boolean isInvalid() {
        return source == null;
    }

    public Uri getUri() {
        assertValid();

        return docFile == null ? Uri.fromFile(ioPath.toFile()) : docFile.getUri();
    }

    public Uri getParentUri() {
        assertValid();

        return sourceTree == null ? null : Uri.parse(sourceTree);
    }

    public void truncate() throws IOException {
        assertValid();

        try (SharpStream fs = getStream()) {
            fs.setLength(0);
        }
    }

    public boolean delete() {
        return delete(true);
    }

    /**
     * Deletes media without requesting grant release. Providers can revoke access themselves.
     *
     * @return whether the target was deleted or confirmed absent
     */
    public boolean deleteRetainingPermission() {
        return delete(false);
    }

    private boolean delete(final boolean releaseGrant) {
        if (source == null) {
            return true;
        }
        if (docFile == null) {
            try {
                Files.deleteIfExists(ioPath);
                return true;
            } catch (final IOException e) {
                Log.e(TAG, "Exception while deleting " + ioPath, e);
                return false;
            }
        }

        if (!docFile.delete() && !isAbsent()) {
            return false;
        }

        if (releaseGrant) {
            releasePermission();
        }
        return true;
    }

    /** Releases a document grant after successful deletion of its owning entry/checkpoint. */
    public void releasePermission() {
        if (docFile == null || context == null) {
            return;
        }
        try {
            final int flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
            context.getContentResolver().releasePersistableUriPermission(docFile.getUri(), flags);
        } catch (final Exception ex) {
            // nothing to do
        }
    }

    /**
     * Distinguishes confirmed absence from a denied or unavailable provider query.
     *
     * @return whether the filesystem or provider positively reports no target
     */
    public boolean isAbsent() {
        if (source == null) {
            return false;
        }
        if (ioPath != null) {
            try {
                return Files.notExists(ioPath);
            } catch (final SecurityException e) {
                return false;
            }
        }
        if (docFile == null || context == null) {
            return false;
        }
        try (Cursor cursor = context.getContentResolver().query(docFile.getUri(),
                new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID}, null, null, null)) {
            return cursor != null && cursor.getCount() == 0;
        } catch (final Exception e) {
            Log.w(TAG, "Unable to establish document absence", e);
            return false;
        }
    }

    public long length() {
        assertValid();

        if (docFile == null) {
            try {
                return Files.size(ioPath);
            } catch (final IOException e) {
                Log.e(TAG, "Exception while getting the size of " + ioPath, e);
                return 0;
            }
        } else {
            return docFile.length();
        }
    }

    public boolean canWrite() {
        if (source == null) {
            return false;
        }
        return docFile == null ? Files.isWritable(ioPath) : docFile.canWrite();
    }

    public String getName() {
        if (source == null) {
            return srcName;
        } else if (docFile == null) {
            return ioPath.getFileName().toString();
        }

        final String name = docFile.getName();
        return name == null ? srcName : name;
    }

    public String getType() {
        if (source == null || docFile == null) {
            return srcType;
        }

        final String type = docFile.getType();
        return type == null ? srcType : type;
    }

    public String getTag() {
        return tag;
    }

    public boolean existsAsFile() {
        if (source == null || (docFile == null && ioPath == null)) {
            if (DEBUG) {
                Log.d(TAG, "existsAsFile called but something is null: source = ["
                        + (source == null ? "null => storage is invalid" : source)
                        + "], docFile = [" + docFile + "], ioPath = [" + ioPath + "]");
            }
            return false;
        }

        // WARNING: DocumentFile.exists() and DocumentFile.isFile() methods are slow
        // docFile.isVirtual() means it is non-physical?
        return docFile == null
                ? Files.isRegularFile(ioPath)
                : (docFile.exists() && docFile.isFile());
    }

    public boolean create() {
        assertValid();
        final boolean result;

        if (docFile == null) {
            try {
                Files.createFile(ioPath);
                result = true;
            } catch (final IOException e) {
                Log.e(TAG, "Exception while creating " + ioPath, e);
                return false;
            }
        } else if (docTree == null) {
            result = false;
        } else {
            if (!docTree.canRead() || !docTree.canWrite()) {
                return false;
            }
            try {
                docFile = createSAF(context, srcType, srcName);
                if (docFile.getName() == null) {
                    return false;
                }
                result = true;
            } catch (final IOException e) {
                return false;
            }
        }

        if (result) {
            source = (docFile == null ? Uri.fromFile(ioPath.toFile()) : docFile.getUri())
                    .toString();
            srcName = getName();
            srcType = getType();
        }

        return result;
    }

    public void invalidate() {
        if (source == null) {
            return;
        }

        srcName = getName();
        srcType = getType();

        source = null;

        docTree = null;
        docFile = null;
        ioPath = null;
        context = null;
    }

    public boolean equals(final StoredFileHelper storage) {
        if (this == storage) {
            return true;
        }

        // note: do not compare tags, files can have the same parent folder
        //if (stringMismatch(this.tag, storage.tag)) return false;

        if (!Objects.equals(this.sourceTree, storage.sourceTree)) {
            return false;
        }

        if (this.isInvalid() || storage.isInvalid()) {
            if (this.srcName == null || storage.srcName == null || this.srcType == null
                    || storage.srcType == null) {
                return false;
            }

            return this.srcName.equalsIgnoreCase(storage.srcName)
                    && this.srcType.equalsIgnoreCase(storage.srcType);
        }

        if (this.isDirect() != storage.isDirect()) {
            return false;
        }

        if (this.isDirect()) {
            return this.ioPath.equals(storage.ioPath);
        }

        return DocumentsContract.getDocumentId(this.docFile.getUri())
                .equalsIgnoreCase(DocumentsContract.getDocumentId(storage.docFile.getUri()));
    }

    @NonNull
    @Override
    public String toString() {
        if (source == null) {
            return "[Invalid state] name=" + srcName + "  type=" + srcType + "  tag=" + tag;
        } else {
            return "sourceFile=" + source + "  treeSource=" + (sourceTree == null ? "" : sourceTree)
                    + "  tag=" + tag;
        }
    }


    private void assertValid() {
        if (source == null) {
            throw new IllegalStateException("In invalid state");
        }
    }

    private void takePermissionSAF() throws IOException {
        try {
            context.getContentResolver().takePersistableUriPermission(docFile.getUri(),
                    StoredDirectoryHelper.PERMISSION_FLAGS);
        } catch (final Exception e) {
            if (docFile.getName() == null) {
                throw new IOException(e);
            }
        }
    }

    @NonNull
    private DocumentFile createSAF(@Nullable final Context ctx, final String mime,
                                   final String filename) throws IOException {
        DocumentFile res = StoredDirectoryHelper.findFileSAFHelper(ctx, docTree, filename);

        if (res != null && res.exists() && res.isDirectory()) {
            if (!res.delete()) {
                throw new IOException("Directory with the same name found but cannot delete");
            }
            res = null;
        }

        if (res == null) {
            res = this.docTree.createFile(srcType == null ? DEFAULT_MIME : mime, filename);
            if (res == null) {
                throw new IOException("Cannot create the file");
            }
        }

        return res;
    }

    public static Intent getPicker(@NonNull final Context ctx,
                                   @NonNull final String mimeType) {
        return new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .putExtra("android.content.extra.SHOW_ADVANCED", true)
                .setType(mimeType)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                        | StoredDirectoryHelper.PERMISSION_FLAGS);
    }

    public static Intent getPicker(@NonNull final Context ctx,
                                   @NonNull final String mimeType,
                                   @Nullable final Uri initialPath) {
        return applyInitialPathToPickerIntent(getPicker(ctx, mimeType), initialPath);
    }

    public static Intent getNewPicker(@NonNull final Context ctx,
                                      @Nullable final String filename,
                                      @NonNull final String mimeType,
                                      @Nullable final Uri initialPath) {
        final Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .putExtra("android.content.extra.SHOW_ADVANCED", true)
                .setType(mimeType)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                        | StoredDirectoryHelper.PERMISSION_FLAGS);
        if (filename != null) {
            intent.putExtra(Intent.EXTRA_TITLE, filename);
        }
        return applyInitialPathToPickerIntent(intent, initialPath);
    }

    private static Intent applyInitialPathToPickerIntent(@NonNull final Intent intent,
                                                         @Nullable final Uri initialPath) {
        return initialPath == null ? intent
                : intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, initialPath);
    }
}
