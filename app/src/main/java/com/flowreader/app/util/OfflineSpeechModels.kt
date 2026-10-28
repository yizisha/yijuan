package com.flowreader.app.util

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class SpeechModelsState(
    val installed: Set<String> = emptySet(),
    val selected: String? = null,
    val voice: Int = 3,
    val rate: Float = 1f,
    val busyId: String? = null,
    val downloaded: Long = 0,
    val stage: String = "",
    val message: String? = null
)

/** Weights and device-local choices never enter the book DB, backups, or the base APK. */
@Singleton
class OfflineSpeechModels @Inject constructor(@ApplicationContext private val context: Context) {
    private val root = File(context.noBackupFilesDir, "offline-speech").apply { mkdirs() }
    private val choices = AtomicFile(File(root, "choices.json"))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writer = Mutex()
    private val installer = Mutex()
    private var work: Job? = null

    @Volatile private var connection: HttpURLConnection? = null
    private val _state = MutableStateFlow(readState())
    val state = _state.asStateFlow()

    private fun installed(model: OfflineSpeechModel): Boolean {
        val folder = folder(model)
        return runCatching {
            File(folder, ".complete").readText() == model.sha256 &&
                File(folder, model.modelFile).length() > 0 && File(folder, "tokens.txt").length() > 0 &&
                File(folder, if (model.kokoro) "voices.bin" else "lexicon.txt").length() > 0
        }.getOrDefault(false)
    }

    private fun readState(): SpeechModelsState {
        val data = runCatching { JSONObject(choices.openRead().bufferedReader().use { it.readText() }) }.getOrDefault(JSONObject())
        val installed = OfflineSpeechCatalog.models.filter(::installed).map { it.id }.toSet()
        return SpeechModelsState(
            installed = installed,
            selected = data.optString("selected").takeIf { it in installed },
            voice = data.optInt("voice", 3).coerceIn(3, 102),
            rate = data.optDouble("rate", 1.0).toFloat().takeIf { it.isFinite() }?.coerceIn(.5f, 2f) ?: 1f
        )
    }

    fun folder(model: OfflineSpeechModel) = File(root, model.id)

    suspend fun choose(id: String?, voice: Int = state.value.voice, rate: Float = state.value.rate) = withContext(Dispatchers.IO) {
        writer.withLock {
            require(id == null || OfflineSpeechCatalog.find(id)?.let(::installed) == true) { "请先下载完整语音包" }
            val json = JSONObject().put("selected", id.orEmpty()).put("voice", voice.coerceIn(3, 102)).put("rate", rate.coerceIn(.5f, 2f))
            val stream = choices.startWrite()
            try {
                stream.write(json.toString().toByteArray(Charsets.UTF_8))
                choices.finishWrite(stream)
            } catch (error: Exception) {
                choices.failWrite(stream)
                throw error
            }
            _state.update { it.copy(selected = id, voice = voice.coerceIn(3, 102), rate = rate.coerceIn(.5f, 2f), message = null) }
        }
    }

    fun download(model: OfflineSpeechModel, source: SpeechDownloadSource) = begin(model) { archive ->
        // Follow HTTPS redirects explicitly; do not downgrade or send private credentials.
        var address = source.url(model)
        var response: HttpURLConnection? = null
        for (attempt in 0..8) {
            currentCoroutineContext().ensureActive()
            val url = URL(address)
            require(url.protocol == "https" && url.userInfo == null)
            val candidate = (url.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 20_000
                readTimeout = 20_000
                setRequestProperty("User-Agent", "Yejian/0.3.7 model-download")
                setRequestProperty("Accept-Encoding", "identity")
            }
            connection = candidate
            val code = candidate.responseCode
            if (code in listOf(301, 302, 303, 307, 308)) {
                val location = candidate.getHeaderField("Location") ?: error("下载源缺少重定向地址")
                address = URL(url, location).toString()
                candidate.disconnect()
            } else {
                require(code == 200) { "下载源返回 HTTP $code，请切换下载源重试" }
                require(candidate.contentLengthLong == -1L || candidate.contentLengthLong == model.bytes) { "下载源返回的文件大小不匹配" }
                response = candidate
                break
            }
        }
        val active = response ?: error("下载源重定向过多")
        try {
            val jobContext = currentCoroutineContext()
            active.inputStream.use { input ->
                OfflineSpeechArchive.copy(input, archive, model.bytes, { jobContext.ensureActive() }) { count ->
                    _state.update { it.copy(downloaded = count) }
                }
            }
        } finally {
            active.disconnect()
            connection = null
        }
    }

    fun import(model: OfflineSpeechModel, uri: Uri) = begin(model) { archive ->
        val jobContext = currentCoroutineContext()
        (context.contentResolver.openInputStream(uri) ?: error("无法读取语音包")).use { input ->
            OfflineSpeechArchive.copy(input, archive, model.bytes, { jobContext.ensureActive() }) { count ->
                _state.update { it.copy(downloaded = count) }
            }
        }
    }

    @Synchronized
    internal fun begin(model: OfflineSpeechModel, fetch: suspend (File) -> Unit) {
        if (work?.isActive == true) return
        _state.update { it.copy(busyId = model.id, downloaded = 0, stage = "下载", message = null) }
        val operation = scope.launch(start = CoroutineStart.LAZY) {
            installer.withLock {
                val archive = File(root, "download-${UUID.randomUUID()}.partial")
                val staging = File(root, "staging-${UUID.randomUUID()}")
                try {
                    if (installed(model)) {
                        _state.update { it.copy(message = "语音包已下载，可选择使用") }
                        return@withLock
                    }
                    val interrupted = Regex("(?:download-[0-9a-f-]{36}\\.partial|staging-[0-9a-f-]{36})")
                    root.listFiles().orEmpty().filter { it.name.matches(interrupted) }.forEach { old ->
                        require(old.canonicalPath.startsWith(root.canonicalPath + File.separator))
                        require(old.deleteRecursively()) { "无法清理上次中断的模型下载" }
                    }
                    require(root.usableSpace > model.bytes + model.expandedBytes + 16 * 1024 * 1024) { "存储空间不足，请先释放空间" }
                    fetch(archive)
                    val jobContext = currentCoroutineContext()
                    _state.update { it.copy(stage = "校验") }
                    val validSize = archive.length() == model.bytes
                    val validHash = validSize && OfflineSpeechArchive.digest(archive) { jobContext.ensureActive() } == model.sha256
                    require(validHash) {
                        "语音包 SHA-256 校验失败，请切换下载源重试"
                    }
                    staging.mkdirs()
                    _state.update { it.copy(stage = "解压") }
                    OfflineSpeechArchive.extract(archive, staging, model) { jobContext.ensureActive() }
                    val extracted = File(staging, model.directory)
                    // Record per-file hashes for verification before native inference.
                    val proof = JSONObject()
                    extracted.walkTopDown().filter { it.isFile }.forEach { file ->
                        jobContext.ensureActive()
                        proof.put(
                            file.relativeTo(extracted).invariantSeparatorsPath,
                            OfflineSpeechArchive.digest(file) { jobContext.ensureActive() }
                        )
                    }
                    File(extracted, ".proof.json").writeText(proof.toString())
                    File(extracted, ".complete").writeText(model.sha256)
                    jobContext.ensureActive()
                    val target = folder(model)
                    // Never replace a working installation. Interrupted invalid directories are disposable model data only.
                    if (target.exists()) require(target.deleteRecursively()) { "无法清理不完整语音包" }
                    require(extracted.renameTo(target)) { "无法保存语音包" }
                    _state.update { it.copy(installed = it.installed + model.id, message = "下载完成，选择使用或试听") }
                } catch (error: Exception) {
                    val cancelled = error is CancellationException || !currentCoroutineContext().isActive
                    _state.update { it.copy(message = if (cancelled) "下载已取消" else error.localizedMessage ?: "语音包处理失败") }
                } finally {
                    connection?.disconnect()
                    connection = null
                    archive.delete()
                    staging.deleteRecursively()
                    _state.update { it.copy(busyId = null, stage = "") }
                }
            }
        }
        work = operation
        operation.invokeOnCompletion {
            if (work === operation) _state.update { it.copy(busyId = null, stage = "") }
        }
        operation.start()
    }

    fun cancelDownload() {
        work?.cancel()
        connection?.disconnect()
    }
    fun clearMessage() {
        _state.update { it.copy(message = null) }
    }

    suspend fun remove(model: OfflineSpeechModel) = withContext(Dispatchers.IO) {
        installer.withLock {
            writer.withLock {
                require(state.value.selected != model.id) { "请先切换到其他语音" }
                require(!folder(model).exists() || folder(model).deleteRecursively()) { "语音包删除失败" }
                _state.update { it.copy(installed = it.installed - model.id, message = "语音包已删除") }
            }
        }
    }

    internal fun verifyFiles(model: OfflineSpeechModel, check: () -> Unit) {
        require(installed(model)) { "语音包不完整，请删除后重新下载" }
        val directory = folder(model)
        val proof = JSONObject(File(directory, ".proof.json").readText())
        require(proof.length() in 1..2000)
        proof.keys().forEach { name ->
            check()
            val file = File(directory, name)
            require(file.canonicalPath.startsWith(directory.canonicalPath + File.separator))
            require(file.isFile && OfflineSpeechArchive.digest(file, check) == proof.getString(name)) { "语音文件损坏，请重新下载" }
        }
        require(proof.has(model.modelFile) && proof.has("tokens.txt"))
    }
}
