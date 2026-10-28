package com.flowreader.app.util

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature
import java.util.Properties
import java.util.zip.ZipInputStream

data class VerifiedSpeechPlugin(val version: String, val dex: ByteArray, val digest: String)

/** Authenticate every byte before a class loader is allowed to see the downloaded code. */
object SpeechPluginArchive {
    const val MAX_BYTES = 2 * 1024 * 1024
    const val ENTRY_CLASS = "com.flowreader.plugin.tts.SystemSpeechPlugin"
    private val names = setOf("plugin.properties", "classes.dex", "signature.bin")

    fun readCapped(input: InputStream, limit: Int = MAX_BYTES, progress: (Int) -> Unit = {}): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val size = input.read(buffer)
            if (size < 0) break
            check(output.size() + size <= limit) { "插件文件超过允许大小" }
            output.write(buffer, 0, size)
            progress(output.size())
        }
        return output.toByteArray()
    }

    fun verify(bytes: ByteArray, trustedKeys: List<PublicKey>): VerifiedSpeechPlugin {
        require(bytes.size <= MAX_BYTES) { "插件文件过大" }
        val entries = mutableMapOf<String, ByteArray>()
        var expanded = 0
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                require(entry.name in names && !entry.isDirectory && entry.name !in entries) { "插件包含异常或重复文件" }
                val data = readCapped(zip, MAX_BYTES - expanded)
                expanded += data.size
                entries[entry.name] = data
            }
        }
        require(entries.keys == names) { "插件文件不完整" }
        val descriptor = entries.getValue("plugin.properties")
        val dex = entries.getValue("classes.dex")
        val signature = entries.getValue("signature.bin")
        require(descriptor.size <= 4096 && signature.size <= 8192) { "插件描述或签名异常" }
        val authenticated = ByteArrayOutputStream().also { output ->
            DataOutputStream(output).use {
                it.writeInt(descriptor.size)
                it.write(descriptor)
                it.writeInt(dex.size)
                it.write(dex)
            }
        }.toByteArray()
        require(trustedKeys.any { key ->
            runCatching {
                Signature.getInstance("SHA256withRSA").run {
                    initVerify(key)
                    update(authenticated)
                    verify(signature)
                }
            }.getOrDefault(false)
        }) { "插件签名不匹配，请使用与一卷同一发布者提供的插件" }
        val properties = Properties().apply { load(ByteArrayInputStream(descriptor)) }
        require(properties.getProperty("id") == "system-tts") { "不是一卷听书插件" }
        require(properties.getProperty("api") == "1") { "插件接口不兼容，请下载对应版本" }
        require(properties.getProperty("entry") == ENTRY_CLASS) { "插件入口不兼容" }
        val version = properties.getProperty("version").orEmpty()
        require(version.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+"))) { "插件版本无效" }
        require(dex.size >= 8 && dex.copyOfRange(0, 4).contentEquals(byteArrayOf(100, 101, 120, 10))) { "插件代码格式无效" }
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        return VerifiedSpeechPlugin(version, dex, digest)
    }
}
