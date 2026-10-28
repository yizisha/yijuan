package com.flowreader.app.ui.screens.reader.components

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flowreader.app.core.designsystem.reader.ReaderMetrics
import com.flowreader.app.core.designsystem.reader.ReaderPalette
import com.flowreader.app.core.designsystem.reader.secondaryText
import com.flowreader.app.core.designsystem.token.FlowSpacing
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Reading footer status bar (掌阅风格页脚状态感知).
 * Renders time, battery icon + level, and chapter title + progress percentage.
 * Highly subtle, low opacity, real-time sensing.
 */
@Composable
fun ReaderFooter(
    chapterTitle: String,
    progressFraction: Float,
    palette: ReaderPalette,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var batteryPercent by remember { mutableIntStateOf(100) }
    var isCharging by remember { mutableStateOf(false) }

    DisposableEffect(context) {
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                intent?.let {
                    val level = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                    val scale = it.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                    val status = it.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                    if (level >= 0 && scale > 0) {
                        batteryPercent = (level * 100) / scale
                    }
                    isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                        status == BatteryManager.BATTERY_STATUS_FULL
                }
            }
        }
        val sticky = context.registerReceiver(receiver, filter)
        sticky?.let { intent ->
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            if (level >= 0 && scale > 0) {
                batteryPercent = (level * 100) / scale
            }
            isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
        }
        onDispose {
            context.unregisterReceiver(receiver)
        }
    }

    var currentTime by remember {
        mutableStateOf(SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date()))
    }
    LaunchedEffect(Unit) {
        while (true) {
            currentTime = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
            delay(15_000L)
        }
    }

    val footerColor = palette.secondaryText.copy(alpha = 0.5f)
    val percentStr = String.format(Locale.getDefault(), "%.1f%%", (progressFraction * 100f).coerceIn(0f, 100f))

    Row(
        modifier = modifier
            .fillMaxWidth()
            // Shared with the paged renderer, which reserves this band so text never hides under it.
            .height(ReaderMetrics.FOOTER_HEIGHT_DP.dp)
            .padding(horizontal = FlowSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // Left: Reading Progress Percent
        Text(
            text = percentStr,
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 11.sp,
                color = footerColor
            ),
            maxLines = 1
        )

        // Right: Time + Battery Icon + Battery Percent
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = currentTime,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 11.sp,
                    color = footerColor
                )
            )

            // Mini Battery Icon
            Canvas(modifier = Modifier.size(width = 16.dp, height = 9.dp)) {
                val strokeWidth = 1.dp.toPx()
                val cornerRadius = CornerRadius(1.5.dp.toPx())
                val bodyWidth = size.width - 2.dp.toPx()
                val bodyHeight = size.height

                // Outlined battery body
                drawRoundRect(
                    color = footerColor,
                    topLeft = Offset(0f, 0f),
                    size = Size(bodyWidth, bodyHeight),
                    cornerRadius = cornerRadius,
                    style = Stroke(width = strokeWidth)
                )

                // Battery positive terminal bump
                drawRoundRect(
                    color = footerColor,
                    topLeft = Offset(bodyWidth, bodyHeight * 0.25f),
                    size = Size(2.dp.toPx(), bodyHeight * 0.5f),
                    cornerRadius = CornerRadius(0.5.dp.toPx())
                )

                // Fill level
                val pad = strokeWidth + 1f
                val fillWidth = ((bodyWidth - pad * 2) * (batteryPercent / 100f)).coerceAtLeast(1f)
                drawRoundRect(
                    color = footerColor,
                    topLeft = Offset(pad, pad),
                    size = Size(fillWidth, bodyHeight - pad * 2),
                    cornerRadius = CornerRadius(1f)
                )
            }

            Text(
                text = "$batteryPercent%",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 11.sp,
                    color = footerColor
                )
            )
        }
    }
}
