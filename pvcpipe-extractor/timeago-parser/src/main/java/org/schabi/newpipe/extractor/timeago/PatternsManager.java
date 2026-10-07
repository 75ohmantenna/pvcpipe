package org.schabi.newpipe.extractor.timeago;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class PatternsManager {
    /**
     * Return the patterns holder for the requested language and optional country code.
     *
     * @return the matching patterns holder, or {@code null} if no exact match exists
     */
    @Nullable
    public static PatternsHolder getPatterns(@Nonnull String languageCode, @Nullable String countryCode) {
        final String targetLocalizationClassName = languageCode +
                (countryCode == null || countryCode.isEmpty() ? "" : "_" + countryCode);
        return PatternMap.getPattern(targetLocalizationClassName);
    }
}
