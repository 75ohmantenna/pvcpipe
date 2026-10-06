package org.schabi.newpipe.download;

import static org.schabi.newpipe.extractor.stream.DeliveryMethod.HLS;
import static org.schabi.newpipe.extractor.stream.DeliveryMethod.PROGRESSIVE_HTTP;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import androidx.documentfile.provider.DocumentFile;
import androidx.preference.PreferenceManager;

import org.schabi.newpipe.R;
import org.schabi.newpipe.extractor.MediaFormat;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.Stream;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.SubtitlesStream;
import org.schabi.newpipe.extractor.stream.VideoStream;
import org.schabi.newpipe.streams.io.StoredDirectoryHelper;
import org.schabi.newpipe.streams.io.StoredFileHelper;
import org.schabi.newpipe.util.SponsorBlockSegment;

import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

import us.shandian.giga.get.MissionRecoveryInfo;
import us.shandian.giga.postprocessing.Postprocessing;
import us.shandian.giga.service.DownloadManager;
import us.shandian.giga.service.DownloadManagerService;
import us.shandian.giga.service.MissionState;

/**
 * Owns destination preparation and submission; presentation supplies user decisions.
 * Commands and their continuations run on the UI thread, alongside the manager's service handler.
 */
final class DownloadPreparation {
    enum Kind { AUDIO, VIDEO, SUBTITLE }
    enum LocationType { FOLDER, DOCUMENT }
    enum CollisionReason { FINISHED, PENDING, RUNNING, UNRELATED }
    enum CollisionAction { OVERWRITE, UNIQUE_NAME, NONE }
    enum FailureReason {
        GENERAL, STORAGE, PATH_CREATION, FILE_CREATION, PERMISSION_DENIED, OVERWRITE,
        INSUFFICIENT_STORAGE, INVALID_SELECTION, DISPATCH
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
            this.segments = segments == null ? new SponsorBlockSegment[0] : segments.clone();
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
        private final String id = UUID.randomUUID().toString();
        private final Ready ready;

        private Location(final LocationType type, final Kind kind, final String filename,
                         final String mime, final Ready ready) {
            this.ready = ready;
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
        private final AtomicBoolean used = new AtomicBoolean();

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
            if (used.compareAndSet(false, true)) {
                continuation.accept(uri);
            }
        }
    }

    static final class CollisionRequest {
        final CollisionReason reason;
        final CollisionAction action;
        private final Runnable continuation;
        private final AtomicBoolean used = new AtomicBoolean();

        CollisionRequest(final CollisionReason reason, final CollisionAction action,
                         final Runnable continuation) {
            this.reason = reason;
            this.action = action;
            this.continuation = continuation;
        }

        void confirm() {
            if (used.compareAndSet(false, true)) {
                continuation.run();
            }
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
            this.urls = urls.clone();
            this.storage = storage;
            this.kind = kind;
            this.threads = threads;
            this.info = info;
            this.psName = psName;
            this.psArgs = psArgs == null ? null : psArgs.clone();
            this.nearLength = nearLength;
            this.recoveryInfo = new ArrayList<>(recoveryInfo);
            this.segments = segments == null ? new SponsorBlockSegment[0] : segments.clone();
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
        default void warnCleanupFailure(final RuntimeException error) {
            // Test adapters can observe cleanup independently from an accepted submission.
        }
    }

    private final Platform platform;
    private final Supplier<Selection> latestSelection;
    private final Set<String> resumedLocations = new HashSet<>();
    private boolean closed;
    private String activeAttemptId;

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
        if (closed) {
            return;
        }
        activeAttemptId = UUID.randomUUID().toString();
        final Ready ready;
        try {
            if (baseName == null || baseName.isBlank() || destination == null) {
                throw new IllegalArgumentException("A download name and destination are required");
            }
            ready = new Ready(activeAttemptId, latestSelection.get());
        } catch (final RuntimeException error) {
            fail(presentation, FailureReason.INVALID_SELECTION, error);
            return;
        }
        final String filename = baseName + "." + ready.suffix;
        final StoredDirectoryHelper folder = destination.folder;
        if (!destination.askDocument && (folder == null || folder.isDirect()
                || folder.isInvalidSafStorage())) {
            chooseLocation(LocationType.FOLDER, filename, ready, presentation);
            return;
        }
        if (destination.askDocument) {
            chooseLocation(LocationType.DOCUMENT, filename, ready, presentation);
            return;
        }
        if (!hasSpace(folder, ready, presentation)) {
            return;
        }
        check(folder, folder.findFile(filename), filename, ready.mime, ready, presentation);
        if (isCurrent(ready)) {
            platform.rememberKind(ready.kind);
        }
    }

    private void chooseLocation(final LocationType type, final String filename, final Ready ready,
                                final Presentation presentation) {
        final Location location = new Location(type, ready.kind, filename, ready.mime, ready);
        presentation.chooseLocation(new LocationRequest(location,
                uri -> resume(location, uri, presentation)));
    }

    void resume(final Location location, final Uri uri, final Presentation presentation) {
        if (closed || location == null) {
            return;
        }
        if (location.ready == null) {
            fail(presentation, FailureReason.INVALID_SELECTION,
                    new IllegalArgumentException("The saved download selection is unavailable"));
            return;
        }
        if (activeAttemptId == null) {
            activeAttemptId = location.ready.id;
        }
        if (!isCurrent(location.ready) || !resumedLocations.add(location.id)) {
            return;
        }
        if (uri == null) {
            fail(presentation, FailureReason.GENERAL, null);
            return;
        }
        try {
            if (location.type == LocationType.FOLDER) {
                final StoredDirectoryHelper picked = platform.pickedFolder(uri, location.kind);
                if (hasSpace(picked, location.ready, presentation)) {
                    check(picked, picked.findFile(location.filename), location.filename,
                            location.mime, location.ready, presentation);
                }
            } else {
                final DocumentMetadata metadata = platform.documentMetadata(uri);
                check(null, uri, metadata.filename, metadata.mime, location.ready, presentation);
            }
        } catch (final IOException error) {
            fail(presentation, FailureReason.GENERAL, error);
        }
    }

    void close() {
        closed = true;
        activeAttemptId = null;
    }

    private boolean isCurrent(final Ready ready) {
        return !closed && ready.id.equals(activeAttemptId);
    }

    private static boolean hasSpace(final StoredDirectoryHelper folder, final Ready ready,
                                    final Presentation presentation) {
        if (folder.getFreeStorageSpace() <= ready.requiredSize) {
            fail(presentation, FailureReason.INSUFFICIENT_STORAGE, null);
            return false;
        }
        return true;
    }

    private void check(final StoredDirectoryHelper folder, final Uri target,
                       final String filename, final String mime, final Ready ready,
                       final Presentation presentation) {
        if (!isCurrent(ready)) {
            return;
        }
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
                        launch(storage, null, ready, presentation);
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
                        launch(created, null, ready, presentation);
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
            if (!isCurrent(ready) || action == CollisionAction.NONE) {
                return;
            }
            if (platform.missionState(storage) != state) {
                check(folder, folder == null ? target : folder.findFile(filename), filename, mime,
                        ready, presentation);
                return;
            }
            if (folder != null && !hasSpace(folder, ready, presentation)) {
                return;
            }
            final StoredFileHelper replacement;
            final StoredFileHelper replacedMission;
            if (folder == null) {
                replacement = storage;
                replacedMission = storage;
            } else if (state == MissionState.PendingRunning) {
                replacement = folder.createUniqueFile(filename, mime);
                replacedMission = null;
                if (replacement == null) {
                    fail(presentation, FailureReason.FILE_CREATION, null);
                    return;
                }
            } else {
                replacedMission = state == MissionState.Finished || state == MissionState.Pending
                        ? storage : null;
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
            launch(replacement, replacedMission, ready, presentation);
        }));
    }

    private void launch(final StoredFileHelper storage, final StoredFileHelper replacedMission,
                        final Ready ready, final Presentation presentation) {
        if (!isCurrent(ready)) {
            return;
        }
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
        try {
            platform.dispatch(ready.launch(storage));
        } catch (final RuntimeException error) {
            fail(presentation, FailureReason.DISPATCH, error);
            return;
        }
        if (replacedMission != null) {
            try {
                platform.forget(replacedMission);
            } catch (final RuntimeException error) {
                platform.warnCleanupFailure(error);
            }
        }
        presentation.submitted();
    }

    private static final class Ready implements Serializable {
        private static final long serialVersionUID = 1L;

        private final String id;
        private final Kind kind;
        private final String suffix;
        private final String mime;
        private final String[] urls;
        private final int threads;
        private final StreamInfo info;
        private final String psName;
        private final String[] psArgs;
        private final long nearLength;
        private final long requiredSize;
        private final List<MissionRecoveryInfo> recovery;
        private final SponsorBlockSegment[] segments;

        Ready(final String id, final Selection selected) {
            this.id = id;
            if (selected == null || selected.kind == null || selected.primary == null
                    || selected.info == null || selected.threads < 1) {
                throw new IllegalArgumentException("A complete stream selection is required");
            }
            final Stream primary = selected.primary;
            if ((selected.kind == Kind.AUDIO && !(primary instanceof AudioStream))
                    || (selected.kind == Kind.VIDEO && !(primary instanceof VideoStream))
                    || (selected.kind == Kind.SUBTITLE && !(primary instanceof SubtitlesStream))) {
                throw new IllegalArgumentException(
                        "The selected stream has a different media kind");
            }
            final MediaFormat format = primary.getFormat();
            if (format == null || !primary.isUrl() || primary.getContent() == null
                    || primary.getContent().isBlank()
                    || (primary.getDeliveryMethod() != PROGRESSIVE_HTTP
                    && !(selected.kind == Kind.VIDEO && primary.getDeliveryMethod() == HLS))) {
                throw new IllegalArgumentException("Unsupported stream format or delivery");
            }
            if (selected.secondary != null && (selected.kind != Kind.VIDEO
                    || primary.getDeliveryMethod() == HLS
                    || selected.secondary.getDeliveryMethod() != PROGRESSIVE_HTTP
                    || selected.secondary.getFormat() == null || !selected.secondary.isUrl()
                    || selected.secondary.getContent() == null
                    || selected.secondary.getContent().isBlank())) {
                throw new IllegalArgumentException("Unsupported secondary stream delivery");
            }
            kind = selected.kind;
            info = selected.info;
            segments = selected.segments.clone();
            mime = kind == Kind.AUDIO && format == MediaFormat.WEBMA_OPUS
                    ? "audio/ogg" : format.mimeType;
            suffix = kind == Kind.AUDIO && format == MediaFormat.WEBMA_OPUS ? "opus"
                    : kind == Kind.SUBTITLE && format == MediaFormat.TTML
                            ? MediaFormat.SRT.getSuffix() : format.getSuffix();
            threads = kind == Kind.SUBTITLE ? 1 : selected.threads;
            requiredSize = combinedSize(selected.primarySize,
                    selected.secondary == null ? 0 : selected.secondarySize);
            nearLength = selected.secondary != null && selected.primarySize > 0
                    && selected.secondarySize > 0 ? requiredSize : 0;
            if (kind == Kind.AUDIO && format == MediaFormat.M4A) {
                psName = Postprocessing.ALGORITHM_M4A_NO_DASH;
            } else if (kind == Kind.AUDIO && format == MediaFormat.WEBMA_OPUS) {
                psName = Postprocessing.ALGORITHM_OGG_FROM_WEBM_DEMUXER;
            } else if (kind == Kind.VIDEO && selected.secondary != null) {
                psName = format == MediaFormat.MPEG_4
                        ? Postprocessing.ALGORITHM_MP4_FROM_DASH_MUXER
                        : Postprocessing.ALGORITHM_WEBM_MUXER;
            } else if (kind == Kind.VIDEO && primary.getDeliveryMethod() == HLS) {
                psName = Postprocessing.ALGORITHM_PVC_HLS_REMUXER;
            } else if (kind == Kind.SUBTITLE && format == MediaFormat.TTML) {
                psName = Postprocessing.ALGORITHM_TTML_CONVERTER;
            } else {
                psName = null;
            }
            psArgs = kind == Kind.SUBTITLE && format == MediaFormat.TTML
                    ? new String[]{format.getSuffix(), "false"} : null;
            if (selected.secondary == null) {
                urls = new String[]{primary.getContent()};
                recovery = List.of(new MissionRecoveryInfo(primary));
            } else {
                urls = new String[]{primary.getContent(), selected.secondary.getContent()};
                recovery = List.of(new MissionRecoveryInfo(primary),
                        new MissionRecoveryInfo(selected.secondary));
            }
        }

        Launch launch(final StoredFileHelper storage) {
            final char mediaKind = kind == Kind.AUDIO ? 'a' : kind == Kind.VIDEO ? 'v' : 's';
            return new Launch(urls, storage, mediaKind, threads, info, psName, psArgs, nearLength,
                    recovery, segments);
        }

        private static long combinedSize(final long primary, final long secondary) {
            final long first = Math.max(0, primary);
            final long second = Math.max(0, secondary);
            return Long.MAX_VALUE - first < second ? Long.MAX_VALUE : first + second;
        }
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
        public void warnCleanupFailure(final RuntimeException error) {
            Log.w(DownloadPreparation.class.getSimpleName(),
                    "Download submitted; replaced mission cleanup failed", error);
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
