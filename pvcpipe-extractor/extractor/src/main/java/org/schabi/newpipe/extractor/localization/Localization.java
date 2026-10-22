package org.schabi.newpipe.extractor.localization;

import org.schabi.newpipe.extractor.utils.LocaleCompat;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;


public class Localization implements Serializable {
    public static final Localization DEFAULT = new Localization("en", "GB");

    @Nonnull
    private final String languageCode;
    @Nullable
    private final String countryCode;

    /**
     * Create a list of localizations from localization codes.
     *
     * @param localizationCodeList a list of localization code, formatted like {@link
     *                             #getLocalizationCode()}
     * @throws IllegalArgumentException If any of the localizationCodeList is formatted incorrectly
     * @return list of Localization objects
     */
    @Nonnull
    public static List<Localization> listFrom(final String... localizationCodeList) {
        final List<Localization> toReturn = new ArrayList<>();
        for (final String localizationCode : localizationCodeList) {
            toReturn.add(fromLocalizationCode(localizationCode)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Not a localization code: " + localizationCode
                    )));
        }
        return Collections.unmodifiableList(toReturn);
    }

    /**
     * Parse a localization code into a localization when valid.
     *
     * @param localizationCode a localization code, formatted like {@link #getLocalizationCode()}
     * @return A Localization, if the code was valid.
     */
    @Nonnull
    public static Optional<Localization> fromLocalizationCode(final String localizationCode) {
        return LocaleCompat.forLanguageTag(localizationCode).map(Localization::fromLocale);
    }

    public Localization(@Nonnull final String languageCode, @Nullable final String countryCode) {
        this.languageCode = languageCode;
        this.countryCode = countryCode;
    }

    public Localization(@Nonnull final String languageCode) {
        this(languageCode, null);
    }

    @Nonnull
    public String getLanguageCode() {
        return languageCode;
    }

    @Nonnull
    public String getCountryCode() {
        return countryCode == null ? "" : countryCode;
    }

    public static Localization fromLocale(@Nonnull final Locale locale) {
        return new Localization(locale.getLanguage(), locale.getCountry());
    }

    /**
     * Return a formatted string in the form of: {@code language-Country}, or
     * just {@code language} if country is {@code null}.
     *
     * @return A correctly formatted localizationCode for this localization.
     */
    public String getLocalizationCode() {
        return languageCode + (countryCode == null ? "" : "-" + countryCode);
    }

    @Override
    public String toString() {
        return "Localization[" + getLocalizationCode() + "]";
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Localization)) {
            return false;
        }

        final Localization that = (Localization) o;

        return languageCode.equals(that.languageCode)
                && Objects.equals(countryCode, that.countryCode);
    }

    @Override
    public int hashCode() {
        int result = languageCode.hashCode();
        result = 31 * result + Objects.hashCode(countryCode);
        return result;
    }
}
