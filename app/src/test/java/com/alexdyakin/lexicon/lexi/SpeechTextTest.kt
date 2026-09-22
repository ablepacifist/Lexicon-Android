package com.alexdyakin.lexicon.lexi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ported case for case from Lexi's `tests/test_sentence_chunker.py` and
 * `tests/test_strip_markdown.py`, so the phone and the Pi cannot drift apart silently.
 */
class SpeechTextTest {

    // ── sentence chunker ─────────────────────────────────────────────────────────────

    @Test
    fun `emits on a sentence boundary`() {
        val c = SentenceChunker()
        assertEquals(emptyList<String>(), c.feed("Hello"))
        // A boundary needs trailing whitespace or a newline.
        assertEquals(emptyList<String>(), c.feed(" world."))
        assertEquals(listOf("Hello world."), c.feed(" Next one!"))
        assertEquals("Next one!", c.flush())
    }

    @Test
    fun `splits several sentences from one feed`() {
        val c = SentenceChunker()
        assertEquals(listOf("One.", "Two?", "Three!"), c.feed("One. Two? Three! "))
        assertNull(c.flush())
    }

    @Test
    fun `a newline is a boundary`() {
        val c = SentenceChunker()
        assertEquals(listOf("a line"), c.feed("a line\nmore"))
        assertEquals("more", c.flush())
    }

    @Test
    fun `soft flushes a long run with no punctuation`() {
        val c = SentenceChunker(softFlushChars = 20)
        val out = c.feed("word ".repeat(10))
        assertTrue("should soft-flush a long run with no sentence boundary", out.isNotEmpty())
        assertTrue(out.all { it.isNotBlank() })
    }

    @Test
    fun `flush returns the remainder once`() {
        val c = SentenceChunker()
        c.feed("dangling text")
        assertEquals("dangling text", c.flush())
        assertNull(c.flush())
    }

    // ── markdown ─────────────────────────────────────────────────────────────────────

    @Test
    fun `strips markdown`() {
        val cases = listOf(
            // The case observed in a real answer.
            "It's currently **Tuesday, September 01** now." to "It's currently Tuesday, September 01 now.",
            "*emphasis* here" to "emphasis here",
            "***all of it***" to "all of it",
            "__bold__ and _italic_" to "bold and italic",
            "~~gone~~ text" to "gone text",
            "use the `run` command" to "use the run command",
            "# Heading\nbody" to "Heading\nbody",
            "> quoted line" to "quoted line",
            "- first\n- second" to "first\nsecond",
            "1. first\n2. second" to "first\nsecond",
            "see [the docs](https://example.com) for more" to "see the docs for more",
            "![alt](img.png) after" to "after",
        )
        for ((raw, expected) in cases) assertEquals("for input: $raw", expected, stripMarkdown(raw))
    }

    @Test
    fun `leaves ambiguous text alone`() {
        val cases = listOf(
            "5 * 3 = 15",               // arithmetic, not italics
            "a * b * c",                // unpaired around spaces
            "file_name_here.txt",       // underscores inside a word
            "snake_case and more_words",
            "2 * 3 and 4 * 5",
            "the * character",          // lone asterisk
            "50% off, 3 - 2 = 1",       // a dash mid-line is not a bullet
        )
        for (raw in cases) assertEquals("for input: $raw", raw, stripMarkdown(raw))
    }

    @Test
    fun `underscores inside non-ASCII words are not italics`() {
        // The Pi's regex is Unicode-aware; this pins the Kotlin port to the same behaviour.
        // Only the lookbehind protects this one: with an ASCII-only word class "é" would not
        // count as a letter and "_menu_" would be read as italics.
        assertEquals("café_menu_ special", stripMarkdown("café_menu_ special"))
    }

    @Test
    fun `drops fenced code blocks`() {
        val out = stripMarkdown("before\n```\nprint('hi')\n```\nafter")
        assertFalse(out.contains("print"))
        assertTrue(out.contains("before") && out.contains("after"))
    }

    @Test
    fun `collapses runs of spaces and trims`() {
        assertEquals("a b", stripMarkdown("  **a**    **b**  "))
    }

    @Test
    fun `empty and syntax-only input produce nothing to speak`() {
        assertEquals("", stripMarkdown(""))
        // A sentence that was nothing but markup must not reach the speech engine at all.
        assertEquals("", stripMarkdown("---"))
    }
}
