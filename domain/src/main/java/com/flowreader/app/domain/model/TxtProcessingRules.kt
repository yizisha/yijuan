package com.flowreader.app.domain.model

/**
 * Rules applied to a plain-text novel before it is split into chapters.
 *
 * These exist because TXT imports from the wild arrive with site banners, ad lines, stray blank
 * runs and inconsistent chapter markers. The rules are applied once, at import time, so the stored
 * chapter text is already clean — the reader never pays for them.
 *
 * [customChapterRegex] is tried before the built-in detector (or instead of it, when
 * [customRegexOnly]); capture group 1 becomes the chapter title when present, otherwise the whole
 * match is used.
 */
data class TxtProcessingRules(
    /** Collapse runs of blank lines and drop leading/trailing blank lines. */
    val stripBlankLines: Boolean = false,
    /** Drop lines that look like site banners, update notices or pagination footers. */
    val removeAdLines: Boolean = true,
    /** One rule per line, `正则 => 替换`; a rule without `=>` deletes every match. */
    val customReplacements: String = "",
    /** User regex that recognises a chapter heading. */
    val customChapterRegex: String = "",
    /** When true the built-in detector is skipped entirely, so a user regex can take over. */
    val customRegexOnly: Boolean = false
) {
    val hasCustomChapterRegex: Boolean get() = customChapterRegex.isNotBlank()

    val hasAnyCleaning: Boolean
        get() = stripBlankLines || removeAdLines || customReplacements.isNotBlank()

    companion object {
        const val MAX_REGEX_LENGTH = 300
        const val MAX_REPLACEMENTS_LENGTH = 4000
    }
}
