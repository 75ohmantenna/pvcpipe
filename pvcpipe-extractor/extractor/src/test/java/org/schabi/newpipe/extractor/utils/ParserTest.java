/*
 * SPDX-FileCopyrightText: 2026 PVCPipe contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.extractor.utils;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Tag("offline")
class ParserTest {
    @Test
    void groupHelpersFindSubstrings() throws Parser.RegexException {
        final String input = "before abc after";
        final Pattern pattern = Pattern.compile("(abc)");
        assertEquals("abc", Parser.matchGroup1("(abc)", input));
        assertEquals("abc", Parser.matchGroup1(pattern, input));
        assertEquals("abc", Parser.matchGroup("(abc)", input, 1));
        assertEquals("abc", Parser.matchGroup(pattern, input, 1));
        assertEquals("abc", Parser.matchGroup1MultiplePatterns(new Pattern[]{pattern}, input));
    }

    @Test
    void unmatchedOptionalCapturesReturnNull() throws Parser.RegexException {
        final Pattern pattern = Pattern.compile("(a)?b");
        assertNull(Parser.matchGroup1("(a)?b", "b"));
        assertNull(Parser.matchGroup1(pattern, "b"));
        assertNull(Parser.matchGroup("(a)?b", "b", 1));
        assertNull(Parser.matchGroup(pattern, "b", 1));
        assertNull(Parser.matchGroup1MultiplePatterns(new Pattern[]{pattern}, "b"));
    }

    @Test
    void participatingEmptyCapturesReturnEmptyStrings() throws Parser.RegexException {
        final Pattern pattern = Pattern.compile("a(b*)");
        assertEquals("", Parser.matchGroup1("a(b*)", "a"));
        assertEquals("", Parser.matchGroup1(pattern, "a"));
        assertEquals("", Parser.matchGroup("a(b*)", "a", 1));
        assertEquals("", Parser.matchGroup(pattern, "a", 1));
        assertEquals("", Parser.matchGroup1MultiplePatterns(new Pattern[]{pattern}, "a"));
    }

    @Test
    void groupZeroReturnsTheWholeMatchedSubstring() throws Parser.RegexException {
        assertEquals("b", Parser.matchGroup("(a)?b", "before b after", 0));
        assertEquals("b", Parser.matchGroup(Pattern.compile("(a)?b"), "before b after", 0));
    }

    @Test
    void absentMatchesStillThrowRegexException() {
        final Pattern pattern = Pattern.compile("(a)");
        assertThrows(Parser.RegexException.class, () -> Parser.matchGroup1("(a)", "b"));
        assertThrows(Parser.RegexException.class, () -> Parser.matchGroup1(pattern, "b"));
        assertThrows(Parser.RegexException.class, () -> Parser.matchGroup("(a)", "b", 1));
        assertThrows(Parser.RegexException.class, () -> Parser.matchGroup(pattern, "b", 1));
        assertThrows(Parser.RegexException.class,
                () -> Parser.matchGroup1MultiplePatterns(new Pattern[]{pattern}, "b"));
        assertThrows(Parser.RegexException.class, () -> Parser.matchGroup(pattern, "b", 99));
    }

    @Test
    void invalidGroupNumbersStillThrowAfterAMatch() {
        final Pattern pattern = Pattern.compile("(a)");
        for (final int group : new int[]{-1, 2}) {
            assertThrows(IndexOutOfBoundsException.class,
                    () -> Parser.matchGroup("(a)", "a", group));
            assertThrows(IndexOutOfBoundsException.class,
                    () -> Parser.matchGroup(pattern, "a", group));
        }
        assertThrows(IndexOutOfBoundsException.class, () -> Parser.matchGroup1("a", "a"));
        assertThrows(IndexOutOfBoundsException.class,
                () -> Parser.matchGroup1(Pattern.compile("a"), "a"));
    }

    @Test
    void multiplePatternsStopAtTheFirstMatchEvenIfItsCaptureIsNull()
            throws Parser.RegexException {
        assertNull(Parser.matchGroup1MultiplePatterns(
                new Pattern[]{Pattern.compile("(a)?b"), Pattern.compile("(b)")}, "b"));
    }

    @Test
    void emptyAndUnmatchedPatternArraysStillThrowRegexException() {
        assertThrows(Parser.RegexException.class,
                () -> Parser.matchGroup1MultiplePatterns(new Pattern[0], "a"));
        assertThrows(Parser.RegexException.class, () -> Parser.matchGroup1MultiplePatterns(
                new Pattern[]{Pattern.compile("(b)"), Pattern.compile("(c)")}, "a"));
    }

    @Test
    void regexArrayCallerRetriesUnmatchedAndNullCaptures() throws Parser.RegexException {
        final String[] patterns = {"(c)", "(a)?b", "(b)"};
        assertEquals("b", Utils.getStringResultFromRegexArray("b", patterns, 1));
        assertEquals("b", Utils.getStringResultFromRegexArray("b",
                new Pattern[]{Pattern.compile("(c)"), Pattern.compile("(a)?b"),
                        Pattern.compile("(b)")}, 1));
    }

    @Test
    void regexArrayCallerRejectsAllNullCapturesButAcceptsEmptyCaptures()
            throws Parser.RegexException {
        assertThrows(Parser.RegexException.class,
                () -> Utils.getStringResultFromRegexArray("b", new String[]{"(a)?b"}, 1));
        assertThrows(Parser.RegexException.class, () -> Utils.getStringResultFromRegexArray("b",
                new Pattern[]{Pattern.compile("(a)?b")}, 1));
        assertEquals("", Utils.getStringResultFromRegexArray("b", new String[]{"(a*)b"}, 1));
        assertEquals("", Utils.getStringResultFromRegexArray("b",
                new Pattern[]{Pattern.compile("(a*)b")}, 1));
    }

    @Test
    void malformedStringPatternsStillThrowPatternSyntaxException() {
        assertThrows(PatternSyntaxException.class, () -> Parser.matchGroup1("(", "a"));
        assertThrows(PatternSyntaxException.class, () -> Parser.matchGroup("(", "a", 0));
    }

    @Test
    void matcherResultIsNonNullEvenWithAnUnmatchedCapture() throws Parser.RegexException {
        final Matcher matcher = Parser.matchOrThrow(Pattern.compile("(a)?b"), "b");
        assertNotNull(matcher);
        assertEquals("b", matcher.group(0));
        assertNull(matcher.group(1));
    }
}
