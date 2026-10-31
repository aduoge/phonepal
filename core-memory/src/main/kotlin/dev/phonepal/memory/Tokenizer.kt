package dev.phonepal.memory

import java.util.Locale

/**
 * Search tokenizer: latin/other words lowercased, CJK runs as unigrams plus bigrams.
 * Queries use CJK bigrams as primary terms and unigrams as weak terms (single-char runs stay primary).
 */
internal object Tokenizer {
    /** Weight of a CJK unigram taken from a multi-character query run. */
    const val WEAK_WEIGHT = 0.3

    private class Run(val text: String, val cjk: Boolean)

    fun isCjk(cp: Int): Boolean = when (Character.UnicodeScript.of(cp)) {
        Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA,
        Character.UnicodeScript.KATAKANA, Character.UnicodeScript.HANGUL -> true
        else -> false
    }

    /** Index terms of [text], with repetitions (for term frequency). */
    fun tokens(text: String): List<String> {
        val out = ArrayList<String>()
        for (run in runs(text)) {
            if (!run.cjk) {
                out += run.text
                continue
            }
            val chars = codePointStrings(run.text)
            out += chars
            for (i in 0 until chars.size - 1) out += chars[i] + chars[i + 1]
        }
        return out
    }

    /** Query terms with weights; 1.0 for primary terms, [WEAK_WEIGHT] for CJK unigrams of longer runs. */
    fun queryTerms(text: String): Map<String, Double> {
        val out = LinkedHashMap<String, Double>()
        fun add(term: String, weight: Double) {
            out[term] = maxOf(out[term] ?: 0.0, weight)
        }
        for (run in runs(text)) {
            if (!run.cjk) {
                add(run.text, 1.0)
                continue
            }
            val chars = codePointStrings(run.text)
            if (chars.size == 1) {
                add(chars[0], 1.0)
                continue
            }
            for (i in 0 until chars.size - 1) add(chars[i] + chars[i + 1], 1.0)
            chars.forEach { add(it, WEAK_WEIGHT) }
        }
        return out
    }

    private fun runs(text: String): List<Run> {
        val runs = ArrayList<Run>()
        val current = StringBuilder()
        var currentCjk = false
        fun flush() {
            if (current.isNotEmpty()) runs += Run(current.toString(), currentCjk)
            current.setLength(0)
        }
        text.codePoints().forEach { cp ->
            val cjk = isCjk(cp)
            val word = cjk || Character.isLetterOrDigit(cp)
            if (!word) {
                flush()
                return@forEach
            }
            if (current.isNotEmpty() && cjk != currentCjk) flush()
            currentCjk = cjk
            if (cjk) current.appendCodePoint(cp) else current.append(String(Character.toChars(cp)).lowercase(Locale.ROOT))
        }
        flush()
        return runs
    }

    private fun codePointStrings(text: String): List<String> =
        text.codePoints().toArray().map { String(Character.toChars(it)) }
}
