package com.par9uet.jm.utils

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class LogEntry(
    val timestamp: String,
    val tag: String,
    val message: String,
    val level: String
) {
    val formatted: String get() = "[$timestamp][$level][$tag] $message"
}

object LogBuffer {
    private const val MAX_ENTRIES = 500
    // [GlassDiag] 临时诊断：诊断 tag 的行另行留存，避免被高频业务日志（封面/解码）挤出环形缓冲，
    // 导致实测完导出时证据已经消失。定位完删除本段。
    private const val MAX_DIAG_ENTRIES = 800
    private val DIAG_TAGS = listOf("GlassDiag", "ReaderDiag")
    private val entries = mutableListOf<LogEntry>()
    private val diagEntries = mutableListOf<LogEntry>()
    private val dateFormatter = SimpleDateFormat("MM-dd HH:mm:ss", Locale.ROOT)

    @Synchronized
    fun append(tag: String, message: String, level: String = "D") {
        val time = dateFormatter.format(Date())
        val entry = LogEntry(time, tag, message, level)
        entries.add(entry)
        if (entries.size > MAX_ENTRIES) {
            entries.removeAt(0)
        }
        if (DIAG_TAGS.any { tag.startsWith(it) }) {
            diagEntries.add(entry)
            if (diagEntries.size > MAX_DIAG_ENTRIES) {
                diagEntries.removeAt(0)
            }
        }
    }

    @Synchronized
    fun appendError(tag: String, message: String) {
        append(tag, message, "E")
    }

    @Synchronized
    fun getLogs(): List<LogEntry> {
        return mergedEntries()
    }

    @Synchronized
    fun getLogText(): String {
        return mergedEntries().joinToString("\n") { it.formatted }
    }

    /** [GlassDiag] 临时诊断：诊断行按时间并入普通行，导出时保持可读的先后顺序。 */
    private fun mergedEntries(): List<LogEntry> {
        if (diagEntries.isEmpty()) return entries.toList()
        return (entries + diagEntries).distinct().sortedBy { it.timestamp }
    }

    @Synchronized
    fun clear() {
        entries.clear()
        diagEntries.clear()
    }
}
