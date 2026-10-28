package com.flowreader.app.util

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.view.View
import com.flowreader.plugin.api.SpeechPlugin
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred

/** Native inference and release share one worker, preventing use-after-free during stop/switch. */
internal class OfflineSpeechPlugin(private val models: OfflineSpeechModels, private val model: OfflineSpeechModel) : SpeechPlugin {
    val modelId get() = model.id
    private val closed = CompletableDeferred<Unit>()
    private val worker = Executors.newSingleThreadExecutor()
    private val generation = AtomicLong()
    private val output = AtomicReference<AudioTrack?>()

    @Volatile private var listener: SpeechPlugin.Listener? = null

    @Volatile private var ready = false

    @Volatile private var released = false

    @Volatile private var playing = false

    @Volatile private var text = ""

    @Volatile private var cursor = 0
    private var native: OfflineTts? = null
    val canResume get() = text.isNotBlank() && cursor < text.length

    override fun attach(context: Context, listener: SpeechPlugin.Listener) {
        this.listener = listener
        listener.onState(false, false, null)
    }

    override fun speak(text: String) {
        stop()
        this.text = text
        cursor = 0
        resume()
    }

    fun resume() {
        if (released || playing || text.isBlank()) return
        val version = generation.incrementAndGet()
        playing = true
        if (cursor >= text.length) cursor = 0
        listener?.onState(false, true, null)
        worker.execute {
            var track: AudioTrack? = null
            try {
                if (native == null) {
                    models.verifyFiles(model) { check(version == generation.get() && !released) }
                    val folder = models.folder(model)
                    fun path(name: String) = File(folder, name).absolutePath
                    val config = OfflineTtsConfig(
                        model = OfflineTtsModelConfig(
                            vits = if (model.kokoro) {
                                OfflineTtsVitsModelConfig()
                            } else {
                                OfflineTtsVitsModelConfig(
                                    model = path(model.modelFile),
                                    tokens = path("tokens.txt"),
                                    lexicon = path("lexicon.txt")
                                )
                            },
                            kokoro = if (!model.kokoro) {
                                OfflineTtsKokoroModelConfig()
                            } else {
                                OfflineTtsKokoroModelConfig(
                                    model = path(model.modelFile),
                                    tokens = path("tokens.txt"),
                                    voices = path("voices.bin"),
                                    dataDir = path("espeak-ng-data"),
                                    lexicon = path("lexicon-zh.txt") + "," + path("lexicon-us-en.txt"),
                                    // Let the supplied Chinese/English lexicons handle mixed text
                                    // without forcing an espeak language override for Latin text.
                                    lang = ""
                                )
                            },
                            numThreads = if (model.kokoro) 4 else 2,
                            provider = "cpu",
                            debug = false
                        ),
                        ruleFsts = (
                            if (model.kokoro) {
                                listOf("phone-zh.fst", "date-zh.fst", "number-zh.fst")
                            } else {
                                listOf("phone.fst", "date.fst", "number.fst")
                            }
                            ).joinToString(",") { path(it) }
                    )
                    native = OfflineTts(config = config)
                    ready = true
                }
                if (version != generation.get()) return@execute
                listener?.onState(false, true, null)
                val engine = native!!
                val format = AudioFormat.Builder().setSampleRate(engine.sampleRate())
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()
                val minimum = AudioTrack.getMinBufferSize(engine.sampleRate(), AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
                require(minimum > 0) { "当前音频设备不支持语音输出" }
                track = AudioTrack.Builder().setAudioFormat(format)
                    .setAudioAttributes(
                        AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
                    )
                    .setBufferSizeInBytes(minimum * 2).setTransferMode(AudioTrack.MODE_STREAM).build()
                output.set(track)
                check(track.state == AudioTrack.STATE_INITIALIZED) { "无法初始化媒体音频输出" }
                val player = track
                var started = false
                var audible = false
                while (cursor < text.length && version == generation.get()) {
                    // Short first sentence reduces the silent wait on slower CPUs. Later chunks
                    // remain bounded and native callbacks stream each completed sentence.
                    val end = SpeechTextChunks.end(text, cursor, if (!started) 24 else 60)
                    val preferences = models.state.value
                    val voice = if (model.kokoro) preferences.voice.coerceIn(3, engine.numSpeakers() - 1) else 0
                    var outputError: Throwable? = null
                    val callback = SpeechPcmCallback receive@{ samples ->
                        // Never throw across a JNI callback. Cancellation stops native generation
                        // at its next sentence boundary, and output errors are handled on the worker.
                        try {
                            if (version != generation.get()) return@receive 0
                            if (!audible) audible = samples.any { kotlin.math.abs(it) > .0001f }
                            if (!started && samples.isNotEmpty()) {
                                player.play()
                                started = true
                                listener?.onState(true, true, null)
                            }
                            var offset = 0
                            while (offset < samples.size && version == generation.get()) {
                                val count = player.write(
                                    samples,
                                    offset,
                                    (samples.size - offset).coerceAtMost(2048),
                                    AudioTrack.WRITE_BLOCKING
                                )
                                check(count > 0) { "语音输出失败（$count）" }
                                offset += count
                            }
                            if (version == generation.get()) 1 else 0
                        } catch (error: Throwable) {
                            outputError = error
                            0
                        }
                    }
                    val audio = engine.generateWithCallback(text.substring(cursor, end), voice, preferences.rate, callback)
                    if (version != generation.get()) return@execute
                    outputError?.let { throw it }
                    // Punctuation-only or whitespace chunks can produce no samples. Skip those
                    // rather than failing a whole chapter before its first real sentence.
                    check(audio.samples.isNotEmpty() || text.substring(cursor, end).none { it.isLetterOrDigit() }) { "此段文字无法合成语音" }
                    if (version == generation.get()) cursor = end
                }
                // Drain the short audio buffer before releasing the last sentence.
                if (version == generation.get()) {
                    check(started && audible) { "模型没有生成有效声音，请切换音色或模型重试" }
                    val pending = track.bufferSizeInFrames * 1000L / engine.sampleRate()
                    Thread.sleep(pending.coerceIn(0, 500))
                    playing = false
                    listener?.onState(true, false, null)
                }
            } catch (error: Throwable) {
                if (version == generation.get() && !released) {
                    playing = false
                    listener?.onState(ready, false, "离线朗读失败：${error.localizedMessage ?: error.javaClass.simpleName}")
                }
            } finally {
                output.compareAndSet(track, null)
                runCatching { track?.stop() }
                runCatching { track?.release() }
            }
        }
    }

    override fun pause() {
        generation.incrementAndGet()
        playing = false
        runCatching {
            output.get()?.pause()
            output.get()?.flush()
        }
        listener?.onState(ready, false, null)
    }

    override fun stop() {
        pause()
        text = ""
        cursor = 0
        listener?.onState(ready, false, null)
    }
    override fun shutdown() {
        if (released) return
        released = true
        pause()
        listener = null
        worker.execute {
            try {
                native?.release()
                native = null
                ready = false
            } finally {
                closed.complete(Unit)
            }
        }
        worker.shutdown()
    }
    suspend fun awaitShutdown() {
        closed.await()
    }
    override fun createControls(context: Context, foreground: Int, secondary: Int, accent: Int, requestContent: Runnable): View =
        error("离线语音使用公共 Compose 控件")
    override fun detachControls() = Unit
}

/** JNI looks up invoke(float[]): Integer; an invokedynamic lambda only has invoke(Object). */
internal class SpeechPcmCallback(private val receive: (FloatArray) -> Int) : (FloatArray) -> Int {
    override fun invoke(samples: FloatArray): Int = receive(samples)
}
