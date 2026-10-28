package com.flowreader.app.core.designsystem.reader

import androidx.compose.ui.graphics.Color
import com.flowreader.app.domain.model.ReaderPaletteId

/**
 * A reader color palette.
 *
 * Values are plain ARGB longs rather than Compose `Color`s so the whole table stays a pure-Kotlin
 * value that JVM unit tests (including the WCAG contrast assertions) can read without Compose.
 * Use the [background]/[text]/[secondaryText]/[selection] extensions below for rendering.
 */
data class ReaderPalette(
    val id: ReaderPaletteId,
    val backgroundArgb: Long,
    val textArgb: Long,
    val secondaryTextArgb: Long,
    val selectionArgb: Long
) {
    val isDark: Boolean get() = id.isDark
    val displayName: String get() = id.displayName
}

/** The 19 built-in reader palettes. Every entry is contrast-checked in `ReaderPaletteContrastTest`. */
object ReaderPalettes {
    private const val LIGHT_SELECTION = 0x557D967BL
    private const val DARK_SELECTION = 0x66E9BF8BL

    val Paper = ReaderPalette(ReaderPaletteId.PAPER, 0xFFF7F1E5, 0xFF35372F, 0xFF68675D, LIGHT_SELECTION)

    /** 黑白: pure black on pure white, the maximum-contrast reading theme. */
    val Mono = ReaderPalette(ReaderPaletteId.MONO, 0xFFFFFFFF, 0xFF000000, 0xFF4D4D4D, LIGHT_SELECTION)
    val Sepia = ReaderPalette(ReaderPaletteId.SEPIA, 0xFFF5EFE0, 0xFF3A3226, 0xFF6B6154, LIGHT_SELECTION)
    val Green = ReaderPalette(ReaderPaletteId.GREEN, 0xFFCCE8CF, 0xFF1E2B1F, 0xFF46584A, LIGHT_SELECTION)
    val Linen = ReaderPalette(ReaderPaletteId.LINEN, 0xFFEDE6D6, 0xFF33302A, 0xFF635F55, LIGHT_SELECTION)
    val Mist = ReaderPalette(ReaderPaletteId.MIST, 0xFFE9EEF2, 0xFF22282C, 0xFF515A61, LIGHT_SELECTION)
    val CoolGray = ReaderPalette(ReaderPaletteId.COOL_GRAY, 0xFFDDE1E4, 0xFF23272A, 0xFF52585C, LIGHT_SELECTION)
    val EInk = ReaderPalette(ReaderPaletteId.EINK, 0xFFF2F2F0, 0xFF000000, 0xFF4A4A48, LIGHT_SELECTION)
    val Night = ReaderPalette(ReaderPaletteId.NIGHT, 0xFF171715, 0xFFE0D6C3, 0xFFAFA694, DARK_SELECTION)
    val InkBlue = ReaderPalette(ReaderPaletteId.INK_BLUE, 0xFF101822, 0xFFC6D3E0, 0xFF8B9AAA, DARK_SELECTION)
    val DeepBrown = ReaderPalette(ReaderPaletteId.DEEP_BROWN, 0xFF1E1712, 0xFFDCCFC0, 0xFFA2907E, DARK_SELECTION)
    val Obsidian = ReaderPalette(ReaderPaletteId.OBSIDIAN, 0xFF191B20, 0xFFCFD3DA, 0xFF939AA5, DARK_SELECTION)
    val Oled = ReaderPalette(ReaderPaletteId.OLED, 0xFF000000, 0xFFC8C8C8, 0xFF8A8A8A, DARK_SELECTION)

    // v56.5.0 additions. Every value below was verified against ColorContrast before being
    // committed — body and secondary text both clear AA_BODY_TEXT (4.5:1, note the secondary
    // assertion uses the body threshold too, not AA_LARGE_TEXT), and each background's measured
    // luminance agrees with its isDark flag on the 0.2 boundary.
    val SolarizedLight =
        ReaderPalette(ReaderPaletteId.SOLARIZED_LIGHT, 0xFFFDF6E3, 0xFF3B4A4F, 0xFF5C6E73, LIGHT_SELECTION)
    val RoseQuartz =
        ReaderPalette(ReaderPaletteId.ROSE_QUARTZ, 0xFFF7EAEC, 0xFF3A2C30, 0xFF6A585C, LIGHT_SELECTION)
    val SolarizedDark =
        ReaderPalette(ReaderPaletteId.SOLARIZED_DARK, 0xFF002B36, 0xFFB8C6C8, 0xFF8AA0A3, DARK_SELECTION)
    val Nord = ReaderPalette(ReaderPaletteId.NORD, 0xFF2E3440, 0xFFD8DEE9, 0xFFA6AFBE, DARK_SELECTION)
    val Gruvbox = ReaderPalette(ReaderPaletteId.GRUVBOX, 0xFF282322, 0xFFE0D2BC, 0xFFAE9E86, DARK_SELECTION)
    val Forest = ReaderPalette(ReaderPaletteId.FOREST, 0xFF101A14, 0xFFC5D6C6, 0xFF94A896, DARK_SELECTION)

    val all: List<ReaderPalette> = listOf(
        Paper,
        Mono,
        Sepia,
        Green,
        Linen,
        Mist,
        CoolGray,
        EInk,
        Night,
        InkBlue,
        DeepBrown,
        Obsidian,
        Oled,
        SolarizedLight,
        RoseQuartz,
        SolarizedDark,
        Nord,
        Gruvbox,
        Forest
    )

    private val byId: Map<ReaderPaletteId, ReaderPalette> = all.associateBy { it.id }

    fun of(
        id: ReaderPaletteId,
        visualStyle: com.flowreader.app.domain.model.AppVisualStyle = com.flowreader.app.domain.model.AppVisualStyle.WARM_PAPER
    ): ReaderPalette {
        val palette = byId.getValue(id)
        return if (id == ReaderPaletteId.PAPER && visualStyle == com.flowreader.app.domain.model.AppVisualStyle.CLEAN_BLUE) {
            palette.copy(backgroundArgb = 0xFFFAF8F2, textArgb = 0xFF292D31, secondaryTextArgb = 0xFF646B72, selectionArgb = 0x5528679D)
        } else {
            palette
        }
    }
}

val ReaderPalette.background: Color get() = Color(backgroundArgb)
val ReaderPalette.text: Color get() = Color(textArgb)
val ReaderPalette.secondaryText: Color get() = Color(secondaryTextArgb)
val ReaderPalette.selection: Color get() = Color(selectionArgb)
