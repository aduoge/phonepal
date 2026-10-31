package dev.phonepal.sentinel

import java.util.concurrent.ConcurrentHashMap

/**
 * Matches confirmation words in on-screen text. Words are matched as substrings, except that an
 * end of the word made of a latin letter or digit must sit at a word boundary, case-insensitively:
 * "order" matches "Place Order" but not "border"; "支付" matches "立即支付".
 */
internal object ConfirmWords {
    val BUILT_IN: List<String> = listOf(
        "支付", "付款", "确认支付", "立即支付", "转账", "发送", "删除", "下单", "提交订单", "确认呼叫", "呼叫", "购买",
        "pay", "send", "delete", "purchase", "order",
    )

    private val patterns = ConcurrentHashMap<String, Regex>()

    /** Trims, drops blanks and duplicates (case-insensitively), keeping the first spelling. */
    fun normalize(words: Iterable<String>): List<String> =
        words.map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }

    fun matches(word: String, text: String): Boolean = pattern(word).containsMatchIn(text)

    /** The longest of [words] found in [text], or null. */
    fun longestIn(text: String, words: List<String>): String? =
        words.filter { matches(it, text) }.maxByOrNull { it.length }

    private fun pattern(word: String): Regex = patterns.getOrPut(word) {
        val head = if (word.first().isLatinWordChar()) "(?<![A-Za-z0-9])" else ""
        val tail = if (word.last().isLatinWordChar()) "(?![A-Za-z0-9])" else ""
        Regex(head + Regex.escape(word) + tail, RegexOption.IGNORE_CASE)
    }

    private fun Char.isLatinWordChar(): Boolean = this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'
}

/** Scheme and host of a URI or a deeplink template (templates may hold `{placeholders}`), lowercased. */
internal data class SchemeHost(val scheme: String, val host: String) {
    override fun toString(): String = if (host.isEmpty()) "$scheme:" else "$scheme://$host"

    companion object {
        fun of(uri: String): SchemeHost? {
            val trimmed = uri.trim()
            val colon = trimmed.indexOf(':')
            if (colon <= 0) return null
            val scheme = trimmed.substring(0, colon)
            if (!scheme.all { it.isLetterOrDigit() || it in "+-." }) return null
            val rest = trimmed.substring(colon + 1)
            val host = if (rest.startsWith("//")) {
                rest.substring(2).takeWhile { it !in "/?#" }.substringAfterLast('@').substringBefore(':')
            } else {
                ""
            }
            return SchemeHost(scheme.lowercase(), host.lowercase())
        }
    }
}
