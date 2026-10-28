package com.flowreader.app.ui.screens.reader.components

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.flowreader.app.R
import com.flowreader.app.core.designsystem.component.FlowChoiceChip
import com.flowreader.app.core.designsystem.component.FlowExpandableGroup
import com.flowreader.app.core.designsystem.component.FlowIcons
import com.flowreader.app.core.designsystem.component.FlowPreferenceGroup
import com.flowreader.app.core.designsystem.component.FlowSlider
import com.flowreader.app.core.designsystem.component.FlowTabBar
import com.flowreader.app.core.designsystem.reader.ReaderPalettes
import com.flowreader.app.core.designsystem.reader.background
import com.flowreader.app.core.designsystem.reader.readerBodyStyle
import com.flowreader.app.core.designsystem.reader.rememberReaderFontFamily
import com.flowreader.app.core.designsystem.reader.text
import com.flowreader.app.core.designsystem.theme.flowColorScheme
import com.flowreader.app.core.designsystem.token.FlowRadius
import com.flowreader.app.core.designsystem.token.FlowSpacing
import com.flowreader.app.core.util.ReaderBackgroundImage
import com.flowreader.app.domain.model.AnnotationDisplayStyle
import com.flowreader.app.domain.model.ChineseConversion
import com.flowreader.app.domain.model.GestureSettings
import com.flowreader.app.domain.model.PageMode
import com.flowreader.app.domain.model.PageTurnAnimation
import com.flowreader.app.domain.model.ReaderFontFamily
import com.flowreader.app.domain.model.ReaderPaletteId
import com.flowreader.app.domain.model.ReaderTextAlign
import com.flowreader.app.domain.model.ReadingSettings
import com.flowreader.app.domain.model.TapZoneMode
import com.flowreader.app.ui.screens.settings.GestureSettingsContent
import com.flowreader.app.ui.screens.settings.ReadingCategory
import com.flowreader.app.util.PaperTextureGenerator
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The four sections of the reader panel, each rendered as its own page inside the sheet. */
private typealias ReaderSettingsPage = ReadingCategory

/**
 * The reader preference panel, as a two-level sheet.
 *
 * The root shows four categories with their current value; tapping one swaps the sheet to that
 * category and a back arrow returns. It used to be a single scroll containing every control, which
 * is exactly what made it feel cluttered — the reader's own settings now read as a menu, and the
 * app-wide preferences live in 设置 (also two-level).
 *
 * A bottom sheet rather than a dialog keeps the page visible, so every change previews live.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderSettingsSheet(
    settings: ReadingSettings,
    onSettingsChange: (ReadingSettings) -> Unit,
    onPickBackgroundImage: () -> Unit,
    onClearBackgroundImage: () -> Unit,
    onPickFont: () -> Unit,
    onClearFont: () -> Unit,
    onDismiss: () -> Unit,
    darkOverride: Boolean? = null
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var selectedTab by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(0) }
    var more by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    val scroll = rememberScrollState()
    LaunchedEffect(selectedTab, more) { scroll.scrollTo(0) }
    val colors =
        flowColorScheme(
            darkOverride ?: ReaderPalettes.of(settings.palette, com.flowreader.app.core.designsystem.theme.LocalFlowVisualStyle.current).isDark
        )
    MaterialTheme(colorScheme = colors) {
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = sheetState,
            containerColor = colors.surface,
            shape = MaterialTheme.shapes.extraLarge
        ) {
            Column(
                Modifier.fillMaxWidth().verticalScroll(scroll).navigationBarsPadding().padding(horizontal = 16.dp).padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (more) {
                    PageHeader(R.string.reader_more_settings) { more = false }
                    FlowPreferenceGroup { EyeCarePage(settings, onSettingsChange) }
                } else {
                    FlowTabBar(
                        listOf(
                            R.string.reader_tab_font,
                            R.string.reader_tab_layout,
                            R.string.reader_tab_theme,
                            R.string.reader_tab_page
                        ).map { stringResource(it) },
                        selectedTab,
                        { selectedTab = it }
                    )
                    if (selectedTab == 0) {
                        CompactReadingAppearance(settings, onSettingsChange, onPickFont, onClearFont)
                    } else {
                        FlowPreferenceGroup {
                            when (selectedTab) {
                                1 -> TypographyPage(settings, onSettingsChange)
                                2 -> ThemePage(settings, onSettingsChange, onPickBackgroundImage, onClearBackgroundImage)
                                else -> PageTurnPage(settings, onSettingsChange)
                            }
                        }
                    }
                    TextButton(
                        onClick = { more = true },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(stringResource(R.string.reader_more_settings)) }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReaderQuickSettings(
    settings: ReadingSettings,
    onSettingsChange: (ReadingSettings) -> Unit,
    showNight: Boolean = true,
    showBrightness: Boolean = true,
    showFontSize: Boolean = true,
    showLineSpacing: Boolean = true
) {
    val context = LocalContext.current
    val activity = remember(context) {
        var current = context
        while (current is android.content.ContextWrapper && current !is android.app.Activity) current = current.baseContext
        current as? android.app.Activity
    }
    var brightness by remember(activity) { mutableFloatStateOf(activity?.window?.attributes?.screenBrightness ?: -1f) }
    val systemBrightness = remember(context) {
        android.provider.Settings.System.getInt(context.contentResolver, android.provider.Settings.System.SCREEN_BRIGHTNESS, 128) / 255f
    }
    val dark = ReaderPalettes.of(settings.palette, com.flowreader.app.core.designsystem.theme.LocalFlowVisualStyle.current).isDark
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (showNight) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(FlowIcons.Moon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    stringResource(R.string.room_night),
                    Modifier.weight(1f).padding(start = 16.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
                Switch(
                    checked = dark,
                    onCheckedChange = { enabled ->
                        onSettingsChange(settings.copy(palette = if (enabled) settings.nightPalette else ReaderPaletteId.PAPER))
                    },
                    modifier = Modifier.semantics { contentDescription = context.getString(R.string.room_night) }
                )
            }
        }
        if (showBrightness) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(FlowIcons.Sun, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Column(Modifier.padding(start = FlowSpacing.lg, end = FlowSpacing.md)) {
                    Text(stringResource(R.string.room_brightness), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "${((if (brightness < 0f) systemBrightness else brightness) * 100).toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                FlowSlider(
                    value = (if (brightness < 0f) systemBrightness else brightness).coerceIn(.01f, 1f),
                    onValueChange = { value ->
                        brightness = value
                        activity?.window?.let { window -> window.attributes = window.attributes.apply { screenBrightness = value } }
                    },
                    valueRange = .01f..1f,
                    modifier = Modifier.weight(1f).semantics { contentDescription = context.getString(R.string.room_brightness) }
                )
                TextButton(onClick = {
                    brightness = -1f
                    activity?.window?.let { window -> window.attributes = window.attributes.apply { screenBrightness = -1f } }
                }) { Text(stringResource(R.string.reader_brightness_system), style = MaterialTheme.typography.labelSmall) }
            }
        }
        if (showFontSize) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    stringResource(R.string.room_font_size),
                    Modifier.width(64.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
                QuickChoice("−", stringResource(R.string.room_font_smaller), false, Modifier.weight(1f)) {
                    onSettingsChange(settings.copy(fontSize = (settings.fontSize - 1).coerceAtLeast(12)))
                }
                QuickChoice("${settings.fontSize}", stringResource(R.string.room_font_reset), true, Modifier.weight(1f)) {
                    onSettingsChange(settings.copy(fontSize = 18))
                }
                QuickChoice("+", stringResource(R.string.room_font_larger), false, Modifier.weight(1f)) {
                    onSettingsChange(settings.copy(fontSize = (settings.fontSize + 1).coerceAtMost(32)))
                }
            }
        }
        if (showLineSpacing) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    stringResource(R.string.room_line_spacing),
                    Modifier.width(64.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
                listOf(
                    1.3f to R.string.room_line_compact,
                    1.5f to R.string.room_line_standard,
                    1.8f to R.string.room_line_relaxed
                ).forEach { (spacing, label) ->
                    val name = stringResource(label)
                    QuickChoice(name, name, kotlin.math.abs(settings.lineSpacing - spacing) < .01f, Modifier.weight(1f)) {
                        onSettingsChange(settings.copy(lineSpacing = spacing))
                    }
                }
            }
        }
    }
}

/** Reference panel: compact appearance controls, backed by the same settings callbacks. */
@Composable
private fun CompactReadingAppearance(
    settings: ReadingSettings,
    onChange: (ReadingSettings) -> Unit,
    onPickFont: () -> Unit,
    onClearFont: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
        ReaderQuickSettings(settings, onChange, showNight = false, showBrightness = false, showLineSpacing = false)
        FontPage(settings, onChange, onPickFont, onClearFont, compact = true)
        ReaderQuickSettings(settings, onChange, showNight = false, showBrightness = false, showFontSize = false)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.reader_setting_para_spacing), Modifier.width(64.dp), style = MaterialTheme.typography.bodyMedium)
            listOf(.8f, 1f, 1.5f).forEach { spacing ->
                QuickChoice(
                    formatOneDecimal(spacing),
                    stringResource(R.string.reader_setting_para_spacing_value, formatOneDecimal(spacing)),
                    kotlin.math.abs(settings.paragraphSpacing - spacing) < .01f,
                    Modifier.weight(1f)
                ) {
                    onChange(settings.copy(paragraphSpacing = spacing))
                }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.reader_setting_margin), Modifier.width(64.dp), style = MaterialTheme.typography.bodyMedium)
            listOf(12, 20, 28).forEach { margin ->
                QuickChoice(
                    "$margin",
                    stringResource(R.string.reader_setting_margin_value, margin),
                    settings.horizontalMarginDp == margin,
                    Modifier.weight(1f)
                ) { onChange(settings.copy(horizontalMarginDp = margin)) }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.reader_tab_theme), Modifier.width(64.dp), style = MaterialTheme.typography.bodyMedium)
            listOf(
                ReaderPaletteId.PAPER,
                ReaderPaletteId.SEPIA,
                ReaderPaletteId.GREEN,
                ReaderPaletteId.MIST,
                settings.nightPalette
            ).distinct().forEach { id ->
                val palette = ReaderPalettes.of(id, com.flowreader.app.core.designsystem.theme.LocalFlowVisualStyle.current)
                Box(
                    Modifier.weight(1f).heightIn(min = 48.dp).clickable { onChange(settings.copy(palette = id)) }
                        .semantics {
                            contentDescription = palette.displayName
                            selected = settings.palette == id
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        Modifier.size(28.dp).background(palette.background, CircleShape).border(
                            1.dp,
                            if (settings.palette == id) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                            CircleShape
                        )
                    )
                }
            }
        }
        ReaderQuickSettings(settings, onChange, showBrightness = false, showFontSize = false, showLineSpacing = false)
        FlowExpandableGroup(stringResource(R.string.room_brightness), stringResource(R.string.reader_brightness_system)) {
            ReaderQuickSettings(settings, onChange, showNight = false, showFontSize = false, showLineSpacing = false)
        }
        FontImportGroup(settings, onPickFont, onClearFont)
    }
}

@Composable
private fun QuickChoice(label: String, description: String, active: Boolean, modifier: Modifier, onClick: () -> Unit) {
    FlowChoiceChip(
        selected = active,
        onClick = onClick,
        label = { Text(label) },
        modifier = modifier.semantics { contentDescription = description }
    )
}

@Composable
private fun pageSummary(page: ReaderSettingsPage, settings: ReadingSettings): String = when (page) {
    ReaderSettingsPage.TYPOGRAPHY -> {
        val imported = settings.customFontPath
            ?.let { path -> runCatching { File(path).takeIf { it.isFile }?.name }.getOrNull() }
        "${imported ?: settings.fontFamily.displayName} · ${settings.fontSize}sp"
    }

    ReaderSettingsPage.PAGE_TURN ->
        "${settings.pageMode.displayName} · ${settings.gestureSettings.tapZoneMode.displayName}"

    ReaderSettingsPage.THEME -> settings.palette.displayName

    ReaderSettingsPage.FONT, ReaderSettingsPage.GESTURE, ReaderSettingsPage.GOALS -> ""
    ReaderSettingsPage.EYE_CARE -> stringResource(
        R.string.reader_summary_eye_care,
        settings.eyeProtectionIntervalMinutes
    )
}

@Composable
private fun SettingsPageRow(page: ReaderSettingsPage, summary: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(FlowRadius.md))
            .clickable(onClick = onClick)
            .padding(vertical = FlowSpacing.md),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = page.icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp)
        )
        Spacer(modifier = Modifier.width(FlowSpacing.md))
        Column(modifier = Modifier.weight(1f)) {
            Text(stringResource(page.titleRes), style = MaterialTheme.typography.bodyLarge)
            Text(
                text = summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Icon(
            imageVector = FlowIcons.Next,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun PageHeader(@StringRes titleRes: Int, onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = FlowSpacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = FlowIcons.Back,
            contentDescription = stringResource(R.string.action_back),
            modifier = Modifier
                .size(24.dp)
                .clickable(onClick = onBack)
        )
        Spacer(modifier = Modifier.width(FlowSpacing.md))
        Text(
            text = stringResource(titleRes),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
    }
}

/**
 * A slider whose thumb follows the finger at 60 fps while the expensive consequences of the change
 * — re-measuring the whole chapter into pages, plus a DataStore write — are debounced.
 *
 * The 字号 / 行距 / 段距 sliders used to call straight through on every tick, so a single drag
 * re-paginated the chapter dozens of times and wrote the settings to disk just as often. Only
 * [local] changes while the finger is down, so only this composable recomposes.
 */
@Composable
internal fun DebouncedSlider(
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    onCommit: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    var local by remember { mutableFloatStateOf(value) }
    var dragging by remember { mutableStateOf(false) }

    // An external change (preset, restore) updates the thumb unless the user is mid-drag.
    LaunchedEffect(value) {
        if (!dragging) local = value
    }

    LaunchedEffect(local) {
        if (!dragging && local != value) {
            delay(SLIDER_COMMIT_DEBOUNCE_MS)
            onCommit(local)
        }
    }

    FlowSlider(
        value = local,
        onValueChange = {
            dragging = true
            local = it
        },
        onValueChangeFinished = {
            dragging = false
            if (local != value) onCommit(local)
        },
        valueRange = valueRange,
        steps = steps,
        modifier = modifier
    )
}

private const val SLIDER_COMMIT_DEBOUNCE_MS = 140L

/**
 * 1.2 字体: family chips, import and clear.
 *
 * Public because 设置 → 阅读 → 字体 renders the exact same controls — one implementation, so the
 * in-reader panel and the settings tree cannot drift apart (they used to be two separate screens
 * that both claimed to own the font).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FontPage(
    settings: ReadingSettings,
    onSettingsChange: (ReadingSettings) -> Unit,
    onPickFont: () -> Unit,
    onClearFont: () -> Unit,
    compact: Boolean = false
) {
    val importedName = settings.customFontPath
        ?.let { path -> runCatching { File(path).takeIf { it.isFile }?.name }.getOrNull() }

    var fontMenu by remember { mutableStateOf(false) }
    Box {
        if (compact) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(stringResource(R.string.reader_setting_font), Modifier.width(64.dp), style = MaterialTheme.typography.bodyMedium)
                listOf(
                    ReaderFontFamily.DEFAULT to R.string.reader_font_system_short,
                    ReaderFontFamily.SERIF to R.string.reader_font_serif_short,
                    ReaderFontFamily.SANS_SERIF to R.string.reader_font_sans_short
                ).forEach { (family, label) ->
                    FlowChoiceChip(
                        settings.customFontPath == null && settings.fontFamily == family,
                        { onSettingsChange(settings.copy(fontFamily = family, customFontPath = null)) },
                        label = { Text(stringResource(label)) },
                        modifier = Modifier.weight(1f)
                    )
                }
                IconButton({ fontMenu = true }, Modifier.size(48.dp)) {
                    Icon(FlowIcons.More, stringResource(R.string.reader_setting_font), Modifier.size(18.dp))
                }
            }
        } else {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { fontMenu = true },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(stringResource(R.string.reader_setting_font), style = MaterialTheme.typography.bodyLarge)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FlowSpacing.sm)) {
                    Text(
                        importedName ?: settings.fontFamily.displayName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = null)
                }
            }
        }
        DropdownMenu(expanded = fontMenu, onDismissRequest = { fontMenu = false }) {
            ReaderFontFamily.entries.forEach { font ->
                DropdownMenuItem(text = { Text(font.displayName) }, onClick = {
                    fontMenu = false
                    onSettingsChange(settings.copy(fontFamily = font, customFontPath = null))
                })
            }
            if (importedName != null) DropdownMenuItem(text = { Text(importedName) }, onClick = { fontMenu = false })
        }
    }
    if (!compact) FontImportGroup(settings, onPickFont, onClearFont)
}

@Composable
private fun FontImportGroup(settings: ReadingSettings, onPickFont: () -> Unit, onClearFont: () -> Unit) {
    val importedName = settings.customFontPath?.let { path -> runCatching { File(path).name }.getOrNull() }
    FlowExpandableGroup(
        stringResource(R.string.ui_font_import),
        importedName ?: stringResource(R.string.reader_font_import_desc),
        customized = settings.customFontPath != null
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(FlowSpacing.sm)) {
            TextButton(onClick = onPickFont) { Text(stringResource(R.string.reader_font_import)) }
            if (settings.customFontPath != null) {
                TextButton(onClick = onClearFont) { Text(stringResource(R.string.reader_font_clear)) }
            }
        }
        Text(
            text = stringResource(R.string.reader_font_import_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        FontPreview(settings)
    }
}

/** Live sample of the current font, size and spacing. */
@Composable
private fun FontPreview(settings: ReadingSettings) {
    val fontFamily = rememberReaderFontFamily(settings)
    val previewStyle =
        readerBodyStyle(
            settings,
            fontFamily,
            ReaderPalettes.of(settings.palette, com.flowreader.app.core.designsystem.theme.LocalFlowVisualStyle.current)
        )
    Surface(
        shape = RoundedCornerShape(FlowSpacing.sm),
        color = ReaderPalettes.of(settings.palette, com.flowreader.app.core.designsystem.theme.LocalFlowVisualStyle.current).background,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = FlowSpacing.sm)
    ) {
        Text(
            text = stringResource(R.string.reader_font_preview),
            style = previewStyle,
            modifier = Modifier.padding(FlowSpacing.md)
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TypographyPage(settings: ReadingSettings, onSettingsChange: (ReadingSettings) -> Unit) {
    LabelledValue(stringResource(R.string.reader_setting_font_size), "${settings.fontSize}sp")
    DebouncedSlider(
        value = settings.fontSize.toFloat(),
        valueRange = 12f..32f,
        steps = 19,
        onCommit = { onSettingsChange(settings.copy(fontSize = it.toInt())) }
    )

    LabelledValue(stringResource(R.string.reader_setting_line_spacing), formatOneDecimal(settings.lineSpacing))
    DebouncedSlider(
        value = settings.lineSpacing,
        valueRange = 1f..2.5f,
        steps = 14,
        onCommit = { onSettingsChange(settings.copy(lineSpacing = it)) }
    )

    val defaults = ReadingSettings()
    FlowExpandableGroup(
        stringResource(R.string.ui_advanced_typography),
        stringResource(
            R.string.ui_typography_summary,
            formatOneDecimal(settings.paragraphSpacing),
            settings.horizontalMarginDp,
            settings.lineChars
        ),
        customized = settings.paragraphSpacing != defaults.paragraphSpacing || settings.horizontalMarginDp != defaults.horizontalMarginDp ||
            settings.lineChars != defaults.lineChars || settings.textAlign != defaults.textAlign ||
            settings.chineseConversion != defaults.chineseConversion || settings.firstLineIndent != defaults.firstLineIndent
    ) {
        LabelledValue(
            stringResource(R.string.reader_setting_para_spacing),
            stringResource(R.string.reader_setting_para_spacing_value, formatOneDecimal(settings.paragraphSpacing))
        )
        DebouncedSlider(
            value = settings.paragraphSpacing,
            valueRange = ReadingSettings.PARAGRAPH_SPACING_MIN..ReadingSettings.PARAGRAPH_SPACING_MAX,
            steps = 10,
            onCommit = { onSettingsChange(settings.copy(paragraphSpacing = it)) }
        )

        LabelledValue(
            label = stringResource(R.string.reader_setting_margin),
            value = stringResource(R.string.reader_setting_margin_value, settings.horizontalMarginDp)
        )
        DebouncedSlider(
            value = settings.horizontalMarginDp.toFloat(),
            valueRange = ReadingSettings.MARGIN_MIN_DP.toFloat()..ReadingSettings.MARGIN_MAX_DP.toFloat(),
            steps = 19,
            onCommit = { onSettingsChange(settings.copy(horizontalMarginDp = it.toInt())) }
        )

        LabelledValue(
            label = stringResource(R.string.reader_setting_line_chars),
            value = stringResource(R.string.reader_setting_line_chars_value, settings.lineChars)
        )
        DebouncedSlider(
            value = settings.lineChars.toFloat(),
            valueRange = ReadingSettings.LINE_CHARS_MIN.toFloat()..ReadingSettings.LINE_CHARS_MAX.toFloat(),
            steps = 15,
            onCommit = { onSettingsChange(settings.copy(lineChars = it.toInt())) }
        )

        LabelledValue(
            label = stringResource(R.string.reader_setting_text_align),
            value = settings.textAlign.displayName
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(FlowSpacing.sm), verticalArrangement = Arrangement.spacedBy(FlowSpacing.sm)) {
            ReaderTextAlign.entries.forEach { align ->
                FlowChoiceChip(
                    selected = settings.textAlign == align,
                    onClick = { onSettingsChange(settings.copy(textAlign = align)) },
                    label = { Text(align.displayName) }
                )
            }
        }

        LabelledValue(
            label = stringResource(R.string.reader_setting_conversion),
            value = settings.chineseConversion.displayName
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(FlowSpacing.sm), verticalArrangement = Arrangement.spacedBy(FlowSpacing.sm)) {
            ChineseConversion.entries.forEach { conversion ->
                FlowChoiceChip(
                    selected = settings.chineseConversion == conversion,
                    onClick = { onSettingsChange(settings.copy(chineseConversion = conversion)) },
                    label = { Text(conversion.displayName) }
                )
            }
        }

        SwitchRow(
            label = stringResource(R.string.reader_first_line_indent),
            checked = settings.firstLineIndent,
            onCheckedChange = { onSettingsChange(settings.copy(firstLineIndent = it)) }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PageTurnPage(settings: ReadingSettings, onSettingsChange: (ReadingSettings) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(FlowSpacing.sm), verticalArrangement = Arrangement.spacedBy(FlowSpacing.sm)) {
        PageMode.entries.forEach { mode ->
            FlowChoiceChip(
                selected = settings.pageMode == mode,
                onClick = { onSettingsChange(settings.copy(pageMode = mode)) },
                label = { Text(mode.displayName) }
            )
        }
    }

    if (settings.pageMode == PageMode.PAGED) {
        LabelledValue(
            label = stringResource(R.string.reader_setting_page_turn_animation),
            value = settings.pageTurnAnimation.displayName
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(FlowSpacing.sm), verticalArrangement = Arrangement.spacedBy(FlowSpacing.sm)) {
            PageTurnAnimation.entries.forEach { animation ->
                FlowChoiceChip(
                    selected = settings.pageTurnAnimation == animation,
                    onClick = { onSettingsChange(settings.copy(pageTurnAnimation = animation)) },
                    label = { Text(animation.displayName) }
                )
            }
        }
    }

    SwitchRow(
        label = stringResource(R.string.reader_setting_volume_key),
        checked = settings.volumeKeyTurnPage,
        onCheckedChange = { onSettingsChange(settings.copy(volumeKeyTurnPage = it)) }
    )
    FlowExpandableGroup(
        stringResource(R.string.ui_advanced_gestures),
        stringResource(
            R.string.ui_gesture_summary,
            settings.gestureSettings.tapZoneMode.displayName,
            stringResource(if (settings.gestureSettings.doubleTapEnabled) R.string.ui_on else R.string.ui_off)
        ),
        customized = settings.gestureSettings != GestureSettings()
    ) {
        LabelledValue(
            label = stringResource(R.string.reader_setting_tap_zone),
            value = settings.gestureSettings.tapZoneMode.displayName
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(FlowSpacing.sm), verticalArrangement = Arrangement.spacedBy(FlowSpacing.sm)) {
            TapZoneMode.entries.forEach { mode ->
                FlowChoiceChip(
                    selected = settings.gestureSettings.tapZoneMode == mode,
                    onClick = {
                        onSettingsChange(
                            settings.copy(gestureSettings = settings.gestureSettings.copy(tapZoneMode = mode))
                        )
                    },
                    label = { Text(mode.displayName) }
                )
            }
        }

        GestureSettingsContent(settings.gestureSettings) { onSettingsChange(settings.copy(gestureSettings = it)) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ThemePage(
    settings: ReadingSettings,
    onSettingsChange: (ReadingSettings) -> Unit,
    onPickBackgroundImage: () -> Unit,
    onClearBackgroundImage: () -> Unit
) {
    PaletteGrid(
        ids = ReaderPaletteId.LIGHT_PALETTES,
        selected = settings.palette,
        onSelect = { onSettingsChange(settings.copy(palette = it)) }
    )
    PaletteGrid(
        ids = ReaderPaletteId.DARK_PALETTES,
        selected = settings.palette,
        onSelect = { onSettingsChange(settings.copy(palette = it)) }
    )

    SwitchRow(
        label = stringResource(R.string.reader_auto_night),
        checked = settings.autoNightMode,
        onCheckedChange = { onSettingsChange(settings.copy(autoNightMode = it)) }
    )
    if (settings.autoNightMode) {
        LabelledValue(stringResource(R.string.reader_setting_night_palette), settings.nightPalette.displayName)
        PaletteGrid(
            ids = ReaderPaletteId.DARK_PALETTES,
            selected = settings.nightPalette,
            onSelect = { onSettingsChange(settings.copy(nightPalette = it)) }
        )
    }

    LabelledValue(
        stringResource(R.string.reader_annotation_style),
        stringResource(
            if (settings.annotationDisplayStyle == AnnotationDisplayStyle.UNDERLINE) R.string.reader_selection_underline else R.string.reader_soft_highlight
        )
    )
    Row(horizontalArrangement = Arrangement.spacedBy(FlowSpacing.sm)) {
        AnnotationDisplayStyle.entries.forEach { style ->
            FlowChoiceChip(
                selected = settings.annotationDisplayStyle == style,
                onClick = { onSettingsChange(settings.copy(annotationDisplayStyle = style)) },
                label = {
                    Text(
                        stringResource(
                            if (style == AnnotationDisplayStyle.UNDERLINE) R.string.reader_selection_underline else R.string.reader_soft_highlight
                        )
                    )
                }
            )
        }
    }

    FlowExpandableGroup(
        stringResource(R.string.ui_advanced_background),
        stringResource(R.string.ui_background_summary, settings.palette.displayName),
        customized = settings.backgroundImagePath != null || settings.customBackgroundColorArgb != null ||
            settings.customTextColorArgb != null
    ) {
        BackgroundImageEditor(
            settings = settings,
            onPickImage = onPickBackgroundImage,
            onClearImage = onClearBackgroundImage,
            onSettingsChange = onSettingsChange
        )

        CustomThemeEditor(settings = settings, onSettingsChange = onSettingsChange)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun EyeCarePage(settings: ReadingSettings, onSettingsChange: (ReadingSettings) -> Unit) {
    SwitchRow(
        label = stringResource(R.string.reader_setting_keep_screen_on),
        checked = settings.keepScreenOn,
        onCheckedChange = { onSettingsChange(settings.copy(keepScreenOn = it)) }
    )

    LabelledValue(
        label = stringResource(R.string.reader_section_eye_protection),
        value = stringResource(R.string.reader_timer_minutes, settings.eyeProtectionIntervalMinutes)
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(FlowSpacing.sm), verticalArrangement = Arrangement.spacedBy(FlowSpacing.sm)) {
        listOf(15, 20, 30, 45, 60).forEach { minutes ->
            FlowChoiceChip(
                selected = settings.eyeProtectionIntervalMinutes == minutes,
                onClick = { onSettingsChange(settings.copy(eyeProtectionIntervalMinutes = minutes)) },
                label = { Text(stringResource(R.string.reader_timer_minutes, minutes)) }
            )
        }
    }

    LabelledValue(
        label = stringResource(R.string.reader_setting_color_temperature),
        value = if (settings.colorTemperature == 0) {
            stringResource(R.string.reader_setting_color_temperature_off)
        } else {
            "${settings.colorTemperature}%"
        }
    )
    DebouncedSlider(
        value = settings.colorTemperature.toFloat(),
        valueRange = 0f..ReadingSettings.COLOR_TEMPERATURE_MAX.toFloat(),
        steps = 9,
        onCommit = { onSettingsChange(settings.copy(colorTemperature = it.toInt())) }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PaletteGrid(ids: List<ReaderPaletteId>, selected: ReaderPaletteId, onSelect: (ReaderPaletteId) -> Unit) {
    val context = LocalContext.current
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(FlowSpacing.sm),
        verticalArrangement = Arrangement.spacedBy(FlowSpacing.sm),
        modifier = Modifier.fillMaxWidth()
    ) {
        ids.forEach { id ->
            val palette = ReaderPalettes.of(id, com.flowreader.app.core.designsystem.theme.LocalFlowVisualStyle.current)
            val isSelected = selected == id
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(FlowRadius.sm))
                    .background(palette.background)
                    .border(
                        width = if (isSelected) 3.dp else 1.dp,
                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                        shape = RoundedCornerShape(FlowRadius.sm)
                    )
                    .clickable { onSelect(id) }
                    .semantics {
                        contentDescription = context.getString(R.string.reader_palette_label, id.displayName)
                        this.selected = isSelected
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = id.displayName,
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.text
                )
            }
        }
    }
}

/**
 * Custom reader colors (v55): user-picked background/text colors override the active palette;
 * the contrast fallback happens in `:core` `ReaderCustomTheme`.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CustomThemeEditor(settings: ReadingSettings, onSettingsChange: (ReadingSettings) -> Unit) {
    val context = LocalContext.current
    SectionTitle(stringResource(R.string.reader_section_custom_theme))
    Text(
        text = stringResource(R.string.reader_custom_color_notice),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    val fallbackString = stringResource(R.string.reader_setting_color_none)
    LabelledValue(stringResource(R.string.reader_setting_bg_color), formatArgb(settings.customBackgroundColorArgb, fallbackString))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(FlowSpacing.sm), verticalArrangement = Arrangement.spacedBy(FlowSpacing.sm)) {
        BACKGROUND_PRESETS.forEach { (name, argb) ->
            ColorSwatch(
                name = name,
                argb = argb,
                selected = settings.customBackgroundColorArgb == argb,
                onClick = {
                    onSettingsChange(
                        settings.copy(customBackgroundColorArgb = if (settings.customBackgroundColorArgb == argb) null else argb)
                    )
                },
                context = context
            )
        }
    }

    LabelledValue(stringResource(R.string.reader_setting_text_color), formatArgb(settings.customTextColorArgb, fallbackString))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(FlowSpacing.sm), verticalArrangement = Arrangement.spacedBy(FlowSpacing.sm)) {
        TEXT_PRESETS.forEach { (name, argb) ->
            ColorSwatch(
                name = name,
                argb = argb,
                selected = settings.customTextColorArgb == argb,
                onClick = {
                    onSettingsChange(settings.copy(customTextColorArgb = if (settings.customTextColorArgb == argb) null else argb))
                },
                context = context
            )
        }
    }

    if (settings.customTextColorArgb != null || settings.customBackgroundColorArgb != null) {
        TextButton(
            onClick = { onSettingsChange(settings.copy(customTextColorArgb = null, customBackgroundColorArgb = null)) }
        ) {
            Text(stringResource(R.string.reader_restore_palette))
        }
    }
}

/**
 * Reader background image: pick, clear, and set the scrim opacity.
 *
 * The scrim slider deliberately has no "off" position. Reader contrast is asserted against a flat
 * background colour, so text over a bare photo has no measurable readability at all — the image is
 * always behind the palette colour at [ReaderBackgroundImage.MIN_SCRIM_ALPHA] or more.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BackgroundImageEditor(
    settings: ReadingSettings,
    onPickImage: () -> Unit,
    onClearImage: () -> Unit,
    onSettingsChange: (ReadingSettings) -> Unit
) {
    SectionTitle(stringResource(R.string.reader_section_background))
    Text(
        text = stringResource(R.string.reader_background_notice),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    Text(
        text = stringResource(R.string.reader_paper_textures),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = FlowSpacing.xs)
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(FlowSpacing.sm), verticalArrangement = Arrangement.spacedBy(FlowSpacing.sm)) {
        PaperTextureGenerator.Preset.entries.forEach { preset ->
            val isSelected = PaperTextureGenerator.isPresetPath(settings.backgroundImagePath, preset)
            FlowChoiceChip(
                selected = isSelected,
                onClick = {
                    // First-use generation draws thousands of strokes; keep it off the main thread
                    // so tapping a texture cannot freeze the sheet.
                    scope.launch {
                        val path = withContext(Dispatchers.IO) {
                            PaperTextureGenerator.getPresetFile(context, preset).absolutePath
                        }
                        val correspondingPalette = when (preset) {
                            PaperTextureGenerator.Preset.PARCHMENT -> ReaderPaletteId.SEPIA
                            PaperTextureGenerator.Preset.KRAFT -> ReaderPaletteId.LINEN
                            PaperTextureGenerator.Preset.BEAN_GREEN -> ReaderPaletteId.GREEN
                            PaperTextureGenerator.Preset.XUAN -> ReaderPaletteId.PAPER
                        }
                        onSettingsChange(
                            settings.copy(
                                backgroundImagePath = path,
                                palette = correspondingPalette
                            )
                        )
                    }
                },
                label = { Text(preset.displayName) }
            )
        }
        if (settings.backgroundImagePath != null) {
            FlowChoiceChip(
                selected = false,
                onClick = onClearImage,
                label = { Text(stringResource(R.string.reader_texture_none)) }
            )
        }
    }

    val hasImage = ReaderBackgroundImage.isActive(settings.backgroundImagePath)
    Row(horizontalArrangement = Arrangement.spacedBy(FlowSpacing.sm)) {
        TextButton(onClick = onPickImage) {
            Text(if (hasImage) stringResource(R.string.reader_background_change) else stringResource(R.string.reader_background_pick))
        }
        if (hasImage) {
            TextButton(onClick = onClearImage) {
                Text(stringResource(R.string.reader_background_remove))
            }
        }
    }

    if (hasImage) {
        val palette = ReaderPalettes.of(settings.palette, com.flowreader.app.core.designsystem.theme.LocalFlowVisualStyle.current)
        val safeAlpha = ReaderBackgroundImage.minimumReadableAlpha(
            textArgb = palette.textArgb,
            scrimArgb = palette.backgroundArgb,
            paletteIsDark = palette.isDark
        )
        val alpha = ReaderBackgroundImage.clampScrimAlpha(settings.backgroundScrimAlpha)
        LabelledValue(stringResource(R.string.reader_setting_scrim_alpha), "${(alpha * 100).toInt()}%")
        FlowSlider(
            value = alpha,
            onValueChange = {
                onSettingsChange(settings.copy(backgroundScrimAlpha = ReaderBackgroundImage.clampScrimAlpha(it)))
            },
            valueRange = ReaderBackgroundImage.MIN_SCRIM_ALPHA..ReaderBackgroundImage.MAX_SCRIM_ALPHA
        )
        if (safeAlpha != null && alpha < safeAlpha) {
            Text(
                text = stringResource(R.string.reader_background_min_alpha, (safeAlpha * 100).toInt()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
private fun ColorSwatch(name: String, argb: Long, selected: Boolean, onClick: () -> Unit, context: android.content.Context) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(RoundedCornerShape(FlowRadius.sm))
            .background(androidx.compose.ui.graphics.Color(argb))
            .border(
                width = if (selected) 3.dp else 1.dp,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(FlowRadius.sm)
            )
            .clickable { onClick() }
            .semantics {
                contentDescription = context.getString(R.string.reader_custom_color_label, name)
                this.selected = selected
            }
    )
}

private fun formatArgb(argb: Long?, fallback: String): String = argb?.let { "#%08X".format(it) } ?: fallback

private val BACKGROUND_PRESETS = listOf(
    "纯白" to 0xFFFFFFFFL,
    "米黄" to 0xFFF5EFE0L,
    "护眼绿" to 0xFFCCE8CFL,
    "晨雾" to 0xFFE9EEF2L,
    "冷灰" to 0xFFDDE1E4L,
    "夜黑" to 0xFF121212L,
    "墨蓝" to 0xFF101822L,
    "纯黑" to 0xFF000000L
)

private val TEXT_PRESETS = listOf(
    "近黑" to 0xFF1A1A1AL,
    "深灰" to 0xFF3A3226L,
    "墨蓝" to 0xFF22282CL,
    "深棕" to 0xFF33302AL,
    "白色" to 0xFFD7D7D7L,
    "浅灰" to 0xFF9A9A9AL,
    "米白" to 0xFFDCCFC0L,
    "青灰" to 0xFFC6D3E0L
)

@Composable
internal fun SectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = FlowSpacing.sm)
    )
}

@Composable
internal fun LabelledValue(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun SwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onCheckedChange, modifier = Modifier.semantics { contentDescription = label })
    }
}

internal fun formatOneDecimal(value: Float): String {
    val tenths = (value * 10).toInt()
    return "${tenths / 10}.${tenths % 10}"
}
