package com.flowreader.app.util

/**
 * Smart chapter and volume detector for plain text novels.
 *
 * Supports Chinese chapter numerals (百/千/万/两/〇), volumes (分卷/第X卷),
 * special sections (序章, 引子, 楔子, 尾声, 后记, 番外, 终章, 大结局, 上架感言, etc.),
 * bracketed chapter titles (【第X章】, [Chapter 1], etc.),
 * western chapters (Chapter 1, Act 1, Part 1),
 * and numeric section headers (01 xxx, 1. xxx, 一、xxx).
 *
 * Includes anti-false-positive heuristic filtering:
 * - Rejects quoted dialogue lines
 * - Rejects lines ending with sentence punctuation
 * - Enforces title length boundaries (typically <= 45 chars)
 * - Two-pass hierarchical scanning (Tier 1 standard -> Tier 2 numbering fallback)
 */
object TxtChapterDetector {
    private const val CN_NUMS = "[0-9零一二三四五六七八九十百千万两〇]"

    /** Volume pattern: matches 第X卷, 卷X, 分卷X, etc. */
    val VOLUME_PATTERN = Regex(
        "^\\s*([第]?\\s*$CN_NUMS+\\s*卷|分卷\\s*$CN_NUMS+|卷\\s*$CN_NUMS+)(?:\\s*[:：\\s]\\s*(.*))?$"
    )

    /** Bracketed volume pattern: matches 【第一卷】, [第1卷], etc. */
    val BRACKETED_VOLUME_PATTERN = Regex(
        "^\\s*[【〔［\\[（(]\\s*([第]?\\s*$CN_NUMS+\\s*卷|分卷\\s*$CN_NUMS+|卷\\s*$CN_NUMS+)[^】〕］\\]）)]*[】〕］\\]）)]\\s*(.*)$"
    )

    /** Special section pattern: 序章, 引子, 楔子, 尾声, etc. */
    val SPECIAL_SECTION_PATTERN = Regex(
        "^\\s*(序[章言]?|引子|楔子|尾声|后记|番外[篇]?|终章|大结局|上架感言|完本感言|写在前面的话|前言|结语)\\s*(.*)$"
    )

    /** Tier 1: High-confidence chapter headings */
    val TIER1_PATTERNS = listOf(
        // 1. 第X章/回/节/集/幕/篇/部/话/折
        Regex("^\\s*(第\\s*$CN_NUMS+\\s*[章节回折集幕篇部话节])\\s*(.*)$"),
        // 2. 特殊章节标识：序章、引子、楔子、尾声、后记、番外、终章、大结局、上架感言、完本感言等
        SPECIAL_SECTION_PATTERN,
        // 3. 卷名 (第X卷...)
        VOLUME_PATTERN,
        // 4. 括号包裹式：【第X章】、[Chapter 1]、〔第X章〕等
        Regex("^\\s*[【〔［\\[（(]\\s*(第\\s*$CN_NUMS+\\s*[章节回折集幕篇部话节卷]|序章|尾声|番外|Chapter\\s*\\d+)[^】〕］\\]）)]*[】〕］\\]）)]\\s*(.*)$"),
        // 5. 西文章节：Chapter 1, Act 1, Part 1, Section 1
        Regex("^\\s*(Chapter\\s+\\d+|CHAPTER\\s+\\d+|Chapter\\s+[IVXLCDM]+|Act\\s+\\d+|Part\\s+\\d+|Section\\s+\\d+)\\s*(.*)$", RegexOption.IGNORE_CASE),
        // 6. Markdown 格式标题
        Regex("^\\s*#{1,3}\\s+(.*)$")
    )

    /** Tier 2: Numbering-only fallback (01 章节名, 1. 章节名, 一、章节名) */
    val TIER2_PATTERNS = listOf(
        Regex("^\\s*(\\d{1,4}|[一二三四五六七八九十]{1,3})[、.．:：\\s]\\s*([^\\n]{1,30})$")
    )

    private val INVALID_PREFIXES = listOf("“", "‘", "「", "『", "\"", "'", "…")
    private val INVALID_SUFFIXES = listOf("。", "！", "？", "，", "、", "…", "；", "!", "?", ",", "”", "’", "\"", "'")

    enum class HeadingKind {
        NONE,
        CHAPTER,
        VOLUME,
        SPECIAL_SECTION
    }

    data class HeadingMatch(
        val isHeading: Boolean,
        val title: String,
        val isVolume: Boolean = false,
        val kind: HeadingKind = if (isVolume) HeadingKind.VOLUME else if (isHeading) HeadingKind.CHAPTER else HeadingKind.NONE
    )

    fun matchHeading(line: String, patterns: List<Regex>, customPatterns: List<Regex> = emptyList()): HeadingMatch {
        val trimmed = line.trim()

        // A user regex is authoritative: it may recognise headings the heuristics deliberately
        // reject (dialogue quotes, long titles), so it runs before the length/prefix filters.
        if (customPatterns.isNotEmpty()) {
            for (pattern in customPatterns) {
                val match = pattern.find(trimmed) ?: continue
                if (trimmed.length > MAX_CUSTOM_TITLE_LENGTH) continue
                val captured = match.groupValues.getOrNull(1)?.trim()
                val title = captured?.takeIf { it.isNotEmpty() } ?: match.value.trim()
                if (title.isEmpty()) continue
                val isVol = VOLUME_PATTERN.matches(trimmed) || BRACKETED_VOLUME_PATTERN.matches(trimmed)
                val isSpecial = !isVol && SPECIAL_SECTION_PATTERN.matches(trimmed)
                val kind = if (isVol) HeadingKind.VOLUME else if (isSpecial) HeadingKind.SPECIAL_SECTION else HeadingKind.CHAPTER
                return HeadingMatch(
                    isHeading = true,
                    title = title.take(MAX_CUSTOM_TITLE_LENGTH),
                    isVolume = isVol,
                    kind = kind
                )
            }
        }

        if (trimmed.length !in 2..45) return HeadingMatch(false, "", false, HeadingKind.NONE)
        if (INVALID_PREFIXES.any { trimmed.startsWith(it) }) return HeadingMatch(false, "", false, HeadingKind.NONE)
        if (INVALID_SUFFIXES.any { trimmed.endsWith(it) }) return HeadingMatch(false, "", false, HeadingKind.NONE)

        if (VOLUME_PATTERN.matches(trimmed) || BRACKETED_VOLUME_PATTERN.matches(trimmed)) {
            return HeadingMatch(isHeading = true, title = trimmed, isVolume = true, kind = HeadingKind.VOLUME)
        }

        if (SPECIAL_SECTION_PATTERN.matches(trimmed)) {
            return HeadingMatch(isHeading = true, title = trimmed, isVolume = false, kind = HeadingKind.SPECIAL_SECTION)
        }

        for (pattern in patterns) {
            if (pattern.matches(trimmed)) {
                return HeadingMatch(isHeading = true, title = trimmed, isVolume = false, kind = HeadingKind.CHAPTER)
            }
        }
        return HeadingMatch(false, "", false, HeadingKind.NONE)
    }

    private const val MAX_CUSTOM_TITLE_LENGTH = 60

    /**
     * Attempts to detect author from metadata lines at the beginning of the file.
     */
    fun extractAuthor(firstLines: List<String>): String? {
        val authorPattern = Regex("^(?:作\\s*者|Author)[:：\\s]\\s*(.+)$", RegexOption.IGNORE_CASE)
        for (line in firstLines.take(30)) {
            val trimmed = line.trim()
            val match = authorPattern.find(trimmed)
            if (match != null) {
                val candidate = match.groupValues[1].trim()
                if (candidate.isNotBlank() && candidate.length <= 20) {
                    return candidate
                }
            }
        }
        return null
    }

    /**
     * Selects best pattern tier based on match count in document.
     */
    fun selectBestPatterns(lines: List<String>): List<Regex> {
        var tier1Matches = 0
        for (line in lines) {
            if (matchHeading(line, TIER1_PATTERNS).isHeading) {
                tier1Matches++
                if (tier1Matches >= 2) return TIER1_PATTERNS
            }
        }

        var tier2Matches = 0
        for (line in lines) {
            if (matchHeading(line, TIER2_PATTERNS).isHeading) {
                tier2Matches++
                if (tier2Matches >= 2) return TIER2_PATTERNS
            }
        }

        return if (tier1Matches > 0) TIER1_PATTERNS else emptyList()
    }
}
