package com.flowreader.app.domain.model

/**
 * 统一字数口径（主计划 3.2 末段、10.2）。
 *
 * **为什么必须是唯一实现**：全书进度按有效文本量加权（R3），剩余时间、速度采样、目录权重都读同一个数字。
 * 一旦有两处各自实现"字数"，长短章进度就会失真（F03 的成因就是按章节等权）。
 *
 * 口径（v1，必须与 UI 说明一致，不宣称语言学词数）：
 * - 统计单位是 **Unicode code point**，不是 UTF-16 单元 —— 一个 emoji 占 2 个 UTF-16 单元但只计 1 字
 *   （10.2 golden：100/900 两章 + emoji 计 1）。
 * - 空白不计（空格、TAB、LF、CR、换页）。
 * - `U+FFFC`（图片等原子对象占位）不计 —— 图片**不换算成"几百字"**，纯图书走 FixedPage 进度（10.2）。
 * - 生成的表格分隔符（TAB / 行末 LF）自然落入"空白不计"。
 * - 结构性卷标题、脚注/尾注（[BlockRole.AUXILIARY]）、非主线文档都不计（10.2 主线计量规则）。
 */
object TextUnits {

    /** 不计入字数的字符：空白 + 对象占位符。 */
    fun isExcluded(codePoint: Int): Boolean = when (codePoint) {
        0x20, 0x09, 0x0A, 0x0D, 0x0C, 0xFFFC -> true
        else -> false
    }

    private fun isExcludedChar(ch: Char): Boolean = ch == ' ' || ch == '\t' || ch == '\n' || ch == '\r' ||
        ch == '\u000C' || ch == '\uFFFC'

    /** 统计一段规范源文本的字数。 */
    fun count(text: CharSequence): Long {
        var total = 0L
        var index = 0
        while (index < text.length) {
            val ch = text[index]
            if (Character.isHighSurrogate(ch) && index + 1 < text.length && Character.isLowSurrogate(text[index + 1])) {
                // 代理对 = 一个 code point。
                if (!isExcluded(Character.toCodePoint(ch, text[index + 1]))) total++
                index += 2
            } else {
                if (!isExcludedChar(ch)) total++
                index++
            }
        }
        return total
    }

    /**
     * 统计一个结构化文档里**主线**部分的字数。
     *
     * 只对 [BlockRole.MAIN] 的块计数；容器块（List/Quote/Figure）通过子项递归，
     * 表格按单元格文本计数（避开生成的 TAB/LF，也避免把整块文本重复计一遍）。
     */
    fun countMainUnits(document: StructuredDocument): Long {
        var total = 0L
        document.blocks.forEach { block -> if (block.role == BlockRole.MAIN) total += countBlock(document, block) }
        return total
    }

    /** 取块在规范源文本里的文字。范围越界（坏数据）时返回空串而不是崩溃。 */
    fun textOf(document: StructuredDocument, range: SourceSpan): String {
        if (range.startUtf16 >= document.text.length) return ""
        val end = minOf(range.endUtf16Exclusive, document.text.length)
        return document.text.substring(range.startUtf16, end)
    }

    private fun countBlock(document: StructuredDocument, block: ContentBlock): Long = when (block) {
        is ParagraphBlock -> count(textOf(document, block.range))
        is HeadingBlock -> count(textOf(document, block.range))
        is PreformattedBlock -> count(block.text)
        is RubyBlock -> count(block.base)
        is UnsupportedBlock -> count(block.fallbackText)
        // 图片不换算成字数（10.2）。
        is ImageBlock -> 0L
        is FigureBlock -> block.caption?.let { count(it) } ?: 0L
        is ListBlock -> block.items.sumOf { item ->
            item.children.sumOf { countBlock(document, it) } + count(textOf(document, item.range))
        }

        is QuoteBlock -> {
            if (block.children.isEmpty()) count(textOf(document, block.range)) else block.children.sumOf { countBlock(document, it) }
        }

        is TableBlock -> block.rows.sumOf { row -> row.cells.sumOf { cell -> count(cell.text) } }
    }
}
