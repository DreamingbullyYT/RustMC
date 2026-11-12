package dev.ferrite.core

/** Minimal JSON codec (no dependencies, so `core` runs on the JVM and on Android alike). */
object Json {
    fun parse(text: String): Any? = Parser(text).run { val v = value(); ws(); require(i == s.length) { "trailing data at $i" }; v }

    fun write(v: Any?, pretty: Boolean = false): String = StringBuilder().also { put(it, v, if (pretty) 0 else -1) }.toString()

    private fun put(b: StringBuilder, v: Any?, ind: Int) {
        val nl = { n: Int -> if (ind >= 0) b.append('\n').append("  ".repeat(n)) }
        when (v) {
            null -> b.append("null")
            is Boolean, is Int, is Long -> b.append(v)
            is Double -> b.append(if (v == Math.floor(v) && !v.isInfinite() && Math.abs(v) < 1e15) v.toLong().toString() else v.toString())
            is Float -> put(b, v.toDouble(), ind)
            is String -> str(b, v)
            is Map<*, *> -> {
                b.append('{'); var first = true
                for ((k, x) in v) { if (!first) b.append(','); first = false; nl(ind + 1); str(b, k.toString()); b.append(':'); if (ind >= 0) b.append(' '); put(b, x, if (ind >= 0) ind + 1 else -1) }
                if (!first) nl(ind); b.append('}')
            }
            is List<*> -> {
                b.append('['); var first = true
                for (x in v) { if (!first) b.append(','); first = false; nl(ind + 1); put(b, x, if (ind >= 0) ind + 1 else -1) }
                if (!first) nl(ind); b.append(']')
            }
            else -> str(b, v.toString())
        }
    }

    private fun str(b: StringBuilder, s: String) {
        b.append('"')
        for (c in s) when (c) {
            '"' -> b.append("\\\""); '\\' -> b.append("\\\\"); '\n' -> b.append("\\n"); '\r' -> b.append("\\r"); '\t' -> b.append("\\t")
            else -> if (c < ' ') b.append("\\u%04x".format(c.code)) else b.append(c)
        }
        b.append('"')
    }

    private class Parser(val s: String) {
        var i = 0
        fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun value(): Any? {
            ws(); require(i < s.length) { "unexpected end" }
            return when (val c = s[i]) {
                '{' -> { i++; val m = LinkedHashMap<String, Any?>(); ws()
                    if (s[i] == '}') { i++; return m }
                    while (true) { ws(); val k = string(); ws(); expect(':'); m[k] = value(); ws(); if (s[i] == ',') { i++ } else { expect('}'); return m } } }
                '[' -> { i++; val l = ArrayList<Any?>(); ws()
                    if (s[i] == ']') { i++; return l }
                    while (true) { l.add(value()); ws(); if (s[i] == ',') { i++ } else { expect(']'); return l } } }
                '"' -> string()
                't' -> lit("true", true); 'f' -> lit("false", false); 'n' -> lit("null", null)
                else -> { require(c == '-' || c.isDigit()) { "bad char '$c' at $i" }; number() }
            }
        }
        fun expect(c: Char) { require(i < s.length && s[i] == c) { "expected '$c' at $i" }; i++ }
        fun lit(w: String, v: Any?): Any? { require(s.startsWith(w, i)) { "bad literal at $i" }; i += w.length; return v }
        fun number(): Any {
            val st = i; if (s[i] == '-') i++
            while (i < s.length && (s[i].isDigit() || s[i] in ".eE+-")) i++
            val t = s.substring(st, i)
            return if (t.any { it in ".eE" }) t.toDouble() else t.toLongOrNull() ?: t.toDouble()
        }
        fun string(): String {
            expect('"'); val b = StringBuilder()
            while (true) {
                require(i < s.length) { "unterminated string" }
                val c = s[i++]
                if (c == '"') return b.toString()
                if (c != '\\') { b.append(c); continue }
                when (val e = s[i++]) {
                    'n' -> b.append('\n'); 't' -> b.append('\t'); 'r' -> b.append('\r'); 'b' -> b.append('\b'); 'f' -> b.append('\u000c')
                    'u' -> { b.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                    else -> b.append(e)
                }
            }
        }
    }
}

@Suppress("UNCHECKED_CAST") fun Any?.obj(): Map<String, Any?> = this as? Map<String, Any?> ?: emptyMap()
@Suppress("UNCHECKED_CAST") fun Any?.arr(): List<Any?> = this as? List<Any?> ?: emptyList()
fun Any?.str(d: String = ""): String = this as? String ?: d
fun Any?.num(d: Double = 0.0): Double = (this as? Number)?.toDouble() ?: d
fun Any?.int(d: Int = 0): Int = (this as? Number)?.toInt() ?: d
fun Any?.bool(d: Boolean = false): Boolean = this as? Boolean ?: d
