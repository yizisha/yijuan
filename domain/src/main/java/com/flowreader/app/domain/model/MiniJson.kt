package com.flowreader.app.domain.model

/**
 * 最小 JSON 解析与序列化（主计划 3.1 / 5.2）。
 *
 * 为什么自己写而不是用 `org.json`：`org.json` 在 JVM 单元测试里只是 Android SDK 的
 * 空壳桩（方法抛 `RuntimeException("Stub!")`），一旦编码/解码路径被单测覆盖就会炸；
 * 引入第三方 JSON 库又要为一个纯数据合同增加依赖（主计划 0.3 禁止无必要依赖）。
 *
 * 本实现只覆盖合同需要的子集：object / array / string / number / bool / null。
 * 不追求完整 JSON 规范（如不做浮点精度承诺），但**严格拒绝**畸形输入而不是返回半个结果。
 */
internal object MiniJson {

    /** 解析失败返回 null；调用方必须把 null 当作「无法解析」而不是「空值」。 */
    fun parse(text: String): Any? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        return runCatching {
            val cursor = Cursor(trimmed)
            cursor.skipWhitespace()
            val value = cursor.readValue()
            cursor.skipWhitespace()
            // 必须消费完整个输入，否则是畸形 JSON（如 `{}{}`）。
            if (cursor.remaining()) null else value
        }.getOrNull()
    }

    fun stringify(value: Any?): String = buildString { write(value, this) }

    @Suppress("UNCHECKED_CAST")
    fun asObject(value: Any?): Map<String, Any?>? = value as? Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    fun asArray(value: Any?): List<Any?>? = value as? List<Any?>

    fun asString(value: Any?): String? = value as? String

    fun asInt(value: Any?): Int? = when (value) {
        is Int -> value
        is Long -> value.toInt()
        is Double -> value.toInt()
        is String -> value.toIntOrNull()
        else -> null
    }

    private fun write(value: Any?, sb: StringBuilder) {
        when (value) {
            null -> sb.append("null")
            is String -> writeString(value, sb)
            is Boolean -> sb.append(if (value) "true" else "false")
            is Int -> sb.append(value)
            is Long -> sb.append(value)
            is Double -> sb.append(value)
            is Float -> sb.append(value)
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, v) in value) {
                    if (!first) sb.append(',')
                    first = false
                    writeString(k.toString(), sb)
                    sb.append(':')
                    write(v, sb)
                }
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('[')
                var first = true
                for (item in value) {
                    if (!first) sb.append(',')
                    first = false
                    write(item, sb)
                }
                sb.append(']')
            }
            else -> writeString(value.toString(), sb)
        }
    }

    private fun writeString(s: String, sb: StringBuilder) {
        sb.append('"')
        for (ch in s) {
            when (ch) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> {
                    if (ch < ' ') {
                        sb.append(String.format("\\u%04x", ch.code))
                    } else {
                        sb.append(ch)
                    }
                }
            }
        }
        sb.append('"')
    }

    private class Cursor(private val text: String) {
        private var pos = 0

        fun remaining(): Boolean {
            skipWhitespace()
            return pos < text.length
        }

        fun skipWhitespace() {
            while (pos < text.length && text[pos].isWhitespace()) pos++
        }

        fun readValue(): Any? {
            skipWhitespace()
            if (pos >= text.length) throw IllegalArgumentException("unexpected end of input")
            return when (val ch = text[pos]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> readString()
                't' -> readLiteral("true", true)
                'f' -> readLiteral("false", false)
                'n' -> readLiteral("null", null)
                else -> if (ch == '-' || ch.isDigit()) {
                    readNumber()
                } else {
                    throw IllegalArgumentException("unexpected char '$ch' at $pos")
                }
            }
        }

        private fun readLiteral(literal: String, value: Any?): Any? {
            if (!text.startsWith(literal, pos)) throw IllegalArgumentException("bad literal at $pos")
            pos += literal.length
            return value
        }

        private fun readObject(): Map<String, Any?> {
            expect('{')
            val result = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (peek() == '}') {
                pos++
                return result
            }
            while (true) {
                skipWhitespace()
                val key = readString()
                skipWhitespace()
                expect(':')
                val value = readValue()
                result[key] = value
                skipWhitespace()
                when (val ch = peek()) {
                    ',' -> pos++
                    '}' -> {
                        pos++
                        return result
                    }
                    else -> throw IllegalArgumentException("expected , or } but got '$ch' at $pos")
                }
            }
        }

        private fun readArray(): List<Any?> {
            expect('[')
            val result = ArrayList<Any?>()
            skipWhitespace()
            if (peek() == ']') {
                pos++
                return result
            }
            while (true) {
                result.add(readValue())
                skipWhitespace()
                when (val ch = peek()) {
                    ',' -> pos++
                    ']' -> {
                        pos++
                        return result
                    }
                    else -> throw IllegalArgumentException("expected , or ] but got '$ch' at $pos")
                }
            }
        }

        private fun readString(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                if (pos >= text.length) throw IllegalArgumentException("unterminated string")
                when (val ch = text[pos++]) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (pos >= text.length) throw IllegalArgumentException("unterminated escape")
                        when (val esc = text[pos++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000c')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 > text.length) throw IllegalArgumentException("bad unicode escape")
                                val hex = text.substring(pos, pos + 4)
                                val code = hex.toIntOrNull(16)
                                    ?: throw IllegalArgumentException("bad unicode escape '$hex'")
                                sb.append(code.toChar())
                                pos += 4
                            }
                            else -> throw IllegalArgumentException("bad escape '\\$esc'")
                        }
                    }
                    else -> sb.append(ch)
                }
            }
        }

        private fun readNumber(): Any {
            val start = pos
            if (peek() == '-') pos++
            while (pos < text.length && text[pos].isDigit()) pos++
            var isDouble = false
            if (pos < text.length && text[pos] == '.') {
                isDouble = true
                pos++
                while (pos < text.length && text[pos].isDigit()) pos++
            }
            if (pos < text.length && (text[pos] == 'e' || text[pos] == 'E')) {
                isDouble = true
                pos++
                if (pos < text.length && (text[pos] == '+' || text[pos] == '-')) pos++
                while (pos < text.length && text[pos].isDigit()) pos++
            }
            val raw = text.substring(start, pos)
            if (raw.isEmpty() || raw == "-") throw IllegalArgumentException("bad number at $start")
            return if (isDouble) raw.toDouble() else raw.toIntOrNull() ?: raw.toLong()
        }

        private fun peek(): Char = if (pos < text.length) text[pos] else throw IllegalArgumentException("unexpected end of input")

        private fun expect(ch: Char) {
            if (pos >= text.length || text[pos] != ch) {
                throw IllegalArgumentException("expected '$ch' at $pos")
            }
            pos++
        }
    }
}
