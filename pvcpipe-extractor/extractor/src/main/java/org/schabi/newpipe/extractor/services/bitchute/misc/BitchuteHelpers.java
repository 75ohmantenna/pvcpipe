package org.schabi.newpipe.extractor.services.bitchute.misc;

import com.grack.nanojson.JsonObject;

import org.schabi.newpipe.extractor.Image;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class BitchuteHelpers {

    private static final Pattern IMAGE_SIZE_PATTERN =
            Pattern.compile("_([1-9][0-9]{0,4})x([1-9][0-9]{0,4})\\.[A-Za-z0-9]+$");
    private static final Pattern CHANNEL_IMAGE_PATTERN =
            Pattern.compile("/channel_images/.*_(?:small|large)\\.[A-Za-z0-9]+$");

    private BitchuteHelpers() {
    }

    /**
     * The BitChute API gives image URLs without a size. Cover images carry their size in the
     * file name ({@code ..._640x360.jpg}). Channel images are served as a {@code _small} (263px)
     * or {@code _large} (about 560px) rendition, both of which are medium resolution.
     */
    public static Image toImage(final String url) {
        if (url != null) {
            final Matcher size = IMAGE_SIZE_PATTERN.matcher(url);
            if (size.find()) {
                final int height = Integer.parseInt(size.group(2));
                return new Image(url, height, Integer.parseInt(size.group(1)),
                        Image.ResolutionLevel.fromHeight(height));
            }
            if (CHANNEL_IMAGE_PATTERN.matcher(url).find()) {
                return new Image(url, Image.HEIGHT_UNKNOWN, Image.WIDTH_UNKNOWN,
                        Image.ResolutionLevel.MEDIUM);
            }
        }
        return new Image(url,
                Image.HEIGHT_UNKNOWN, Image.WIDTH_UNKNOWN, Image.ResolutionLevel.UNKNOWN);
    }

    /**
     * sometimes nanojson threats the content as Integer and not as String if it is
     * internally declares as integer. This is a workaround to give always the result as String
     */
    public static String getIntAlwaysAsString(final JsonObject result, final String jsonKey) {
        String strNumber = result.getString(jsonKey);
        if (null == strNumber) {
            final int number = result.getInt(jsonKey);
            strNumber = String.valueOf(number);
        }
        return strNumber;
    }

}
