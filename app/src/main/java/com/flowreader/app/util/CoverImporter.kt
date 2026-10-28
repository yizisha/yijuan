package com.flowreader.app.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Copies a user-picked image into `filesDir/covers/` and returns the stored path.
 *
 * Same contract as the other importers: the bytes are copied into app-private storage and
 * referenced by absolute path, so no long-lived `content://` permission is held and the picked file
 * may move afterwards. The image is downsampled to a cover-sized bitmap before it is written — a
 * 12MP photo would otherwise be decoded at full size on every shelf render.
 */
@Singleton
class CoverImporter @Inject constructor(
    @ApplicationContext context: Context
) {
    private val appContext = context.applicationContext

    private val coverDir: File
        get() = File(appContext.filesDir, COVER_DIR)

    fun import(uri: Uri): Result<String> = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        appContext.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, bounds)
        } ?: error("无法读取所选图片")
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) error("所选文件不是可解码的图片")

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight)
        }
        val bitmap = appContext.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, options)
        } ?: error("无法解码所选图片")

        try {
            if (!coverDir.exists() && !coverDir.mkdirs()) error("无法创建封面目录")
            val target = File(coverDir, "cover_${System.currentTimeMillis()}.webp")
            target.outputStream().use { output ->
                @Suppress("DEPRECATION")
                val ok = bitmap.compress(Bitmap.CompressFormat.WEBP, 90, output)
                if (!ok) error("无法写入封面")
            }
            target.absolutePath
        } finally {
            bitmap.recycle()
        }
    }

    /** Deletes a previously imported cover, if it lives in the covers directory. */
    fun clear(path: String?) {
        val file = path?.let(::File) ?: return
        if (file.parentFile?.absolutePath == coverDir.absolutePath) file.delete()
    }

    private fun sampleSize(width: Int, height: Int): Int {
        var sample = 1
        while (width / (sample * 2) >= MAX_EDGE_PX && height / (sample * 2) >= MAX_EDGE_PX) {
            sample *= 2
        }
        return sample
    }

    private companion object {
        const val COVER_DIR = "covers"
        const val MAX_EDGE_PX = 720
    }
}
