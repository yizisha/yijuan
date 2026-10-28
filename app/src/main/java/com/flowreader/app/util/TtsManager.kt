package com.flowreader.app.util

import android.content.Context
import android.view.View
import com.flowreader.app.domain.repository.SettingsRepository
import com.flowreader.plugin.api.SpeechPlugin
import com.flowreader.plugin.tts.SystemSpeechPlugin
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class TtsState(
    val isReady: Boolean = false,
    val isSpeaking: Boolean = false,
    val error: String? = null,
    val canResume: Boolean = false
)

/** Bundled speech, opt-in. No engine is constructed until an enabled user requests playback. */
@Singleton
class TtsManager internal constructor(
    context: Context,
    private val settings: SettingsRepository,
    val models: OfflineSpeechModels? = null,
    private val factory: () -> SpeechPlugin
) {
    constructor(context: Context, settings: SettingsRepository) :
        this(context, settings, factory = { SystemSpeechPlugin() })

    @Inject constructor(@ApplicationContext context: Context, settings: SettingsRepository, models: OfflineSpeechModels) :
        this(context, settings, models, { SystemSpeechPlugin() })

    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val writer = Mutex()
    private val _state = MutableStateFlow(TtsState())
    val state: StateFlow<TtsState> = _state.asStateFlow()
    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()
    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()
    private val _saving = MutableStateFlow(false)
    val saving: StateFlow<Boolean> = _saving.asStateFlow()
    private val _feedback = MutableStateFlow<String?>(null)
    val feedback: StateFlow<String?> = _feedback.asStateFlow()
    private var engine: SpeechPlugin? = null
    private var controls: View? = null
    private var generation = 0L
    private val retiring = CopyOnWriteArrayList<OfflineSpeechPlugin>()

    init {
        scope.launch {
            settings.appSettings.map { it.speechEnabled }.distinctUntilChanged()
                .catch {
                    _loaded.value = true
                    _feedback.value = "听书设置加载失败，请重试"
                }
                .collect {
                    _enabled.value = it
                    _loaded.value = true
                    if (!it) shutdown()
                }
        }
    }

    suspend fun setEnabled(value: Boolean): Result<Unit> = writer.withLock {
        _saving.value = true
        _feedback.value = null
        try {
            settings.updateSpeechEnabled(value)
            _enabled.value = value
            if (!value) shutdown()
            Result.success(Unit)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            _feedback.value = "听书设置保存失败：${error.localizedMessage ?: "未知错误"}"
            Result.failure(error)
        } finally {
            _saving.value = false
        }
    }

    fun speak(text: String) {
        if (text.isBlank()) return
        try {
            activeEngine()?.speak(text)
        } catch (error: Exception) {
            shutdown()
            _state.value = TtsState(error = "听书启动失败：${error.localizedMessage ?: "未知错误"}")
        }
    }

    private fun activeEngine(): SpeechPlugin? {
        if (!_enabled.value) {
            _state.value = TtsState(error = "请先在设置 → 应用中开启听书")
            return null
        }
        engine?.let { return it }
        val activeGeneration = ++generation
        val selected = OfflineSpeechCatalog.find(models?.state?.value?.selected)
        return (if (selected == null) factory() else OfflineSpeechPlugin(models!!, selected)).also {
            engine = it
            try {
                it.attach(app) { ready, speaking, error ->
                    scope.launch {
                        if (activeGeneration == generation && _enabled.value) {
                            _state.value = TtsState(ready, speaking, error, (engine as? OfflineSpeechPlugin)?.canResume == true)
                        }
                    }
                }
            } catch (error: Exception) {
                shutdown()
                throw error
            }
        }
    }

    fun createControls(context: Context, foreground: Int, secondary: Int, accent: Int, requestContent: Runnable): View? =
        activeEngine()?.createControls(context, foreground, secondary, accent, requestContent)?.also { controls = it }
    fun detachControls(view: View) {
        if (view === controls) {
            engine?.detachControls()
            controls = null
        }
    }
    fun pause() {
        engine?.pause()
        _state.update { it.copy(isSpeaking = false) }
    }
    fun resumeOffline() {
        (activeEngine() as? OfflineSpeechPlugin)?.resume()
    }
    suspend fun selectModel(id: String?) {
        if (models?.state?.value?.selected != id) shutdown()
        _feedback.value = null
        try {
            models?.choose(id)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            _feedback.value = "语音选择保存失败：${error.localizedMessage}"
        }
    }
    suspend fun setOfflineVoice(voice: Int, rate: Float) {
        _feedback.value = null
        try {
            models?.let { it.choose(it.state.value.selected, voice, rate) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            _feedback.value = "语音设置保存失败：${error.localizedMessage}"
        }
    }
    suspend fun removeModel(model: OfflineSpeechModel) {
        _feedback.value = null
        try {
            if (models?.state?.value?.selected == model.id) selectModel(null)
            retiring.filter { it.modelId == model.id }.forEach { it.awaitShutdown() }
            models?.remove(model)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            _feedback.value = "语音包删除失败：${error.localizedMessage}"
        }
    }
    fun stop() {
        engine?.stop()
        _state.update { it.copy(isSpeaking = false, canResume = false) }
    }
    fun shutdown() {
        generation++
        (engine as? OfflineSpeechPlugin)?.let { old ->
            retiring += old
            scope.launch {
                old.awaitShutdown()
                retiring.remove(old)
            }
        }
        runCatching { engine?.shutdown() }
        engine = null
        controls = null
        _state.value = TtsState()
    }
    internal fun dispose() {
        shutdown()
        scope.cancel()
    }
}
