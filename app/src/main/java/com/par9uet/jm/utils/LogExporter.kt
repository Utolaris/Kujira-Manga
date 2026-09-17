package com.par9uet.jm.utils

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 把内存日志落到应用私有目录，供「日志」页导出后离线取证。
 *
 * 为什么需要它：`log()` 的另一个出口是 logcat，但 logcat 不可靠——
 * 实测有设备（HyperOS 构建）`logd` 不再接收应用日志，清空缓冲后启动应用 10s 仍为 0 行；
 * 常规设备上缓冲也会被系统轮转清空。日志页只有「复制」按钮时，日志只能进剪贴板，
 * 排查现场问题拿不到可读的文件。
 *
 * 导出目标固定在 `filesDir/logs/`：debug 包可用 `adb exec-out run-as <appId> cat ...`
 * 直接取回，不依赖 logcat，也不依赖外部存储权限（`/sdcard/Android/data/<pkg>` 在
 * Android 11+ 对 adb shell 不可读）。
 */
object LogExporter {

    private const val DIR_NAME = "logs"
    private const val MAX_FILES = 5

    /** @return 写出的文件；失败时抛异常，由调用方决定如何提示。 */
    fun export(context: Context, text: String): File {
        val dir = File(context.filesDir, DIR_NAME).apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())
        val file = File(dir, "kujira-log-$stamp.txt")
        file.writeText(text)
        prune(dir)
        return file
    }

    /** 只保留最近 [MAX_FILES] 份，避免反复导出把私有目录撑满。 */
    private fun prune(dir: File) {
        val files = dir.listFiles { f -> f.isFile && f.name.startsWith("kujira-log-") }
            ?.sortedByDescending { it.lastModified() }
            ?: return
        files.drop(MAX_FILES).forEach { it.delete() }
    }
}
