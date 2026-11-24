package com.pentax.k1sync.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ApiLogEntry(
    val time: String,
    val method: String,
    val path: String,
    val statusCode: Int,
    val elapsedMs: Long,
    val detail: String
)

/**
 * Lightweight ring-buffer protocol logger for real Pentax K-1 firmware troubleshooting.
 * Records the last 30 HTTP/WebSocket interactions so any firmware quirk can be copied and diagnosed immediately.
 */
object ApiDebugLogger {

    private const val MAX_ENTRIES = 30
    private val lock = Any()
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _entries = MutableStateFlow<List<ApiLogEntry>>(emptyList())
    val entries: StateFlow<List<ApiLogEntry>> = _entries.asStateFlow()

    fun setEnabled(on: Boolean) {
        _enabled.value = on
    }

    fun clear() {
        synchronized(lock) { _entries.value = emptyList() }
    }

    fun record(method: String, path: String, statusCode: Int, elapsedMs: Long, detail: String) {
        if (!_enabled.value) return
        val cleanDetail = detail.replace("\n", " ").replace("\r", "").take(220)
        val entry = ApiLogEntry(
            time = synchronized(lock) { timeFmt.format(Date()) },
            method = method,
            path = path,
            statusCode = statusCode,
            elapsedMs = elapsedMs,
            detail = cleanDetail
        )
        synchronized(lock) {
            val next = (listOf(entry) + _entries.value).take(MAX_ENTRIES)
            _entries.value = next
        }
    }

    fun formatAllForCopy(): String {
        val list = _entries.value
        if (list.isEmpty()) return "=== Pentax K-1 Sync Protocol Log (空) ==="
        return buildString {
            appendLine("=== Pentax K-1 Sync v7.0.4 Protocol Log (${list.size} entries) ===")
            list.forEach { e ->
                val statusStr = if (e.statusCode > 0) "HTTP ${e.statusCode}" else "ERR"
                appendLine("[${e.time}] ${e.method} ${e.path} -> $statusStr (${e.elapsedMs}ms) | ${e.detail}")
            }
        }
    }
}
