package com.flowreader.app.ui.screens.reader.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BorderColor
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.clipToBounds
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Log
import com.flowreader.app.R
import com.flowreader.app.domain.model.Annotation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * PDF viewer with region-based annotation (v55): `PdfRenderer` has no text layer, so highlights
 * are rectangles. A region is packed into the annotation's start/end positions as
 * `x * 10000 + y` where x/y are 0..999 normalized page coordinates.
 */
object PdfRegionCodec {
    private const val SCALE = 10_000

    fun pack(x: Int, y: Int): Int = x.coerceIn(0, 999) * SCALE + y.coerceIn(0, 999)

    fun unpackX(packed: Int): Int = packed / SCALE

    fun unpackY(packed: Int): Int = packed % SCALE

    fun unpackRect(packedStart: Int, packedEnd: Int): IntArray {
        val x0 = unpackX(packedStart)
        val y0 = unpackY(packedStart)
        val x1 = unpackX(packedEnd)
        val y1 = unpackY(packedEnd)
        return intArrayOf(minOf(x0, x1), minOf(y0, y1), maxOf(x0, x1), maxOf(y0, y1))
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun PdfViewer(
    filePath: String,
    currentPage: Int,
    textColor: Color,
    backgroundColor: Color,
    annotations: List<Annotation> = emptyList(),
    onAddPdfAnnotation: (Int, Int, Int, Int, Int) -> Unit,
    onPageChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    onTap: () -> Unit = {},
    onPageRendered: () -> Unit = {}
) {
    val session = remember(filePath) { com.flowreader.app.util.PdfDocumentSession(filePath) }
    DisposableEffect(session) { onDispose { session.close() } }
    var rendered by remember(filePath, currentPage) { mutableStateOf<com.flowreader.app.util.RenderedPdfPage?>(null) }
    var error by remember(filePath, currentPage) { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    var scale by remember(currentPage) { mutableFloatStateOf(1f) }
    var offset by remember(currentPage) { mutableStateOf(Offset.Zero) }
    var annotationMode by remember { mutableStateOf(false) }
    var dragStart by remember(currentPage) { mutableStateOf<Offset?>(null) }
    var dragEnd by remember(currentPage) { mutableStateOf<Offset?>(null) }
    val density = LocalDensity.current
    val context = LocalContext.current
    val annotationTint = MaterialTheme.colorScheme.primary
    BoxWithConstraints(modifier.fillMaxSize().background(backgroundColor).clipToBounds()) {
        val width = with(density) { maxWidth.toPx() }
        val height = with(density) { maxHeight.toPx() }
        LaunchedEffect(session, currentPage, width.toInt(), retry) {
            error = null
            try {
                rendered = session.render(currentPage, width.toInt())
                onPageRendered()
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.localizedMessage ?: failure.javaClass.simpleName }
        }
        val bitmap = rendered?.bitmap
        val fit = if (bitmap == null) 1f else minOf(width / bitmap.width, height / bitmap.height)
        val pageWidth = (bitmap?.width ?: 1) * fit
        val pageHeight = (bitmap?.height ?: 1) * fit
        fun normalized(point: Offset): Pair<Int, Int>? {
            val x = (point.x - width / 2 - offset.x) / scale + pageWidth / 2
            val y = (point.y - height / 2 - offset.y) / scale + pageHeight / 2
            if (x !in 0f..pageWidth || y !in 0f..pageHeight) return null
            return (x / pageWidth * 999).toInt() to (y / pageHeight * 999).toInt()
        }
        val latestSwipe by rememberUpdatedState<(Boolean) -> Unit> { forward ->
            if (com.flowreader.app.BuildConfig.DEBUG) android.util.Log.d("PdfGesture", "page=$currentPage forward=$forward")
            if (forward) onPageChange(currentPage + 1) else if (currentPage > 0) onPageChange(currentPage - 1)
        }
        val latestTap by rememberUpdatedState<(Offset) -> Unit> { point ->
            when {
                scale > 1.01f -> onTap()
                point.x < width * .3f && currentPage > 0 -> onPageChange(currentPage - 1)
                point.x > width * .7f -> onPageChange(currentPage + 1)
                else -> onTap()
            }
        }
        val latestDoubleTap by rememberUpdatedState<() -> Unit> {
            scale = if (scale > 1.01f) 1f else 2f
            offset = Offset.Zero
        }
        val latestZoomed by rememberUpdatedState(scale > 1.01f)
        val latestTransform by rememberUpdatedState<(Float, Offset) -> Unit> { zoom, pan ->
            scale = (scale * zoom).coerceIn(1f, 4f)
            val proposed = offset + pan
            offset = Offset(proposed.x.coerceIn(-width * (scale - 1) / 2, width * (scale - 1) / 2),
                proposed.y.coerceIn(-height * (scale - 1) / 2, height * (scale - 1) / 2))
        }
        val gestures = remember(filePath, context, density.density) {
            PdfGestureHandler(context, 64 * density.density, { latestZoomed },
                { latestSwipe(it) }, { latestTap(it) }, { latestDoubleTap() }, { zoom, pan -> latestTransform(zoom, pan) })
        }
        val touchInput = remember(gestures) { Modifier.pointerInteropFilter { gestures.onTouchEvent(it) } }
        DisposableEffect(gestures, annotationMode) { onDispose { gestures.cancel() } }
        Box(
            Modifier.fillMaxSize()
                .then(if (annotationMode) Modifier else touchInput)
                .pointerInput(annotationMode, currentPage, scale, offset) {
                    if (!annotationMode) return@pointerInput
                    detectDragGestures(onDragStart = { dragStart = it; dragEnd = it }, onDrag = { change, _ ->
                        change.consume(); dragEnd = change.position
                    }, onDragCancel = { dragStart = null; dragEnd = null }, onDragEnd = {
                        val start = dragStart?.let(::normalized)
                        val end = dragEnd?.let(::normalized)
                        if (start != null && end != null && kotlin.math.abs(start.first - end.first) + kotlin.math.abs(start.second - end.second) > 5) {
                            onAddPdfAnnotation(currentPage, start.first, start.second, end.first, end.second)
                        }
                        dragStart = null; dragEnd = null
                    })
                }, contentAlignment = Alignment.Center
        ) {
            when {
                error != null -> Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                    Text(stringResource(R.string.pdf_load_error, error.orEmpty()), color = textColor)
                    OutlinedButton(onClick = { retry++ }) { Text(stringResource(R.string.action_retry)) }
                }
                bitmap == null -> CircularProgressIndicator(color = textColor)
                else -> Box(Modifier.size(with(density) { pageWidth.toDp() }, with(density) { pageHeight.toDp() })
                    .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y)) {
                    Image(bitmap.asImageBitmap(), stringResource(R.string.reader_pdf_page, currentPage + 1), Modifier.fillMaxSize())
                    Canvas(Modifier.fillMaxSize()) {
                        annotations.forEach { annotation ->
                            val rect = PdfRegionCodec.unpackRect(annotation.startPosition, annotation.endPosition)
                            drawRect(annotationTint.copy(alpha = .22f), Offset(rect[0] / 999f * size.width, rect[1] / 999f * size.height),
                                Size((rect[2] - rect[0]) / 999f * size.width, (rect[3] - rect[1]) / 999f * size.height))
                        }
                    }
                }
            }
            val start = dragStart
            val end = dragEnd
            if (annotationMode && start != null && end != null) Canvas(Modifier.fillMaxSize()) {
                drawRect(textColor.copy(alpha = .45f), Offset(minOf(start.x, end.x), minOf(start.y, end.y)),
                    Size(kotlin.math.abs(end.x - start.x), kotlin.math.abs(end.y - start.y)),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
            }
        }
        FloatingActionButton(onClick = { annotationMode = !annotationMode }, modifier = Modifier.align(Alignment.TopEnd).padding(top = 80.dp, end = 16.dp),
            containerColor = if (annotationMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface) {
            Icon(Icons.Default.BorderColor, stringResource(if (annotationMode) R.string.reader_pdf_annotation_off else R.string.reader_pdf_annotation_on),
                tint = if (annotationMode) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)
        }
    }
}
