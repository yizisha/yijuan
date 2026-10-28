package com.flowreader.app.util.epub

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.CRC32
import java.util.zip.Deflater

/**
 * 最小 EPUB 容器构建器（主计划 14.1：夹具必须真实有效，不使用版权样书）。
 *
 * 放在 **main** 而不是 test 源集：单元测试与真机 instrumentation 测试（`app/src/androidTest`）
 * 必须用**同一份字节**验证，否则"真机验过的"与"单测验过的"就不是同一个夹具。
 * 它只依赖 JDK 的 ZIP/CRC 实现，不引用任何 Android 类型，因此放进 main 不引入运行时成本。
 *
 * 为什么不用 `ZipOutputStream` 直接写：`ZipOutputStream` 会拒绝同名条目，而
 * 「重复 ZIP 条目」是计划 4.2 第 7 条 / T090 明确要求的受控失败场景，必须能在中央目录里
 * 造出两个同名条目。因此这里手写本地头 + 中央目录，结构完全按 ZIP 规范，
 * 任何标准解压工具都能读出条目（重复名表现为两个条目）。
 */
class EpubFixtureBuilder {

    private class Recorded(
        val name: String,
        val content: ByteArray,
        val crc32: Long,
        val compressedSize: Int,
        val compressionMethod: Int,
        val localHeaderOffset: Long
    )

    private val records = mutableListOf<Recorded>()
    private val buffer = ByteArrayOutputStream()

    /**
     * **写入位置**。不能直接用 [buffer] 的 size 当写入位置：`ByteArrayOutputStream` 在 20,001 条目
     * 这类夹具上会扩容并把已写内容拷到新数组，而这里要的是相对文件起点的绝对偏移。
     * 每次 [writeTo] 后都重置，允许同一个 builder 反复写出（测试里常犯的二次写入否则会拼出垃圾文件）。
     */
    private var position = 0L

    /** 追加一个 DEFLATE 压缩条目。 */
    fun addEntry(name: String, content: String): EpubFixtureBuilder =
        addEntry(name, content.toByteArray(Charsets.UTF_8))

    fun addEntry(name: String, content: ByteArray): EpubFixtureBuilder = addEntry(name, content, deflated = true)

    /**
     * [deflated] 为 false 时使用 STORED（压缩方法 0）。构造“条目数上限”这类需要上万个条目的
     * 夹具时用它，避免为了空条目做一万次 zlib 初始化。
     */
    fun addEntry(name: String, content: ByteArray, deflated: Boolean): EpubFixtureBuilder {
        val nameBytes = name.toByteArray(Charsets.UTF_8)
        val crc = CRC32().apply { update(content) }.value
        val method = if (deflated) 8 else 0
        val payload = if (deflated) deflate(content) else content

        val header = ByteArrayOutputStream()
        writeInt(header, 0x04034B50) // local file header signature
        writeShort(header, 20) // version needed
        writeShort(header, 0) // general purpose flag
        writeShort(header, method)
        writeShort(header, 0) // mod time
        writeShort(header, 0) // mod date
        writeInt(header, crc.toInt())
        writeInt(header, payload.size)
        writeInt(header, content.size)
        writeShort(header, nameBytes.size)
        writeShort(header, 0) // extra field length
        header.write(nameBytes)

        val localHeaderOffset = position
        append(header.toByteArray())
        append(payload)
        records += Recorded(name, content, crc, payload.size, method, localHeaderOffset)
        return this
    }

    /** 写入目标文件并返回它。 */
    fun writeTo(target: File): File {
        val directoryStart = position
        records.forEach { record ->
            val nameBytes = record.name.toByteArray(Charsets.UTF_8)
            val entry = ByteArrayOutputStream()
            writeInt(entry, 0x02014B50) // central directory header signature
            writeShort(entry, 20) // version made by
            writeShort(entry, 20) // version needed
            writeShort(entry, 0) // general purpose flag
            writeShort(entry, record.compressionMethod)
            writeShort(entry, 0) // mod time
            writeShort(entry, 0) // mod date
            writeInt(entry, record.crc32.toInt())
            writeInt(entry, record.compressedSize)
            writeInt(entry, record.content.size)
            writeShort(entry, nameBytes.size)
            writeShort(entry, 0) // extra field length
            writeShort(entry, 0) // file comment length
            writeShort(entry, 0) // disk number start
            writeShort(entry, 0) // internal attributes
            writeInt(entry, 0) // external attributes
            writeInt(entry, record.localHeaderOffset.toInt())
            entry.write(nameBytes)
            append(entry.toByteArray())
        }
        val directorySize = position - directoryStart

        val end = ByteArrayOutputStream()
        writeInt(end, 0x06054B50) // end of central directory signature
        writeShort(end, 0) // number of this disk
        writeShort(end, 0) // disk with central directory
        writeShort(end, records.size)
        writeShort(end, records.size)
        writeInt(end, directorySize.toInt())
        writeInt(end, directoryStart.toInt())
        writeShort(end, 0) // comment length
        append(end.toByteArray())

        target.parentFile?.mkdirs()
        target.writeBytes(buffer.toByteArray())
        position = 0L
        return target
    }

    private fun append(bytes: ByteArray) {
        buffer.write(bytes)
        position += bytes.size
    }

    /** ZIP 全部多字节字段都是**小端**（这是当初写出「java.util.zip 打不开」的原因）。 */
    private fun writeInt(target: ByteArrayOutputStream, value: Int) {
        target.write(value and 0xFF)
        target.write((value ushr 8) and 0xFF)
        target.write((value ushr 16) and 0xFF)
        target.write((value ushr 24) and 0xFF)
    }

    private fun writeShort(target: ByteArrayOutputStream, value: Int) {
        target.write(value and 0xFF)
        target.write((value ushr 8) and 0xFF)
    }

    private fun deflate(bytes: ByteArray): ByteArray {
        if (bytes.isEmpty()) return ByteArray(0)
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true)
        try {
            deflater.setInput(bytes)
            deflater.finish()
            val out = ByteArrayOutputStream(bytes.size / 2 + 16)
            val buffer = ByteArray(8 * 1024)
            while (!deflater.finished()) {
                val written = deflater.deflate(buffer)
                if (written > 0) out.write(buffer, 0, written)
                if (written == 0 && deflater.needsInput()) break
            }
            return out.toByteArray()
        } finally {
            deflater.end()
        }
    }

    companion object {
        /** 一个结构完整、可直接导入的最小 EPUB3。 */
        fun minimalEpub3(vararg extraEntries: Pair<String, String>): EpubFixtureBuilder {
            val builder = EpubFixtureBuilder()
            builder.addEntry("mimetype", "application/epub+zip")
            builder.addEntry(
                "META-INF/container.xml",
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                  <rootfiles>
                    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
                  </rootfiles>
                </container>
                """.trimIndent()
            )
            builder.addEntry(
                "OEBPS/content.opf",
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="pub-id">
                  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                    <dc:identifier id="pub-id">urn:uuid:fixture</dc:identifier>
                    <dc:title>夹具</dc:title>
                  </metadata>
                  <manifest>
                    <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
                    <item id="c1" href="Text/ch1.xhtml" media-type="application/xhtml+xml"/>
                  </manifest>
                  <spine>
                    <itemref idref="c1"/>
                  </spine>
                </package>
                """.trimIndent()
            )
            builder.addEntry(
                "OEBPS/nav.xhtml",
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
                  <body><nav epub:type="toc"><ol><li><a href="Text/ch1.xhtml">第一章</a></li></ol></nav></body>
                </html>
                """.trimIndent()
            )
            builder.addEntry(
                "OEBPS/Text/ch1.xhtml",
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <html xmlns="http://www.w3.org/1999/xhtml">
                  <head><title>第一章</title></head>
                  <body><h1 id="h1">第一章</h1><p>正文</p></body>
                </html>
                """.trimIndent()
            )
            extraEntries.forEach { (name, content) -> builder.addEntry(name, content) }
            return builder
        }
    }
}
