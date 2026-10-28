package com.flowreader.app.domain.model

/**
 * [TextLocator] 的纯 Kotlin JSON 序列化与反序列化（主计划 3.1 / 5.2）。
 *
 * 零外部依赖，不依赖 Android `org.json` 的平台桩，保证在 JVM 单元测试、Robolectric、
 * 以及 Android 设备上均具备 100% 确定性行为。
 */
object TextLocatorCodec {

    fun toJson(locator: TextLocator): String = buildString {
        append("{")
        append("\"version\":").append(locator.version).append(",")
        append("\"publicationId\":").append(quote(locator.publicationId)).append(",")
        append("\"revision\":").append(quote(locator.revision)).append(",")
        append("\"normalizationVersion\":").append(locator.normalizationVersion).append(",")
        if (locator.sectionId != null) {
            append("\"sectionId\":").append(quote(locator.sectionId)).append(",")
        }
        append("\"documentKey\":").append(quote(locator.documentKey)).append(",")
        append("\"offsetUtf16\":").append(locator.offsetUtf16).append(",")
        append("\"affinity\":").append(quote(locator.affinity.name)).append(",")
        if (locator.blockId != null) {
            append("\"blockId\":").append(quote(locator.blockId)).append(",")
        }
        if (locator.elementId != null) {
            append("\"elementId\":").append(quote(locator.elementId)).append(",")
        }
        append("\"context\":{")
        append("\"before\":").append(quote(locator.context.before)).append(",")
        append("\"after\":").append(quote(locator.context.after))
        append("}")
        append("}")
    }

    fun fromJson(json: String): TextLocator? {
        val trimmed = json.trim()
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) return null
        return runCatching {
            var version = TextLocator.CURRENT_VERSION
            var publicationId = ""
            var revision = ""
            var normalizationVersion = 1
            var sectionId: String? = null
            var documentKey = ""
            var offsetUtf16 = 0
            var affinity = Affinity.AFTER
            var blockId: String? = null
            var elementId: String? = null
            var contextBefore = ""
            var contextAfter = ""

            val entries = parseObjectEntries(trimmed.substring(1, trimmed.length - 1))
            for ((key, value) in entries) {
                when (key) {
                    "version" -> version = value.toIntOrNull() ?: version
                    "publicationId" -> publicationId = unquote(value)
                    "revision" -> revision = unquote(value)
                    "normalizationVersion" -> normalizationVersion = value.toIntOrNull() ?: normalizationVersion
                    "sectionId" -> sectionId = if (value == "null") null else unquote(value)
                    "documentKey" -> documentKey = unquote(value)
                    "offsetUtf16" -> offsetUtf16 = value.toIntOrNull() ?: 0
                    "affinity" -> affinity = runCatching { Affinity.valueOf(unquote(value)) }.getOrDefault(Affinity.AFTER)
                    "blockId" -> blockId = if (value == "null") null else unquote(value)
                    "elementId" -> elementId = if (value == "null") null else unquote(value)
                    "context" -> {
                        if (value.startsWith("{") && value.endsWith("}")) {
                            val contextEntries = parseObjectEntries(value.substring(1, value.length - 1))
                            for ((cKey, cVal) in contextEntries) {
                                when (cKey) {
                                    "before" -> contextBefore = unquote(cVal)
                                    "after" -> contextAfter = unquote(cVal)
                                }
                            }
                        }
                    }
                }
            }

            if (publicationId.isBlank() || documentKey.isBlank()) return null
            TextLocator(
                version = version,
                publicationId = publicationId,
                revision = revision,
                normalizationVersion = normalizationVersion,
                sectionId = sectionId,
                documentKey = documentKey,
                offsetUtf16 = offsetUtf16,
                affinity = affinity,
                blockId = blockId,
                elementId = elementId,
                context = TextContext(before = contextBefore, after = contextAfter)
            )
        }.getOrNull()
    }

    private fun quote(s: String): String = buildString {
        append('"')
        for (ch in s) {
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\b' -> append("\\b")
                '\u000c' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> {
                    if (ch < ' ') {
                        append(String.format("\\u%04x", ch.code))
                    } else {
                        append(ch)
                    }
                }
            }
        }
        append('"')
    }

    private fun unquote(s: String): String {
        val trimmed = s.trim()
        if (trimmed.length < 2 || !trimmed.startsWith('"') || !trimmed.endsWith('"')) {
            return if (trimmed == "null") "" else trimmed
        }
        val inner = trimmed.substring(1, trimmed.length - 1)
        val sb = StringBuilder()
        var i = 0
        while (i < inner.length) {
            val ch = inner[i]
            if (ch == '\\' && i + 1 < inner.length) {
                when (val next = inner[i + 1]) {
                    '\\' -> {
                        sb.append('\\')
                        i += 2
                    }
                    '"' -> {
                        sb.append('"')
                        i += 2
                    }
                    'b' -> {
                        sb.append('\b')
                        i += 2
                    }
                    'f' -> {
                        sb.append('\u000c')
                        i += 2
                    }
                    'n' -> {
                        sb.append('\n')
                        i += 2
                    }
                    'r' -> {
                        sb.append('\r')
                        i += 2
                    }
                    't' -> {
                        sb.append('\t')
                        i += 2
                    }
                    'u' -> {
                        if (i + 5 < inner.length) {
                            val hex = inner.substring(i + 2, i + 6)
                            val code = hex.toIntOrNull(16)
                            if (code != null) {
                                sb.append(code.toChar())
                                i += 6
                                continue
                            }
                        }
                        sb.append(ch)
                        i++
                    }
                    else -> {
                        sb.append(next)
                        i += 2
                    }
                }
            } else {
                sb.append(ch)
                i++
            }
        }
        return sb.toString()
    }

    private fun parseObjectEntries(content: String): List<Pair<String, String>> {
        val entries = mutableListOf<Pair<String, String>>()
        var i = 0
        val len = content.length

        while (i < len) {
            while (i < len && (content[i].isWhitespace() || content[i] == ',')) i++
            if (i >= len) break

            // Key
            if (content[i] != '"') break
            val keyStart = i + 1
            var keyEnd = keyStart
            var escaped = false
            while (keyEnd < len) {
                if (content[keyEnd] == '\\' && !escaped) {
                    escaped = true
                } else if (content[keyEnd] == '"' && !escaped) {
                    break
                } else {
                    escaped = false
                }
                keyEnd++
            }
            val key = content.substring(keyStart, keyEnd)
            i = keyEnd + 1

            // Colon
            while (i < len && (content[i].isWhitespace() || content[i] == ':')) {
                if (content[i] == ':') {
                    i++
                    break
                }
                i++
            }

            while (i < len && content[i].isWhitespace()) i++
            if (i >= len) break

            // Value
            val valStart = i
            val value: String
            when (content[i]) {
                '"' -> {
                    var vEnd = i + 1
                    var vEscaped = false
                    while (vEnd < len) {
                        if (content[vEnd] == '\\' && !vEscaped) {
                            vEscaped = true
                        } else if (content[vEnd] == '"' && !vEscaped) {
                            break
                        } else {
                            vEscaped = false
                        }
                        vEnd++
                    }
                    value = content.substring(valStart, minOf(vEnd + 1, len))
                    i = minOf(vEnd + 1, len)
                }
                '{' -> {
                    var depth = 1
                    var vEnd = i + 1
                    var inString = false
                    var vEscaped = false
                    while (vEnd < len && depth > 0) {
                        val ch = content[vEnd]
                        if (inString) {
                            if (ch == '\\' && !vEscaped) {
                                vEscaped = true
                            } else if (ch == '"' && !vEscaped) {
                                inString = false
                            } else {
                                vEscaped = false
                            }
                        } else {
                            if (ch == '"') {
                                inString = true
                            } else if (ch == '{') {
                                depth++
                            } else if (ch == '}') {
                                depth--
                            }
                        }
                        vEnd++
                    }
                    value = content.substring(valStart, vEnd)
                    i = vEnd
                }
                else -> {
                    var vEnd = i
                    while (vEnd < len && content[vEnd] != ',' && content[vEnd] != '}') {
                        vEnd++
                    }
                    value = content.substring(valStart, vEnd).trim()
                    i = vEnd
                }
            }
            entries.add(key to value)
        }
        return entries
    }
}

fun TextLocator.toJson(): String = TextLocatorCodec.toJson(this)
fun TextLocator.Companion.fromJson(json: String): TextLocator? = TextLocatorCodec.fromJson(json)
