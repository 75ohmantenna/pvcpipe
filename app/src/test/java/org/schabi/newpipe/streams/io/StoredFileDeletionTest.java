/*
 * SPDX-FileCopyrightText: 2026 PVCPipe contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.streams.io;

import android.app.Application;
import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;

import androidx.documentfile.provider.DocumentFile;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Real filesystem operations; SAF query/deletion/grant results are controlled doubles. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, application = Application.class, sdk = 26)
public class StoredFileDeletionTest {
    @Rule public final TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void deletingAnAlreadyAbsentDirectFileSucceeds() throws Exception {
        final File file = folder.newFile("media.bin");
        final StoredFileHelper storage = new StoredFileHelper(null,
                Uri.fromFile(file), "video/mp4");
        assertTrue(storage.delete());
        assertTrue(storage.isAbsent());
        assertTrue(storage.delete());
    }

    @Test
    public void filesystemRefusalKeepsContentsAndCanBeRetried() throws Exception {
        final File directory = folder.newFolder("not-empty");
        final File child = new File(directory, "keep.bin");
        Files.write(child.toPath(), new byte[]{1, 2, 3});
        final StoredFileHelper storage = new StoredFileHelper(null,
                Uri.fromFile(directory), "application/octet-stream");
        assertFalse(storage.delete());
        assertFalse(storage.isAbsent());
        assertEquals(3, Files.size(child.toPath()));
        Files.delete(child.toPath());
        assertTrue(storage.delete());
    }

    @Test
    public void refusedSafDeletionKeepsTheGrantUntilSuccessfulRetry() {
        final Context context = mock(Context.class);
        final ContentResolver resolver = mock(ContentResolver.class);
        final DocumentFile document = mock(DocumentFile.class);
        final Uri uri = Uri.parse("content://fixture/document/clip");
        when(context.getContentResolver()).thenReturn(resolver);
        when(document.getUri()).thenReturn(uri);
        when(document.delete()).thenReturn(false, true);
        final Cursor cursor = mock(Cursor.class);
        when(cursor.getCount()).thenReturn(1);
        when(resolver.query(eq(uri), any(String[].class), isNull(), isNull(), isNull()))
                .thenReturn(cursor);
        try (MockedStatic<DocumentFile> documents = mockStatic(DocumentFile.class)) {
            documents.when(() -> DocumentFile.fromSingleUri(context, uri)).thenReturn(document);
            final StoredFileHelper storage = new StoredFileHelper(context, uri, "video/mp4");
            assertFalse(storage.delete());
            verify(resolver, never()).releasePersistableUriPermission(any(Uri.class), anyInt());
            assertTrue(storage.delete());
            verify(resolver).releasePersistableUriPermission(eq(uri), anyInt());
        }
    }

    @Test
    public void nullOrDeniedQueriesDoNotEstablishAbsence() {
        final Context context = mock(Context.class);
        final ContentResolver resolver = mock(ContentResolver.class);
        final DocumentFile document = mock(DocumentFile.class);
        final Uri uri = Uri.parse("content://fixture/document/denied");
        when(context.getContentResolver()).thenReturn(resolver);
        when(document.getUri()).thenReturn(uri);
        when(resolver.query(eq(uri), any(String[].class), isNull(), isNull(), isNull()))
                .thenReturn(null).thenThrow(new SecurityException("denied"));
        try (MockedStatic<DocumentFile> documents = mockStatic(DocumentFile.class)) {
            documents.when(() -> DocumentFile.fromSingleUri(context, uri)).thenReturn(document);
            final StoredFileHelper storage = new StoredFileHelper(context, uri, "video/mp4");
            assertFalse(storage.delete());
            assertFalse(storage.delete());
            verify(resolver, never()).releasePersistableUriPermission(any(Uri.class), anyInt());
        }
    }

    @Test
    public void confirmedAbsentSafDocumentCompletesDeletion() {
        final Context context = mock(Context.class);
        final ContentResolver resolver = mock(ContentResolver.class);
        final DocumentFile document = mock(DocumentFile.class);
        final Cursor cursor = mock(Cursor.class);
        final Uri uri = Uri.parse("content://fixture/document/gone");
        when(context.getContentResolver()).thenReturn(resolver);
        when(document.getUri()).thenReturn(uri);
        when(resolver.query(eq(uri), any(String[].class), isNull(), isNull(), isNull()))
                .thenReturn(cursor);
        try (MockedStatic<DocumentFile> documents = mockStatic(DocumentFile.class)) {
            documents.when(() -> DocumentFile.fromSingleUri(context, uri)).thenReturn(document);
            assertTrue(new StoredFileHelper(context, uri, "video/mp4").delete());
            verify(resolver).releasePersistableUriPermission(eq(uri), anyInt());
            verify(cursor).close();
        }
    }

    @Test
    public void deletionRestoreKeepsTheUriAndSavedNameWhenMetadataDisappears() throws Exception {
        final Context context = mock(Context.class);
        final ContentResolver resolver = mock(ContentResolver.class);
        final DocumentFile document = mock(DocumentFile.class);
        final Uri uri = Uri.parse("content://fixture/document/123");
        when(context.getContentResolver()).thenReturn(resolver);
        when(document.getUri()).thenReturn(uri);
        when(document.getName()).thenReturn("remembered.mp4");
        when(document.getType()).thenReturn("video/mp4");
        try (MockedStatic<DocumentFile> documents = mockStatic(DocumentFile.class)) {
            documents.when(() -> DocumentFile.fromSingleUri(context, uri)).thenReturn(document);
            final StoredFileHelper saved = new StoredFileHelper(context, null, uri, "video");
            when(document.getName()).thenReturn(null);
            final StoredFileHelper restored =
                    StoredFileHelper.deserializeForDeletion(saved, context);
            assertFalse(restored.isInvalid());
            assertEquals(uri, restored.getUri());
            assertEquals("remembered.mp4", restored.getName());
            assertEquals("video", restored.getTag());
        }
    }
}
