package com.flowreader.app.domain.model

/**
 * App-level theme mode. This only decides whether the Material surfaces *around* the reader are
 * light or dark; the reader body has its own palette ([ReaderPaletteId]) and never follows this.
 */
/** Display only: stored annotation colours, types and source ranges stay unchanged. */
enum class AnnotationDisplayStyle {
    UNDERLINE,
    SOFT_HIGHLIGHT;

    companion object {
        fun fromStoredName(raw: String?): AnnotationDisplayStyle = entries.firstOrNull { it.name == raw } ?: UNDERLINE
    }
}

enum class AppVisualStyle(val displayName: String) {
    CLEAN_BLUE("清简蓝"),
    WARM_PAPER("暖纸墨韵");

    companion object {
        fun fromStoredName(raw: String?): AppVisualStyle = entries.firstOrNull { it.name == raw } ?: CLEAN_BLUE
    }
}

enum class AppThemeMode(val displayName: String) {
    LIGHT("浅色"),
    DARK("深色"),
    FOLLOW_SYSTEM("跟随系统");

    companion object {
        fun fromStoredName(raw: String?): AppThemeMode = entries.firstOrNull { it.name == raw } ?: FOLLOW_SYSTEM
    }
}

/**
 * Where the Material color scheme comes from. [BRAND] keeps the app's own visual identity,
 * [DYNAMIC] follows the wallpaper on Android 12+ and falls back to [BRAND] below it.
 */
enum class ColorSource(val displayName: String) {
    BRAND("主题配色"),
    MONOCHROME("玄墨极简"),
    DYNAMIC("跟随壁纸");

    companion object {
        fun fromStoredName(raw: String?): ColorSource = entries.firstOrNull { it.name == raw } ?: BRAND
    }
}

/**
 * The 18 built-in reader palettes. Only the identity lives here — the actual color values are a
 * rendering concern and live in `:core` (`ReaderPalettes`).
 */
enum class ReaderPaletteId(val displayName: String, val isDark: Boolean) {
    PAPER("纸白", false),

    /** Pure black on pure white: the highest-contrast option, for bright light and OLED. */
    MONO("黑白", false),
    SEPIA("米黄", false),
    GREEN("护眼绿", false),
    LINEN("亚麻", false),
    MIST("晨雾", false),
    COOL_GRAY("冷灰", false),
    EINK("电子墨水", false),
    NIGHT("夜黑", true),
    INK_BLUE("墨蓝", true),
    DEEP_BROWN("深棕", true),
    OBSIDIAN("曜石", true),
    OLED("纯黑", true),
    SOLARIZED_LIGHT("曝光浅", false),
    ROSE_QUARTZ("石英粉", false),
    SOLARIZED_DARK("曝光深", true),
    NORD("极地", true),
    GRUVBOX("复古暖", true),
    FOREST("深林", true);

    companion object {
        val LIGHT_PALETTES: List<ReaderPaletteId> get() = entries.filter { !it.isDark }
        val DARK_PALETTES: List<ReaderPaletteId> get() = entries.filter { it.isDark }

        /**
         * Reads a persisted palette id, migrating the pre-v52 `reader_theme` values
         * (`LIGHT` / `DARK`) that shared this slot.
         */
        fun fromStoredName(raw: String?, fallback: ReaderPaletteId = PAPER): ReaderPaletteId = when (raw) {
            null -> fallback
            "LIGHT" -> PAPER
            "DARK" -> NIGHT
            else -> entries.firstOrNull { it.name == raw } ?: fallback
        }
    }
}

/**
 * Page-turn behaviour. [SLIDE] animates the chapter swap and the scroll reset, [NONE] jumps
 * instantly, [PAGED] renders the chapter as measured pages swiped horizontally.
 * The pre-v52 `SIMULATION` / `CURL` / `SLIDE_OVER` values were never rendered and are gone.
 */
enum class PageMode(val displayName: String) {
    SLIDE("上下滑动"),
    PAGED("左右翻页"),
    NONE("无动画");

    companion object {
        fun fromStoredName(raw: String?, fallback: PageMode = PAGED): PageMode = entries.firstOrNull { it.name == raw } ?: fallback
    }
}

/** How a horizontal page turn is animated inside [PageMode.PAGED]. */
enum class PageTurnAnimation(val displayName: String) {
    /** Pages travel together, like a filmstrip. */
    SLIDE("滑动"),

    /** The next page slides in over a pinned current page, with a soft leading shadow. */
    COVER("覆盖");

    companion object {
        fun fromStoredName(raw: String?, fallback: PageTurnAnimation = SLIDE): PageTurnAnimation =
            entries.firstOrNull { it.name == raw } ?: fallback
    }
}

/**
 * Reader font families.
 *
 * [systemFamilyName] is resolved through `Typeface.create(name, NORMAL)`, so the list can offer
 * every weight/width the platform actually ships instead of only the four generic families. A name
 * the device does not have degrades to its default face rather than failing — the CJK coverage of
 * these faces is whatever the OEM installed.
 */
enum class ReaderFontFamily(val displayName: String, val systemFamilyName: String?) {
    DEFAULT("系统默认", null),
    SERIF("宋体 / 衬线", "serif"),
    SANS_SERIF("黑体 / 无衬线", "sans-serif"),
    MONOSPACE("等宽", "monospace"),
    LIGHT("细体", "sans-serif-light"),
    MEDIUM("中黑", "sans-serif-medium"),
    BLACK("特粗", "sans-serif-black"),
    CONDENSED("紧凑", "sans-serif-condensed"),
    THIN("极细", "sans-serif-thin"),
    CASUAL("手写", "casual"),
    CURSIVE("草书", "cursive");

    companion object {
        /** Maps the pre-v52 eight-value enum onto the faces that actually render. */
        fun fromStoredName(raw: String?): ReaderFontFamily = when (raw) {
            null -> DEFAULT
            "SONG", "FANGSONG", "KAI" -> SERIF
            "HEI" -> SANS_SERIF
            else -> entries.firstOrNull { it.name == raw } ?: DEFAULT
        }
    }
}

enum class GestureAction(val displayName: String) {
    PREVIOUS_PAGE("上一章"),
    NEXT_PAGE("下一章"),
    TOGGLE_CONTROLS("显示/隐藏控制栏"),
    SHOW_SETTINGS("阅读设置"),
    SHOW_BOOKMARKS("书签列表"),
    SHOW_TOC("目录"),
    ADD_BOOKMARK("添加书签"),
    NONE("无");

    companion object {
        fun fromStoredName(raw: String?, fallback: GestureAction): GestureAction = entries.firstOrNull { it.name == raw } ?: fallback
    }
}

/**
 * Tap-zone layout. [SYMMETRIC] is the classic three-band layout; the one-handed modes give the
 * bulk of the screen to "next page" so a thumb can reach it, and move the menu band to the far
 * edge of the holding hand.
 */
enum class TapZoneMode(val displayName: String) {
    SYMMETRIC("左右对称"),
    LEFT_HAND("左手单手"),
    RIGHT_HAND("右手单手");

    companion object {
        fun fromStoredName(raw: String?, fallback: TapZoneMode = SYMMETRIC): TapZoneMode =
            entries.firstOrNull { it.name == raw } ?: fallback
    }
}

data class GestureSettings(
    val leftTapAction: GestureAction = GestureAction.PREVIOUS_PAGE,
    val middleTapAction: GestureAction = GestureAction.TOGGLE_CONTROLS,
    val rightTapAction: GestureAction = GestureAction.NEXT_PAGE,
    val swipeLeftAction: GestureAction = GestureAction.NEXT_PAGE,
    val swipeRightAction: GestureAction = GestureAction.PREVIOUS_PAGE,
    /**
     * Double tap is opt-in ([doubleTapEnabled]) because a configured double-tap action forces the
     * tap detector to wait out the double-tap window before a single tap can fire — which is exactly
     * the lag users notice when tapping to show the control bar. Off by default: single taps are
     * then dispatched on finger-up with no wait at all.
     */
    val doubleTapAction: GestureAction = GestureAction.SHOW_SETTINGS,
    val doubleTapEnabled: Boolean = false,
    val longPressAction: GestureAction = GestureAction.ADD_BOOKMARK,
    val tapZoneMode: TapZoneMode = TapZoneMode.SYMMETRIC,
    val edgeGestureEnabled: Boolean = true,
    val leftEdgeWidth: Int = 20,
    val rightEdgeWidth: Int = 20
) {
    /** True when the detector has to spend time distinguishing a double tap. */
    val waitsForDoubleTap: Boolean get() = doubleTapEnabled && doubleTapAction != GestureAction.NONE
}

data class ReadingSettings(
    val fontSize: Int = 18,
    val lineSpacing: Float = 1.5f,
    /** Multiple of the font size, not a raw dp value. See `ReaderMetrics.paragraphSpacingDp`. */
    val paragraphSpacing: Float = 1.0f,
    val firstLineIndent: Boolean = true,
    val fontFamily: ReaderFontFamily = ReaderFontFamily.DEFAULT,
    val customFontPath: String? = null,
    /** Page margin in dp, 8–48. */
    val horizontalMarginDp: Int = 20,
    /** Measure cap in CJK glyphs, 16–48. */
    val lineChars: Int = 34,
    val textAlign: ReaderTextAlign = ReaderTextAlign.JUSTIFY,
    /** Display-only simplified/traditional conversion; stored text is never rewritten. */
    val chineseConversion: ChineseConversion = ChineseConversion.NONE,
    /** Warm overlay strength, 0 (off) – 100. */
    val colorTemperature: Int = 0,
    val palette: ReaderPaletteId = ReaderPaletteId.PAPER,
    val nightPalette: ReaderPaletteId = ReaderPaletteId.NIGHT,
    val pageMode: PageMode = PageMode.PAGED,
    /** Only meaningful in [PageMode.PAGED]. */
    val pageTurnAnimation: PageTurnAnimation = PageTurnAnimation.SLIDE,
    val keepScreenOn: Boolean = true,
    val screenTimeoutMinutes: Int = 0,
    val eyeProtectionIntervalMinutes: Int = 20,
    val autoNightMode: Boolean = false,
    val tapZoneRatio: Float = 0.3f,
    val customTextColorArgb: Long? = null,
    val customBackgroundColorArgb: Long? = null,
    /**
     * Absolute path to a user-imported reader background under `filesDir/backgrounds/`, or null for
     * the palette's flat colour. Always rendered under a scrim — see `ReaderBackgroundImage`.
     */
    val backgroundImagePath: String? = null,
    /** Scrim opacity over [backgroundImagePath]. Clamped by `ReaderBackgroundImage.clampScrimAlpha`. */
    val backgroundScrimAlpha: Float = 0.85f,
    val gestureSettings: GestureSettings = GestureSettings(),
    val volumeKeyTurnPage: Boolean = true,
    val annotationDisplayStyle: AnnotationDisplayStyle = AnnotationDisplayStyle.UNDERLINE
) {
    companion object {
        const val PARAGRAPH_SPACING_MIN = 0.25f
        const val PARAGRAPH_SPACING_MAX = 3.0f

        const val MARGIN_MIN_DP = 8
        const val MARGIN_MAX_DP = 48
        const val LINE_CHARS_MIN = 16
        const val LINE_CHARS_MAX = 48
        const val COLOR_TEMPERATURE_MAX = 100

        /**
         * Before v52 `paragraphSpacing` was fed straight into `Modifier.padding(...dp)`, so stored
         * values could be anything. Clamp legacy values back into the multiple domain on read.
         */
        fun normalizeParagraphSpacing(raw: Float): Float = when {
            raw.isNaN() -> 1.0f
            raw < PARAGRAPH_SPACING_MIN -> PARAGRAPH_SPACING_MIN
            raw > PARAGRAPH_SPACING_MAX -> 1.0f
            else -> raw
        }
    }
}

/**
 * Reader body alignment. Justify is the CJK default but it stretches sparse lines; left/center are
 * offered because that is what many readers prefer for mixed Chinese/English text.
 */
enum class ReaderTextAlign(val displayName: String) {
    JUSTIFY("两端对齐"),
    START("左对齐"),
    CENTER("居中");

    companion object {
        fun fromStoredName(raw: String?, fallback: ReaderTextAlign = JUSTIFY): ReaderTextAlign =
            entries.firstOrNull { it.name == raw } ?: fallback
    }
}

/** Simplified <-> traditional conversion applied to the rendered text only. */
enum class ChineseConversion(val displayName: String) {
    NONE("不转换"),
    SIMPLIFIED_TO_TRADITIONAL("简 → 繁"),
    TRADITIONAL_TO_SIMPLIFIED("繁 → 简");

    companion object {
        fun fromStoredName(raw: String?, fallback: ChineseConversion = NONE): ChineseConversion =
            entries.firstOrNull { it.name == raw } ?: fallback
    }
}

/** How large the cover in a list row is; drives how many books fit on one screen. */
enum class LibraryCoverSize(val displayName: String, val coverWidthDp: Int, val coverHeightDp: Int) {
    SMALL("小", 52, 74),
    MEDIUM("中", 70, 100),
    LARGE("大", 88, 126);

    companion object {
        fun fromStoredName(raw: String?, fallback: LibraryCoverSize = MEDIUM): LibraryCoverSize =
            entries.firstOrNull { it.name == raw } ?: fallback
    }
}

data class AppSettings(
    val themeMode: AppThemeMode = AppThemeMode.FOLLOW_SYSTEM,
    val colorSource: ColorSource = ColorSource.BRAND,
    val language: AppLanguage = AppLanguage.FOLLOW_SYSTEM,
    val defaultReadingSettings: ReadingSettings = ReadingSettings(),
    val readingReminderEnabled: Boolean = false,
    val readingReminderHour: Int = 20,
    val readingReminderMinute: Int = 0,
    /**
     * Which optional destinations the bottom bar shows. Both are off by default, so a fresh install
     * starts with just 书架 and 设置 — the two screens every reader actually needs.
     */
    val showContinueTab: Boolean = false,
    val showStatsTab: Boolean = false,
    /**
     * Shelf grouping behaviour: when true, a book that belongs to a group is hidden from 全部书籍
     * and only listed inside its group.
     */
    val hideGroupedBooksFromAll: Boolean = false,
    /** Books per row in the grid ("图墙") view, 2–5. */
    val libraryGridColumns: Int = 3,
    /** Cover size in the list view; smaller covers mean more books per screen. */
    val libraryCoverSize: LibraryCoverSize = LibraryCoverSize.MEDIUM,
    /**
     * Tap behaviour on the shelf: a book that has been opened at least once jumps straight into the
     * reader instead of its detail page. On by default — that is what "continue reading" means for
     * an already-started book.
     */
    val openReadBookDirectly: Boolean = true,
    /** TXT import rules (cleaning + custom chapter regex). */
    val txtProcessing: TxtProcessingRules = TxtProcessingRules(),
    /** SAF tree URI that is scanned for new books, or null when auto-scan is off. */
    val scanFolderUri: String? = null,
    /** Epoch millis of the last folder scan, used to throttle automatic scans. */
    val lastScanTime: Long = 0L,
    /** When true the app asks for a fingerprint / PIN before showing the library. */
    val appLockEnabled: Boolean = false,
    /** Salted SHA-256 of the PIN, or null when only biometrics are configured. */
    val appLockPinHash: String? = null,
    val visualStyle: AppVisualStyle = AppVisualStyle.CLEAN_BLUE,
    val speechEnabled: Boolean = false
)
