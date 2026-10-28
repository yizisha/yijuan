package com.flowreader.app.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import java.io.File
import java.io.FileOutputStream
import java.util.Random

/**
 * Generates the built-in classic paper textures (iReader style presets):
 * - Parchment (经典羊皮纸): warm aged paper with subtle fiber specks and edge vignette
 * - Kraft (牛皮纸): warm rustic brownish-yellow paper with wood pulp fibers
 * - Bean Green (豆沙绿): anti-fatigue soothing pastel green with delicate paper grain
 * - Xuan Paper (宣纸微纹理): traditional Chinese Xuan paper with delicate organic fibers
 */
object PaperTextureGenerator {

    enum class Preset(val fileName: String, val displayName: String) {
        PARCHMENT("preset_parchment.png", "羊皮纸"),
        KRAFT("preset_kraft.png", "牛皮纸"),
        BEAN_GREEN("preset_bean_green.png", "豆沙绿"),
        XUAN("preset_xuan.png", "宣纸")
    }

    /**
     * Returns the cached texture file, generating it on first use.
     *
     * Generation draws thousands of strokes and PNG-compresses a 720×1280 bitmap, so it must never
     * run on the main thread — [warmUp] pre-generates every preset in the background, and callers
     * that may hit the cold path (the settings sheet) do it from `Dispatchers.IO` too. The lock
     * keeps a warm-up racing with a tap from producing a half-written file.
     */
    @Synchronized
    fun getPresetFile(context: Context, preset: Preset): File {
        val dir = File(context.filesDir, "backgrounds")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        val file = File(dir, preset.fileName)
        if (!file.exists() || file.length() == 0L) {
            generateTexture(file, preset)
        }
        return file
    }

    /** Generates any missing preset texture. Safe to call on a background dispatcher at startup. */
    fun warmUp(context: Context) {
        val appContext = context.applicationContext
        Preset.entries.forEach { preset ->
            runCatching { getPresetFile(appContext, preset) }
        }
    }

    fun isPresetPath(path: String?, preset: Preset): Boolean {
        if (path == null) return false
        return path.endsWith(preset.fileName)
    }

    private fun generateTexture(targetFile: File, preset: Preset) {
        val width = 720
        val height = 1280
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val random = Random(preset.ordinal.toLong() + 42L)

        when (preset) {
            Preset.PARCHMENT -> renderParchment(canvas, width, height, random)
            Preset.KRAFT -> renderKraft(canvas, width, height, random)
            Preset.BEAN_GREEN -> renderBeanGreen(canvas, width, height, random)
            Preset.XUAN -> renderXuan(canvas, width, height, random)
        }

        FileOutputStream(targetFile).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 90, out)
        }
        bitmap.recycle()
    }

    private fun renderParchment(canvas: Canvas, width: Int, height: Int, random: Random) {
        // Base antique paper color
        canvas.drawColor(Color.rgb(0xF4, 0xEA, 0xD2))

        // Vignette on edges
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val vignette = RadialGradient(
            width / 2f,
            height / 2f,
            maxOf(width, height) * 0.7f,
            Color.TRANSPARENT,
            Color.argb(0x28, 0x96, 0x76, 0x48),
            Shader.TileMode.CLAMP
        )
        paint.shader = vignette
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        paint.shader = null

        // Organic flecks and aged paper fiber
        paint.strokeCap = Paint.Cap.ROUND
        for (i in 0 until 4000) {
            val x = random.nextFloat() * width
            val y = random.nextFloat() * height
            val alpha = random.nextInt(18) + 6
            paint.color = Color.argb(alpha, 0x6E, 0x50, 0x2A)
            paint.strokeWidth = random.nextFloat() * 1.5f + 0.5f
            val len = random.nextFloat() * 4f + 1f
            val angle = random.nextFloat() * Math.PI.toFloat() * 2
            canvas.drawLine(x, y, x + (Math.cos(angle.toDouble()) * len).toFloat(), y + (Math.sin(angle.toDouble()) * len).toFloat(), paint)
        }
    }

    private fun renderKraft(canvas: Canvas, width: Int, height: Int, random: Random) {
        // Warm kraft base
        canvas.drawColor(Color.rgb(0xD6, 0xBE, 0x9B))

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.strokeCap = Paint.Cap.ROUND

        // Wood pulp grains
        for (i in 0 until 5000) {
            val x = random.nextFloat() * width
            val y = random.nextFloat() * height
            val isDark = random.nextBoolean()
            val alpha = random.nextInt(22) + 8
            if (isDark) {
                paint.color = Color.argb(alpha, 0x7A, 0x54, 0x2C)
            } else {
                paint.color = Color.argb(alpha, 0xF5, 0xE6, 0xD0)
            }
            paint.strokeWidth = random.nextFloat() * 2f + 0.6f
            val len = random.nextFloat() * 7f + 2f
            val angle = (random.nextFloat() - 0.5f) * 0.8f // mainly horizontal fiber lines
            canvas.drawLine(x, y, x + (Math.cos(angle.toDouble()) * len).toFloat(), y + (Math.sin(angle.toDouble()) * len).toFloat(), paint)
        }
    }

    private fun renderBeanGreen(canvas: Canvas, width: Int, height: Int, random: Random) {
        // Soothing bean green base
        canvas.drawColor(Color.rgb(0xCD, 0xE7, 0xCF))

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.strokeCap = Paint.Cap.ROUND

        // Gentle matte paper grain
        for (i in 0 until 3500) {
            val x = random.nextFloat() * width
            val y = random.nextFloat() * height
            val isLight = random.nextBoolean()
            val alpha = random.nextInt(14) + 4
            if (isLight) {
                paint.color = Color.argb(alpha, 0xFA, 0xFF, 0xFA)
            } else {
                paint.color = Color.argb(alpha, 0x8A, 0xB0, 0x8E)
            }
            paint.strokeWidth = random.nextFloat() * 1.2f + 0.6f
            canvas.drawPoint(x, y, paint)
        }
    }

    private fun renderXuan(canvas: Canvas, width: Int, height: Int, random: Random) {
        // Classic Xuan off-white base
        canvas.drawColor(Color.rgb(0xF8, 0xF6, 0xF1))

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.strokeCap = Paint.Cap.ROUND

        // Delicate floating plant fiber shreds (云龙宣纸纤维)
        for (i in 0 until 800) {
            val x = random.nextFloat() * width
            val y = random.nextFloat() * height
            val alpha = random.nextInt(26) + 10
            paint.color = Color.argb(alpha, 0x82, 0x76, 0x66)
            paint.strokeWidth = random.nextFloat() * 1.4f + 0.4f
            val len = random.nextFloat() * 16f + 4f
            val angle = random.nextFloat() * Math.PI.toFloat() * 2
            canvas.drawLine(x, y, x + (Math.cos(angle.toDouble()) * len).toFloat(), y + (Math.sin(angle.toDouble()) * len).toFloat(), paint)
        }

        // Cloud pulp soft flecks
        for (i in 0 until 2000) {
            val x = random.nextFloat() * width
            val y = random.nextFloat() * height
            paint.color = Color.argb(random.nextInt(12) + 3, 0xA0, 0x98, 0x8E)
            paint.strokeWidth = random.nextFloat() * 2f + 0.5f
            canvas.drawPoint(x, y, paint)
        }
    }
}
