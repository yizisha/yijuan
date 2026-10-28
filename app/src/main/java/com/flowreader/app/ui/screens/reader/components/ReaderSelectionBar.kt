package com.flowreader.app.ui.screens.reader.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.FormatColorFill
import androidx.compose.material.icons.filled.FormatUnderlined
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.flowreader.app.R
import com.flowreader.app.core.designsystem.reader.ReaderPalette
import com.flowreader.app.core.designsystem.reader.background
import com.flowreader.app.core.designsystem.reader.text
import kotlin.math.roundToInt

/**
 * Floating action bar shown above a native text selection: highlight, copy and bookmark.
 * Rendered through a [Popup] in window coordinates so it floats over the reader regardless
 * of scroll state.
 */
@Composable
fun ReaderSelectionBar(
    windowTopLeft: Offset,
    windowBottomRight: Offset,
    palette: ReaderPalette,
    onHighlight: () -> Unit,
    onCopy: () -> Unit,
    onBookmark: () -> Unit,
    onDismiss: () -> Unit,
    onNote: (() -> Unit)? = null,
    markAsUnderline: Boolean = false
) {
    val spacingPx = with(LocalDensity.current) { 8.dp.roundToPx() }
    val positionProvider = object : PopupPositionProvider {
        override fun calculatePosition(
            anchorBounds: IntRect,
            windowSize: IntSize,
            layoutDirection: LayoutDirection,
            popupContentSize: IntSize
        ): IntOffset {
            return selectionBarPosition(windowTopLeft, windowBottomRight, windowSize, popupContentSize, spacingPx)
        }
    }

    Popup(
        popupPositionProvider = positionProvider,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = false, dismissOnClickOutside = false)
    ) {
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = palette.background,
            shadowElevation = 6.dp,
            modifier = Modifier.border(1.dp, palette.text.copy(alpha = 0.2f), RoundedCornerShape(10.dp))
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                SelectionAction(
                    label = stringResource(
                        if (markAsUnderline) R.string.reader_selection_underline else R.string.reader_selection_highlight
                    ),
                    icon = {
                        Icon(
                            if (markAsUnderline) Icons.Default.FormatUnderlined else Icons.Default.FormatColorFill,
                            contentDescription = stringResource(
                                if (markAsUnderline) R.string.reader_selection_underline else R.string.reader_selection_highlight_desc
                            ),
                            tint = it
                        )
                    },
                    tint = palette.text,
                    onClick = onHighlight
                )
                SelectionAction(
                    label = stringResource(R.string.reader_selection_copy),
                    icon = {
                        Icon(
                            Icons.Default.ContentCopy,
                            contentDescription = stringResource(R.string.reader_selection_copy_desc),
                            tint = it
                        )
                    },
                    tint = palette.text,
                    onClick = onCopy
                )
                SelectionAction(
                    label = stringResource(R.string.reader_selection_bookmark),
                    icon = {
                        Icon(
                            Icons.Default.BookmarkAdd,
                            contentDescription = stringResource(R.string.reader_selection_bookmark_desc),
                            tint = it
                        )
                    },
                    tint = palette.text,
                    onClick = onBookmark
                )
                if (onNote != null) {
                    SelectionAction(
                        label = stringResource(R.string.reader_selection_note),
                        icon = { Icon(Icons.Default.EditNote, contentDescription = null, tint = it) },
                        tint = palette.text,
                        onClick = onNote
                    )
                }
            }
        }
    }
}

/** Window coordinates are already pixels; clamp the measured popup, never an estimated dp width. */
internal fun selectionBarPosition(topLeft: Offset, bottomRight: Offset, window: IntSize, popup: IntSize, gap: Int): IntOffset {
    val maxX = (window.width - popup.width).coerceAtLeast(0)
    val maxY = (window.height - popup.height).coerceAtLeast(0)
    val x = ((topLeft.x + bottomRight.x) / 2f - popup.width / 2f).roundToInt().coerceIn(0, maxX)
    val below = bottomRight.y.roundToInt() + gap
    val y = if (below <= maxY) below else topLeft.y.roundToInt() - popup.height - gap
    return IntOffset(x, y.coerceIn(0, maxY))
}

@Composable
private fun SelectionAction(label: String, icon: @Composable (Color) -> Unit, tint: Color, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.padding(horizontal = 2.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            icon(tint)
            Text(text = label, color = tint, style = MaterialTheme.typography.labelSmall)
        }
    }
}
