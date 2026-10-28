package com.flowreader.app

import android.app.ActivityManager
import android.app.Application
import android.util.Log
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.flowreader.app.util.PaperTextureGenerator
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

@HiltAndroidApp
class FlowReaderApplication : Application(), ImageLoaderFactory {
    @javax.inject.Inject lateinit var automaticBackup: com.flowreader.app.util.AutomaticBackup

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 图片解码专用线程池：**并发上限 2**（主计划 11.2）。
     *
     * 为什么单独限流：一张 EPUB 插图解码后可能占几十 MB，多张同时解码会直接触发 OOM，
     * 而"解码失败"在渲染层表现为空白 —— 用户看到的是"图片丢了"。限到 2 条之后，
     * 峰值与并发都可预期；代价是首屏多图会稍微排队，这是计划书明确选择的取舍。
     */
    private val decodeExecutor = Executors.newFixedThreadPool(MAX_CONCURRENT_IMAGE_DECODES) { runnable ->
        Thread(runnable, "flowreader-image-decode")
    }

    override fun onCreate() {
        super.onCreate()
        // The four built-in paper textures are drawn pixel by pixel and PNG-compressed. Doing that
        // lazily on the first tap froze the settings sheet for as long as it took to render, so it
        // happens once, off the main thread, while the user is still on the shelf.
        applicationScope.launch {
            PaperTextureGenerator.warmUp(this@FlowReaderApplication)
        }
        applicationScope.launch { runCatching { automaticBackup.schedule() }.onFailure { Log.w(TAG, "Automatic backup scheduling failed", it) } }
    }

    /**
     * 应用级 Coil 加载器：给**位图内存**一个有上限的预算，并把解码并发限制在 2。
     *
     * 预算是 `min(64MiB, memoryClass / 8)`（11.2 原文），而不是 Coil 默认的
     * "可用内存的某个百分比"：后者在中低端机上会随系统压力漂移，无法作为验收口径。
     * 解码尺寸仍由 Composable 的布局约束决定（`AsyncImage` 按目标视口采样），
     * 因此**不会**把整本大图按原尺寸解进内存。
     *
     * SVG（schema v3 批次）：注册 [SvgDecoder] 让 EPUB 里的静态 `image/svg+xml`
     * 可以像位图一样走 `AsyncImage` 渲染。安全边界依赖 androidsvg 的**纯静态渲染**：
     * 没有 JS 引擎（`<script>` 不执行），也不提供 `SVGExternalFileResolver`
     * （外链资源一律解析失败，**不会**发起网络请求）。见 0.2.14 报告的 spike 一节。
     */
    override fun newImageLoader(): ImageLoader {
        val budgetBytes = imageMemoryBudgetBytes()
        Log.i(
            TAG,
            "Coil image loader: memoryCache=${budgetBytes / (1024 * 1024)}MiB, " +
                "decodeConcurrency=$MAX_CONCURRENT_IMAGE_DECODES"
        )
        return ImageLoader.Builder(this)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizeBytes(budgetBytes)
                    .build()
            }
            // 磁盘缓存只用于网络图（OPDS 封面）；本地文件不经过它。
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(DISK_CACHE_BYTES)
                    .build()
            }
            .components {
                add(coil.decode.SvgDecoder.Factory())
            }
            .dispatcher(decodeExecutor.asCoroutineDispatcher())
            .build()
    }

    /**
     * `min(64MiB, memoryClass / 8)`（主计划 11.2）。
     *
     * 返回 Int 是因为 Coil 2 的 `MemoryCache.Builder.maxSizeBytes` 只接受 Int
     * （`DiskCache` 才是 Long）；64MiB 远在 Int 范围内。
     */
    private fun imageMemoryBudgetBytes(): Int {
        val memoryClassMb = runCatching {
            val manager = getSystemService(ActivityManager::class.java)
            manager?.memoryClass ?: 0
        }.getOrDefault(0)
        val eighthOfHeap = if (memoryClassMb > 0) memoryClassMb * 1024 * 1024 / 8 else 0
        val budget = if (eighthOfHeap > 0) minOf(MAX_IMAGE_MEMORY_BYTES, eighthOfHeap) else MAX_IMAGE_MEMORY_BYTES
        return budget.coerceAtLeast(MIN_IMAGE_MEMORY_BYTES)
    }

    private companion object {
        const val TAG = "FlowReaderApp"

        /** 并发解码上限（主计划 11.2 第 4 条）。 */
        const val MAX_CONCURRENT_IMAGE_DECODES = 2

        const val MAX_IMAGE_MEMORY_BYTES = 64 * 1024 * 1024

        /** 即使 heap 很小也不要低于这个值，否则连一屏插图都放不下，会反复解码。 */
        const val MIN_IMAGE_MEMORY_BYTES = 8 * 1024 * 1024

        const val DISK_CACHE_BYTES = 64L * 1024 * 1024
    }
}
