package com.buccaneer.browser

/** Iterative preflight, not a JSON validator. Reject pathological recursion before JSONTokener. */
internal object JsonInputBudget {
    const val MAX_DEPTH = 32

    // Android JSONTokener accepts unquoted literals, including embedded quotes.
    // Only a quote at the START of a token opens a string. Treating every quote
    // as an opener would let an unquoted a' token hide deeply nested arrays.
    private fun literalDelimiter(c: Char): Boolean = when (c) {
        '{', '}', '[', ']', '/', '\\', ':', ',', '=', ';', '#',
        ' ', '\t', '\n', '\r', '\u000c' -> true
        else -> false
    }

    fun allows(raw: String, maxDepth: Int = MAX_DEPTH): Boolean {
        var depth = 0
        var quote = '\u0000'
        var literal = false
        var escaped = false
        var lineComment = false
        var blockComment = false
        var i = if (raw.startsWith('\ufeff')) 1 else 0
        while (i < raw.length) {
            val c = raw[i]
            val next = if (i + 1 < raw.length) raw[i + 1] else '\u0000'
            when {
                lineComment -> if (c == '\n' || c == '\r') lineComment = false
                blockComment -> if (c == '*' && next == '/') { blockComment = false; i++ }
                quote != '\u0000' -> when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == quote -> quote = '\u0000'
                }
                literal && !literalDelimiter(c) -> Unit
                else -> {
                    literal = false
                    when {
                        c == '"' || c == '\'' -> quote = c
                        c == '/' && next == '*' -> { blockComment = true; i++ }
                        c == '/' && next == '/' -> { lineComment = true; i++ }
                        c == '#' -> lineComment = true
                        c == '{' || c == '[' -> { depth++; if (depth > maxDepth) return false }
                        c == '}' || c == ']' -> { depth--; if (depth < 0) return false }
                        c == '\\' || c == '/' -> return false // Invalid outside a string/comment.
                        c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\u000c' ||
                            c == ':' || c == ',' || c == '=' || c == ';' -> Unit
                        else -> literal = true
                    }
                }
            }
            i++
        }
        return true // Syntax, closure and types are still checked by the existing parser.
    }
}
