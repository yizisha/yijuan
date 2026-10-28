package com.flowreader.app.util

import java.util.Locale

data class OfflineSpeechModel(
    val id: String,
    val title: String,
    val description: String,
    val bytes: Long,
    val expandedBytes: Long,
    val sha256: String,
    val directory: String,
    val modelFile: String,
    val kokoro: Boolean = false,
    val license: String
) {
    val fileName get() = "$directory.tar.bz2"
    val origin get() = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/$fileName"
    val sizeLabel get() = String.format(Locale.ROOT, "%.1f MiB", bytes / 1048576.0)
    val installedSizeLabel get() = String.format(Locale.ROOT, "%.1f MiB", expandedBytes / 1048576.0)
    val voiceIds get() = if (kokoro) (3..102).toList() else listOf(0)
}

enum class SpeechDownloadSource(val label: String, private val prefix: String) {
    GHFAST("镜像 · ghfast.top", "https://ghfast.top/"),
    GHPROXY("镜像 · gh-proxy.com", "https://gh-proxy.com/"),
    OFFICIAL("官方 · GitHub", "");

    fun url(model: OfflineSpeechModel) = prefix + model.origin
}

/** Only pinned publisher archives are accepted, including when using community mirrors. */
object OfflineSpeechCatalog {
    val models = listOf(
        OfflineSpeechModel(
            "xiaoya", "小雅 · 轻量中文", "单音色 · INT8 · 建议先试听小包", 14016124, 20933412,
            "eab027e194e70289233cf12308373611fa4e2e96ef2d97354ef433ab663d831f",
            "vits-piper-zh_CN-xiao_ya-medium-int8", "zh_CN-xiao_ya-medium.onnx",
            license = "模型卡标注训练数据限非商业用途；详见包内 MODEL_CARD。"
        ),
        OfflineSpeechModel(
            "chaowen", "超文 · 轻量中文", "单音色 · INT8 · 与小雅不同音色", 14011298, 20933252,
            "f5f7c8628427fbb259ea4b7ec1a9a822a0c04e3f267071f0abfa0610371d9e0c",
            "vits-piper-zh_CN-chaowen-medium-int8", "zh_CN-chaowen-medium.onnx",
            license = "模型卡标注数据集 CC0，基于小雅微调；详见包内 MODEL_CARD。"
        ),
        OfflineSpeechModel(
            "kokoro", "Kokoro · 多音色", "INT8 · 可切换中文音色 · 包较大", 147031220, 215321602,
            "a1e94694776049035c4f2c6529f003aaece993c76aae9a78995831c3c4dcafc6",
            "kokoro-int8-multi-lang-v1_1", "model.int8.onnx", kokoro = true,
            license = "Kokoro 模型 Apache 2.0；附带资源各遵循原许可。"
        )
    )
    fun find(id: String?) = models.firstOrNull { it.id == id }
}

/** Bound synthesis memory; keep punctuation and never cut a UTF-16 surrogate pair. */
object SpeechTextChunks {
    fun end(text: String, start: Int, limit: Int = 120): Int {
        require(start in 0..text.length && limit >= 2)
        var end = (start + limit).coerceAtMost(text.length)
        if (end < text.length && end > start && text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end--
        for (index in end - 1 downTo start) {
            if (text[index] in "。！？；.!?;\n") return index + 1
        }
        return end
    }
}
