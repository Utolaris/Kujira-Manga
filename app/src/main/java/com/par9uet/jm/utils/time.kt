package com.par9uet.jm.utils

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Locale

fun translateCommentTime(time: String): String {
    return try {
        val inputFormat = SimpleDateFormat("MMM dd, yyyy", Locale.ENGLISH)
        val outputFormat = SimpleDateFormat("yyyy-MM-dd", Locale.CHINESE)
        val date = inputFormat.parse(time)
        if (date != null) {
            outputFormat.format(date)
        } else {
            ""
        }
    } catch (e: Exception) {
        Log.d("comment", "评论时间解析错误，原时间：$time ")
        "" // 或者处理异常情况
    }
}

/**
 * 上游 `addtime` 原文 →「x年x月x日上架」。空值返回空串，UI 不展示。
 * 兼容：
 * - Unix 秒 / 毫秒时间戳（如 `1772762965`）
 * - `2024-03-15`、`2024/03/15`、`2024-03-15 12:00:00`
 */
fun formatAlbumAddTimeDisplay(raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return ""
    if (trimmed.all { it.isDigit() }) {
        val epoch = trimmed.toLongOrNull() ?: return ""
        // 10 位约到 2286 年为秒；更长按毫秒处理。
        val millis = if (trimmed.length >= 13) epoch else epoch * 1000L
        val formatted = runCatching {
            java.text.SimpleDateFormat("yyyy年M月d日", Locale.CHINA)
                .format(java.util.Date(millis))
        }.getOrNull()
        if (formatted != null) return "${formatted}上架"
    }
    val datePart = trimmed.substringBefore(' ').substringBefore('T')
    val parts = datePart.split(Regex("[/.\\-]"))
    if (parts.size >= 3) {
        val year = parts[0]
        val month = parts[1].toIntOrNull() ?: parts[1]
        val day = parts[2].toIntOrNull() ?: parts[2]
        return "${year}年${month}月${day}日上架"
    }
    return "${trimmed}上架"
}