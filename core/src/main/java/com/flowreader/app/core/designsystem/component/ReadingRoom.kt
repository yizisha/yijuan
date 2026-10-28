package com.flowreader.app.core.designsystem.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Local vector artwork: no image request, bitmap allocation or network dependency. */
@Composable
fun PaperLandscape(modifier: Modifier = Modifier, ink: Color = MaterialTheme.colorScheme.primary) {
    val sun = MaterialTheme.colorScheme.secondary
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        drawCircle(sun.copy(alpha = .25f), h * .15f, Offset(w * .76f, h * .25f))
        val ridges = listOf(
            listOf(.0f to .75f, .13f to .60f, .25f to .67f, .39f to .30f, .48f to .43f, .60f to .20f, .68f to .35f, .80f to .17f, 1f to .46f),
            listOf(.0f to .88f, .18f to .70f, .30f to .79f, .43f to .58f, .57f to .69f, .71f to .41f, .84f to .56f, 1f to .32f),
            listOf(.0f to .98f, .24f to .92f, .40f to .85f, .57f to .94f, .72f to .74f, .85f to .68f, 1f to .72f)
        )
        ridges.forEachIndexed { index, ridge ->
            val path = Path().apply {
                moveTo(0f, h)
                var previousX = 0f
                var previousY = h * ridge.first().second
                lineTo(previousX, previousY)
                ridge.drop(1).forEach { (x, y) ->
                    val nextX = x * w
                    val nextY = y * h
                    val step = nextX - previousX
                    cubicTo(previousX + step * .38f, previousY, nextX - step * .28f, nextY, nextX, nextY)
                    previousX = nextX
                    previousY = nextY
                }
                lineTo(w, h)
                close()
            }
            drawPath(path, Brush.verticalGradient(listOf(ink.copy(alpha = .12f + index * .1f), ink.copy(alpha = .03f + index * .05f))))
        }
        // Wisps of river mist give depth while leaving the reading foreground quiet.
        repeat(5) { index ->
            val y = h * (.70f + index * .053f)
            drawLine(ink.copy(alpha = .07f), Offset(w * .22f, y), Offset(w * .9f, y), h * .005f)
        }
        // Pavilion and pines on the far bank.
        val x = w * .86f
        val y = h * .69f
        drawRect(ink.copy(alpha = .48f), Offset(x - w * .035f, y), androidx.compose.ui.geometry.Size(w * .07f, h * .10f), style = Stroke(h * .007f))
        drawPath(Path().apply {
            moveTo(x - w * .052f, y)
            lineTo(x, y - h * .065f)
            lineTo(x + w * .052f, y)
            close()
        }, ink.copy(alpha = .62f))
        listOf(.76f to .64f, .95f to .56f, .99f to .62f).forEach { (px, py) ->
            val tx = w * px
            val ty = h * py
            drawLine(ink.copy(alpha = .6f), Offset(tx, ty), Offset(tx, ty + h * .18f), h * .009f)
            repeat(4) { branch ->
                val by = ty + h * .033f * branch
                val half = h * (.026f + branch * .013f)
                drawLine(ink.copy(alpha = .5f), Offset(tx - half, by + h * .016f), Offset(tx, by), h * .018f)
                drawLine(ink.copy(alpha = .5f), Offset(tx, by), Offset(tx + half, by + h * .013f), h * .018f)
            }
        }
    }
}

/** A restrained scenic banner shared by shelf and preferences. */
@Composable
fun ReadingRoomBanner(title: String, subtitle: String, modifier: Modifier = Modifier, content: @Composable () -> Unit = {}) {
    val colors = MaterialTheme.colorScheme
    Box(modifier.clip(RoundedCornerShape(20.dp)).background(Brush.verticalGradient(listOf(colors.secondaryContainer.copy(alpha = .55f), colors.primaryContainer.copy(alpha = .45f))))) {
        PaperLandscape(Modifier.matchParentSize(), colors.primary)
        Column(Modifier.fillMaxWidth().padding(22.dp)) {
            Text(title, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth(.73f))
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, modifier = Modifier.fillMaxWidth(.7f).padding(top = 8.dp, bottom = 12.dp))
            content()
        }
    }
}
