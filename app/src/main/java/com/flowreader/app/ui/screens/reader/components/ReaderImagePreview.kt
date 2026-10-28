package com.flowreader.app.ui.screens.reader.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.flowreader.app.R
import java.io.File

/** 正在预览的插图：路径 + 图注/alt（显示在预览底部）。 */
internal data class ReaderImagePreviewTarget(val path: String, val alt: String?)

/** 预览缩放上下限。 */
private const val PREVIEW_MIN_SCALE = 1f
private const val PREVIEW_MAX_SCALE = 6f
private const val PREVIEW_DOUBLE_TAP_SCALE = 2.5f

/**
 * 图片放大预览（主计划 4.6 第 10 条）。
 *
 * 约束：
 * - **手势在本层消费**：用独立 [Dialog] 承载，预览期间的缩放/拖动/双击不会传给正文翻页层，
 *   也不会弹出正文菜单；
 * - 关闭方式有三条（点空白、右上角关闭、系统返回键），都只关闭预览；
 * - **不产生导航或阅读进度**：关闭不会跳页、不会改写定位 —— 因为这里不碰 ScrollState，
 *   也不调用任何 ViewModel 写入口；
 * - 图注/alt 在底部显示，便于确认"这张图说的是什么"。
 *
 * 本期不含：分享/保存、多图左右切换（计划书未要求）。
 */
@Composable
internal fun ReaderImagePreviewDialog(
    path: String,
    alt: String?,
    onDismiss: () -> Unit
) {
    var scale by remember(path) { mutableStateOf(PREVIEW_MIN_SCALE) }
    var offset by remember(path) { mutableStateOf(Offset.Zero) }

    fun resetTransform() {
        scale = PREVIEW_MIN_SCALE
        offset = Offset.Zero
    }

    Dialog(
        onDismissRequest = onDismiss,
        // 默认 Dialog 宽度有限，图片预览需要整屏。
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.96f))
                .pointerInput(path) {
                    detectTapGestures(
                        onTap = { onDismiss() },
                        onDoubleTap = {
                            if (scale > PREVIEW_MIN_SCALE) resetTransform() else scale = PREVIEW_DOUBLE_TAP_SCALE
                        }
                    )
                }
                .pointerInput(path) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        val next = (scale * zoom).coerceIn(PREVIEW_MIN_SCALE, PREVIEW_MAX_SCALE)
                        scale = next
                        // 只在放大后允许拖动；缩回 1x 时归位，避免图片跑到屏幕外。
                        offset = if (next > PREVIEW_MIN_SCALE) offset + pan else Offset.Zero
                    }
                }
        ) {
            AsyncImage(
                model = File(path),
                contentDescription = alt ?: stringResource(R.string.reader_illustration),
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp)
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offset.x,
                        translationY = offset.y
                    )
            )

            if (!alt.isNullOrBlank()) {
                Text(
                    text = alt,
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(start = 24.dp, end = 24.dp, bottom = 64.dp)
                )
            }

            Text(
                text = stringResource(R.string.reader_image_preview_hint),
                color = Color.White.copy(alpha = 0.65f),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 28.dp)
            )

            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .windowInsetsPadding(WindowInsets.statusBars)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(R.string.reader_image_preview_close),
                    tint = Color.White
                )
            }
        }
    }
}
