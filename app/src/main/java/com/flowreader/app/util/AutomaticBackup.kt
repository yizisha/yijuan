package com.flowreader.app.util

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.documentfile.provider.DocumentFile
import com.flowreader.app.data.repository.BackupArchive
import com.flowreader.app.data.repository.dataStore
import com.flowreader.app.domain.repository.BackupRepository
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class AutomaticBackupState(
    val directory: String? = null,
    val enabled: Boolean = false,
    val keep: Int = 3,
    val lastSuccess: Long = 0,
    val error: String? = null,
    val running: Boolean = false
)

/** 只操作用户选定目录下带本功能专用文件名的备份。系统任务无需网络或新依赖。 */
@Singleton
class AutomaticBackup @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: BackupRepository
) {
    private val mutex = Mutex()
    private val running = MutableStateFlow(false)
    val state: Flow<AutomaticBackupState> = combine(
        context.dataStore.data.catch { error ->
            if (error is CancellationException) throw error
            emit(mutablePreferencesOf(ERROR to "自动备份设置读取失败，请重试"))
        },
        running
    ) { prefs, busy ->
        AutomaticBackupState(
            prefs[DIRECTORY],
            prefs[ENABLED] ?: false,
            prefs[KEEP] ?: 3,
            prefs[LAST_SUCCESS] ?: 0,
            prefs[ERROR],
            busy
        )
    }

    suspend fun chooseDirectory(uri: Uri) {
        withContext(Dispatchers.IO) {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            val directory = DocumentFile.fromTreeUri(context, uri) ?: error("无法访问所选目录")
            require(directory.isDirectory && directory.canWrite()) { "目录无法写入" }
            context.dataStore.edit {
                it[DIRECTORY] = uri.toString()
                it.remove(ERROR)
            }
            schedule()
        }
    }

    suspend fun setEnabled(enabled: Boolean) {
        require(!enabled || state.first().directory != null) { "请先选择备份目录" }
        context.dataStore.edit {
            it[ENABLED] = enabled
            it.remove(ERROR)
        }
        schedule()
    }

    suspend fun setKeep(keep: Int) {
        require(keep in listOf(3, 5, 10))
        context.dataStore.edit { it[KEEP] = keep }
    }

    suspend fun schedule() {
        val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
        if (!state.first().enabled) {
            scheduler.cancel(JOB_ID)
            return
        }
        val job = JobInfo.Builder(JOB_ID, ComponentName(context, BackupJobService::class.java))
            .setPeriodic(INTERVAL).setRequiresBatteryNotLow(true).build()
        // 重启后应用启动重新注册；不申请额外的开机广播权限。
        if (scheduler.getPendingJob(JOB_ID) == null && scheduler.schedule(job) != JobScheduler.RESULT_SUCCESS) {
            context.dataStore.edit { it[ERROR] = "系统未接受自动备份任务，可使用立即备份" }
        }
    }

    suspend fun run(dueOnly: Boolean = false): Result<Unit> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val current = state.first()
            if (dueOnly && (!current.enabled || current.lastSuccess > 0 && System.currentTimeMillis() - current.lastSuccess < INTERVAL)) {
                return@withLock Result.success(
                    Unit
                )
            }
            running.value = true
            var target: DocumentFile? = null
            val temporary = File(context.cacheDir, "auto-backup-${UUID.randomUUID()}.zip")
            try {
                val tree = current.directory?.let { DocumentFile.fromTreeUri(context, Uri.parse(it)) } ?: error("请先选择备份目录")
                require(tree.isDirectory && tree.canWrite()) { "备份目录权限失效，请重新选择" }
                val folder = tree.findFile(FOLDER) ?: tree.createDirectory(FOLDER) ?: error("无法创建备份文件夹")
                require(folder.isDirectory && folder.canWrite())
                repository.exportDataToFile(temporary).getOrThrow()
                val digest = BackupArchive.digest(temporary)
                val name = "yejian-auto-v1-${System.currentTimeMillis()}-${UUID.randomUUID()}.zip"
                target = folder.createFile("application/octet-stream", "$name.partial") ?: error("无法创建自动备份文件")
                val output = context.contentResolver.openOutputStream(target.uri, "wt") ?: error("无法写入自动备份")
                temporary.inputStream().use { input ->
                    output.use { out ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            out.write(buffer, 0, count)
                        }
                    }
                }
                // 回读验证 SAF provider 实际保存的字节，只有成功完成才更新最后成功时间。
                val hash = java.security.MessageDigest.getInstance("SHA-256")
                val input = context.contentResolver.openInputStream(target.uri) ?: error("无法校验自动备份")
                input.use { stream ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = stream.read(buffer)
                        if (count < 0) break
                        hash.update(buffer, 0, count)
                    }
                }
                require(hash.digest().joinToString("") { "%02x".format(it) } == digest) { "自动备份回读校验失败" }
                require(target.renameTo(name)) { "无法完成自动备份文件，请更换目录" }
                context.dataStore.edit {
                    it[LAST_SUCCESS] = System.currentTimeMillis()
                    it.remove(ERROR)
                }
                target = null
                val stale = folder.listFiles().filter { it.isFile && it.name?.matches(FILE_PATTERN) == true }
                    .sortedByDescending { it.name }.drop(current.keep)
                if (stale.any { !it.delete() }) context.dataStore.edit { it[ERROR] = "备份已成功，但部分旧备份未能清理" }
                Result.success(Unit)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                context.dataStore.edit { it[ERROR] = error.message ?: "自动备份失败" }
                Result.failure(error)
            } finally {
                target?.delete()
                temporary.delete()
                running.value = false
            }
        }
    }

    companion object {
        const val INTERVAL = 24L * 60 * 60 * 1000
        internal const val JOB_ID = 5702
        internal const val FOLDER = "页间自动备份"
        internal val FILE_PATTERN = Regex("yejian-auto-v1-[0-9]{13}-[a-f0-9-]{36}\\.zip")
        private val DIRECTORY = stringPreferencesKey("auto_backup_directory")
        private val ENABLED = booleanPreferencesKey("auto_backup_enabled")
        private val KEEP = intPreferencesKey("auto_backup_keep")
        private val LAST_SUCCESS = longPreferencesKey("auto_backup_last_success")
        private val ERROR = stringPreferencesKey("auto_backup_error")
    }
}

@AndroidEntryPoint
class BackupJobService : JobService() {
    @Inject lateinit var automaticBackup: AutomaticBackup
    private var task: Job? = null
    override fun onStartJob(params: JobParameters): Boolean {
        task = CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            val result = automaticBackup.run(dueOnly = true)
            jobFinished(params, result.isFailure)
        }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean {
        task?.cancel()
        task = null
        return true
    }
    override fun onDestroy() {
        task?.cancel()
        super.onDestroy()
    }
}
