package com.flowreader.app.ui.screens.reader.components

import android.app.Activity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BrightnessHigh
import androidx.compose.material.icons.filled.BrightnessLow
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Highlight
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.flowreader.app.R
import com.flowreader.app.core.designsystem.component.FlowIcons
import com.flowreader.app.core.designsystem.reader.ReaderPalette
import com.flowreader.app.core.designsystem.reader.background
import com.flowreader.app.core.designsystem.reader.secondaryText
import com.flowreader.app.core.designsystem.reader.text
import com.flowreader.app.core.designsystem.token.FlowMotion
import com.flowreader.app.core.designsystem.token.FlowSpacing

/** Fast enough to feel instant, slow enough to read as motion rather than a flicker. */
private const val CONTROL_ANIM_MS = 160

/**
 * Progress-scrubbing state shared by the slider and the centred HUD.
 *
 * It lives in its own holder so the HUD reads it inside [ScrubHudHost] and the app bars never
 * recompose while a finger is dragging the slider.
 */
private class ScrubState {
    var isDragging by mutableStateOf(false)
    var fraction by mutableFloatStateOf(0f)
}

/**
 * The reader control layer.
 *
 * Layout follows the reference design:
 * - Top bar: back arrow on the left, overflow menu on the right.
 * - Bottom bar: 上一章 | progress slider | 下一章 above 目录 | 亮度 | 夜间 | 设置.
 *
 * Responsiveness (v0.0.3): the layer is always composed and animates its own alpha/offset from
 * [visible] instead of being added to and removed from the tree by an `AnimatedVisibility` in
 * `ReaderScreen`. Toggling used to re-inflate a `TopAppBar` plus the whole bottom surface on every
 * tap, which is what made showing the bar feel laggy; the slide itself is a 160 ms transform on the
 * existing nodes, and the dismiss scrim only takes input while the bar is actually up, so a second
 * tap always reaches the reading surface immediately.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderControls(
    visible: Boolean,
    bookTitle: String,
    chapterTitle: String,
    currentChapter: Int,
    totalChapters: Int,
    progressProvider: () -> Float,
    isTtsPlaying: Boolean,
    palette: ReaderPalette,
    chapterTitleAt: (Float) -> String,
    onBackClick: () -> Unit,
    onPreviousChapterClick: () -> Unit,
    onNextChapterClick: () -> Unit,
    onChapterClick: () -> Unit,
    onBookmarkClick: () -> Unit,
    onAddBookmark: () -> Unit,
    onTtsClick: () -> Unit,
    onAnnotationClick: () -> Unit,
    onSearchClick: () -> Unit,
    onImmersiveClick: () -> Unit,
    onShareClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onNightModeToggle: () -> Unit,
    onProgressCommit: (Float) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    ttsAvailable: Boolean = true,
    ttsVisible: Boolean = true,
    isBookmarked: Boolean = false,
    pageBasedDocument: Boolean = false,
    hasNextVolume: Boolean = false
) {
    val context = LocalContext.current
    var overflowExpanded by remember { mutableStateOf(false) }
    var bookmarkSubmenuExpanded by remember { mutableStateOf(false) }
    var showBrightnessPanel by remember { mutableStateOf(false) }
    var screenBrightness by remember { mutableFloatStateOf(-1f) }
    val scrub = remember { ScrubState() }

    val reveal by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(CONTROL_ANIM_MS),
        label = "readerControlsReveal"
    )

    if (reveal <= 0f) return

    Box(modifier = modifier.fillMaxSize()) {
        // 1. Center transparent scrim. Present only while the bar is up: dismissing is instant and
        //    the very next tap goes straight to the reading surface.
        if (visible) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss
                    )
            )
        }

        // Scrubbing HUD (掌阅式居中进度悬浮气泡)
        ScrubHudHost(scrub = scrub, chapterTitleAt = chapterTitleAt, palette = palette)

        // 2. Top app bar: slides up out of the status bar as the layer reveals.
        TopAppBar(
            title = { },
            navigationIcon = {
                IconButton(onClick = onBackClick) {
                    Icon(
                        FlowIcons.Back,
                        contentDescription = stringResource(R.string.action_back),
                        tint = palette.text
                    )
                }
            },
            actions = {
                if (ttsVisible) IconButton(onClick = onTtsClick, enabled = ttsAvailable) {
                    Icon(
                        FlowIcons.Headphones,
                        stringResource(if (isTtsPlaying) R.string.reader_tts_pause else R.string.reader_tts_play),
                        tint = palette.text.copy(alpha = if (ttsAvailable) 1f else .4f)
                    )
                }
                IconButton(
                    onClick = onAddBookmark
                ) {
                    Icon(
                        if (isBookmarked) Icons.Default.Bookmark else FlowIcons.Bookmark,
                        stringResource(if (isBookmarked) R.string.reader_bookmark_saved else R.string.reader_bookmark_add),
                        tint = palette.text
                    )
                }
                Box {
                    IconButton(onClick = { overflowExpanded = true }) {
                        Icon(
                            FlowIcons.MoreVertical,
                            contentDescription = stringResource(R.string.action_more),
                            tint = palette.text
                        )
                    }
                    DropdownMenu(
                        expanded = overflowExpanded,
                        onDismissRequest = { overflowExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.reader_menu_search)) },
                            leadingIcon = { Icon(FlowIcons.Search, contentDescription = null) },
                            onClick = {
                                overflowExpanded = false
                                onSearchClick()
                            }
                        )
                        // 书签 is a submenu: add the current page, or jump to an existing bookmark.
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.reader_bookmarks_title)) },
                            leadingIcon = { Icon(FlowIcons.Bookmark, contentDescription = null) },
                            trailingIcon = {
                                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                            },
                            onClick = { bookmarkSubmenuExpanded = true }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.reader_menu_annotations)) },
                            leadingIcon = { Icon(Icons.Default.Highlight, contentDescription = null) },
                            onClick = {
                                overflowExpanded = false
                                onAnnotationClick()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.reader_menu_share)) },
                            leadingIcon = { Icon(Icons.Default.Share, contentDescription = null) },
                            onClick = {
                                overflowExpanded = false
                                onShareClick()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.reader_settings_title)) },
                            leadingIcon = { Icon(FlowIcons.Settings, contentDescription = null) },
                            onClick = {
                                overflowExpanded = false
                                onSettingsClick()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.reader_menu_focus)) },
                            leadingIcon = { Icon(Icons.Default.Fullscreen, contentDescription = null) },
                            onClick = {
                                overflowExpanded = false
                                onImmersiveClick()
                            }
                        )
                    }

                    DropdownMenu(
                        expanded = bookmarkSubmenuExpanded,
                        onDismissRequest = { bookmarkSubmenuExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.reader_bookmark_add)) },
                            leadingIcon = { Icon(FlowIcons.Bookmark, contentDescription = null) },
                            onClick = {
                                bookmarkSubmenuExpanded = false
                                overflowExpanded = false
                                onAddBookmark()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.reader_bookmark_jump)) },
                            leadingIcon = { Icon(FlowIcons.Contents, contentDescription = null) },
                            onClick = {
                                bookmarkSubmenuExpanded = false
                                overflowExpanded = false
                                onBookmarkClick()
                            }
                        )
                    }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = palette.background.copy(alpha = 0.95f)),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .windowInsetsPadding(WindowInsets.statusBars)
                .height(48.dp)
                .graphicsLayer {
                    alpha = reveal
                    translationY = -size.height * (1f - reveal)
                }
        )

        // 3. Bottom control surface: slides down out of the navigation bar.
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .graphicsLayer {
                    alpha = reveal
                    translationY = size.height * (1f - reveal)
                },
            color = palette.background.copy(alpha = 0.95f)
        ) {
            BottomControlBar(
                currentChapter = currentChapter,
                totalChapters = totalChapters,
                pageBasedDocument = pageBasedDocument,
                hasNextVolume = hasNextVolume,
                progressProvider = progressProvider,
                chapterTitleAt = chapterTitleAt,
                scrub = scrub,
                onProgressCommit = onProgressCommit,
                showBrightnessPanel = showBrightnessPanel,
                onToggleBrightnessPanel = { showBrightnessPanel = !showBrightnessPanel },
                screenBrightness = screenBrightness,
                onScreenBrightnessChange = { value ->
                    screenBrightness = value
                    val activity = context as? Activity
                    activity?.window?.attributes = activity?.window?.attributes?.apply {
                        this.screenBrightness = value.coerceIn(0.01f, 1.0f)
                    }
                },
                onRestoreSystemBrightness = {
                    screenBrightness = -1f
                    val activity = context as? Activity
                    activity?.window?.attributes = activity?.window?.attributes?.apply {
                        this.screenBrightness = -1f
                    }
                },
                palette = palette,
                onPreviousChapterClick = onPreviousChapterClick,
                onNextChapterClick = onNextChapterClick,
                onChapterClick = onChapterClick,
                onNightModeToggle = onNightModeToggle,
                onSettingsClick = onSettingsClick
            )
        }
    }
}

/**
 * The progress slider owns the scrub state so that the chapter fraction — which changes on every
 * scrolled pixel — is only read inside this small composable. Reading it in [ReaderControls] itself
 * would recompose the app bars on every frame of a scroll.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BottomControlBar(
    pageBasedDocument: Boolean,
    hasNextVolume: Boolean,
    currentChapter: Int,
    totalChapters: Int,
    progressProvider: () -> Float,
    chapterTitleAt: (Float) -> String,
    scrub: ScrubState,
    onProgressCommit: (Float) -> Unit,
    showBrightnessPanel: Boolean,
    onToggleBrightnessPanel: () -> Unit,
    screenBrightness: Float,
    onScreenBrightnessChange: (Float) -> Unit,
    onRestoreSystemBrightness: () -> Unit,
    palette: ReaderPalette,
    onPreviousChapterClick: () -> Unit,
    onNextChapterClick: () -> Unit,
    onChapterClick: () -> Unit,
    onNightModeToggle: () -> Unit,
    onSettingsClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = FlowSpacing.md, vertical = FlowSpacing.xs),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        AnimatedVisibility(
            visible = showBrightnessPanel,
            enter = fadeIn(tween(FlowMotion.QUICK_MS)) + slideInVertically(tween(FlowMotion.QUICK_MS)) { it },
            exit = fadeOut(tween(FlowMotion.QUICK_MS)) + slideOutVertically(tween(FlowMotion.QUICK_MS)) { it }
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = FlowSpacing.md, vertical = FlowSpacing.xs),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.BrightnessLow,
                    contentDescription = stringResource(R.string.reader_brightness_lower),
                    tint = palette.secondaryText,
                    modifier = Modifier.size(20.dp)
                )
                Slider(
                    value = if (screenBrightness < 0f) 0.5f else screenBrightness,
                    onValueChange = onScreenBrightnessChange,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = FlowSpacing.sm),
                    colors = SliderDefaults.colors(
                        thumbColor = palette.text,
                        activeTrackColor = palette.text,
                        inactiveTrackColor = palette.secondaryText.copy(alpha = 0.3f)
                    )
                )
                Icon(
                    Icons.Default.BrightnessHigh,
                    contentDescription = stringResource(R.string.reader_brightness_raise),
                    tint = palette.secondaryText,
                    modifier = Modifier.size(20.dp)
                )
                TextButton(onClick = onRestoreSystemBrightness) {
                    Text(
                        text = if (screenBrightness < 0f) {
                            stringResource(R.string.reader_brightness_system)
                        } else {
                            stringResource(R.string.reader_brightness_restore)
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (screenBrightness < 0f) palette.text else palette.secondaryText
                    )
                }
            }
        }

        val sliderValue = if (scrub.isDragging) scrub.fraction else progressProvider().coerceIn(0f, 1f)

        if (scrub.isDragging) {
            Text(
                text = chapterTitleAt(scrub.fraction),
                style = MaterialTheme.typography.labelMedium,
                color = palette.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(bottom = 2.dp)
            )
        }

        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                stringResource(if (pageBasedDocument) R.string.pdf_progress_pages else R.string.reader_progress_chapters, currentChapter.coerceAtMost(totalChapters), totalChapters),
                color = palette.secondaryText,
                style = MaterialTheme.typography.labelSmall
            )
            Text(
                com.flowreader.app.core.util.FlowFormatters.percent(sliderValue),
                color = palette.secondaryText,
                style = MaterialTheme.typography.labelSmall
            )
        }
        val progressAccent = if (palette.isDark) palette.text else com.flowreader.app.core.designsystem.theme.flowColorScheme(false).primary
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onPreviousChapterClick, enabled = currentChapter > 1, modifier = Modifier.widthIn(min = 64.dp).heightIn(min = 56.dp)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        FlowIcons.Back,
                        stringResource(if (pageBasedDocument) R.string.pdf_prev_page else R.string.reader_prev_chapter),
                        Modifier.size(18.dp),
                        tint = palette.text.copy(alpha = if (currentChapter > 1) 1f else .3f)
                    )
                    Text(
                        stringResource(if (pageBasedDocument) R.string.pdf_prev_page else R.string.reader_prev_chapter),
                        style = MaterialTheme.typography.labelSmall,
                        color = palette.text.copy(alpha = if (currentChapter > 1) 1f else .3f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Slider(
                value = sliderValue,
                onValueChange = {
                    scrub.isDragging = true
                    scrub.fraction = it
                },
                onValueChangeFinished = {
                    scrub.isDragging = false
                    onProgressCommit(scrub.fraction)
                },
                modifier = Modifier.weight(1f),
                colors = SliderDefaults.colors(
                    thumbColor = progressAccent,
                    activeTrackColor = progressAccent,
                    inactiveTrackColor = palette.secondaryText.copy(alpha = .3f)
                ),
                thumb = { Box(Modifier.size(10.dp).background(progressAccent, androidx.compose.foundation.shape.CircleShape)) },
                track = {
                    SliderDefaults.Track(
                        it,
                        Modifier.height(2.dp),
                        colors = SliderDefaults.colors(
                            activeTrackColor = progressAccent,
                            inactiveTrackColor = palette.secondaryText.copy(alpha = .3f)
                        ),
                        thumbTrackGapSize = 0.dp,
                        drawStopIndicator = null,
                        drawTick = { _, _ -> }
                    )
                }
            )
            IconButton(onClick = onNextChapterClick, enabled = (currentChapter < totalChapters || hasNextVolume), modifier = Modifier.widthIn(min = 64.dp).heightIn(min = 56.dp)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        stringResource(if (currentChapter >= totalChapters && hasNextVolume) R.string.series_next_volume else if (pageBasedDocument) R.string.pdf_next_page else R.string.reader_next_chapter),
                        Modifier.size(18.dp),
                        tint = palette.text.copy(alpha = if ((currentChapter < totalChapters || hasNextVolume)) 1f else .3f)
                    )
                    Text(
                        stringResource(if (currentChapter >= totalChapters && hasNextVolume) R.string.series_next_volume else if (pageBasedDocument) R.string.pdf_next_page else R.string.reader_next_chapter),
                        style = MaterialTheme.typography.labelSmall,
                        color = palette.text.copy(alpha = if ((currentChapter < totalChapters || hasNextVolume)) 1f else .3f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = FlowSpacing.xs, bottom = FlowSpacing.sm),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            ReaderBottomActionButton(
                icon = FlowIcons.Contents,
                label = stringResource(R.string.reader_menu_toc),
                palette = palette,
                onClick = onChapterClick,
                modifier = Modifier.weight(1f)
            )
            ReaderBottomActionButton(
                icon = if (palette.isDark) FlowIcons.Sun else FlowIcons.Moon,
                label = if (palette.isDark) {
                    stringResource(R.string.reader_mode_day)
                } else {
                    stringResource(R.string.reader_mode_night)
                },
                palette = palette,
                onClick = onNightModeToggle,
                modifier = Modifier.weight(1f)
            )
            ReaderBottomActionButton(
                icon = FlowIcons.Sun,
                label = stringResource(R.string.reader_menu_brightness),
                palette = palette,
                onClick = onToggleBrightnessPanel,
                modifier = Modifier.weight(1f)
            )
            ReaderBottomActionButton(
                icon = FlowIcons.Settings,
                label = stringResource(R.string.settings_title),
                palette = palette,
                onClick = onSettingsClick,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/** Reads [ScrubState] in its own scope, so a drag repaints the HUD and nothing else. */
@Composable
private fun ScrubHudHost(scrub: ScrubState, chapterTitleAt: (Float) -> String, palette: ReaderPalette) {
    AnimatedVisibility(
        visible = scrub.isDragging,
        enter = fadeIn(tween(120)),
        exit = fadeOut(tween(250))
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            ScrubHud(
                title = chapterTitleAt(scrub.fraction),
                fraction = scrub.fraction,
                palette = palette
            )
        }
    }
}

@Composable
private fun ScrubHud(title: String, fraction: Float, palette: ReaderPalette) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = palette.background.copy(alpha = 0.94f),
        shadowElevation = 10.dp,
        tonalElevation = 4.dp,
        modifier = Modifier.padding(horizontal = 40.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                color = palette.text,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "${String.format(java.util.Locale.getDefault(), "%.1f", fraction * 100f)}%",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                color = palette.secondaryText
            )
        }
    }
}

@Composable
private fun ReaderBottomActionButton(
    icon: ImageVector,
    label: String,
    palette: ReaderPalette,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(bounded = true, radius = 28.dp),
                onClick = onClick
            )
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = palette.text,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = palette.secondaryText
        )
    }
}
