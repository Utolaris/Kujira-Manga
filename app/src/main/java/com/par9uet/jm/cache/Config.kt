package com.par9uet.jm.cache

import android.content.Context
import androidx.core.content.edit
import com.par9uet.jm.utils.tryCreateDir
import java.io.File

private const val DOWNLOAD_TREE_PREFERENCES = "download_storage"
private const val DOWNLOAD_TREE_URI_KEY = "tree_uri"
private const val CACHE_MIGRATION_REQUEST_ID_KEY = "cache_migration_request_id"

fun getCommonCacheDir(context: Context) = tryCreateDir(File(context.cacheDir, "common"))

/**
 * 默认离线下载目录放在应用私有持久化目录 `filesDir/download`，而非 `cacheDir/download`：
 * 系统存储回收与手机自带清理软件会直接清空 `cacheDir`，导致已下载漫画被误删。
 */
fun getDownloadDir(context: Context) = tryCreateDir(File(context.filesDir, "download"))

/** 历史版本的默认下载目录（位于 `cacheDir/download`），仅用于一次性迁移与兼容清理。 */
fun getLegacyCacheDownloadDir(context: Context): File = File(context.cacheDir, "download")

/**
 * 将历史版本落在 `cacheDir/download` 下的已下载漫画无损迁入 `filesDir/download`。
 * 仅在未配置自定义 SAF 目录且旧目录非空时执行；幂等、异常静默，不阻断启动。
 */
fun migrateLegacyDownloadDirIfNeeded(context: Context) {
    if (getDownloadTreeUri(context) != null) return
    val legacy = getLegacyCacheDownloadDir(context)
    if (!legacy.isDirectory) return
    val entries = legacy.listFiles().orEmpty()
    if (entries.isEmpty()) {
        runCatching { legacy.delete() }
        return
    }
    val targetRoot = getDownloadDir(context)
    for (entry in entries) {
        val dest = File(targetRoot, entry.name)
        if (dest.exists()) continue
        runCatching {
            if (!entry.renameTo(dest)) {
                if (entry.isDirectory) {
                    entry.copyRecursively(dest, overwrite = false)
                    entry.deleteRecursively()
                } else {
                    entry.copyTo(dest, overwrite = false)
                    entry.delete()
                }
            }
        }
    }
    if (legacy.listFiles().isNullOrEmpty()) {
        runCatching { legacy.delete() }
    }
}

fun getDownloadTreeUri(context: Context): android.net.Uri? =
    context.getSharedPreferences(DOWNLOAD_TREE_PREFERENCES, Context.MODE_PRIVATE)
        .getString(DOWNLOAD_TREE_URI_KEY, null)
        ?.takeIf { it.isNotBlank() }
        ?.let(android.net.Uri::parse)

fun setDownloadTreeUri(context: Context, uri: String) {
    context.getSharedPreferences(DOWNLOAD_TREE_PREFERENCES, Context.MODE_PRIVATE)
        .edit {
            putString(DOWNLOAD_TREE_URI_KEY, uri)
        }
}

/**
 * 最后一次提交的迁移任务 id。WorkManager 不保证同名任务的顺序，只有记下这个 id 才能在
 * 进程重启之后认出"这次"的结果，而不是从历史记录里随便挑一条当结果。
 */
fun getCacheMigrationRequestId(context: Context): String? =
    context.getSharedPreferences(DOWNLOAD_TREE_PREFERENCES, Context.MODE_PRIVATE)
        .getString(CACHE_MIGRATION_REQUEST_ID_KEY, null)

fun setCacheMigrationRequestId(context: Context, requestId: String) {
    context.getSharedPreferences(DOWNLOAD_TREE_PREFERENCES, Context.MODE_PRIVATE)
        .edit {
            putString(CACHE_MIGRATION_REQUEST_ID_KEY, requestId)
        }
}
