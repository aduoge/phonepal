package dev.phonepal.memory

import java.util.Locale

/** Permalink validation and generation; permalinks are folder-relative paths without `.md`. */
object Permalinks {
    private val ILLEGAL = Regex("""[:*?"<>|\x00-\x1f\x7f]""")
    private val DRIVE = Regex("^[A-Za-z]:")
    private val DASHES = Regex("-{2,}")

    /**
     * Validates [raw] and returns its canonical form: `/` separators, no `.md` suffix, trimmed segments,
     * filesystem-hostile characters replaced by `-`. CJK and other letters are kept as-is.
     *
     * @throws IllegalArgumentException for empty input, absolute paths, empty, `.`/`..` or hidden segments.
     */
    fun normalize(raw: String): String {
        var path = raw.trim().replace('\\', '/')
        require(path.isNotEmpty()) { "Permalink is empty" }
        require(!path.startsWith("/") && !DRIVE.containsMatchIn(path)) { "Permalink must be relative: $raw" }
        if (path.endsWith(".md", ignoreCase = true)) path = path.dropLast(3)
        return path.split('/').joinToString("/") { segment ->
            val s = segment.trim()
            require(s.isNotEmpty()) { "Permalink has an empty segment: $raw" }
            require(s != "." && s != "..") { "Permalink must not contain . or ..: $raw" }
            require(!s.startsWith(".")) { "Permalink segments must not start with '.': $raw" }
            ILLEGAL.replace(s, "-")
        }
    }

    /** Slug for [title]: latin lowercased, letters/digits (incl. CJK) kept, everything else `-`. Empty if none. */
    fun fromTitle(title: String): String {
        val slug = buildString {
            title.trim().lowercase(Locale.ROOT).codePoints().forEach { cp ->
                when {
                    Character.isLetterOrDigit(cp) || cp == '_'.code -> appendCodePoint(cp)
                    else -> append('-')
                }
            }
        }
        return DASHES.replace(slug, "-").trim('-')
    }
}
