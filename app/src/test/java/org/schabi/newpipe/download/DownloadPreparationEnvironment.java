package org.schabi.newpipe.download;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.net.Uri;

import org.schabi.newpipe.streams.io.StoredDirectoryHelper;
import org.schabi.newpipe.streams.io.StoredFileHelper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import us.shandian.giga.service.MissionState;

/** Controlled Android and service effects; destination mutations use real temporary files. */
final class DownloadPreparationEnvironment implements DownloadPreparation.Platform,
        DownloadPreparation.Presentation {
    final Path directory;
    final Path output;
    final Path uniqueOutput;
    final StoredDirectoryHelper folder = mock(StoredDirectoryHelper.class);
    final Uri folderUri = mock(Uri.class);
    final Uri fileUri = mock(Uri.class);
    final List<DownloadPreparation.Launch> launches = new ArrayList<>();
    final List<DownloadPreparation.Failure> failures = new ArrayList<>();
    final List<StoredFileHelper> forgotten = new ArrayList<>();
    final List<RuntimeException> cleanupWarnings = new ArrayList<>();
    final List<DownloadPreparation.Kind> remembered = new ArrayList<>();
    final StoredFileHelper storage;
    final StoredFileHelper uniqueStorage;
    DownloadPreparation.LocationRequest location;
    DownloadPreparation.CollisionRequest collision;
    DownloadPreparation.DocumentMetadata metadata =
            new DownloadPreparation.DocumentMetadata("picked.mp4", "video/mp4");
    MissionState state = MissionState.None;
    boolean failResolve;
    boolean failDispatch;
    boolean failForget;
    boolean missingLookup;
    boolean failCreate;
    boolean failTruncate;
    boolean writable = true;
    int submissions;
    int resolutions;
    String createdName;
    String createdMime;

    DownloadPreparationEnvironment(final Path directory) throws IOException {
        this.directory = directory;
        output = directory.resolve("output");
        uniqueOutput = directory.resolve("unique-output");
        storage = file(output);
        uniqueStorage = file(uniqueOutput);
        when(folderUri.toString()).thenReturn(directory.toUri().toString());
        when(fileUri.toString()).thenReturn(output.toUri().toString());
        when(folder.getUri()).thenReturn(folderUri);
        when(folder.getTag()).thenReturn("video");
        when(folder.getFreeStorageSpace()).thenReturn(Long.MAX_VALUE);
        when(folder.findFile(anyString())).thenAnswer(call ->
                !missingLookup && Files.exists(output) ? fileUri : null);
        when(folder.mkdirs()).thenAnswer(call -> {
            Files.createDirectories(directory);
            return true;
        });
        when(folder.createFile(anyString(), nullable(String.class))).thenAnswer(call -> {
            createdName = call.getArgument(0);
            createdMime = call.getArgument(1);
            if (failCreate) {
                return null;
            }
            Files.deleteIfExists(output);
            Files.createFile(output);
            return storage;
        });
        when(folder.createUniqueFile(anyString(), nullable(String.class))).thenAnswer(call -> {
            createdName = call.getArgument(0);
            createdMime = call.getArgument(1);
            Files.createFile(uniqueOutput);
            return uniqueStorage;
        });
    }

    private StoredFileHelper file(final Path path) throws IOException {
        final StoredFileHelper result = mock(StoredFileHelper.class);
        when(result.canWrite()).thenAnswer(call -> writable);
        when(result.existsAsFile()).thenAnswer(call -> Files.isRegularFile(path));
        when(result.length()).thenAnswer(call -> Files.size(path));
        when(result.create()).thenAnswer(call -> {
            if (failCreate) {
                return false;
            }
            Files.createFile(path);
            return true;
        });
        doAnswer(call -> {
            if (failTruncate) {
                throw new IOException("truncate failed");
            }
            Files.write(path, new byte[0]);
            return null;
        }).when(result).truncate();
        return result;
    }

    @Override
    public StoredDirectoryHelper pickedFolder(final Uri uri, final DownloadPreparation.Kind kind) {
        return folder;
    }

    @Override
    public DownloadPreparation.DocumentMetadata documentMetadata(final Uri uri) {
        return metadata;
    }

    @Override
    public StoredFileHelper file(final StoredDirectoryHelper parent, final Uri uri)
            throws IOException {
        resolutions++;
        if (failResolve) {
            throw new IOException("document unavailable");
        }
        return storage;
    }

    @Override
    public MissionState missionState(final StoredFileHelper file) {
        return file == uniqueStorage ? MissionState.None : state;
    }

    @Override
    public void forget(final StoredFileHelper file) {
        if (failForget) {
            throw new IllegalStateException("metadata cleanup failed");
        }
        forgotten.add(file);
        state = MissionState.None;
    }

    @Override
    public void warnCleanupFailure(final RuntimeException error) {
        cleanupWarnings.add(error);
    }

    @Override
    public void dispatch(final DownloadPreparation.Launch launch) {
        if (failDispatch) {
            throw new IllegalStateException("service rejected launch");
        }
        launches.add(launch);
    }

    @Override
    public void rememberKind(final DownloadPreparation.Kind kind) {
        remembered.add(kind);
    }

    @Override
    public void chooseLocation(final DownloadPreparation.LocationRequest request) {
        location = request;
    }

    @Override
    public void confirmCollision(final DownloadPreparation.CollisionRequest request) {
        collision = request;
    }

    @Override
    public void submitted() {
        submissions++;
    }

    @Override
    public void failed(final DownloadPreparation.Failure failure) {
        failures.add(failure);
    }
}
