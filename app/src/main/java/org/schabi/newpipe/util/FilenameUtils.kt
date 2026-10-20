/*
 * SPDX-FileCopyrightText: 2017-2025 NewPipe contributors <https://newpipe.net>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.util

import android.content.Context
import androidx.preference.PreferenceManager
import java.util.regex.Matcher
import org.schabi.newpipe.R
import org.schabi.newpipe.ktx.getStringSafe

object FilenameUtils {
    private const val CHARSET_MOST_SPECIAL = "[\\n\\r|?*<\":\\\\>/']+"
    private const val CHARSET_ONLY_LETTERS_AND_DIGITS = "[^\\w\\d]+"
    private const val DEFAULT_REPLACEMENT = "_"
    private val SEPARATORS = Regex("[/\\\\\\u0000]")

    /**
     * #143 #44 #42 #22: make sure that the filename does not contain illegal chars.
     *
     * @param context the context to retrieve strings and preferences from
     * @param title the title to create a filename from
     * @return the filename
     */
    @JvmStatic
    fun createFilename(context: Context, title: String): String {
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)

        val charsetLd = context.getString(R.string.charset_letters_and_digits_value)
        val charsetMs = context.getString(R.string.charset_most_special_value)
        val defaultCharset = context.getString(R.string.default_file_charset_value)

        val replacementChar = sharedPreferences.getStringSafe(
            context.getString(R.string.settings_file_replacement_character_key),
            "_"
        )
        val selectedCharset = sharedPreferences.getStringSafe(
            context.getString(R.string.settings_file_charset_key),
            ""
        ).ifEmpty { defaultCharset }

        return createFilename(title, selectedCharset, replacementChar, charsetLd, charsetMs)
    }

    /**
     * Stored settings are checked every time they are read, as an import or an older version
     * may have written them: an invalid custom pattern falls back to the default charset, and
     * a replacement containing a path separator falls back to `_`. Path separators are always
     * replaced, so that a valid custom pattern cannot leave one in the name.
     *
     * @param title the title to create a filename from
     * @param selectedCharset the stored charset: a preset value or a custom pattern
     * @param replacementChar the stored replacement text
     * @param charsetLd the preset value for letters and digits only
     * @param charsetMs the preset value for most special characters
     * @return a name without path separators
     */
    internal fun createFilename(
        title: String,
        selectedCharset: String,
        replacementChar: String,
        charsetLd: String,
        charsetMs: String
    ): String {
        val charset = when (selectedCharset) {
            charsetLd -> CHARSET_ONLY_LETTERS_AND_DIGITS
            charsetMs -> CHARSET_MOST_SPECIAL
            else -> selectedCharset // Is the user using a custom charset?
        }
        val invalid = runCatching { charset.toRegex() }
            .getOrElse { CHARSET_MOST_SPECIAL.toRegex() }
        val replacement = Matcher.quoteReplacement(
            if (isValidReplacement(replacementChar)) replacementChar else DEFAULT_REPLACEMENT
        )
        return title.replace(invalid, replacement).replace(SEPARATORS, replacement)
    }

    /**
     * @param replacement replacement text for invalid filename characters
     * @return whether it keeps a name within one path component
     */
    @JvmStatic
    fun isValidReplacement(replacement: String): Boolean {
        return !SEPARATORS.containsMatchIn(replacement)
    }
}
