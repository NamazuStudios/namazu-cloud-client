// This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
// If a copy of the MPL was not distributed with this file, You can obtain one at
// https://mozilla.org/MPL/2.0/.

package com.namazustudios.cloud.protocol

/**
 * The wire codec for [ControlFrame]s — a small, dependency-free JSON implementation.
 *
 * The control channel is a closed protocol with flat, well-known frames, so a hand-rolled codec is
 * both safer (no "no Creators, like default constructor" classloader surprises across the two
 * repos that share this artifact) and perfectly adequate. The protocol artifact must remain free of
 * serialization frameworks.
 */
object ControlWire {

    /**
     * Encodes a frame to its canonical JSON text. The `type` discriminator is always present and
     * the object is always a flat mapping (the one exception is `ControlRequestSession.actor`,
     * a nested object).
     */
    fun encode(frame: ControlFrame): String = when (frame) {
        is ControlChallenge -> obj(
            "type" to frame.type,
            "nonce" to frame.nonce,
            "expiresAt" to frame.expiresAt,
        )

        is ControlResponse -> obj(
            "type" to frame.type,
            "clientId" to frame.clientId,
            "challengeNonce" to frame.challengeNonce,
            "nonceClient" to frame.nonceClient,
            "mac" to frame.mac,
        )

        is ControlServerAuth -> obj(
            "type" to frame.type,
            "mac" to frame.mac,
        )

        is ControlBye -> obj(
            "type" to frame.type,
            *listOf("reason" to frame.reason).takeIf { it[0].second != null }?.toTypedArray()
                ?: emptyArray(),
        )

        is ControlRequestSession -> obj(
            "type" to frame.type,
            "requestId" to frame.requestId,
            "actor" to raw(
                obj(
                    "userId" to frame.actor.userId,
                    "userEmail" to frame.actor.userEmail,
                    "level" to frame.actor.level,
                )
            ),
            *frame.expiresAt?.let { arrayOf("expiresAt" to it) } ?: emptyArray(),
        )

        is ControlSession -> obj(
            "type" to frame.type,
            "requestId" to frame.requestId,
            "sessionSecret" to frame.sessionSecret,
            *frame.expiresAt?.let { arrayOf("expiresAt" to it) } ?: emptyArray(),
            *frame.userId?.let { arrayOf("userId" to it) } ?: emptyArray(),
            *frame.userName?.let { arrayOf("userName" to it) } ?: emptyArray(),
        )

        is ControlError -> obj(
            "type" to frame.type,
            "code" to frame.code,
            *frame.message?.let { arrayOf("message" to it) } ?: emptyArray(),
            *frame.requestId?.let { arrayOf("requestId" to it) } ?: emptyArray(),
        )

        is ControlAck -> obj(
            "type" to frame.type,
            "requestId" to frame.requestId,
            "ok" to frame.ok,
        )
    }

    /**
     * Decodes a JSON text into its typed [ControlFrame].
     *
     * @throws ControlWireException on malformed JSON, a missing `type`, an unknown type, or a frame
     *   missing a required field. The protocol is versioned by the type labels, so an unknown type
     *   should be treated as an incompatible-peer condition, not silently ignored.
     */
    fun decode(json: String): ControlFrame {
        val root = Parser(json).parse() as? Json.Obj
            ?: throw ControlWireException("frame must be a JSON object")
        val type = root.field("type") as? Json.Str
            ?: throw ControlWireException("frame missing \"type\"")
        return when (type.value) {
            ControlFrame.TYPE_CHALLENGE -> ControlChallenge(
                nonce = root.str("nonce"),
                expiresAt = root.long("expiresAt"),
            )

            ControlFrame.TYPE_RESPONSE -> ControlResponse(
                clientId = root.str("clientId"),
                challengeNonce = root.str("challengeNonce"),
                nonceClient = root.str("nonceClient"),
                mac = root.str("mac"),
            )

            ControlFrame.TYPE_SERVER_AUTH -> ControlServerAuth(
                mac = root.str("mac"),
            )

            ControlFrame.TYPE_BYE -> ControlBye(
                reason = root.optionalStr("reason"),
            )

            ControlFrame.TYPE_REQUEST_SESSION -> ControlRequestSession(
                requestId = root.str("requestId"),
                actor = root.actor(),
                expiresAt = root.optionalLong("expiresAt"),
            )

            ControlFrame.TYPE_SESSION -> ControlSession(
                requestId = root.str("requestId"),
                sessionSecret = root.str("sessionSecret"),
                expiresAt = root.optionalLong("expiresAt"),
                userId = root.optionalStr("userId"),
                userName = root.optionalStr("userName"),
            )

            ControlFrame.TYPE_ERROR -> ControlError(
                code = root.str("code"),
                message = root.optionalStr("message"),
                requestId = root.optionalStr("requestId"),
            )

            ControlFrame.TYPE_ACK -> ControlAck(
                requestId = root.str("requestId"),
                ok = root.bool("ok"),
            )

            else -> throw ControlWireException("unknown frame type: \"${type.value}\"")
        }
    }

    private class ControlWireException(message: String) : RuntimeException(message)

    // ---------------------------------------------------------------- JSON value model

    private sealed class Json {
        class Str(val value: String) : Json()
        class Num(val value: Long) : Json()
        class Bool(val value: Boolean) : Json()
        class Arr(val value: List<Json>) : Json()
        class Obj(val value: LinkedHashMap<String, Json>) : Json() {
            fun field(name: String): Json? = value[name]
            fun str(name: String): String =
                (value[name] as? Json.Str)?.value
                    ?: throw ControlWireException("missing string field \"$name\"")
            fun optionalStr(name: String): String? = (value[name] as? Json.Str)?.value
            fun long(name: String): Long =
                (value[name] as? Json.Num)?.value
                    ?: throw ControlWireException("missing number field \"$name\"")
            fun optionalLong(name: String): Long? = (value[name] as? Json.Num)?.value
            fun bool(name: String): Boolean =
                (value[name] as? Json.Bool)?.value
                    ?: throw ControlWireException("missing boolean field \"$name\"")
            fun actor(): ControlActor {
                val a = value["actor"] as? Json.Obj
                    ?: throw ControlWireException("missing object field \"actor\"")
                return ControlActor(
                    userId = a.str("userId"),
                    userEmail = a.str("userEmail"),
                    level = a.str("level"),
                )
            }
        }
    }

    // ---------------------------------------------------------------- encoding helpers

    private fun obj(vararg fields: Pair<String, Any?>): String {
        val sb = StringBuilder("{")
        var first = true
        for ((k, v) in fields) {
            if (v == null) continue
            if (!first) sb.append(',')
            first = false
            sb.append('"').append(escape(k)).append("\":")
            sb.append(jsonValue(v))
        }
        return sb.append('}').toString()
    }

    private fun raw(json: String): Any = Raw(json)

    private class Raw(val text: String)

    private fun jsonValue(v: Any): String = when (v) {
        is Raw -> v.text
        is String -> "\"" + escape(v) + "\""
        is Number -> v.toString()
        is Boolean -> v.toString()
        else -> throw ControlWireException("unsupported value type: ${v::class.java.name}")
    }

    private fun escape(s: String): String {
        val sb = StringBuilder(s.length + 8)
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        return sb.toString()
    }

    // ---------------------------------------------------------------- parsing

    private class Parser(private val s: String) {
        private var i = 0

        fun parse(): Json {
            skipWs()
            val v = parseValue()
            skipWs()
            if (i != s.length) throw ControlWireException("trailing characters after JSON at index $i")
            return v
        }

        private fun parseValue(): Json = when {
            peek() == '{' -> parseObject()
            peek() == '[' -> parseArray()
            peek() == '"' -> Json.Str(parseString())
            peek() == '-' || peek().isDigit() -> Json.Num(parseNumber())
            else -> parseLiteral()
        }

        private fun parseObject(): Json.Obj {
            expect('{')
            val map = LinkedHashMap<String, Json>()
            skipWs()
            if (peek() == '}') {
                i++
                return Json.Obj(map)
            }
            while (true) {
                skipWs()
                if (peek() != '"') throw ControlWireException("expected string key at index $i")
                val key = parseString()
                skipWs()
                expect(':')
                skipWs()
                map[key] = parseValue()
                skipWs()
                when (peek()) {
                    ',' -> { i++; continue }
                    '}' -> { i++; return Json.Obj(map) }
                    else -> throw ControlWireException("expected ',' or '}' at index $i")
                }
            }
        }

        private fun parseArray(): Json.Arr {
            expect('[')
            val list = ArrayList<Json>()
            skipWs()
            if (peek() == ']') {
                i++
                return Json.Arr(list)
            }
            while (true) {
                skipWs()
                list.add(parseValue())
                skipWs()
                when (peek()) {
                    ',' -> { i++; continue }
                    ']' -> { i++; return Json.Arr(list) }
                    else -> throw ControlWireException("expected ',' or ']' at index $i")
                }
            }
        }

        private fun parseString(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                if (i >= s.length) throw ControlWireException("unterminated string")
                val c = s[i++]
                when {
                    c == '"' -> return sb.toString()
                    c == '\\' -> {
                        if (i >= s.length) throw ControlWireException("unterminated escape")
                        when (val e = s[i++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (i + 4 > s.length) throw ControlWireException("bad \\u escape")
                                val hex = s.substring(i, i + 4)
                                val code = hex.toIntOrNull(16)
                                    ?: throw ControlWireException("bad \\u escape: $hex")
                                sb.append(code.toChar())
                                i += 4
                            }
                            else -> throw ControlWireException("unknown escape: \\$e")
                        }
                    }
                    c < ' ' -> throw ControlWireException("unescaped control char in string")
                    else -> sb.append(c)
                }
            }
        }

        private fun parseNumber(): Long {
            val start = i
            if (peek() == '-') i++
            while (i < s.length && (s[i].isDigit() || s[i] == '.' || s[i] == 'e' || s[i] == 'E' || s[i] == '+' || s[i] == '-')) i++
            val text = s.substring(start, i)
            return text.toLongOrNull() ?: throw ControlWireException("expected integer at index $start: $text")
        }

        private fun parseLiteral(): Json {
            val start = i
            while (i < s.length && s[i].isLetter()) i++
            return when (s.substring(start, i)) {
                "true" -> Json.Bool(true)
                "false" -> Json.Bool(false)
                "null" -> throw ControlWireException("null values are not supported by the protocol")
                else -> throw ControlWireException("unexpected literal at index $start")
            }
        }

        private fun peek(): Char = if (i < s.length) s[i] else throw ControlWireException("unexpected end of JSON")

        private fun expect(c: Char) {
            if (peek() != c) throw ControlWireException("expected '$c' at index $i")
            i++
        }

        private fun skipWs() {
            while (i < s.length && s[i].isWhitespace()) i++
        }
    }
}