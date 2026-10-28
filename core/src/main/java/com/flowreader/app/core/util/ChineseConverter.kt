package com.flowreader.app.core.util

import com.flowreader.app.domain.model.ChineseConversion

/**
 * Simplified <-> traditional conversion for display only.
 *
 * The table is character-level and 1:1 ([ChineseConversionTable]), so [convert] preserves the
 * length of the input. That is what makes it safe to apply at render time: stored highlight and
 * bookmark offsets are character offsets into the raw chapter text, and a length-preserving
 * transform leaves every offset pointing at the same character.
 *
 * Limitation, by design: phrase-level disambiguation is not performed, so an ambiguous character
 * takes OpenCC's most common reading (发 -> 發, not 髮). This is a reading aid, not a converter for
 * publishing.
 */
object ChineseConverter {

    private val simplifiedToTraditional: Map<Char, Char> by lazy {
        buildMap(ChineseConversionTable.SIMPLIFIED_TO_TRADITIONAL)
    }

    private val traditionalToSimplified: Map<Char, Char> by lazy {
        buildMap(ChineseConversionTable.TRADITIONAL_TO_SIMPLIFIED)
    }

    /** True when [mode] would actually rewrite something. */
    fun isActive(mode: ChineseConversion): Boolean = mode != ChineseConversion.NONE

    fun convert(text: String, mode: ChineseConversion): String {
        val table = tableFor(mode) ?: return text
        if (text.isEmpty()) return text
        var changed = false
        val builder = StringBuilder(text.length)
        for (char in text) {
            val mapped = table[char]
            if (mapped == null) {
                builder.append(char)
            } else {
                builder.append(mapped)
                changed = true
            }
        }
        return if (changed) builder.toString() else text
    }

    fun convert(char: Char, mode: ChineseConversion): Char = tableFor(mode)?.get(char) ?: char

    private fun tableFor(mode: ChineseConversion): Map<Char, Char>? = when (mode) {
        ChineseConversion.NONE -> null
        ChineseConversion.SIMPLIFIED_TO_TRADITIONAL -> simplifiedToTraditional
        ChineseConversion.TRADITIONAL_TO_SIMPLIFIED -> traditionalToSimplified
    }

    /** Decodes the packed `源目标源目标…` pair string into a lookup table. */
    private fun buildMap(pairs: String): Map<Char, Char> = buildMap(pairs.length / 2) {
        var index = 0
        while (index + 1 < pairs.length) {
            put(pairs[index], pairs[index + 1])
            index += 2
        }
    }
}
