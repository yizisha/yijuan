package com.flowreader.app.util

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.provider.OpenableColumns
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Copies a user-picked `.ttf` / `.otf` into `filesDir/fonts/` for the reader to render with.
 *
 * Same contract as `ReaderBackgroundImporter`: the bytes are copied into app-private storage and
 * referenced by absolute path, so no long-lived `content://` permission is held and the picked file
 * may move or vanish afterwards. The file is parsed once with [Typeface.createFromFile] before it is
 * kept — a renamed `.txt` would otherwise be stored happily and then fail on every page render.
 */
@Singleton
class CustomFontImporter @Inject constructor(
    @ApplicationContext context: Context
) {
    private val appContext = context.applicationContext

    private val fontDir: File
        get() = File(appContext.filesDir, FONT_DIR)

    fun import(uri: Uri): Result<String> = runCatching {
        val displayName = queryDisplayName(uri)
        val extension = displayName
            ?.substringAfterLast('.', "")
            ?.lowercase()
            ?.takeIf { it in SUPPORTED_EXTENSIONS }
            ?: DEFAULT_EXTENSION

        if (!fontDir.exists() && !fontDir.mkdirs()) {
            error("无法创建字体目录")
        }
        val target = File(fontDir, "reader_font_${System.currentTimeMillis()}.$extension")
        try {
            appContext.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: error("无法读取所选文件")
            // Throws IllegalArgumentException for anything that is not a font.
            Typeface.createFromFile(target)
        } catch (e: Exception) {
            target.delete()
            throw e
        }
        pruneOldFonts(keep = target)
        target.absolutePath
    }

    /** Deletes the stored font, if any. Safe to call when none is set. */
    fun clear(path: String?) {
        val file = path?.let(::File) ?: return
        if (file.parentFile?.absolutePath == fontDir.absolutePath) {
            file.delete()
        }
    }

    /** Only one imported font is referenced at a time, so the previous import is dead weight. */
    private fun pruneOldFonts(keep: File) {
        fontDir.listFiles()?.forEach { file ->
            if (file.isFile && file.absolutePath != keep.absolutePath) {
                file.delete()
            }
        }
    }

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
    }.getOrNull()

    private companion object {
        const val FONT_DIR = "fonts"
        const val DEFAULT_EXTENSION = "ttf"
        val SUPPORTED_EXTENSIONS = setOf("ttf", "otf", "ttc")
    }
}
