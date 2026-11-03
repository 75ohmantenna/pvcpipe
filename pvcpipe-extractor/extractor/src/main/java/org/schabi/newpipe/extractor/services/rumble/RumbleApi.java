package org.schabi.newpipe.extractor.services.rumble;

import com.grack.nanojson.JsonArray;
import com.grack.nanojson.JsonObject;
import com.grack.nanojson.JsonWriter;

import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException;
import org.schabi.newpipe.extractor.exceptions.ParsingException;
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException;
import org.schabi.newpipe.extractor.localization.Localization;
import org.schabi.newpipe.extractor.utils.JsonUtils;
import org.schabi.newpipe.extractor.utils.Utils;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.annotation.Nullable;

/** Shared validation for Rumble's public JSON services, including legacy root envelopes. */
public final class RumbleApi {
    private RumbleApi() {
    }

    public static String serviceUrl(final String name, final Map<String, String> parameters) {
        final StringBuilder url = new StringBuilder("https://rumble.com/service.php?name=")
                .append(Utils.encodeUrlUtf8(name));
        for (final Map.Entry<String, String> parameter : parameters.entrySet()) {
            url.append('&').append(Utils.encodeUrlUtf8(parameter.getKey())).append('=')
                    .append(Utils.encodeUrlUtf8(parameter.getValue()));
        }
        return url.toString();
    }

    public static JsonObject fetchJson(final Downloader downloader, final String url,
                                       @Nullable final Localization localization)
            throws IOException, ReCaptchaException, ParsingException {
        final Response response = RumbleParsingHelper.fetchResponse(downloader, url,
                Map.of("Accept", List.of("application/json")), localization);
        final String contentType = response.getHeader("Content-Type");
        if (contentType != null) {
            final String mime = contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
            if (!mime.equals("application/json") && !mime.endsWith("+json")) {
                throw new ParsingException("Expected a Rumble JSON response, got " + contentType);
            }
        }

        final JsonObject root = JsonUtils.toJsonObject(response.responseBody());
        if (root.get("errors") != null) {
            final JsonArray errors = JsonUtils.getArray(root, "errors");
            if (!errors.isEmpty()) {
                throw apiError(errors);
            }
        }
        if (hasError(root.get("error"))) {
            throw apiError(root.get("error"));
        }
        if (root.get("data") instanceof JsonObject) {
            final JsonObject data = JsonUtils.getObject(root, "data");
            if (data.get("success") != null && !JsonUtils.getBoolean(data, "success")) {
                throw apiError(hasError(data.get("error")) ? data.get("error")
                        : "data.success=false");
            }
        }
        return root;
    }

    public static JsonObject fetchData(final Downloader downloader, final String url,
                                       @Nullable final Localization localization)
            throws IOException, ReCaptchaException, ParsingException {
        return JsonUtils.getObject(fetchJson(downloader, url, localization), "data");
    }

    private static boolean hasError(@Nullable final Object error) {
        if (error == null) {
            return false;
        }
        if (error instanceof String) {
            return !((String) error).trim().isEmpty();
        }
        if (error instanceof JsonObject) {
            return !((JsonObject) error).isEmpty();
        }
        if (error instanceof JsonArray) {
            return !((JsonArray) error).isEmpty();
        }
        return true;
    }

    private static ContentNotAvailableException apiError(final Object error) {
        return new ContentNotAvailableException("Rumble API error: " + JsonWriter.string(error));
    }
}
