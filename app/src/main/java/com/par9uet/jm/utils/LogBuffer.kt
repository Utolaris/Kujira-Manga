package com.par9uet.jm.utils

/**
 * 内存日志环形缓冲。导出走 [LogExporter] 的结构化 JSON，不是纯文本拼接。
 */
data class LogEntry(
    val timestampMillis: Long,
    val tag: String,
    val message: String,
    val level: String,
    val seq: Int,
) {
    /** ISO-8601，导出与排序用。 */
    val timestamp: String get() = formatIso(timestampMillis)

    /** 日志页展示用的短时间戳。 */
    val formatted: String get() = "[${formatClock(timestampMillis)}][$level][$tag] $message"
}

object LogBuffer {
    private const val MAX_ENTRIES = 500
    private val entries = ArrayDeque<LogEntry>()
    private var nextSeq = 0

    @Synchronized
    fun append(tag: String, message: String, level: String = "D") {
        entries.addLast(
            LogEntry(
                timestampMillis = System.currentTimeMillis(),
                tag = tag,
                message = message,
                level = level,
                seq = nextSeq++,
            ),
        )
        while (entries.size > MAX_ENTRIES) {
            entries.removeFirst()
        }
    }

    @Synchronized
    fun appendError(tag: String, message: String) {
        append(tag, message, "E")
    }

    @Synchronized
    fun getLogs(): List<LogEntry> = entries.toList()

    @Synchronized
    fun clear() {
        entries.clear()
        nextSeq = 0
    }
}

internal fun formatIso(millis: Long): String =
    java.time.Instant.ofEpochMilli(millis)
        .atZone(java.time.ZoneId.systemDefault())
        .toOffsetDateTime()
        .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX"))

internal fun formatClock(millis: Long): String =
    java.time.Instant.ofEpochMilli(millis)
        .atZone(java.time.ZoneId.systemDefault())
        .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss.SSS"))
