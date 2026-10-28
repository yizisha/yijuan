package com.flowreader.app.ui.screens.reader.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flowreader.app.core.designsystem.reader.ReaderMetrics
import com.flowreader.app.core.designsystem.reader.ReaderPalette
import com.flowreader.app.core.designsystem.reader.secondaryText
import com.flowreader.app.core.designsystem.token.FlowSpacing

/**
 * Reading header status bar (左上角轻灵章节页眉).
 * 对标掌阅/经典出版物排版规范：常驻呈现当前章节标题，11sp 淡墨灰阶，低透明度。
 */
@Composable
fun ReaderHeader(
    chapterTitle: String,
    palette: ReaderPalette,
    modifier: Modifier = Modifier
) {
    if (chapterTitle.isBlank()) return

    val headerColor = palette.secondaryText.copy(alpha = 0.45f)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(ReaderMetrics.HEADER_HEIGHT_DP.dp)
            .padding(horizontal = FlowSpacing.md),
        contentAlignment = Alignment.CenterStart
    ) {
        Text(
            text = chapterTitle,
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 11.sp,
                color = headerColor
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
