package org.schabi.newpipe.download;

import static org.schabi.newpipe.extractor.stream.DeliveryMethod.HLS;
import static org.schabi.newpipe.extractor.stream.DeliveryMethod.PROGRESSIVE_HTTP;

import android.content.Context;
import android.net.Uri;

import androidx.documentfile.provider.DocumentFile;
import androidx.preference.PreferenceManager;

import org.schabi.newpipe.R;
import org.schabi.newpipe.extractor.MediaFormat;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.Stream;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.streams.io.StoredDirectoryHelper;
import org.schabi.newpipe.streams.io.StoredFileHelper;
import org.schabi.newpipe.util.SponsorBlockSegment;

import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

import us.shandian.giga.get.MissionRecoveryInfo;
import us.shandian.giga.postprocessing.Postprocessing;
import us.shandian.giga.service.DownloadManager;
import us.shandian.giga.service.DownloadManagerService;
import us.shandian.giga.service.MissionState;

/** Owns destination preparation and submission; presentation supplies user decisions. */
final class DownloadPreparation {
    enum Kind { AUDIO, VIDEO, SUBTITLE }
    enum LocationType { FOLDER, DOCUMENT }
    enum CollisionReason { FINISHED, PENDING, RUNNING, UNRELATED }
    enum CollisionAction { OVERWRITE, UNIQUE_NAME, NONE }
    enum FailureReason {
        GENERAL, STORAGE, PATH_CREATION, FILE_CREATION, PERMISSION_DENIED, OVERWRITE,
        INSUFFICIENT_STORAGE
    }

    static final class Selection {
        final Kind kind;
        final Stream primary;
        final AudioStream secondary;
        final long primarySize;
        final long secondarySize;
        final int threads;
        final StreamInfo info;
        final SponsorBlockSegment[] segments;

        @SuppressWarnings("checkstyle:ParameterNumber")
        Selection(final Kind kind, final Stream primary, final AudioStream secondary,
                  final long primarySize, final long secondarySize, final int threads,
                  final StreamInfo info, final SponsorBlockSegment[] segments) {
            this.kind = kind;
            this.primary = primary;
            this.secondary = secondary;
            this.primarySize = primarySize;
            this.secondarySize = secondarySize;
            this.threads = threads;
            this.info = info;
            this.segments = segments;
        }
    }

    static final class Destination {
        final StoredDirectoryHelper folder;
        final boolean askDocument;

        private Destination(final StoredDirectoryHelper folder, final boolean askDocument) {
            this.folder = folder;
            this.askDocument = askDocument;
        }

        static Destination savedFolder(final StoredDirectoryHelper folder) {
            return new Destination(folder, false);
        }

        static Destination askDocument() {
            return new Destination(null, true);
        }
    }

    interface Presentation {
        void chooseLocation(LocationRequest request);
        void confirmCollision(CollisionRequest request);
        void submitted();
        void failed(Failure failure);
    }

    static final class Location implements Serializable {
        private static final long serialVersionUID = 1L;

        final LocationType type;
        final Kind kind;
        final String filename;
        final String mime;

        private Location(final LocationType type, final Kind kind, final String filename,
                         final String mime) {
            this.type = type;
            this.kind = kind;
            this.filename = filename;
            this.mime = mime;
        }
    }

    static final class LocationRequest {
        final LocationType type;
        final Kind kind;
        final String filename;
        final String mime;
        private final Location location;
        private final Consumer<Uri> continuation;

        LocationRequest(final Location location, final Consumer<Uri> continuation) {
            this.type = location.type;
            this.kind = location.kind;
            this.filename = location.filename;
            this.mime = location.mime;
            this.location = location;
            this.continuation = continuation;
        }

        Location state() {
            return location;
        }

        void complete(final Uri uri) {
            continuation.accept(uri);
        }
    }

    static final class CollisionRequest {
        final CollisionReason reason;
        final CollisionAction action;
        private final Runnable continuation;

        CollisionRequest(final CollisionReason reason, final CollisionAction action,
                         final Runnable continuation) {
            this.reason = reason;
            this.action = action;
            this.continuation = continuation;
        }

        void confirm() {
            continuation.run();
        }
    }

    static final class Failure {
        final FailureReason reason;
        final Throwable cause;

        Failure(final FailureReason reason, final Throwable cause) {
            this.reason = reason;
            this.cause = cause;
        }
    }

    static final class DocumentMetadata {
        final String filename;
        final String mime;

        DocumentMetadata(final String filename, final String mime) {
            this.filename = filename;
            this.mime = mime;
        }
    }

    static final class Launch {
        final String[] urls;
        final StoredFileHelper storage;
        final char kind;
        final int threads;
        final StreamInfo info;
        final String psName;
        final String[] psArgs;
        final long nearLength;
        final ArrayList<MissionRecoveryInfo> recoveryInfo;
        final SponsorBlockSegment[] segments;

        @SuppressWarnings("checkstyle:ParameterNumber")
        Launch(final String[] urls, final StoredFileHelper storage, final char kind,
               final int threads, final StreamInfo info, final String psName,
               final String[] psArgs, final long nearLength,
               final List<MissionRecoveryInfo> recoveryInfo,
               final SponsorBlockSegment[] segments) {
            this.urls = urls;
            this.storage = storage;
            this.kind = kind;
            this.threads = threads;
            this.info = info;
            this.psName = psName;
            this.psArgs = psArgs;
            this.nearLength = nearLength;
            this.recoveryInfo = new ArrayList<>(recoveryInfo);
            this.segments = segments;
        }
    }

    interface Platform {
        StoredDirectoryHelper pickedFolder(Uri uri, Kind kind) throws IOException;
        DocumentMetadata documentMetadata(Uri uri) throws IOException;
        StoredFileHelper file(StoredDirectoryHelper folder, Uri uri) throws IOException;
        MissionState missionState(StoredFileHelper file);
        void forget(StoredFileHelper file);
        void dispatch(Launch launch);
        void rememberKind(Kind kind);
    }

    private final Platform platform;
    private final Supplier<Selection> latestSelection;
    private String lastMime;

    DownloadPreparation(final Context context, final DownloadManager manager,
                        final Supplier<Selection> latestSelection) {
        this(new AndroidPlatform(context, manager), latestSelection);
    }

    DownloadPreparation(final Platform platform, final Supplier<Selection> latestSelection) {
        this.platform = platform;
        this.latestSelection = latestSelection;
    }

    void save(final String baseName, final Destination destination,
              final Presentation presentation) {
        final Selection selected = latestSelection.get();
        if (selected == null) {
            throw new IllegalStateException("No stream selected");
        }
        final MediaFormat format = selected.primary.getFormat();
        String filename = baseName + ".";
        if (selected.kind == Kind.AUDIO && format == MediaFormat.WEBMA_OPUS) {
            lastMime = "audio/ogg";
            filename += "opus";
        } else if (format != null) {
            lastMime = format.mimeType;
            filename += selected.kind == Kind.SUBTITLE && format == MediaFormat.TTML
                    ? MediaFormat.SRT.getSuffix() : format.getSuffix();
        }
        final String outputName = filename;
        final String mime = lastMime;
        final StoredDirectoryHelper folder = destination.folder;
        if (!destination.askDocument && (folder == null || folder.isDirect()
                || folder.isInvalidSafStorage())) {
            final Location location = new Location(LocationType.FOLDER, selected.kind,
                    outputName, mime);
            presentation.chooseLocation(new LocationRequest(location,
                    uri -> resume(location, uri, presentation)));
            return;
        }
        if (destination.askDocument) {
            final Location location = new Location(LocationType.DOCUMENT, selected.kind,
                    outputName, mime);
            presentation.chooseLocation(new LocationRequest(location,
                    uri -> resume(location, uri, presentation)));
            return;
        }
        if (folder.getFreeStorageSpace() <= selected.primarySize) {
            fail(presentation, FailureReason.INSUFFICIENT_STORAGE, null);
            return;
        }
        check(folder, folder.findFile(outputName), outputName, mime, presentation);
        platform.rememberKind(selected.kind);
    }

    void resume(final Location location, final Uri uri, final Presentation presentation) {
        if (uri == null) {
            fail(presentation, FailureReason.GENERAL, null);
            return;
        }
        try {
            if (location.type == LocationType.FOLDER) {
                final StoredDirectoryHelper picked = platform.pickedFolder(uri, location.kind);
                check(picked, picked.findFile(location.filename), location.filename, location.mime,
                        presentation);
            } else {
                final DocumentMetadata metadata = platform.documentMetadata(uri);
                check(null, uri, metadata.filename, metadata.mime, presentation);
            }
        } catch (final IOException error) {
            fail(presentation, FailureReason.GENERAL, error);
        }
    }

    private void check(final StoredDirectoryHelper folder, final Uri target,
                       final String filename, final String mime,
                       final Presentation presentation) {
        final StoredFileHelper storage;
        try {
            storage = folder != null && target == null
                    ? new StoredFileHelper(folder.getUri(), filename, mime, folder.getTag())
                    : platform.file(folder, target);
        } catch (final Exception error) {
            fail(presentation, FailureReason.STORAGE, error);
            return;
        }
        final MissionState state = platform.missionState(storage);
        final CollisionReason reason;
        final CollisionAction action;
        switch (state) {
            case Finished:
                reason = CollisionReason.FINISHED;
                action = CollisionAction.OVERWRITE;
                break;
            case Pending:
                reason = CollisionReason.PENDING;
                action = CollisionAction.OVERWRITE;
                break;
            case PendingRunning:
                reason = CollisionReason.RUNNING;
                action = folder == null ? CollisionAction.NONE : CollisionAction.UNIQUE_NAME;
                break;
            case None:
                if (folder == null) {
                    if (!storage.existsAsFile() && !storage.create()) {
                        fail(presentation, FailureReason.FILE_CREATION, null);
                    } else {
                        launch(storage, presentation);
                    }
                    return;
                }
                if (target == null) {
                    if (!folder.mkdirs()) {
                        fail(presentation, FailureReason.PATH_CREATION, null);
                        return;
                    }
                    final StoredFileHelper created = folder.createFile(filename, mime);
                    if (created == null || !created.canWrite()) {
                        fail(presentation, FailureReason.FILE_CREATION, null);
                    } else {
                        launch(created, presentation);
                    }
                    return;
                }
                reason = CollisionReason.UNRELATED;
                action = CollisionAction.OVERWRITE;
                break;
            default:
                return;
        }
        presentation.confirmCollision(new CollisionRequest(reason, action, () -> {
            if (action == CollisionAction.NONE) {
                return;
            }
            if (folder == null) {
                platform.forget(storage);
                launch(storage, presentation);
                return;
            }
            final StoredFileHelper replacement;
            if (state == MissionState.PendingRunning) {
                replacement = folder.createUniqueFile(filename, mime);
                if (replacement == null) {
                    fail(presentation, FailureReason.FILE_CREATION, null);
                    return;
                }
            } else {
                if (state == MissionState.Finished || state == MissionState.Pending) {
                    platform.forget(storage);
                }
                if (target == null) {
                    replacement = folder.createFile(filename, mime);
                } else {
                    StoredFileHelper resolved;
                    try {
                        resolved = platform.file(folder, target);
                    } catch (final IOException error) {
                        resolved = null;
                    }
                    replacement = resolved;
                }
                if (replacement == null || !replacement.canWrite()) {
                    fail(presentation, FailureReason.FILE_CREATION, null);
                    return;
                }
            }
            launch(replacement, presentation);
        }));
    }

    private void launch(final StoredFileHelper storage, final Presentation presentation) {
        if (!storage.canWrite()) {
            fail(presentation, FailureReason.PERMISSION_DENIED, null);
            return;
        }
        try {
            if (storage.length() > 0) {
                storage.truncate();
            }
        } catch (final IOException error) {
            fail(presentation, FailureReason.OVERWRITE, error);
            return;
        }
        final Selection selected = latestSelection.get();
        if (selected == null) {
            return;
        }
        int threads = selected.threads;
        final char kind;
        String psName = null;
        String[] psArgs = null;
        long nearLength = 0;
        switch (selected.kind) {
            case AUDIO:
                kind = 'a';
                if (selected.primary.getFormat() == MediaFormat.M4A) {
                    psName = Postprocessing.ALGORITHM_M4A_NO_DASH;
                } else if (selected.primary.getFormat() == MediaFormat.WEBMA_OPUS) {
                    psName = Postprocessing.ALGORITHM_OGG_FROM_WEBM_DEMUXER;
                }
                break;
            case VIDEO:
                kind = 'v';
                if (selected.primary.getDeliveryMethod() == HLS) {
                    psName = Postprocessing.ALGORITHM_PVC_HLS_REMUXER;
                }
                if (selected.secondary != null) {
                    psName = selected.primary.getFormat() == MediaFormat.MPEG_4
                            ? Postprocessing.ALGORITHM_MP4_FROM_DASH_MUXER
                            : Postprocessing.ALGORITHM_WEBM_MUXER;
                    if (selected.secondarySize > 0 && selected.primarySize > 0) {
                        nearLength = selected.secondarySize + selected.primarySize;
                    }
                }
                break;
            case SUBTITLE:
                kind = 's';
                threads = 1;
                if (selected.primary.getFormat() == MediaFormat.TTML) {
                    psName = Postprocessing.ALGORITHM_TTML_CONVERTER;
                    psArgs = new String[]{selected.primary.getFormat().getSuffix(), "false"};
                }
                break;
            default:
                return;
        }
        final String[] urls;
        final List<MissionRecoveryInfo> recovery;
        if (selected.secondary == null) {
            urls = new String[]{selected.primary.getContent()};
            recovery = List.of(new MissionRecoveryInfo(selected.primary));
        } else {
            if (selected.secondary.getDeliveryMethod() != PROGRESSIVE_HTTP) {
                throw new IllegalArgumentException("Unsupported stream delivery format"
                        + selected.secondary.getDeliveryMethod());
            }
            urls = new String[]{selected.primary.getContent(), selected.secondary.getContent()};
            recovery = List.of(new MissionRecoveryInfo(selected.primary),
                    new MissionRecoveryInfo(selected.secondary));
        }
        platform.dispatch(new Launch(urls, storage, kind, threads, selected.info, psName, psArgs,
                nearLength, recovery, selected.segments));
        presentation.submitted();
    }

    private static void fail(final Presentation presentation, final FailureReason reason,
                             final Throwable cause) {
        presentation.failed(new Failure(reason, cause));
    }

    private static final class AndroidPlatform implements Platform {
        private final Context context;
        private final DownloadManager manager;

        AndroidPlatform(final Context context, final DownloadManager manager) {
            this.context = context;
            this.manager = manager;
        }

        @Override
        public StoredDirectoryHelper pickedFolder(final Uri uri, final Kind kind)
                throws IOException {
            context.grantUriPermission(context.getPackageName(), uri,
                    StoredDirectoryHelper.PERMISSION_FLAGS);
            final int key = kind == Kind.AUDIO ? R.string.download_path_audio_key
                    : R.string.download_path_video_key;
            PreferenceManager.getDefaultSharedPreferences(context).edit()
                    .putString(context.getString(key), uri.toString()).apply();
            return new StoredDirectoryHelper(context, uri, kind == Kind.AUDIO
                    ? DownloadManager.TAG_AUDIO : DownloadManager.TAG_VIDEO);
        }

        @Override
        public DocumentMetadata documentMetadata(final Uri uri) throws IOException {
            final DocumentFile document = DocumentFile.fromSingleUri(context, uri);
            if (document == null) {
                throw new IOException("No document available");
            }
            return new DocumentMetadata(document.getName(), document.getType());
        }

        @Override
        public StoredFileHelper file(final StoredDirectoryHelper folder, final Uri uri)
                throws IOException {
            return new StoredFileHelper(context, folder == null ? null : folder.getUri(), uri,
                    folder == null ? "" : folder.getTag());
        }

        @Override
        public MissionState missionState(final StoredFileHelper file) {
            return manager.checkForExistingMission(file);
        }

        @Override
        public void forget(final StoredFileHelper file) {
            manager.forgetMission(file);
        }

        @Override
        public void dispatch(final Launch launch) {
            DownloadManagerService.startMission(context, launch.urls, launch.storage, launch.kind,
                    launch.threads, launch.info, launch.psName, launch.psArgs, launch.nearLength,
                    launch.recoveryInfo, launch.segments);
        }

        @Override
        public void rememberKind(final Kind kind) {
            final int value;
            switch (kind) {
                case AUDIO:
                    value = R.string.last_download_type_audio_key;
                    break;
                case VIDEO:
                    value = R.string.last_download_type_video_key;
                    break;
                case SUBTITLE:
                    value = R.string.last_download_type_subtitle_key;
                    break;
                default:
                    throw new IllegalArgumentException("Unknown media kind");
            }
            PreferenceManager.getDefaultSharedPreferences(context).edit()
                    .putString(context.getString(R.string.last_used_download_type),
                            context.getString(value)).apply();
        }
    }
}
