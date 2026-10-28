package org.schabi.newpipe.extractor.utils;

import java.util.Locale;
import java.util.Optional;

/**
 * Legacy locale parser retained by localization and subtitle callers.
 * Accepts hyphen- or underscore-separated language, country and variant components; it does not
 * implement full BCP 47 script/extension parsing like {@link Locale#forLanguageTag(String)}.
 * PVCPipe's minimum API is 26, so the original pre-21 compatibility motivation no longer applies
 * to the app. Replacing this parser would be a behavior change for existing callers.
 */
public final class LocaleCompat {
    private LocaleCompat() {
    }

    // Source: The AndroidX LocaleListCompat class's private forLanguageTagCompat() method.
    // Use Locale.forLanguageTag() on Android API level >= 21 / Java instead.
    public static Optional<Locale> forLanguageTag(final String str) {
        if (str.contains("-")) {
            final String[] args = str.split("-", -1);
            if (args.length > 2) {
                return Optional.of(new Locale(args[0], args[1], args[2]));
            } else if (args.length > 1) {
                return Optional.of(new Locale(args[0], args[1]));
            } else if (args.length == 1) {
                return Optional.of(new Locale(args[0]));
            }
        } else if (str.contains("_")) {
            final String[] args = str.split("_", -1);
            if (args.length > 2) {
                return Optional.of(new Locale(args[0], args[1], args[2]));
            } else if (args.length > 1) {
                return Optional.of(new Locale(args[0], args[1]));
            } else if (args.length == 1) {
                return Optional.of(new Locale(args[0]));
            }
        } else {
            return Optional.of(new Locale(str));
        }

        return Optional.empty();
    }
}
