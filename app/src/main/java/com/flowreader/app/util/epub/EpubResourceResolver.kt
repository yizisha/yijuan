package com.flowreader.app.util.epub

import com.flowreader.app.domain.model.ContentErrorCode

/**
 * 包内引用解析（主计划 4.2）。
 *
 * **全应用只允许这一处实现路径逻辑。** 旧实现（`BookParser`）在三处各自 `substringBefore('#')`，
 * 于是同一 XHTML 的多个锚点互相覆盖（F05）；图片兜底用表单式 `URLDecoder`，会把 `+` 变成空格
 * 并按 basename 猜图（F11）。
 *
 * 设计要点（逐条对应计划 4.2）：
 * 1. **先按组件拆分再解码**：scheme / authority / path / query / fragment 分开，绝不把表单式
 *    `URLDecoder` 用在整条 URL 上。
 * 2. 相对路径以**引用它的文档目录**为基准；OPF manifest href 以 OPF 为基准；nav/NCX 的
 *    content src 以 nav/NCX 文件为基准 —— 都由调用方传入正确的 [baseDocumentKey]。
 * 3. 路径按段 percent-decode **恰好一次**（UTF-8），保留 `+`、大小写与 Unicode；非法百分号或
 *    非法 UTF-8 返回 [ContentErrorCode.INVALID_URI]；解码后段内出现 `/`、`\`、NUL 同样拒绝；
 *    **禁止二次解码**。ZIP 条目名本身是文件名，不走 URL 解码。
 * 4. 解码后处理 `.` / `..`；上溢容器根返回 [ContentErrorCode.PATH_ESCAPE]；绝对设备路径、UNC、
 *    驱动器路径一律不允许。容器根相对 `/path` 映射到容器根，与宿主绝对路径彻底隔离。
 * 5. query 从资源身份中分离但保留原值；包内静态资源检索不使用 query，不把 `?x` 当文件名。
 *    fragment 单独解码一次，**不删、不 lowercase**；`#` 单独出现表示文档起点，`#id` 才是元素目标。
 * 6. 资源键使用与 ZIP 条目完全一致的规范容器路径，**大小写敏感**；**不做 basename 兜底**。
 * 7. 外部目标只识别不加载；`javascript/file/content/intent` 不作为 EPUB 链接执行。
 *
 * 本类是纯 Kotlin（无 Android 类型），可在 JVM 上直接测试。
 */
object EpubResourceResolver {

    private const val SCHEME_HTTP = "http"
    private const val SCHEME_HTTPS = "https"
    private const val SCHEME_MAILTO = "mailto"

    /** 明确禁止执行的 scheme（安全边界，不因“能打开”就放行）。 */
    private val BLOCKED_SCHEMES = setOf("javascript", "file", "content", "intent", "vbscript", "data")

    sealed interface ResourceTarget {
        /** 引用原始字符串，诊断时原样回显。 */
        val raw: String

        /**
         * 包内目标。[containerPath] 是规范容器路径（大小写敏感、已解码、已消解 dot-segment），
         * 可直接与 ZIP 条目名比较；[fragment] 已单独解码一次，未提供时为 null。
         */
        data class Internal(
            override val raw: String,
            val containerPath: String,
            val fragment: String? = null,
            val query: String? = null
        ) : ResourceTarget

        /** 外部目标。只识别，**不自动请求**；由用户显式确认后交系统处理。 */
        data class External(override val raw: String, val scheme: String) : ResourceTarget

        /** 拒绝的目标，带稳定错误码。 */
        data class Rejected(
            override val raw: String,
            val code: ContentErrorCode,
            val detail: String? = null
        ) : ResourceTarget
    }

    /**
     * 解析 [rawHref]，[baseDocumentKey] 是**引用它的那个文件**的容器路径（例如 `OEBPS/Text/a.xhtml`）。
     *
     * @param baseDocumentKey 空字符串表示以容器根为基准（例如解析 OPF 里的 href 时传 OPF 路径）。
     */
    fun resolve(baseDocumentKey: String, rawHref: String): ResourceTarget {
        val raw = rawHref
        if (rawHref.isBlank()) {
            return ResourceTarget.Rejected(raw, ContentErrorCode.INVALID_URI, "空引用")
        }

        // ---- 1. 先拆组件，再各自解码
        val schemeSplit = splitScheme(rawHref)
        val scheme = schemeSplit.first
        if (scheme != null) {
            val lower = scheme.lowercase()
            if (lower in BLOCKED_SCHEMES) {
                return ResourceTarget.Rejected(raw, ContentErrorCode.UNSUPPORTED_MEDIA, "禁止执行的 scheme: $lower")
            }
            if (lower == SCHEME_HTTP || lower == SCHEME_HTTPS || lower == SCHEME_MAILTO) {
                return ResourceTarget.External(raw, lower)
            }
            // 其它带 scheme 的形式（如 urn:、tel:）按外部处理，不尝试按包内路径解释。
            return ResourceTarget.External(raw, lower)
        }

        val withoutFragment = rawHref.substringBefore('#')
        val fragmentRaw = rawHref.substringAfter('#', missingDelimiterValue = "")
        val hasFragment = rawHref.contains('#')

        val withoutQuery = withoutFragment.substringBefore('?')
        val queryRaw = withoutFragment.substringAfter('?', missingDelimiterValue = "")
        val hasQuery = withoutFragment.contains('?')

        // fragment 单独解码一次：不删、不 lowercase（`#注1` 与 `#%E6%B3%A81` 等价）。
        val fragment = if (!hasFragment) {
            null
        } else {
            val decoded = percentDecode(fragmentRaw)
                ?: return ResourceTarget.Rejected(raw, ContentErrorCode.INVALID_URI, "fragment 百分号编码非法")
            // `href="#"` 表示文档起点，语义上等价于“无 fragment”。
            if (decoded.isEmpty()) null else decoded
        }

        // ---- 2/3. 以引用它的文档目录为基准，逐段解码一次
        val baseDir = baseDocumentKey.substringBeforeLast('/', missingDelimiterValue = "")
        val rawPath = withoutQuery

        // 纯 fragment 引用（`#n1`）：目标是**基准文档自身**，而不是它所在的目录。
        if (rawPath.isEmpty()) {
            if (baseDocumentKey.isEmpty()) {
                return ResourceTarget.Rejected(raw, ContentErrorCode.INVALID_URI, "缺少基准文档: $rawHref")
            }
            return ResourceTarget.Internal(raw = raw, containerPath = baseDocumentKey, fragment = fragment)
        }

        val combined = when {
            rawPath.startsWith("/") -> rawPath.removePrefix("/") // 容器根相对，与宿主绝对路径隔离
            baseDir.isEmpty() -> rawPath
            else -> "$baseDir/$rawPath"
        }

        val segments = ArrayList<String>()
        for (segment in combined.split('/')) {
            when (segment) {
                "", "." -> continue
                ".." -> {
                    if (segments.isEmpty()) {
                        return ResourceTarget.Rejected(raw, ContentErrorCode.PATH_ESCAPE, "越过容器根: $rawHref")
                    }
                    segments.removeAt(segments.size - 1)
                }

                else -> {
                    val decoded = percentDecode(segment)
                        ?: return ResourceTarget.Rejected(raw, ContentErrorCode.INVALID_URI, "路径段编码非法: $segment")
                    // 解码后不得再出现分隔符或 NUL —— 否则 `%2e%2e%2f` 之类就能绕过上面的 `..` 检查。
                    if (decoded.contains('/') || decoded.contains('\\') || decoded.contains('\u0000')) {
                        return ResourceTarget.Rejected(raw, ContentErrorCode.INVALID_URI, "路径段解码后含分隔符: $segment")
                    }
                    // 解码后可能恰好变成 `.` / `..`，需要再判定一次（但不能二次解码百分号）。
                    when (decoded) {
                        "." -> continue
                        ".." -> {
                            if (segments.isEmpty()) {
                                return ResourceTarget.Rejected(raw, ContentErrorCode.PATH_ESCAPE, "解码后越界: $rawHref")
                            }
                            segments.removeAt(segments.size - 1)
                        }

                        else -> segments.add(decoded)
                    }
                }
            }
        }

        if (segments.isEmpty()) {
            return ResourceTarget.Rejected(raw, ContentErrorCode.INVALID_URI, "路径为空: $rawHref")
        }

        val containerPath = segments.joinToString("/")
        // 驱动器/UNC 形态绝不允许出现在容器路径里。
        if (containerPath.contains(':') || containerPath.startsWith("//")) {
            return ResourceTarget.Rejected(raw, ContentErrorCode.PATH_ESCAPE, "疑似宿主绝对路径: $containerPath")
        }

        val query = if (hasQuery && queryRaw.isNotEmpty()) queryRaw else null
        return ResourceTarget.Internal(
            raw = raw,
            containerPath = containerPath,
            fragment = fragment,
            query = query
        )
    }

    /** 取 scheme（形如 `name:` 且 name 合法），没有则返回 null 与剩余串。 */
    private fun splitScheme(value: String): Pair<String?, String> {
        val colon = value.indexOf(':')
        if (colon <= 0) return null to value
        // 单字母会被 Windows 盘符与相对路径误判，这里要求 scheme 至少 2 个字母。
        if (colon < 2) return null to value
        val candidate = value.substring(0, colon)
        if (!candidate.all { it.isLetter() || it.isDigit() || it == '+' || it == '-' || it == '.' }) {
            return null to value
        }
        if (!candidate.first().isLetter()) return null to value
        return candidate to value.substring(colon + 1)
    }

    /**
     * 严格的单次 percent-decode。
     *
     * 与表单式 `URLDecoder` 的两点区别，正是 F11 的成因：
     * - **不把 `+` 当空格**（包内文件名里的 `+` 是字面加号）；
     * - 非法百分号或非法 UTF-8 返回 null 交给上层报错，而不是静默替换成 U+FFFD。
     *
     * 只解码一次：解出的 `%` 不会再次参与解码（`%252e%252e` → 字面 `%2e%2e`）。
     */
    fun percentDecode(value: String): String? {
        if (value.isEmpty()) return value
        if (!value.contains('%')) return value
        val bytes = java.io.ByteArrayOutputStream(value.length)
        var i = 0
        while (i < value.length) {
            val ch = value[i]
            if (ch == '%') {
                if (i + 2 >= value.length) return null
                val hi = hexValue(value[i + 1])
                val lo = hexValue(value[i + 2])
                if (hi < 0 || lo < 0) return null
                bytes.write((hi shl 4) or lo)
                i += 3
            } else {
                // 非百分号字符原样写入 UTF-8（保留 `+`、大小写与 Unicode）。
                val str = ch.toString()
                bytes.write(str.toByteArray(Charsets.UTF_8))
                i++
            }
        }
        return try {
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
            decoder.decode(java.nio.ByteBuffer.wrap(bytes.toByteArray())).toString()
        } catch (e: java.nio.charset.CharacterCodingException) {
            null
        }
    }

    private fun hexValue(ch: Char): Int = when (ch) {
        in '0'..'9' -> ch - '0'
        in 'a'..'f' -> ch - 'a' + 10
        in 'A'..'F' -> ch - 'A' + 10
        else -> -1
    }
}
