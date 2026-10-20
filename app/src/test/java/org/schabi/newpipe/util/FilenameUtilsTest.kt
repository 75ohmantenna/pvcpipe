package org.schabi.newpipe.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FilenameUtilsTest {
    private val letters = "CHARSET_LETTERS_AND_DIGITS"
    private val special = "CHARSET_MOST_SPECIAL"

    private fun name(title: String, charset: String = special, replacement: String = "_"): String {
        return FilenameUtils.createFilename(title, charset, replacement, letters, special)
    }

    @Test
    fun presetsKeepTheirBehaviour() {
        assertEquals("a_b_c", name("a/b:c"))
        assertEquals("a_b", name("a. b", charset = letters))
    }

    @Test
    fun anInvalidStoredPatternFallsBackToTheDefaultCharset() {
        // an import or an older version can store any string as the charset
        assertEquals("a_b", name("a/b", charset = "[unclosed"))
        assertEquals("a_b_c", name("a?b*c", charset = "(?<"))
    }

    @Test
    fun aValidCustomPatternIsPreserved() {
        assertEquals("h_ll_", name("hello", charset = "[eo]"))
        assertEquals("hello", name("hello", charset = "x"))
    }

    @Test
    fun separatorsAreReplacedEvenWhenACustomPatternKeepsThem() {
        assertEquals("a_b_c_d", name("a/b\\c\u0000d", charset = "x"))
    }

    @Test
    fun aStoredReplacementWithASeparatorFallsBackToTheDefault() {
        assertEquals("_title", name("/title", replacement = "../"))
        assertEquals("_title", name("/title", replacement = "/"))
        assertEquals("_title", name("/title", replacement = "\\"))
        // dots alone stay within one component, and the result is checked again on creation
        assertEquals("..title", name("/title", replacement = ".."))
        assertEquals("a\$1b", name("a/b", replacement = "\$1"))
    }

    @Test
    fun replacementValidationRejectsOnlySeparators() {
        assertTrue(FilenameUtils.isValidReplacement("_"))
        assertTrue(FilenameUtils.isValidReplacement(".."))
        assertTrue(FilenameUtils.isValidReplacement(""))
        assertFalse(FilenameUtils.isValidReplacement("/"))
        assertFalse(FilenameUtils.isValidReplacement("a\\b"))
        assertFalse(FilenameUtils.isValidReplacement("\u0000"))
    }
}
