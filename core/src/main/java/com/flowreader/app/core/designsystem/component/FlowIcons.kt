package com.flowreader.app.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import com.flowreader.app.core.designsystem.theme.LocalFlowColorSource
import com.flowreader.app.core.designsystem.theme.LocalFlowVisualStyle
import com.flowreader.app.domain.model.AppVisualStyle
import com.flowreader.app.domain.model.ColorSource
import kotlin.math.cos
import kotlin.math.sin

/** One rounded, 1.5-unit stroke family for the reference reader's functional icons. */
object FlowIcons {
    private fun line(name: String, block: PathBuilder.() -> Unit): ImageVector = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
        path(
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.5f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
            pathBuilder = block
        )
    }.build()

    private fun PathBuilder.circle(x: Float, y: Float, radius: Float) {
        moveTo(x - radius, y)
        arcTo(radius, radius, 0f, false, true, x + radius, y)
        arcTo(radius, radius, 0f, false, true, x - radius, y)
        close()
    }

    private fun PathBuilder.rect(left: Float, top: Float, right: Float, bottom: Float) {
        moveTo(left, top)
        lineTo(right, top)
        lineTo(right, bottom)
        lineTo(left, bottom)
        close()
    }

    val Back by lazy {
        line("Back") {
            moveTo(14f, 5f)
            lineTo(7f, 12f)
            lineTo(14f, 19f)
        }
    }
    val Next by lazy {
        line("Next") {
            moveTo(9f, 5f)
            lineTo(16f, 12f)
            lineTo(9f, 19f)
        }
    }
    val Down by lazy {
        line("Down") {
            moveTo(6f, 9f)
            lineTo(12f, 15f)
            lineTo(18f, 9f)
        }
    }
    val Search by lazy {
        line("Search") {
            circle(10.5f, 10.5f, 6.5f)
            moveTo(15.2f, 15.2f)
            lineTo(21f, 21f)
        }
    }
    val Shelf by lazy {
        line("Shelf") {
            rect(4f, 4f, 8f, 20f)
            rect(8f, 4f, 12f, 20f)
            moveTo(15f, 4f)
            lineTo(19f, 3f)
            lineTo(22f, 19f)
            lineTo(18f, 20f)
            close()
            moveTo(4f, 8f)
            lineTo(12f, 8f)
        }
    }
    val Statistics by lazy {
        line("Statistics") {
            rect(3f, 12f, 7f, 21f)
            rect(10f, 3f, 14f, 21f)
            rect(17f, 8f, 21f, 21f)
        }
    }
    val Notes by lazy {
        line("Notes") {
            moveTo(20f, 14f)
            lineTo(20f, 20f)
            lineTo(4f, 20f)
            lineTo(4f, 4f)
            lineTo(11f, 4f)
            moveTo(9f, 15f)
            lineTo(10f, 11f)
            lineTo(19f, 2f)
            lineTo(22f, 5f)
            lineTo(13f, 14f)
            close()
            moveTo(17f, 4f)
            lineTo(20f, 7f)
        }
    }
    val Settings by lazy {
        line("Settings") {
            for (i in 0..31) {
                val angle = Math.PI * 2 * i / 32 - Math.PI / 2
                val radius = if (i % 4 == 0 || i % 4 == 3) 9.5 else 7.7
                val x = (12 + cos(angle) * radius).toFloat()
                val y = (12 + sin(angle) * radius).toFloat()
                if (i == 0) moveTo(x, y) else lineTo(x, y)
            }
            close()
            circle(12f, 12f, 3.4f)
        }
    }
    val Book by lazy {
        line("Book") {
            moveTo(12f, 5f)
            curveTo(9f, 3f, 5f, 3f, 2f, 4f)
            lineTo(2f, 20f)
            curveTo(5f, 19f, 9f, 19f, 12f, 21f)
            curveTo(15f, 19f, 19f, 19f, 22f, 20f)
            lineTo(22f, 4f)
            curveTo(19f, 3f, 15f, 3f, 12f, 5f)
            lineTo(12f, 21f)
        }
    }
    val Headphones by lazy {
        line("Headphones") {
            moveTo(4f, 15f)
            lineTo(4f, 10f)
            curveTo(4f, 0f, 20f, 0f, 20f, 10f)
            lineTo(20f, 15f)
            rect(3f, 12f, 7f, 21f)
            rect(17f, 12f, 21f, 21f)
        }
    }
    val Bookmark by lazy {
        line("Bookmark") {
            moveTo(6f, 3f)
            lineTo(18f, 3f)
            lineTo(18f, 21f)
            lineTo(12f, 17f)
            lineTo(6f, 21f)
            close()
        }
    }
    val Contents by lazy {
        line("Contents") {
            for (y in listOf(5f, 12f, 19f)) {
                moveTo(3f, y)
                lineTo(4f, y)
                moveTo(8f, y)
                lineTo(21f, y)
            }
        }
    }
    val Sun by lazy {
        line("Sun") {
            circle(12f, 12f, 4f)
            for (i in 0..7) {
                val a = Math.PI * i / 4
                moveTo((12 + cos(a) * 7).toFloat(), (12 + sin(a) * 7).toFloat())
                lineTo((12 + cos(a) * 10).toFloat(), (12 + sin(a) * 10).toFloat())
            }
        }
    }
    val Moon by lazy {
        line("Moon") {
            moveTo(20f, 15f)
            curveTo(17f, 22f, 5f, 22f, 3f, 13f)
            curveTo(1f, 6f, 7f, 1f, 12f, 3f)
            curveTo(7f, 8f, 11f, 17f, 20f, 15f)
            close()
        }
    }
    val Font by lazy {
        line("Font") {
            moveTo(4f, 21f)
            lineTo(12f, 3f)
            lineTo(20f, 21f)
            moveTo(7f, 14f)
            lineTo(17f, 14f)
        }
    }
    val More by lazy { line("More") { for (x in listOf(4f, 12f, 20f)) circle(x, 12f, .8f) } }
    val MoreVertical by lazy { line("MoreVertical") { for (y in listOf(4f, 12f, 20f)) circle(12f, y, .8f) } }
    val Folder by lazy {
        line("Folder") {
            moveTo(2f, 6f)
            lineTo(9f, 6f)
            lineTo(11f, 9f)
            lineTo(22f, 9f)
            lineTo(22f, 21f)
            lineTo(2f, 21f)
            close()
            moveTo(2f, 9f)
            lineTo(22f, 9f)
        }
    }
    val Import by lazy {
        line("Import") {
            moveTo(8f, 4f)
            lineTo(4f, 4f)
            lineTo(4f, 21f)
            lineTo(20f, 21f)
            lineTo(20f, 4f)
            lineTo(16f, 4f)
            moveTo(12f, 2f)
            lineTo(12f, 14f)
            moveTo(8f, 10f)
            lineTo(12f, 14f)
            lineTo(16f, 10f)
        }
    }
    val Cloud by lazy {
        line("Cloud") {
            moveTo(7f, 19f)
            curveTo(0f, 19f, 0f, 9f, 7f, 9f)
            curveTo(8f, 0f, 20f, 1f, 20f, 11f)
            curveTo(26f, 12f, 24f, 19f, 19f, 19f)
            close()
        }
    }
    val Palette by lazy {
        line("Palette") {
            moveTo(12f, 21f)
            curveTo(-2f, 21f, -1f, 2f, 12f, 2f)
            curveTo(22f, 2f, 26f, 14f, 17f, 14f)
            curveTo(10f, 12f, 18f, 21f, 12f, 21f)
            close()
            circle(6f, 9f, .8f)
            circle(10f, 6f, .8f)
            circle(16f, 7f, .8f)
        }
    }
    val Info by lazy {
        line("Info") {
            circle(12f, 12f, 9f)
            moveTo(12f, 11f)
            lineTo(12f, 17f)
            circle(12f, 7f, .5f)
        }
    }
    val Scan by lazy {
        line("Scan") {
            moveTo(3f, 8f)
            lineTo(3f, 3f)
            lineTo(8f, 3f)
            moveTo(16f, 3f)
            lineTo(21f, 3f)
            lineTo(21f, 8f)
            moveTo(21f, 16f)
            lineTo(21f, 21f)
            lineTo(16f, 21f)
            moveTo(8f, 21f)
            lineTo(3f, 21f)
            lineTo(3f, 16f)
            rect(8f, 7f, 16f, 17f)
        }
    }
    val Clock by lazy {
        line("Clock") {
            circle(12f, 12f, 9f)
            moveTo(12f, 6f)
            lineTo(12f, 12f)
            lineTo(17f, 15f)
        }
    }
    val Lock by lazy {
        line("Lock") {
            rect(4f, 10f, 20f, 21f)
            moveTo(7f, 10f)
            lineTo(7f, 7f)
            curveTo(7f, 0f, 17f, 0f, 17f, 7f)
            lineTo(17f, 10f)
            moveTo(12f, 14f)
            lineTo(12f, 17f)
        }
    }
    val Eye by lazy {
        line("Eye") {
            moveTo(2f, 12f)
            curveTo(7f, 2f, 17f, 2f, 22f, 12f)
            curveTo(17f, 22f, 7f, 22f, 2f, 12f)
            close()
            circle(12f, 12f, 3f)
        }
    }
    val Grid by lazy {
        line("Grid") {
            rect(3f, 3f, 10f, 10f)
            rect(14f, 3f, 21f, 10f)
            rect(3f, 14f, 10f, 21f)
            rect(14f, 14f, 21f, 21f)
        }
    }
    val File by lazy {
        line("File") {
            moveTo(4f, 2f)
            lineTo(14f, 2f)
            lineTo(20f, 8f)
            lineTo(20f, 22f)
            lineTo(4f, 22f)
            close()
            moveTo(14f, 2f)
            lineTo(14f, 8f)
            lineTo(20f, 8f)
        }
    }
    val Share by lazy {
        line("Share") {
            moveTo(4f, 11f)
            lineTo(4f, 21f)
            lineTo(20f, 21f)
            lineTo(20f, 11f)
            moveTo(12f, 15f)
            lineTo(12f, 2f)
            moveTo(7f, 7f)
            lineTo(12f, 2f)
            lineTo(17f, 7f)
        }
    }
    val Trash by lazy {
        line("Trash") {
            moveTo(3f, 6f)
            lineTo(21f, 6f)
            moveTo(8f, 6f)
            lineTo(8f, 3f)
            lineTo(16f, 3f)
            lineTo(16f, 6f)
            moveTo(5f, 6f)
            lineTo(6f, 22f)
            lineTo(18f, 22f)
            lineTo(19f, 6f)
            moveTo(10f, 10f)
            lineTo(10f, 18f)
            moveTo(14f, 10f)
            lineTo(14f, 18f)
        }
    }
    val Refresh by lazy {
        line("Refresh") {
            moveTo(20f, 8f)
            curveTo(14f, -3f, 0f, 4f, 4f, 15f)
            moveTo(20f, 3f)
            lineTo(20f, 8f)
            lineTo(15f, 8f)
            moveTo(4f, 16f)
            curveTo(10f, 27f, 24f, 20f, 20f, 9f)
            moveTo(4f, 21f)
            lineTo(4f, 16f)
            lineTo(9f, 16f)
        }
    }
}

/** Reference-style feature badges; these colours identify categories, not selected states. */
@Composable
fun FlowFeatureIcon(icon: ImageVector, category: Int, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val lightClean = LocalFlowVisualStyle.current == AppVisualStyle.CLEAN_BLUE &&
        LocalFlowColorSource.current == ColorSource.BRAND && colors.background.red > .8f
    val background = if (lightClean) {
        listOf(0xFF80AFB9, 0xFFDC9784, 0xFFA493CF, 0xFF70AD90, 0xFFD9B05D, 0xFF829DB3)[Math.floorMod(category, 6)]
            .let { Color(it) }
    } else {
        colors.primaryContainer
    }
    Box(modifier.size(32.dp).background(background, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size(20.dp), tint = if (lightClean) Color.White else colors.onPrimaryContainer)
    }
}
