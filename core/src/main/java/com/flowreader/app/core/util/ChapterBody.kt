package com.flowreader.app.core.util

/**
 * Trims the copy of the chapter heading that imported files carry inside their own text.
 *
 * EPUB and TXT sources routinely repeat the heading as the first line of the chapter body
 * (`<h3>第999章</h3><p><strong>世尊保佑，行善积德</strong></p>`), while the reader already draws the
 * heading from the table of contents. Rendering both showed the title twice and made the second
 * copy look like a bold sub-heading — reported as 「章节会重复显示标题，还会把首段切分成黑体加粗的章节名」.
 *
 * The rule is deliberately conservative: only *leading* lines are considered, at most two of them,
 * and each must be a non-empty substring of the title. A real first paragraph is therefore never
 * removed, and the stored text is untouched — this only affects what the reader draws.
 */
object ChapterBody {

    private const val MAX_ECHOED_LINES = 2

    fun stripLeadingTitleEcho(content: String, title: String): String {
        val normalizedTitle = normalize(title)
        if (normalizedTitle.isEmpty()) return content

        val lines = content.split('\n')
        var index = 0
        var consumed = 0

        while (index < lines.size && consumed < MAX_ECHOED_LINES) {
            val line = lines[index]
            val normalizedLine = normalize(line)
            if (normalizedLine.isEmpty()) {
                // Blank separator lines before the heading are part of the echo.
                if (index == 0) {
                    index++
                    continue
                }
                break
            }
            if (normalizedLine.length > normalizedTitle.length) break
            if (!normalizedTitle.contains(normalizedLine)) break
            index++
            consumed++
        }

        if (consumed == 0) return content
        return lines.drop(index).joinToString("\n").trimStart('\n')
    }

    /** Drops markdown heading markers, emphasis markers and every kind of whitespace. */
    private fun normalize(text: String): String = text
        .removePrefix("## ")
        .replace("**", "")
        .replace("*", "")
        .filterNot { it.isWhitespace() || it == '\u3000' }
}
