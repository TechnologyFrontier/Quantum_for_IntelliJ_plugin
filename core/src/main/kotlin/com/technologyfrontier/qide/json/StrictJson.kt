package com.technologyfrontier.qide.json

/**
 * qosctl の出力を検証するための厳格な JSON パーサー。
 *
 * - RFC 8259 に従い、末尾のゴミ・コメント・末尾カンマを受け付けない。
 * - オブジェクトの重複キーは解析エラーとする（契約違反の検出のため）。
 * - 数値は元の文字列表現のまま保持し、浮動小数点へ変換しない（64ビットシード等の精度保持）。
 *
 * 外部ライブラリーに依存しないことで、IDE 同梱ライブラリーとの版競合を避ける。
 */
sealed interface JsonValue

data class JsonObject(val members: Map<String, JsonValue>) : JsonValue {
    operator fun get(key: String): JsonValue? = members[key]
    val keys: Set<String> get() = members.keys
}

data class JsonArray(val items: List<JsonValue>) : JsonValue

data class JsonString(val value: String) : JsonValue

/** 数値は字句のまま保持する。 */
data class JsonNumber(val text: String) : JsonValue {
    /** 小数部・指数部を持たない整数字句か。 */
    val isIntegerToken: Boolean get() = INTEGER.matches(text)

    /** 整数字句で Long に収まる場合のみ値を返す。 */
    fun toLongExactOrNull(): Long? = if (isIntegerToken) text.toLongOrNull() else null

    private companion object {
        val INTEGER = Regex("-?(0|[1-9][0-9]*)")
    }
}

data class JsonBoolean(val value: Boolean) : JsonValue

data object JsonNull : JsonValue

class JsonParseException(message: String, val offset: Int) : Exception("$message (offset $offset)")

object StrictJson {
    private const val MAX_DEPTH = 64

    fun parse(text: String): JsonValue {
        val parser = Parser(text)
        parser.skipWhitespace()
        val value = parser.readValue(0)
        parser.skipWhitespace()
        if (!parser.atEnd()) throw JsonParseException("JSONの後に余分な文字があります", parser.pos)
        return value
    }

    private class Parser(private val s: String) {
        var pos = 0

        fun atEnd() = pos >= s.length

        fun skipWhitespace() {
            while (pos < s.length) {
                when (s[pos]) {
                    ' ', '\t', '\n', '\r' -> pos++
                    else -> return
                }
            }
        }

        private fun fail(msg: String): Nothing = throw JsonParseException(msg, pos)

        private fun expect(c: Char) {
            if (pos >= s.length || s[pos] != c) fail("'$c' が必要です")
            pos++
        }

        fun readValue(depth: Int): JsonValue {
            if (depth > MAX_DEPTH) fail("入れ子が深すぎます")
            if (atEnd()) fail("値がありません")
            return when (val c = s[pos]) {
                '{' -> readObject(depth)
                '[' -> readArray(depth)
                '"' -> JsonString(readString())
                't' -> readLiteral("true", JsonBoolean(true))
                'f' -> readLiteral("false", JsonBoolean(false))
                'n' -> readLiteral("null", JsonNull)
                else -> if (c == '-' || c in '0'..'9') readNumber() else fail("不正な文字 '$c'")
            }
        }

        private fun readLiteral(word: String, value: JsonValue): JsonValue {
            if (!s.startsWith(word, pos)) fail("不正なリテラル")
            pos += word.length
            return value
        }

        private fun readObject(depth: Int): JsonObject {
            expect('{')
            val members = LinkedHashMap<String, JsonValue>()
            skipWhitespace()
            if (pos < s.length && s[pos] == '}') {
                pos++
                return JsonObject(members)
            }
            while (true) {
                skipWhitespace()
                if (atEnd() || s[pos] != '"') fail("キー文字列が必要です")
                val keyPos = pos
                val key = readString()
                if (members.containsKey(key)) throw JsonParseException("重複したキー \"$key\"", keyPos)
                skipWhitespace()
                expect(':')
                skipWhitespace()
                members[key] = readValue(depth + 1)
                skipWhitespace()
                if (atEnd()) fail("オブジェクトが閉じていません")
                when (s[pos]) {
                    ',' -> pos++
                    '}' -> {
                        pos++
                        return JsonObject(members)
                    }
                    else -> fail("',' または '}' が必要です")
                }
            }
        }

        private fun readArray(depth: Int): JsonArray {
            expect('[')
            val items = ArrayList<JsonValue>()
            skipWhitespace()
            if (pos < s.length && s[pos] == ']') {
                pos++
                return JsonArray(items)
            }
            while (true) {
                skipWhitespace()
                items += readValue(depth + 1)
                skipWhitespace()
                if (atEnd()) fail("配列が閉じていません")
                when (s[pos]) {
                    ',' -> pos++
                    ']' -> {
                        pos++
                        return JsonArray(items)
                    }
                    else -> fail("',' または ']' が必要です")
                }
            }
        }

        private fun readString(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                if (atEnd()) fail("文字列が閉じていません")
                val c = s[pos++]
                when {
                    c == '"' -> return sb.toString()
                    c == '\\' -> {
                        if (atEnd()) fail("不正なエスケープ")
                        when (val e = s[pos++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 > s.length) fail("不正な\\uエスケープ")
                                val hex = s.substring(pos, pos + 4)
                                if (!hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) fail("不正な\\uエスケープ")
                                sb.append(hex.toInt(16).toChar())
                                pos += 4
                            }
                            else -> fail("不正なエスケープ '\\$e'")
                        }
                    }
                    c < ' ' -> fail("文字列内に制御文字があります")
                    else -> sb.append(c)
                }
            }
        }

        private fun readNumber(): JsonNumber {
            val start = pos
            if (s[pos] == '-') pos++
            if (atEnd()) fail("不正な数値")
            if (s[pos] == '0') {
                pos++
            } else if (s[pos] in '1'..'9') {
                while (pos < s.length && s[pos] in '0'..'9') pos++
            } else {
                fail("不正な数値")
            }
            if (pos < s.length && s[pos] == '.') {
                pos++
                val digitsStart = pos
                while (pos < s.length && s[pos] in '0'..'9') pos++
                if (pos == digitsStart) fail("不正な数値")
            }
            if (pos < s.length && (s[pos] == 'e' || s[pos] == 'E')) {
                pos++
                if (pos < s.length && (s[pos] == '+' || s[pos] == '-')) pos++
                val digitsStart = pos
                while (pos < s.length && s[pos] in '0'..'9') pos++
                if (pos == digitsStart) fail("不正な数値")
            }
            return JsonNumber(s.substring(start, pos))
        }
    }
}

