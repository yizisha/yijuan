package com.flowreader.app.util

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.Closeable
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.min
import kotlin.math.sqrt

/** Bound pixel allocation for unusually large PDF page dimensions. */
internal fun pdfBitmapSize(width: Int, height: Int, requestedWidth: Int): Pair<Int, Int> {
    require(width > 0 && height > 0)
    val scale = min(requestedWidth.coerceIn(1, 2048).toDouble() / width,
        min(4096.0 / maxOf(width, height), sqrt(4_000_000.0 / (width.toDouble() * height))))
    return maxOf(1, (width * scale).toInt()) to maxOf(1, (height * scale).toInt())
}

data class RenderedPdfPage(val bitmap: Bitmap, val pageCount: Int)

/** One serialized renderer per open document, never opened or closed on the UI thread. */
class PdfDocumentSession(private val filePath: String) : Closeable {
    private val mutex = Mutex()
    private val closed = AtomicBoolean()
    private var renderer: PdfRenderer? = null

    suspend fun render(pageIndex: Int, width: Int): RenderedPdfPage = withContext(Dispatchers.IO) {
        mutex.withLock {
            check(!closed.get()) { "PDF 已关闭" }
            val document = renderer ?: run {
                val descriptor = ParcelFileDescriptor.open(File(filePath), ParcelFileDescriptor.MODE_READ_ONLY)
                try { PdfRenderer(descriptor) } catch (error: Throwable) { descriptor.close(); throw error }
            }.also { renderer = it }
            require(pageIndex in 0 until document.pageCount) { "PDF 页码超出范围" }
            document.openPage(pageIndex).use { page ->
                val (w, h) = pdfBitmapSize(page.width, page.height, width)
                val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                try {
                    bitmap.eraseColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    RenderedPdfPage(bitmap, document.pageCount)
                } catch (error: Throwable) { bitmap.recycle(); throw error }
            }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        CoroutineScope(Dispatchers.IO).launch {
            mutex.withLock { renderer?.close(); renderer = null }
        }
    }
}
