package com.buccaneer.browser

import org.json.JSONArray
import java.util.Locale

/** evaluateJavascript() returns JSON text; turn a JSON string result into the plain string (null for null). */
fun jsString(raw: String?): String? {
    if (raw == null || raw == "null" || raw.length > 1024 * 1024 || !JsonInputBudget.allows(raw)) return null
    val decoded = try {
        JSONArray("[$raw]").getString(0)
    } catch (e: Exception) {
        raw
    }
    // Many helper results are JSON objects encoded inside a JSON string. Checking
    // only the outer transport would miss recursion in the subsequently parsed body.
    var start = 0
    while (start < decoded.length && (decoded[start].isWhitespace() || decoded[start] == '\ufeff')) start++
    val first = decoded.getOrNull(start)
    val next = decoded.getOrNull(start + 1)
    // Android's parser also accepts comments before an object/array. Those must
    // not bypass the decoded-body budget; ordinary scalar strings stay intact.
    val jsonLike = first == '{' || first == '[' || first == '#' || (first == '/' && (next == '/' || next == '*'))
    return if (jsonLike && !JsonInputBudget.allows(decoded)) null else decoded
}

fun formatTime(totalSeconds: Double): String {
    val s = Math.max(0L, totalSeconds.toLong())
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) {
        String.format(Locale.US, "%d:%02d:%02d", h, m, sec)
    } else {
        String.format(Locale.US, "%d:%02d", m, sec)
    }
}
