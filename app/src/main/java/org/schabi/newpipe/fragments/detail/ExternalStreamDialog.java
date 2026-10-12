package org.schabi.newpipe.fragments.detail;

import static org.schabi.newpipe.util.ListHelper.getUrlStreams;

import android.content.Context;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import org.schabi.newpipe.R;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.Stream;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.VideoStream;
import org.schabi.newpipe.util.ListHelper;
import org.schabi.newpipe.util.Localization;
import org.schabi.newpipe.util.external_communication.ShareUtils;

import java.util.List;
import java.util.function.Consumer;

/** Explicit external-player choices, distinct from automatic player stream selection. */
final class ExternalStreamDialog {
    private ExternalStreamDialog() { }

    static void showVideo(final Context activity, final StreamInfo currentInfo,
                          final String url, final Consumer<Stream> onSelected) {
        if (currentInfo == null) {
            return;
        }

        final AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        builder.setTitle(R.string.select_quality_external_players);
        builder.setNeutralButton(R.string.open_in_browser, (dialog, i) ->
                ShareUtils.openUrlInBrowser(activity, url));

        final List<VideoStream> videoStreamsForExternalPlayers =
                ListHelper.getSortedStreamVideosList(
                        activity,
                        getUrlStreams(currentInfo.getVideoStreams()),
                        getUrlStreams(currentInfo.getVideoOnlyStreams()),
                        false,
                        false
                );

        if (videoStreamsForExternalPlayers.isEmpty()) {
            builder.setMessage(R.string.no_video_streams_available_for_external_players);
            builder.setPositiveButton(R.string.ok, null);

        } else {
            final int selectedVideoStreamIndexForExternalPlayers =
                    ListHelper.getDefaultResolutionIndex(activity, videoStreamsForExternalPlayers);
            final CharSequence[] resolutions = videoStreamsForExternalPlayers.stream()
                    .map(VideoStream::getResolution).toArray(CharSequence[]::new);

            builder.setSingleChoiceItems(resolutions, selectedVideoStreamIndexForExternalPlayers,
                    null);
            builder.setNegativeButton(R.string.cancel, null);
            builder.setPositiveButton(R.string.ok, (dialog, i) -> {
                final int index = ((AlertDialog) dialog).getListView().getCheckedItemPosition();
                // We don't have to manage the index validity because if there is no stream
                // available for external players, this code will be not executed and if there is
                // no stream which matches the default resolution, 0 is returned by
                // ListHelper.getDefaultResolutionIndex.
                // The index cannot be outside the bounds of the list as its always between 0 and
                // the list size - 1, .
                onSelected.accept(videoStreamsForExternalPlayers.get(index));
            });
        }
        builder.show();
    }

    static void showAudio(final Context activity, final StreamInfo currentInfo,
                          final String url, final Consumer<Stream> onSelected) {
        if (currentInfo == null) {
            return;
        }

        final List<AudioStream> audioStreams = getUrlStreams(
                currentInfo.getAudioStreams());
        final List<AudioStream> audioTracks =
                ListHelper.getFilteredAudioStreams(activity, audioStreams);

        if (audioTracks.isEmpty()) {
            Toast.makeText(activity, R.string.no_audio_streams_available_for_external_players,
                    Toast.LENGTH_SHORT).show();
        } else if (audioTracks.size() == 1) {
            onSelected.accept(audioTracks.get(0));
        } else {
            final int selectedAudioStream =
                    ListHelper.getDefaultAudioFormat(activity, audioTracks);
            final CharSequence[] trackNames = audioTracks.stream()
                    .map(audioStream -> Localization.audioTrackName(activity, audioStream))
                    .toArray(CharSequence[]::new);

            new AlertDialog.Builder(activity)
                    .setTitle(R.string.select_audio_track_external_players)
                    .setNeutralButton(R.string.open_in_browser, (dialog, i) ->
                            ShareUtils.openUrlInBrowser(activity, url))
                    .setSingleChoiceItems(trackNames, selectedAudioStream, null)
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.ok, (dialog, i) -> {
                        final int index = ((AlertDialog) dialog).getListView()
                                .getCheckedItemPosition();
                        onSelected.accept(audioTracks.get(index));
                    })
                    .show();
        }
    }
}
