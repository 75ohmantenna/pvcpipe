package org.schabi.newpipe.extractor.services.youtube;

import org.schabi.newpipe.extractor.exceptions.ParsingException;
import org.schabi.newpipe.extractor.utils.JavaScript;

import javax.annotation.Nonnull;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/** Cache one selected player's extraction results without evaluating the full player. */
public final class YoutubeJavaScriptPlayerManager {
    private static volatile PlayerData current = new PlayerData();

    private YoutubeJavaScriptPlayerManager() {
    }

    /**
     * Get the selected player's signature timestamp, fetching it once on a cold cache.
     * @param videoId video used by the player discovery fallback
     * @return the timestamp
     * @throws ParsingException if discovery or timestamp extraction fails
     */
    @Nonnull
    public static Integer getSignatureTimestamp(@Nonnull final String videoId)
            throws ParsingException {
        return current.timestamp(videoId);
    }

    /**
     * Deobfuscate a signature using the selected player's extracted function.
     * @param videoId video used by the player discovery fallback
     * @param obfuscatedSignature input signature
     * @return the transformed signature
     * @throws ParsingException if discovery, extraction or evaluation fails
     */
    @Nonnull
    public static String deobfuscateSignature(@Nonnull final String videoId,
                                             @Nonnull final String obfuscatedSignature)
            throws ParsingException {
        final String code = current.signature(videoId);
        try {
            return Objects.requireNonNullElse(JavaScript.run(code,
                    YoutubeSignatureUtils.DEOBFUSCATION_FUNCTION_NAME, obfuscatedSignature), "");
        } catch (final Exception e) {
            throw new ParsingException(
                    "Could not run signature parameter deobfuscation JavaScript function", e);
        }
    }

    /**
     * Transform only the first nonempty query n value; preserve all other URL bytes.
     * The query value is percent-decoded before evaluation and encoded afterward.
     * @param videoId video used by the player discovery fallback
     * @param streamingUrl original streaming URL
     * @return the URL with its n value transformed, or the unchanged URL without n
     * @throws ParsingException if a present value is malformed or cannot be transformed
     */
    @Nonnull
    public static String getUrlWithThrottlingParameterDeobfuscated(
            @Nonnull final String videoId, @Nonnull final String streamingUrl)
            throws ParsingException {
        final int[] span = YoutubeThrottlingParameterUtils.throttlingParameterSpan(streamingUrl);
        if (span == null || span[0] == span[1]) {
            return streamingUrl;
        }
        final String input;
        try {
            input = URLDecoder.decode(streamingUrl.substring(span[0], span[1]),
                    StandardCharsets.UTF_8);
        } catch (final IllegalArgumentException e) {
            throw new ParsingException("Malformed throttling parameter encoding", e);
        }
        final PlayerData player = current;
        // Capture this cache before evaluation: a concurrent clear retires this map.
        final Map<String, String> parameters = player.parameters;
        String result = parameters.get(input);
        if (result == null) {
            final ThrottlingFunction function = player.throttling(videoId);
            try {
                result = JavaScript.run(function.code, function.name, input);
                if (result == null || result.isEmpty()) {
                    throw new IllegalStateException("Extracted n-parameter is empty");
                }
                parameters.put(input, result);
            } catch (final Exception e) {
                throw new ParsingException(
                        "Could not run throttling parameter deobfuscation JavaScript function", e);
            }
        }
        final String encoded = URLEncoder.encode(result, StandardCharsets.UTF_8)
                .replace("+", "%20");
        return streamingUrl.substring(0, span[0]) + encoded + streamingUrl.substring(span[1]);
    }

    /**
     * Inspect the current parameter cache.
     * @return the current generation's number of cached n values
     */
    public static int getThrottlingParametersCacheSize() {
        return current.parameters.size();
    }

    /** Retire all state; in-flight calls may finish but cannot populate the new generation. */
    public static void clearAllCaches() {
        current = new PlayerData();
    }

    /** Clear n results without discarding the selected player and its extracted functions. */
    public static void clearThrottlingParametersCache() {
        current.parameters = new ConcurrentHashMap<>();
    }

    private static final class ThrottlingFunction {
        private final String name;
        private final String code;

        ThrottlingFunction(final String name, final String code) {
            this.name = name;
            this.code = code;
        }
    }

    // Only cold download/extraction is serialized. Each Rhino evaluation owns its scope and
    // runs outside this monitor, so unrelated warm evaluations remain parallel.
    private static final class PlayerData {
        private volatile Map<String, String> parameters = new ConcurrentHashMap<>();
        private String code;
        private Integer timestamp;
        private String signature;
        private ThrottlingFunction throttling;
        private ParsingException timestampError;
        private ParsingException signatureError;
        private ParsingException throttlingError;

        private String code(final String videoId) throws ParsingException {
            if (code == null) {
                code = YoutubeJavaScriptExtractor.extractJavaScriptPlayerCode(videoId);
            }
            return code;
        }

        synchronized Integer timestamp(final String videoId) throws ParsingException {
            if (timestampError != null) {
                throw timestampError;
            }
            if (timestamp == null) {
                final String playerCode = code(videoId);
                try {
                    timestamp = Integer.valueOf(YoutubeSignatureUtils.getSignatureTimestamp(
                            playerCode));
                } catch (final NumberFormatException e) {
                    timestampError = new ParsingException(
                            "Could not convert signature timestamp to a number", e);
                    throw timestampError;
                } catch (final ParsingException e) {
                    timestampError = e;
                    throw e;
                }
            }
            return timestamp;
        }

        synchronized String signature(final String videoId) throws ParsingException {
            if (signatureError != null) {
                throw signatureError;
            }
            if (signature == null) {
                final String playerCode = code(videoId);
                try {
                    signature = YoutubeSignatureUtils.getDeobfuscationCode(playerCode);
                } catch (final Exception e) {
                    signatureError = e instanceof ParsingException ? (ParsingException) e
                            : new ParsingException("Could not extract signature function", e);
                    throw signatureError;
                }
            }
            return signature;
        }

        synchronized ThrottlingFunction throttling(final String videoId) throws ParsingException {
            if (throttlingError != null) {
                throw throttlingError;
            }
            if (throttling == null) {
                final String playerCode = code(videoId);
                try {
                    final String name = YoutubeThrottlingParameterUtils
                            .getDeobfuscationFunctionName(playerCode);
                    final String function = YoutubeThrottlingParameterUtils
                            .getDeobfuscationFunction(playerCode, name);
                    throttling = new ThrottlingFunction(name, function);
                } catch (final Exception e) {
                    throttlingError = e instanceof ParsingException ? (ParsingException) e
                            : new ParsingException("Could not extract throttling function", e);
                    throw throttlingError;
                }
            }
            return throttling;
        }
    }
}
