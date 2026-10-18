package org.schabi.newpipe.extractor.services.kick;

import org.schabi.newpipe.extractor.MediaFormat;
import org.schabi.newpipe.extractor.exceptions.ParsingException;
import org.schabi.newpipe.extractor.stream.DeliveryMethod;
import org.schabi.newpipe.extractor.stream.VideoStream;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Exposes IVS media-playlist URLs, rather than the master, as download sources. */
public final class KickHls {
    private static final Pattern VARIANT = Pattern.compile(
            "#EXT-X-STREAM-INF:([^\\r\\n]*)\\r?\\n"
                    + "(?:#[^\\r\\n]*\\r?\\n)*([^#\\r\\n][^\\r\\n]*)");
    private static final Pattern RESOLUTION = Pattern.compile("(?:^|,)RESOLUTION=\\d+x(\\d+)");
    private static final Pattern BANDWIDTH = Pattern.compile("(?:^|,)BANDWIDTH=(\\d+)");
    private static final Pattern AVERAGE = Pattern.compile("(?:^|,)AVERAGE-BANDWIDTH=(\\d+)");

    private KickHls() {
    }

    public static List<VideoStream> variants(final String url, final String manifest)
            throws ParsingException {
        final List<VideoStream> streams = new ArrayList<>();
        final Matcher variant = VARIANT.matcher(manifest);
        while (variant.find()) {
            final Matcher resolution = RESOLUTION.matcher(variant.group(1));
            // An audio-only rendition has no video resolution.
            if (!resolution.find()) {
                continue;
            }
            final String media;
            try {
                media = URI.create(url).resolve(variant.group(2).trim()).toString();
            } catch (final IllegalArgumentException e) {
                throw new ParsingException("Invalid Kick HLS rendition URL", e);
            }
            if (!KickUrl.isHttps(media)) {
                throw new ParsingException("Kick HLS rendition URLs must use HTTPS");
            }
            final VideoStream stream = new VideoStream.Builder().setId("hls-" + streams.size())
                    .setContent(media, true).setDeliveryMethod(DeliveryMethod.HLS)
                    .setManifestUrl(media).setMediaFormat(MediaFormat.MPEG_4)
                    .setIsVideoOnly(false).setResolution(resolution.group(1) + "p").build();
            final Matcher average = AVERAGE.matcher(variant.group(1));
            final Matcher peak = BANDWIDTH.matcher(variant.group(1));
            try {
                if (average.find()) {
                    stream.pvcSetBitrate(Integer.parseInt(average.group(1)));
                } else if (peak.find()) {
                    stream.pvcSetBitrate((int) (Integer.parseInt(peak.group(1)) * 0.85f));
                }
            } catch (final NumberFormatException e) {
                throw new ParsingException("Invalid Kick HLS bandwidth", e);
            }
            streams.add(stream);
        }
        if (streams.isEmpty()) {
            if (manifest.contains("#EXT-X-STREAM-INF")) {
                throw new ParsingException("Kick HLS master has no video renditions");
            }
            if (!manifest.contains("#EXTINF:")) {
                throw new ParsingException("Kick HLS media playlist has no segments");
            }
            streams.add(new VideoStream.Builder().setId("hls").setContent(url, true)
                    .setDeliveryMethod(DeliveryMethod.HLS).setManifestUrl(url)
                    .setMediaFormat(MediaFormat.MPEG_4).setIsVideoOnly(false)
                    .setResolution("unknown").build());
        }
        return streams;
    }
}
