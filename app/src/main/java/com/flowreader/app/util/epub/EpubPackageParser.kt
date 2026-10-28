package com.flowreader.app.util.epub

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser

/**
 * EPUB 容器与 OPF 包解析（主计划 4.1 第 1、2 步）。
 *
 * 职责边界：**只做 XML → 结构**，不碰 ZIP I/O、不碰 Android。调用方负责读条目字节并把文本交给
 * 这里，因此可在 JVM 上直接测试（真实容器读取留给 `EpubContainerReader` 的 IO 适配层）。
 *
 * 计划要求记录的内容：manifest 的 properties / media-type / fallback、spine 的 linear、
 * page-progression-direction、rendition 属性；同时区分 EPUB 2 与 3。固定版式必须被识别出来并
 * 提示「统一排版兼容显示」，**不得静默当成普通可重排书**（0.2 第 4 条）。
 */
object EpubPackageParser {

    data class Diagnostic(val code: String, val detail: String)

    data class ManifestItem(
        val id: String,
        /** 已按 OPF 目录解引用、大小写敏感的规范容器路径。 */
        val containerPath: String,
        val mediaType: String,
        val properties: List<String> = emptyList(),
        val fallback: String? = null
    ) {
        val isNav: Boolean get() = properties.contains("nav")
        val isNcx: Boolean get() = mediaType.equals("application/x-dtbncx+xml", ignoreCase = true)
        val isImage: Boolean get() = mediaType.startsWith("image/", ignoreCase = true)
        val isXhtml: Boolean
            get() = mediaType.equals("application/xhtml+xml", ignoreCase = true)
    }

    /** spine 的一次出现。同一 idref 可以出现多次，**顺序以本列表为准**。 */
    data class SpineItem(val idref: String, val linear: Boolean)

    data class Package(
        /** `3.0` / `2.0`；无法判定时为空串。 */
        val version: String,
        val uniqueIdentifier: String?,
        val rootFilePath: String,
        val baseDirectory: String,
        val manifest: Map<String, ManifestItem>,
        /** 原始 spine 顺序（含 linear=no）。 */
        val spine: List<SpineItem>,
        val pageProgressionDirection: String?,
        val renditionLayout: String?,
        /** EPUB2 spine 的 `toc` 属性指向的 NCX manifest id。 */
        val spineTocId: String?,
        val diagnostics: List<Diagnostic> = emptyList()
    ) {
        /** 主阅读顺序：只取 `linear` 为 yes/省略的项（主计划 4.3）。 */
        val mainReadingOrder: List<ManifestItem>
            get() = spine.filter { it.linear }.mapNotNull { manifest[it.idref] }

        /** 辅助访问入口：`linear=no` 项。 */
        val auxiliaryReadingOrder: List<ManifestItem>
            get() = spine.filterNot { it.linear }.mapNotNull { manifest[it.idref] }

        /** 主目录 nav 文档（EPUB3）。 */
        val navItem: ManifestItem? get() = manifest.values.firstOrNull { it.isNav }

        /** 备用目录 NCX（EPUB2）。 */
        val ncxItem: ManifestItem? get() = spineTocId?.let { manifest[it] }
            ?: manifest.values.firstOrNull { it.isNcx }

        /**
         * 固定版式：必须提示「统一排版兼容显示」，不承诺原版式。
         * 判定依据是 rendition:layout=pre-paged（或 EPUB2 的 fixed-layout meta，由调用方补充）。
         */
        val isFixedLayout: Boolean
            get() = renditionLayout?.equals("pre-paged", ignoreCase = true) == true
    }

    // ---------------------------------------------------------------- container.xml

    /**
     * 从 `META-INF/container.xml` 取出 OPF 的容器路径。
     *
     * 只接受第一个 `rootfile`，但要求 `full-path` 非空；找不到时返回诊断而不是猜一个文件名。
     */
    fun parseRootFilePath(containerXml: String): Pair<String?, List<Diagnostic>> {
        val doc = Jsoup.parse(containerXml, "", Parser.xmlParser())
        val rootfile = doc.select("rootfile").firstOrNull()
            ?: return null to listOf(Diagnostic("CONTAINER_ROOTFILE_MISSING", "container.xml 里没有 rootfile"))
        val fullPath = rootfile.attr("full-path")
        if (fullPath.isBlank()) {
            return null to listOf(Diagnostic("CONTAINER_ROOTFILE_MISSING", "rootfile 缺少 full-path"))
        }
        // 容器路径本身是 ZIP 条目名，不是 URL；这里只做前导斜杠与反斜杠归一。
        val normalized = fullPath.replace('\\', '/').trimStart('/')
        return normalized to emptyList()
    }

    // ---------------------------------------------------------------- OPF

    fun parse(opfXml: String, opfPath: String): Package {
        val doc = Jsoup.parse(opfXml, "", Parser.xmlParser())
        val diagnostics = mutableListOf<Diagnostic>()

        val packageElement = doc.select("package").firstOrNull()
        if (packageElement == null) {
            diagnostics += Diagnostic("OPF_PACKAGE_MISSING", "未找到 package 元素")
            return Package("", null, opfPath, opfPath.substringBeforeLast('/', ""), emptyMap(), emptyList(), null, null, null, diagnostics)
        }

        val version = packageElement.attr("version").trim()
        val uniqueIdentifier = packageElement.attr("unique-identifier").takeIf { it.isNotBlank() }
        val pageProgression = packageElement.attr("page-progression-direction").takeIf { it.isNotBlank() }

        // ---- manifest（按 id 建表；重复 id 报结构错误，不能 firstOrNull 静默任选，见 4.2 第 7 条）
        val manifest = LinkedHashMap<String, ManifestItem>()
        doc.select("manifest").firstOrNull()?.let { manifestElement ->
            manifestElement.children().filter { it.tagName().equals("item", ignoreCase = true) }.forEach { item ->
                val id = item.attr("id")
                val href = item.attr("href")
                if (id.isBlank() || href.isBlank()) {
                    diagnostics += Diagnostic("MANIFEST_ITEM_INVALID", "item 缺少 id 或 href: $href")
                    return@forEach
                }
                val resolved = EpubResourceResolver.resolve(opfPath, href)
                if (resolved !is EpubResourceResolver.ResourceTarget.Internal) {
                    diagnostics += Diagnostic("MANIFEST_HREF_REJECTED", "manifest href 无法解析为包内路径: $href")
                    return@forEach
                }
                if (manifest.containsKey(id)) {
                    diagnostics += Diagnostic("DUPLICATE_MANIFEST_ID", "manifest id 重复: $id")
                    return@forEach
                }
                manifest[id] = ManifestItem(
                    id = id,
                    containerPath = resolved.containerPath,
                    mediaType = item.attr("media-type").trim(),
                    properties = item.attr("properties").split(Regex("\\s+")).filter { it.isNotBlank() },
                    fallback = item.attr("fallback").takeIf { it.isNotBlank() }
                )
            }
        }
        if (manifest.isEmpty()) {
            diagnostics += Diagnostic("MANIFEST_EMPTY", "manifest 为空")
        }

        // ---- spine（顺序以 itemref 出现顺序为准，重复出现全部保留）
        val spine = mutableListOf<SpineItem>()
        var spineTocId: String? = null
        doc.select("spine").firstOrNull()?.let { spineElement ->
            spineTocId = spineElement.attr("toc").takeIf { it.isNotBlank() }
            spineElement.children().filter { it.tagName().equals("itemref", ignoreCase = true) }.forEach { ref ->
                val idref = ref.attr("idref")
                if (idref.isBlank()) {
                    diagnostics += Diagnostic("SPINE_ITEMREF_INVALID", "itemref 缺少 idref")
                    return@forEach
                }
                if (!manifest.containsKey(idref)) {
                    diagnostics += Diagnostic("SPINE_IDREF_UNKNOWN", "itemref 指向不存在的 manifest id: $idref")
                }
                // linear 省略视为 yes（标准默认值）。
                val linear = !ref.attr("linear").equals("no", ignoreCase = true)
                spine.add(SpineItem(idref = idref, linear = linear))
            }
        }
        if (spine.isEmpty()) {
            diagnostics += Diagnostic("SPINE_EMPTY", "spine 为空")
        }

        // ---- rendition:layout：可能在 package 属性上，或在 meta 里。
        // EPUB3 的形式是 `<meta property="rendition:layout">pre-paged</meta>`（值在**文本**里），
        // EPUB2 的形式是 `<meta name="rendition:layout" content="pre-paged"/>`（值在属性里），
        // 两种都必须支持，否则固定版式识别不出来、会被当成普通可重排书。
        fun metaValue(attributeName: String, value: String): String? =
            doc.select("meta")
                .firstOrNull { it.attr(attributeName) == value }
                ?.let { element ->
                    element.attr("content").takeIf { it.isNotBlank() }
                        ?: element.text().trim().takeIf { it.isNotBlank() }
                }

        val renditionLayout = packageElement.attr("rendition:layout").takeIf { it.isNotBlank() }
            ?: metaValue("property", "rendition:layout")
            ?: metaValue("name", "rendition:layout")

        return Package(
            version = version,
            uniqueIdentifier = uniqueIdentifier,
            rootFilePath = opfPath,
            baseDirectory = opfPath.substringBeforeLast('/', ""),
            manifest = manifest,
            spine = spine,
            pageProgressionDirection = pageProgression,
            renditionLayout = renditionLayout,
            spineTocId = spineTocId,
            diagnostics = diagnostics
        )
    }

    /** metadata 里的 dc:title / dc:creator 等，按本节点直接文本读取。 */
    fun metadata(packageElement: Element): Map<String, String> {
        val result = mutableMapOf<String, String>()
        packageElement.children().filter { it.tagName().equals("metadata", ignoreCase = true) }.forEach { metadata ->
            metadata.children().forEach { child ->
                val key = child.tagName().substringAfterLast(':').lowercase()
                val text = child.text().trim()
                if (text.isNotEmpty() && !result.containsKey(key)) result[key] = text
            }
        }
        return result
    }
}
