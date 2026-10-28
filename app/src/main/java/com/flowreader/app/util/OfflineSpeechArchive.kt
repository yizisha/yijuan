package com.flowreader.app.util

import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream

internal object OfflineSpeechArchive {
    fun digest(file: File, check: () -> Unit = {}): String {
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                check()
                val count = input.read(buffer)
                if (count < 0) break
                hash.update(buffer, 0, count)
            }
        }
        return hash.digest().joinToString("") { "%02x".format(it) }
    }

    fun copy(input: InputStream, target: File, maximum: Long, check: () -> Unit = {}, progress: (Long) -> Unit = {}): Long {
        var total = 0L
        target.outputStream().buffered().use { output ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                check()
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                require(total <= maximum) { "语音包超过目录中的大小限制" }
                output.write(buffer, 0, count)
                progress(total)
            }
        }
        return total
    }

    /** Extract only into a fresh staging directory. Reject links, devices and traversal. */
    fun extract(file: File, staging: File, model: OfflineSpeechModel, check: () -> Unit = {}) {
        require(staging.isDirectory && staging.list().orEmpty().isEmpty())
        val seen = HashSet<String>()
        var total = 0L
        BZip2CompressorInputStream(file.inputStream().buffered()).use { bzip ->
            TarArchiveInputStream(bzip).use { tar ->
                while (true) {
                    check()
                    val entry = tar.nextTarEntry ?: break
                    val name = entry.name.trimEnd('/')
                    require(name == model.directory || name.startsWith("${model.directory}/")) { "语音包根目录不匹配" }
                    require(name.split('/').none { it == ".." || it == "." } && '\\' !in name && ':' !in name && '\u0000' !in name)
                    require(seen.add(name) && seen.size <= 2000) { "重复或过多的语音文件" }
                    require(
                        entry.isDirectory || entry.isFile && !entry.isLink && !entry.isSymbolicLink && !entry.isSparse
                    ) { "语音包包含不支持的链接或特殊文件" }
                    val destination = File(staging, name)
                    require(destination.canonicalPath.startsWith(staging.canonicalPath + File.separator))
                    if (entry.isDirectory) {
                        require(destination.mkdirs() || destination.isDirectory)
                    } else {
                        require(entry.size >= 0 && entry.size <= model.expandedBytes - total)
                        destination.parentFile!!.mkdirs()
                        val copied = copy(tar, destination, entry.size, check)
                        require(copied == entry.size) { "语音文件截断" }
                        total += copied
                    }
                }
            }
        }
        require(total == model.expandedBytes) { "语音包解压大小不匹配" }
        val root = File(staging, model.directory)
        require(File(root, model.modelFile).isFile && File(root, "tokens.txt").isFile)
        require(
            if (model.kokoro) File(root, "voices.bin").isFile && File(root, "lexicon-zh.txt").isFile else File(root, "lexicon.txt").isFile
        )
    }
}
