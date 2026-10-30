package org.schabi.newpipe.extractor.services.kick;

import org.schabi.newpipe.extractor.MediaFormat;
import org.schabi.newpipe.extractor.exceptions.ParsingException;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.DeliveryMethod;
import org.schabi.newpipe.extractor.stream.SubtitlesStream;
import org.schabi.newpipe.extractor.stream.VideoStream;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.IllformedLocaleException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Keeps native HLS groups while exposing actual media playlists to external consumers. */
public final class KickHls {
    private KickHls() {
    }

    /** Immutable rendition lists from one master (or an unlabelled media playlist). */
    public static final class Manifest {
        private final List<VideoStream> videoStreams;
        private final List<VideoStream> videoOnlyStreams;
        private final List<AudioStream> audioStreams;
        private final List<SubtitlesStream> subtitles;

        private Manifest(final List<VideoStream> videos, final List<VideoStream> videoOnly,
                         final List<AudioStream> audio, final List<SubtitlesStream> text) {
            videoStreams = List.copyOf(videos);
            videoOnlyStreams = List.copyOf(videoOnly);
            audioStreams = List.copyOf(audio);
            subtitles = List.copyOf(text);
        }

        public List<VideoStream> getVideoStreams() {
            return videoStreams;
        }

        public List<VideoStream> getVideoOnlyStreams() {
            return videoOnlyStreams;
        }

        public List<AudioStream> getAudioStreams() {
            return audioStreams;
        }

        public List<SubtitlesStream> getSubtitles() {
            return subtitles;
        }
    }

    public static Manifest parse(final String masterUrl, final String manifest)
            throws ParsingException {
        if (!KickUrl.isHttps(masterUrl)) {
            throw new ParsingException("Kick HLS URLs must use HTTPS");
        }
        if (manifest == null || !manifest.stripLeading().startsWith("#EXTM3U\n")
                && !manifest.stripLeading().startsWith("#EXTM3U\r\n")
                && !"#EXTM3U".equals(manifest.trim())) {
            throw new ParsingException("Invalid Kick HLS playlist");
        }
        final List<Map<String, String>> variants = new ArrayList<>();
        final List<Map<String, String>> renditions = new ArrayList<>();
        Map<String, String> pending = null;
        boolean hasSegments = false;
        for (final String raw : manifest.split("\\r?\\n")) {
            final String line = raw.trim();
            if (line.startsWith("#EXT-X-STREAM-INF:")) {
                if (pending != null) {
                    throw new ParsingException("Kick HLS variant has no playlist URL");
                }
                pending = attributes(line.substring("#EXT-X-STREAM-INF:".length()));
            } else if (line.startsWith("#EXT-X-MEDIA:")) {
                renditions.add(attributes(line.substring("#EXT-X-MEDIA:".length())));
            } else if (line.startsWith("#EXTINF:")) {
                hasSegments = true;
            } else if (!line.isEmpty() && !line.startsWith("#") && pending != null) {
                pending.put("URI", resolve(masterUrl, line));
                variants.add(pending);
                pending = null;
            }
        }
        if (pending != null) {
            throw new ParsingException("Kick HLS variant has no playlist URL");
        }
        final List<VideoStream> videos = new ArrayList<>();
        final List<VideoStream> videoOnly = new ArrayList<>();
        final List<AudioStream> audio = new ArrayList<>();
        final List<SubtitlesStream> text = new ArrayList<>();
        if (variants.isEmpty() && renditions.isEmpty()) {
            if (!hasSegments) {
                throw new ParsingException("Kick HLS media playlist has no segments");
            }
            videos.add(video(masterUrl, "hls", "unknown", false, 0));
            return new Manifest(videos, videoOnly, audio, text);
        }

        final Set<Map<String, String>> seen = new HashSet<>();
        final Set<String> audioGroups = new HashSet<>();
        final Set<String> subtitleGroups = new HashSet<>();
        for (final Map<String, String> variant : variants) {
            final int bandwidth = positiveInteger(required(variant, "BANDWIDTH"));
            final int bitrate = variant.containsKey("AVERAGE-BANDWIDTH")
                    ? positiveInteger(variant.get("AVERAGE-BANDWIDTH")) : bandwidth;
            final double frameRate = variant.containsKey("FRAME-RATE")
                    ? frameRate(variant.get("FRAME-RATE")) : 0;
            final String resolution = variant.get("RESOLUTION");
            final String quality = resolution == null ? null : quality(resolution, frameRate);
            if (!seen.add(variant)) {
                continue;
            }
            if (quality == null && !audioOnly(variant.get("CODECS"))) {
                continue;
            }
            final String audioGroup = variant.get("AUDIO");
            if (audioGroup != null) {
                audioGroups.add(audioGroup);
            }
            final String subtitleGroup = variant.get("SUBTITLES");
            if (subtitleGroup != null) {
                subtitleGroups.add(subtitleGroup);
            }
            final String media = variant.get("URI");
            if (quality == null) {
                audio.add(audio(masterUrl, media, "hls-audio:" + media, null, null,
                        audioFormat(variant.get("CODECS")), bitrate));
            } else {
                final boolean separateAudio = externalAudio(renditions, audioGroup);
                final VideoStream stream = video(media, "hls-video-" + seen.size(), quality,
                        separateAudio, variant.containsKey("AVERAGE-BANDWIDTH")
                                ? bitrate : (int) (bandwidth * 0.85));
                (separateAudio ? videoOnly : videos).add(stream);
            }
        }
        seen.clear();
        for (final Map<String, String> rendition : renditions) {
            final String type = rendition.get("TYPE");
            if (!"AUDIO".equals(type) && !"SUBTITLES".equals(type)) {
                continue;
            }
            final String group = required(rendition, "GROUP-ID");
            final String name = required(rendition, "NAME");
            final String uri = rendition.get("URI");
            final int bitrate = rendition.containsKey("BANDWIDTH")
                    ? positiveInteger(rendition.get("BANDWIDTH")) : 0;
            if (uri == null) {
                // URI-less audio describes audio already muxed into the variant.
                continue;
            }
            final String media = resolve(masterUrl, uri);
            rendition.put("URI", media);
            if (!seen.add(rendition)) {
                continue;
            }
            final Locale locale = language(rendition.get("LANGUAGE"));
            final String id = "hls-" + type.toLowerCase(Locale.ROOT) + ":"
                    + group.length() + ":" + group + ":" + name;
            if ("AUDIO".equals(type) && audioGroups.contains(group)) {
                audio.add(audio(masterUrl, media, id, name, locale,
                        groupAudioFormat(variants, group), bitrate));
            } else if ("SUBTITLES".equals(type) && subtitleGroups.contains(group)
                    && locale != null) {
                text.add(new SubtitlesStream.Builder().setId(id).setContent(media, true)
                        .setDeliveryMethod(DeliveryMethod.HLS).setManifestUrl(masterUrl)
                        .setMediaFormat(MediaFormat.VTT)
                        .setLanguageCode(rendition.get("LANGUAGE"))
                        .setAutoGenerated(false).build());
            }
        }
        if (videos.isEmpty() && videoOnly.isEmpty() && audio.isEmpty()) {
            throw new ParsingException("Kick HLS master has no supported renditions");
        }
        return new Manifest(videos, videoOnly, audio, text);
    }

    private static VideoStream video(final String media, final String id, final String quality,
                                     final boolean videoOnly, final int bitrate) {
        final VideoStream stream = new VideoStream.Builder().setId(id).setContent(media, true)
                .setDeliveryMethod(DeliveryMethod.HLS).setManifestUrl(media)
                .setMediaFormat(MediaFormat.MPEG_4).setIsVideoOnly(videoOnly)
                .setResolution(quality).build();
        if (bitrate > 0) {
            stream.pvcSetBitrate(bitrate);
        }
        return stream;
    }

    private static AudioStream audio(final String master, final String media, final String id,
                                     final String name, final Locale locale,
                                     final MediaFormat format, final int bitrate) {
        return new AudioStream.Builder().setId(id).setContent(media, true)
                .setDeliveryMethod(DeliveryMethod.HLS).setManifestUrl(master).setMediaFormat(format)
                .setAudioTrackId(id).setAudioTrackName(name).setAudioLocale(locale)
                .setAverageBitrate(bitrate > 0 ? bitrate / 1000 : AudioStream.UNKNOWN_BITRATE)
                .build();
    }

    private static boolean externalAudio(final List<Map<String, String>> renditions,
                                         final String group) {
        boolean external = false;
        for (final Map<String, String> rendition : renditions) {
            if ("AUDIO".equals(rendition.get("TYPE")) && group != null
                    && group.equals(rendition.get("GROUP-ID"))) {
                if (!rendition.containsKey("URI")) {
                    return false;
                }
                external = true;
            }
        }
        return external;
    }

    private static MediaFormat groupAudioFormat(final List<Map<String, String>> variants,
                                                final String group) {
        for (final Map<String, String> variant : variants) {
            if (group.equals(variant.get("AUDIO"))) {
                final MediaFormat format = audioFormat(variant.get("CODECS"));
                if (format != null) {
                    return format;
                }
            }
        }
        return null;
    }

    private static MediaFormat audioFormat(final String codecs) {
        if (codecs != null) {
            for (final String codec : codecs.toLowerCase(Locale.ROOT).split(",")) {
                if (codec.trim().startsWith("mp4a") || codec.trim().equals("ac-3")
                        || codec.trim().equals("ec-3") || codec.trim().equals("alac")) {
                    return MediaFormat.M4A;
                }
                if (codec.trim().equals("opus")) {
                    return MediaFormat.OPUS;
                }
            }
        }
        return null;
    }

    private static boolean audioOnly(final String codecs) {
        if (codecs == null || codecs.isBlank()) {
            return false;
        }
        for (final String raw : codecs.toLowerCase(Locale.ROOT).split(",", -1)) {
            final String codec = raw.trim();
            if (!codec.startsWith("mp4a.") && !codec.equals("ac-3") && !codec.equals("ec-3")
                    && !codec.equals("ac-4") && !codec.equals("opus") && !codec.equals("flac")
                    && !codec.equals("alac") && !codec.equals("mp3")) {
                return false;
            }
        }
        return true;
    }

    private static String quality(final String resolution, final double frameRate)
            throws ParsingException {
        final String[] dimensions = resolution.split("x", -1);
        if (dimensions.length != 2) {
            throw new ParsingException("Invalid Kick HLS resolution");
        }
        positiveInteger(dimensions[0]);
        final int height = positiveInteger(dimensions[1]);
        return height + "p" + (frameRate > 30 ? Long.toString(Math.round(frameRate)) : "");
    }

    private static int positiveInteger(final String value) throws ParsingException {
        try {
            if (!value.matches("[0-9]+")) {
                throw new NumberFormatException("Not an unsigned decimal integer");
            }
            final int number = Integer.parseInt(value);
            if (number <= 0) {
                throw new NumberFormatException("Not positive");
            }
            return number;
        } catch (final NumberFormatException e) {
            throw new ParsingException("Invalid Kick HLS numeric metadata", e);
        }
    }

    private static double frameRate(final String value) throws ParsingException {
        try {
            final double number = Double.parseDouble(value);
            if (!value.matches("[0-9]+(?:\\.[0-9]+)?") || !Double.isFinite(number)
                    || number <= 0 || number > Integer.MAX_VALUE) {
                throw new NumberFormatException("Invalid frame rate");
            }
            return number;
        } catch (final NumberFormatException e) {
            throw new ParsingException("Invalid Kick HLS frame rate", e);
        }
    }

    private static Locale language(final String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        try {
            final Locale locale = new Locale.Builder().setLanguageTag(code).build();
            return locale.getLanguage().isEmpty() || "und".equals(locale.getLanguage())
                    ? null : locale;
        } catch (final IllformedLocaleException e) {
            return null;
        }
    }

    private static String required(final Map<String, String> attributes, final String name)
            throws ParsingException {
        final String value = attributes.get(name);
        if (value == null || value.isEmpty()) {
            throw new ParsingException("Kick HLS rendition is missing " + name);
        }
        return value;
    }

    private static String resolve(final String master, final String reference)
            throws ParsingException {
        if (reference.isBlank()) {
            throw new ParsingException("Kick HLS rendition has an empty URL");
        }
        try {
            final URI uri = URI.create(reference);
            // Do not normalize or decode absolute signed URLs.
            final String resolved = uri.isAbsolute() ? reference
                    : URI.create(master).resolve(uri).toString();
            if (!KickUrl.isHttps(resolved)) {
                throw new ParsingException("Kick HLS rendition URLs must use HTTPS");
            }
            return resolved;
        } catch (final IllegalArgumentException e) {
            throw new ParsingException("Invalid Kick HLS rendition URL", e);
        }
    }

    /** Attribute values may contain commas (notably CODECS, NAME and signed URIs). */
    private static Map<String, String> attributes(final String source) throws ParsingException {
        final Map<String, String> result = new LinkedHashMap<>();
        int index = 0;
        while (index < source.length()) {
            final int equals = source.indexOf('=', index);
            if (equals < 0) {
                throw new ParsingException("Invalid Kick HLS attributes");
            }
            final String key = source.substring(index, equals).trim();
            if (key.isEmpty() || key.indexOf(',') >= 0) {
                throw new ParsingException("Invalid Kick HLS attribute name");
            }
            index = equals + 1;
            final String value;
            if (index < source.length() && source.charAt(index) == '"') {
                final int end = source.indexOf('"', index + 1);
                if (end < 0) {
                    throw new ParsingException("Unterminated Kick HLS quoted attribute");
                }
                value = source.substring(index + 1, end);
                index = end + 1;
                while (index < source.length() && Character.isWhitespace(source.charAt(index))) {
                    index++;
                }
            } else {
                final int comma = source.indexOf(',', index);
                final int end = comma < 0 ? source.length() : comma;
                value = source.substring(index, end).trim();
                index = end;
            }
            if (result.putIfAbsent(key, value) != null) {
                throw new ParsingException("Duplicate Kick HLS attribute: " + key);
            }
            if (index < source.length()) {
                if (source.charAt(index++) != ',' || index == source.length()) {
                    throw new ParsingException("Invalid Kick HLS attribute separator");
                }
            }
        }
        return result;
    }
}
