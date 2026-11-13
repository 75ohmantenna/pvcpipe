package org.schabi.newpipe.download;

import static org.schabi.newpipe.extractor.stream.DeliveryMethod.HLS;
import static org.schabi.newpipe.extractor.stream.DeliveryMethod.PROGRESSIVE_HTTP;
import static org.schabi.newpipe.util.ListHelper.getStreamsOfSpecifiedDelivery;

import android.content.Context;

import org.schabi.newpipe.extractor.MediaFormat;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.Stream;
import org.schabi.newpipe.extractor.stream.VideoStream;
import org.schabi.newpipe.util.ListHelper;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Download-only admission and defaults; playback preferences remain unchanged. */
final class DownloadAudioStreams {
    private DownloadAudioStreams() {
    }

    static boolean isSupportedHls(final Stream stream) {
        return stream instanceof AudioStream && stream.getDeliveryMethod() == HLS
                && stream.getFormat() == MediaFormat.M4A && stream.isUrl()
                && stream.getContent() != null && !stream.getContent().isBlank();
    }

    static List<List<AudioStream>> groups(final Context context, final List<AudioStream> streams) {
        if (streams == null) {
            return List.of();
        }
        final List<AudioStream> progressive = getStreamsOfSpecifiedDelivery(streams,
                PROGRESSIVE_HTTP);
        final List<AudioStream> hls = new ArrayList<>();
        for (final AudioStream stream : streams) {
            if (isSupportedHls(stream)) {
                hls.add(stream);
            }
        }
        if (progressive.isEmpty()) {
            return ListHelper.getGroupedAudioStreams(context, hls);
        }
        // Grouping all deliveries together can drop a previously available unknown-language
        // progressive group when HLS introduces a named language. Preserve the old groups.
        final List<List<AudioStream>> groups = new ArrayList<>();
        for (final List<AudioStream> group : ListHelper
                .getGroupedAudioStreams(context, progressive)) {
            groups.add(new ArrayList<>(group));
        }
        for (final AudioStream stream : hls) {
            List<AudioStream> matching = null;
            for (final List<AudioStream> group : groups) {
                if (Objects.equals(group.get(0).getAudioTrackId(), stream.getAudioTrackId())) {
                    matching = group;
                    break;
                }
            }
            if (matching == null) {
                matching = new ArrayList<>();
                groups.add(matching);
            }
            matching.add(stream);
        }
        return groups;
    }

    static boolean requiresSeparateAudio(final Stream video, final List<AudioStream> track,
                                         final AudioStream paired) {
        return video instanceof VideoStream && ((VideoStream) video).isVideoOnly()
                && paired == null && track.stream().anyMatch(DownloadAudioStreams::isSupportedHls);
    }

    static int progressiveGroupCount(final List<List<AudioStream>> groups) {
        return (int) groups.stream().filter(group -> group.stream()
                .anyMatch(stream -> stream.getDeliveryMethod() == PROGRESSIVE_HTTP)).count();
    }

    static int defaultGroup(final Context context, final List<List<AudioStream>> groups) {
        final List<List<AudioStream>> candidates = new ArrayList<>();
        for (final List<AudioStream> group : groups) {
            final List<AudioStream> progressive = getStreamsOfSpecifiedDelivery(group,
                    PROGRESSIVE_HTTP);
            if (!progressive.isEmpty()) {
                candidates.add(progressive);
            }
        }
        if (candidates.isEmpty()) {
            return ListHelper.getDefaultAudioTrackGroup(context, groups);
        }
        final AudioStream chosen = candidates.get(
                ListHelper.getDefaultAudioTrackGroup(context, candidates)).get(0);
        for (int i = 0; i < groups.size(); i++) {
            if (groups.get(i).contains(chosen)) {
                return i;
            }
        }
        throw new IllegalStateException("Default audio track is missing");
    }

    static int defaultFormat(final Context context, final List<AudioStream> streams) {
        final List<AudioStream> progressive = getStreamsOfSpecifiedDelivery(streams,
                PROGRESSIVE_HTTP);
        if (progressive.isEmpty()) {
            return ListHelper.getDefaultAudioFormat(context, streams);
        }
        return streams.indexOf(progressive.get(ListHelper.getDefaultAudioFormat(context,
                progressive)));
    }
}
