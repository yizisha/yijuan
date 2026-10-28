package com.flowreader.app.core.util

import com.flowreader.app.domain.model.TxtProcessingRules

/**
 * Plain-text cleaning and rule compilation for TXT imports.
 *
 * Pure Kotlin and side-effect free so it is unit-testable on the JVM; [com.flowreader.app.util.BookParser]
 * is the only caller that matters, and it applies the result once at import time.
 *
 * Every pattern here is deliberately simple and anchored: a user-supplied regex is compiled with a
 * length cap and never run against the whole file at once (cleaning is line-by-line), which keeps a
 * pathological rule from turning an import into a hang.
 */
object TxtCleaner {

    /**
     * Lines that are site furniture rather than story. Kept short and literal-ish on purpose: an
     * over-eager rule would eat real prose, and the user can always add their own.
     */
    private val AD_LINE_PATTERNS = listOf(
        Regex("""^\s*(?:请记住本站|记住本站|本站网址|收藏本站|手机版阅读网址|最新章节|全文免费阅读|免费阅读|无弹窗|无广告)[:：]?.*$"""),
        Regex("""^\s*(?:www\.|https?://|m\.)\S+\s*$""", RegexOption.IGNORE_CASE),
        Regex("""^\s*(?:更新时间|更新时间[:：]|字数[:：]|本章字数[:：]).*$"""),
        Regex("""^\s*(?:第?\s*\d{1,4}\s*/\s*\d{1,4}\s*页|共\s*\d+\s*页|\d{1,4}\s*/\s*\d{1,4})\s*$"""),
        Regex("""^\s*(?:本书由.*整理|声明[:：].*转载|内容简介[:：]?)\s*$"""),
        Regex("""^\s*[=\-*~_#·•]{3,}\s*$""")
    )

    /** Normalises line endings and trims trailing whitespace on every line. */
    fun normalize(text: String): String = text
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .lineSequence()
        .joinToString("\n") { it.trimEnd() }

    fun clean(text: String, rules: TxtProcessingRules): String {
        var working = normalize(text)

        if (rules.customReplacements.isNotBlank()) {
            working = applyReplacements(working, rules.customReplacements)
        }

        if (rules.removeAdLines) {
            working = working.lineSequence()
                .filterNot { line -> AD_LINE_PATTERNS.any { it.matches(line) } }
                .joinToString("\n")
        }

        if (rules.stripBlankLines) {
            working = stripBlankRuns(working)
        }

        return working.trim()
    }

    /** Collapses runs of blank lines to a single blank line (the chapter splitter relies on `\n\n`). */
    fun stripBlankRuns(text: String): String {
        val output = StringBuilder(text.length)
        var blankRun = 0
        text.lineSequence().forEach { line ->
            if (line.isBlank()) {
                blankRun++
                if (blankRun == 1) output.append('\n')
            } else {
                blankRun = 0
                output.append(line).append('\n')
            }
        }
        return output.toString().trim()
    }

    /**
     * Parses `正则 => 替换` rules (one per line). A rule without `=>` deletes every match of its
     * pattern. Invalid patterns are skipped rather than failing the whole import.
     */
    fun parseReplacements(raw: String): List<Pair<Regex, String>> {
        if (raw.isBlank()) return emptyList()
        return raw.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .mapNotNull { line ->
                val separator = line.indexOf("=>")
                val pattern = if (separator >= 0) line.substring(0, separator).trim() else line
                val replacement = if (separator >= 0) line.substring(separator + 2).trim() else ""
                if (pattern.isEmpty() || pattern.length > TxtProcessingRules.MAX_REGEX_LENGTH) {
                    null
                } else {
                    runCatching { Regex(pattern) }.getOrNull()?.let { it to replacement }
                }
            }
            .toList()
    }

    private fun applyReplacements(text: String, raw: String): String {
        val rules = parseReplacements(raw)
        if (rules.isEmpty()) return text
        return text.lineSequence().joinToString("\n") { line ->
            var result = line
            rules.forEach { (pattern, replacement) -> result = pattern.replace(result, replacement) }
            result
        }
    }

    /**
     * Compiles the user's chapter regex. Returns null when blank or invalid so the caller can fall
     * back to the built-in detector instead of failing the import.
     */
    fun compileChapterRegex(raw: String): Regex? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed.length > TxtProcessingRules.MAX_REGEX_LENGTH) return null
        return runCatching { Regex(trimmed) }.getOrNull()
    }
}
