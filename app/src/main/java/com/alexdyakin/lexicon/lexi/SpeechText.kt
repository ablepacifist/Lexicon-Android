package com.alexdyakin.lexicon.lexi

/**
 * Text on its way to the speech engine.
 *
 * A direct port of `lexi/audio.py` in the Lexi repo, so the phone and the Pi say exactly
 * the same thing for the same answer. The tests are ported with it, including the "leaves
 * alone" cases — they are as load-bearing as the stripping ones.
 */

/** Where a streamed answer may be cut: terminal punctuation plus whitespace, or a newline. */
private val SENTENCE_BOUNDARY = Regex("""([.!?])(\s+)|(\n+)""")

/**
 * Do not hold a partial sentence forever when the model never emits terminal punctuation;
 * flush at a space once the buffer grows past this.
 */
private const val SOFT_FLUSH_CHARS = 180

/**
 * Feed streamed token text in; get complete sentences out.
 *
 * This is the latency trick: the first sentence starts speaking while the brain is still
 * generating the rest.
 */
class SentenceChunker(private val softFlushChars: Int = SOFT_FLUSH_CHARS) {
    private val buffer = StringBuilder()

    /** Adds streamed text and returns zero or more sentences ready to speak. */
    fun feed(text: String): List<String> {
        buffer.append(text)
        val out = ArrayList<String>()
        while (true) {
            val match = SENTENCE_BOUNDARY.find(buffer)
            if (match != null) {
                val end = match.range.last + 1
                val chunk = buffer.substring(0, end).trim()
                buffer.delete(0, end)
                if (chunk.isNotEmpty()) out += chunk
                continue
            }
            // No hard boundary; consider a soft flush at the last space.
            if (buffer.length >= softFlushChars) {
                val cut = buffer.lastIndexOf(" ", softFlushChars - 1)
                if (cut > 0) {
                    val chunk = buffer.substring(0, cut).trim()
                    buffer.delete(0, cut)
                    while (buffer.isNotEmpty() && buffer[0].isWhitespace()) buffer.deleteCharAt(0)
                    if (chunk.isNotEmpty()) {
                        out += chunk
                        continue
                    }
                }
            }
            break
        }
        return out
    }

    /** Whatever remains at the end of the stream, which may lack punctuation. */
    fun flush(): String? {
        val rest = buffer.toString().trim()
        buffer.setLength(0)
        return rest.ifEmpty { null }
    }
}

// Obrenna answers in markdown, because it is the same brain the web UI talks to, and a
// speech engine pronounces the syntax. On the Pi, "**Tuesday at 7 PM**" took 4.98 s to
// speak against 2.11 s clean.
//
// Deliberately conservative: these only fire on paired, well-formed markup, so arithmetic
// ("5 * 3"), an underscore inside a filename, or a stray asterisk pass through untouched.
//
// Python's `\w` is Unicode-aware; Java's is ASCII-only unless (?U) is set, and Android's
// ICU-backed regex does not reliably accept that flag. Spelling the class out keeps both
// platforms matching what the Pi does.
private const val WORD = """\p{L}\p{N}_"""

/**
 * Capture group for emphasis content between [delim]s. The content may not contain the
 * delimiter, so "**a** **b**" is two spans rather than one greedy match, and may not start
 * or end with whitespace, so spaced-out arithmetic is not read as italics.
 */
private fun emphasis(delim: String) = """([^\s$delim][^$delim\n]*[^\s$delim]|[^\s$delim])"""

private val STAR = emphasis("*")
private val UNDER = emphasis("_")
private val TILDE = emphasis("~")

private val MULTILINE = setOf(RegexOption.MULTILINE)

private val MARKDOWN_RULES: List<Pair<Regex, String>> = listOf(
    Regex("```.*?```", RegexOption.DOT_MATCHES_ALL) to " ",           // fenced code
    Regex("""`([^`\n]+)`""") to "$1",                                  // inline code
    Regex("""!\[[^\]]*\]\([^)]*\)""") to " ",                         // images: drop
    Regex("""\[([^\]]+)\]\([^)]*\)""") to "$1",                       // links: keep text
    Regex("""\*\*\*$STAR\*\*\*""") to "$1",
    Regex("""\*\*$STAR\*\*""") to "$1",                                // bold
    Regex("""(?<![$WORD*])\*$STAR\*(?![$WORD*])""") to "$1",           // italic
    Regex("""(?<![$WORD])__${UNDER}__(?![$WORD])""") to "$1",
    Regex("""(?<![$WORD])_${UNDER}_(?![$WORD])""") to "$1",
    Regex("""~~$TILDE~~""") to "$1",                                   // strikethrough
    Regex("""^\s{0,3}#{1,6}\s+""", MULTILINE) to "",                   // headings
    Regex("""^\s{0,3}>\s?""", MULTILINE) to "",                        // blockquote
    Regex("""^\s{0,3}([-*+]|\d+\.)\s+""", MULTILINE) to "",            // list bullets
    Regex("""^\s{0,3}([-*_])\s*(?:\1\s*){2,}$""", MULTILINE) to " ",   // horizontal rule
)

private val RUNS_OF_SPACES = Regex("[ \t]{2,}")

/**
 * Flattens markdown to plain prose for text-to-speech.
 *
 * Not a parser and not trying to be one: it removes the syntax a speech engine would read
 * aloud and leaves anything ambiguous alone.
 */
fun stripMarkdown(text: String): String {
    var out = text
    for ((pattern, replacement) in MARKDOWN_RULES) out = pattern.replace(out, replacement)
    return RUNS_OF_SPACES.replace(out, " ").trim()
}
