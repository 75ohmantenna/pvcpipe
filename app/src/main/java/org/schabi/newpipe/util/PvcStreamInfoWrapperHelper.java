package org.schabi.newpipe.util;

import org.schabi.newpipe.extractor.MediaFormat;
import org.schabi.newpipe.extractor.stream.DeliveryMethod;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.Stream;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.VideoStream;

public final class PvcStreamInfoWrapperHelper {
    private PvcStreamInfoWrapperHelper() {
    }

    /**
     * check if stream is HLS and if so set the size of the stream.
     * <p>
     * As HLS streams the length cannot be determined by using HEAD and extracting Content-Length
     * we have to calc a approximate size.
     *
     * @param stream         the stream we want to calculate the download size
     * @param streamInfo     overall metadata that applies to all streams.
     * @param streamsWrapper the wrapper
     * @param <X>            the stream type to get the {@link MediaFormat}.
     * @return true if HLS was handled, including when its media size remains unknown
     */
    public static <X extends Stream> boolean pvcIfStreamIsHlsCalcAndSetSize(
            final X stream,
            final StreamInfo streamInfo,
            final StreamItemAdapter.StreamInfoWrapper<X> streamsWrapper) {
        if (stream.getDeliveryMethod() == DeliveryMethod.HLS) {
            final long approxSize = pvcCalcSize(streamInfo, stream);
            if (approxSize > 0) {
                streamsWrapper.setSize(stream, approxSize);
            }
            // A playlist's Content-Length is never the size of its media payload.
            return true;
        }
        return false;
    }

    private static <X extends Stream> long pvcCalcSize(
            final StreamInfo streamInfo,
            final X stream) {
        final long bitrate;
        if (stream instanceof VideoStream videoStream) {
            bitrate = videoStream.getBitrate();
        } else if (stream instanceof AudioStream audioStream) {
            bitrate = (long) audioStream.getAverageBitrate() * 1000L;
        } else {
            return -1;
        }
        final long duration = streamInfo == null ? -1 : streamInfo.getDuration();
        if (bitrate > 0 && duration > 0) {
            try {
                return Math.multiplyExact(bitrate / 8L, duration);
            } catch (final ArithmeticException ignored) {
                // An invalid estimate must remain unknown, never wrap to a small size.
            }
        }
        return -1; // size could not be calculated
    }
}
