package com.par9uet.jm.utils

import android.content.Context
import com.google.gson.GsonBuilder
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 把内存日志导出为 **AI 友好的结构化 JSON**（`kujira-log/1`）。
 *
 * 为什么是 JSON 而不是纯文本：
 * - 纯文本 `[ts][level][tag] msg` 要靠正则再拆一遍，字段边界模糊、多行 message 会碎掉；
 * - 结构化后 AI 可直接按 `level` / `tag` / `errors` 切片，不必先做格式还原。
 *
 * 内容排布刻意为「先结论、后全量」：
 * 1. `app` / `summary` —— 环境与体量，决定要不要读下去；
 * 2. `errors` —— `level=E` 的条目（按时间正序），排查入口；
 * 3. `entries` —— 全量时序，含 errors 的超集，`seq` 为写入序号。
 *
 * 导出目标固定在 `filesDir/logs/`：debug 包可用 `adb exec-out run-as <appId> cat ...`
 * 直接取回，不依赖 logcat，也不依赖外部存储权限。
 */
object LogExporter {

    private const val DIR_NAME = "logs"
    private const val MAX_FILES = 5
    private const val SCHEMA = "kujira-log/1"
    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

    data class AppMeta(
        val packageName: String,
        val versionName: String?,
        val versionCode: Long?,
        val debug: Boolean,
    )

    data class ExportEntry(
        val seq: Int,
        val ts: String,
        val level: String,
        val tag: String,
        val msg: String,
    )

    data class Summary(
        val entryCount: Int,
        val errorCount: Int,
        val levelCounts: Map<String, Int>,
        val tagCounts: Map<String, Int>,
    )

    data class Document(
        val schema: String,
        val readme: String,
        val exportedAt: String,
        val app: AppMeta,
        val summary: Summary,
        val errors: List<ExportEntry>,
        val entries: List<ExportEntry>,
    )

    /** 纯序列化，便于单测；不碰文件系统。 */
    fun toJson(entries: List<LogEntry>, app: AppMeta, exportedAtMillis: Long = System.currentTimeMillis()): String {
        val exportEntries = entries.map { it.toExport() }
        val levelCounts = entries.groupingBy { it.level }.eachCount().toSortedMap()
        val tagCounts = entries.groupingBy { it.tag }.eachCount()
            .entries
            .sortedByDescending { it.value }
            .associate { it.key to it.value }
        val document = Document(
            schema = SCHEMA,
            readme = "seq 为写入序号（升序=时间序）；errors 是 entries 中 level=E 的子集；msg 已按写入侧脱敏策略处理。",
            exportedAt = formatIso(exportedAtMillis),
            app = app,
            summary = Summary(
                entryCount = entries.size,
                errorCount = entries.count { it.level == "E" },
                levelCounts = levelCounts,
                tagCounts = tagCounts,
            ),
            errors = exportEntries.filter { it.level == "E" },
            entries = exportEntries,
        )
        return gson.toJson(document)
    }

    /** @return 写出的文件；失败时抛异常，由调用方决定如何提示。 */
    fun export(context: Context, entries: List<LogEntry>): File {
        val dir = File(context.filesDir, DIR_NAME).apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())
        val file = File(dir, "kujira-log-$stamp.json")
        file.writeText(toJson(entries, appMeta(context)))
        prune(dir)
        return file
    }

    private fun appMeta(context: Context): AppMeta {
        val info = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0)
        }.getOrNull()
        val versionCode = if (android.os.Build.VERSION.SDK_INT >= 28) {
            info?.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info?.versionCode?.toLong()
        }
        return AppMeta(
            packageName = context.packageName,
            versionName = info?.versionName,
            versionCode = versionCode,
            debug = com.par9uet.jm.BuildConfig.DEBUG,
        )
    }

    /** 只保留最近 [MAX_FILES] 份，避免反复导出把私有目录撑满。 */
    private fun prune(dir: File) {
        val files = dir.listFiles { f -> f.isFile && f.name.startsWith("kujira-log-") }
            ?.sortedByDescending { it.lastModified() }
            ?: return
        files.drop(MAX_FILES).forEach { it.delete() }
    }

    private fun LogEntry.toExport() = ExportEntry(
        seq = seq,
        ts = timestamp,
        level = level,
        tag = tag,
        msg = message,
    )
}
