package org.schabi.newpipe.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.util.LinkedList;
import java.util.Queue;

public class StateSaverTest {
    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    private String restored;

    private final StateSaver.WriteRead state = new StateSaver.WriteRead() {
        @Override
        public String generateSuffix() {
            return ".test";
        }

        @Override
        public void writeTo(final Queue<Object> objectsToSave) {
            objectsToSave.add("what the screen showed");
        }

        @Override
        public void readFrom(@NonNull final Queue<Object> savedObjects) {
            restored = (String) savedObjects.poll();
        }
    };

    private File useInternalCache() throws IOException {
        final File cache = temporaryFolder.newFolder("cache");
        final Context context = mock(Context.class);
        when(context.getCacheDir()).thenReturn(cache);
        when(context.getExternalCacheDir()).thenReturn(null);
        StateSaver.init(context);
        return cache;
    }

    private static Bundle bundleWith(final SavedState savedState) {
        final Bundle bundle = mock(Bundle.class);
        when(bundle.getParcelable(StateSaver.KEY_SAVED_STATE)).thenReturn(savedState);
        return bundle;
    }

    @Test
    public void stateIsSavedInTheInternalCacheAndRestoredFromIt() throws IOException {
        try (var logs = mockStatic(Log.class); var text = mockStatic(TextUtils.class)) {
            text.when(() -> TextUtils.isEmpty(any())).thenAnswer(call -> {
                final CharSequence value = call.getArgument(0);
                return value == null || value.length() == 0;
            });
            final File cache = useInternalCache();

            final SavedState saved = StateSaver.tryToSave(false, null, mock(Bundle.class), state);
            assertNotNull(saved);
            final File file = new File(saved.getPathFileSaved());
            assertTrue(file.isFile());
            assertEquals(new File(cache, "state_cache"), file.getParentFile());

            assertNotNull(StateSaver.tryToRestore(bundleWith(saved), state));
            assertEquals("what the screen showed", restored);
        }
    }

    @Test
    public void aStateFileOutsideTheInternalCacheIsNotRead() throws IOException {
        try (var logs = mockStatic(Log.class); var text = mockStatic(TextUtils.class)) {
            useInternalCache();
            // a well-formed state file where another app could have put it
            final File shared = new File(temporaryFolder.newFolder("shared", "state_cache"),
                    "1234.test");
            try (ObjectOutputStream out = new ObjectOutputStream(new FileOutputStream(shared))) {
                final LinkedList<Object> planted = new LinkedList<>();
                planted.add("planted");
                out.writeObject(planted);
            }

            final SavedState named = new SavedState("1234", shared.getPath());
            assertNull(StateSaver.tryToRestore(bundleWith(named), state));
            assertNull(restored);
            assertTrue(shared.exists());
        }
    }
}
