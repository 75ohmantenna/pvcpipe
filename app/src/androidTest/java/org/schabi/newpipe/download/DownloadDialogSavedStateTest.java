package org.schabi.newpipe.download;

import android.net.Uri;
import android.os.Bundle;
import android.os.Parcel;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.schabi.newpipe.extractor.stream.VideoStream;
import org.schabi.newpipe.util.AudioTrackAdapter.AudioTracksWrapper;
import org.schabi.newpipe.util.StreamItemAdapter.StreamInfoWrapper;

import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public class DownloadDialogSavedStateTest {
    private static final String SUFFIX =
            "org.schabi.newpipe.download.DownloadDialog$$StateSaver";

    @Test
    public void parcelledDialogStateRestoresSelectionsAndSerializableWrappers() {
        final DownloadDialog source = new DownloadDialog();
        source.wrappedVideoStreams = new StreamInfoWrapper<VideoStream>(
                Collections.emptyList(), null);
        source.wrappedAudioTracks = new AudioTracksWrapper(Collections.emptyList(), null);
        source.selectedVideoIndex = 3;
        source.selectedAudioIndex = 2;
        source.selectedAudioTrackIndex = -1;
        source.pendingLocationResult = Uri.parse("content://example.invalid/download/9");

        final Bundle state = new Bundle();
        source.saveDialogState(state);
        final DownloadDialog target = new DownloadDialog();
        target.restoreDialogState(parcelRoundTrip(state));

        assertNotNull(target.wrappedVideoStreams);
        assertTrue(target.wrappedVideoStreams.getStreamsList().isEmpty());
        assertNotNull(target.wrappedAudioTracks);
        assertEquals(0, target.wrappedAudioTracks.size());
        assertEquals(3, target.selectedVideoIndex);
        assertEquals(2, target.selectedAudioIndex);
        assertEquals(-1, target.selectedAudioTrackIndex);
        assertEquals(source.pendingLocationResult, target.pendingLocationResult);
    }

    @Test
    public void restoresLegacyGeneratedKeysWithoutChangingMissingSelections() {
        final Bundle oldState = new Bundle();
        oldState.putParcelable("pendingLocationResult" + SUFFIX,
                Uri.parse("content://example.invalid/download/10"));
        oldState.putInt("selectedSubtitleIndex" + SUFFIX, 4);
        final DownloadDialog target = new DownloadDialog();
        target.selectedAudioIndex = 8;
        target.restoreDialogState(parcelRoundTrip(oldState));

        assertEquals(4, target.selectedSubtitleIndex);
        assertEquals(8, target.selectedAudioIndex);
        assertEquals(Uri.parse("content://example.invalid/download/10"),
                target.pendingLocationResult);
        assertNull(target.pendingLocationState);
    }

    private static Bundle parcelRoundTrip(final Bundle state) {
        final Parcel parcel = Parcel.obtain();
        try {
            state.writeToParcel(parcel, 0);
            parcel.setDataPosition(0);
            final Bundle restored = parcel.readBundle(DownloadDialog.class.getClassLoader());
            if (restored == null) {
                throw new AssertionError("Saved download state disappeared while unparcelling");
            }
            return restored;
        } finally {
            parcel.recycle();
        }
    }
}
