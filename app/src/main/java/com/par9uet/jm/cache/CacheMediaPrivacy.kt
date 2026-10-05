package com.par9uet.jm.cache

import android.content.Context
import android.provider.DocumentsContract
import androidx.core.net.toUri
import com.par9uet.jm.database.model.DownloadComic
import com.par9uet.jm.utils.logError
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val NO_MEDIA = ".nomedia"
private val markerLock = Any()

internal fun ensureNoMediaFile(directory: File) {
    check(directory.isDirectory) { "漫画缓存目录不存在" }
    val marker = File(directory, NO_MEDIA)
    if (!marker.exists()) marker.createNewFile()
    check(marker.isFile) { "无法创建漫画缓存相册屏蔽文件" }
}

/** Only call for an app-owned comic root, never for the user-selected parent tree. */
fun ensureComicCacheNoMedia(context: Context, rootPath: String) = synchronized(markerLock) {
    if (!isDocumentCachePath(rootPath)) {
        ensureNoMediaFile(File(rootPath))
    } else {
        val existing = findCacheChildPathOrThrow(context, rootPath, NO_MEDIA)
        val marker = existing ?: getOrCreateCacheFile(context, rootPath, NO_MEDIA, "application/octet-stream")
        val valid = context.contentResolver.query(
            marker.toUri(),
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE),
            null, null, null,
        )?.use { cursor ->
            cursor.moveToFirst() && cursor.getString(0) == NO_MEDIA &&
                cursor.getString(1) != DocumentsContract.Document.MIME_TYPE_DIR
        } == true
        if (!valid && existing == null) deleteCachePath(context, marker)
        check(valid) { "该缓存目录无法屏蔽相册收录，请更换目录" }
        if (existing == null) openCacheOutputStream(context, marker).use { }
    }
}

/** Retry on each startup so revoked permissions or a temporary provider outage are recoverable. */
suspend fun protectExistingComicCache(context: Context, records: List<DownloadComic>): Int = withContext(Dispatchers.IO) {
    var failures = 0
    val roots = linkedSetOf<String>()
    val treeRoot = getDownloadTreeUri(context)?.let { tree ->
        DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree)).toString()
    }
    val examinedNames = mutableSetOf<String>()
    records.forEach { record ->
        val name = getComicCacheRootName(record)
        if (treeRoot != null && examinedNames.add(name)) {
            try { findCacheChildPathOrThrow(context, treeRoot, name)?.let { roots += it } }
            catch (error: Exception) { failures++; logError("缓存相册屏蔽", "读取目录失败：${error.message}") }
        }
        listOf(record.coverPath, record.zipPath).filter(String::isNotBlank).forEach { saved ->
            getCacheParentPath(saved)?.let { parent ->
                val parentName = if (isDocumentCachePath(parent)) {
                    runCatching {
                        context.contentResolver.query(parent.toUri(), arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
                            ?.use { if (it.moveToFirst()) it.getString(0) else null }
                    }.getOrElse { failures++; null }
                } else File(parent).name
                if (parentName == name) roots += parent
            }
        }
        File(getDownloadDir(context), name).takeIf(File::isDirectory)?.let { roots += it.absolutePath }
    }
    roots.forEach { root ->
        try { ensureComicCacheNoMedia(context, root) }
        catch (error: Exception) { failures++; logError("缓存相册屏蔽", "补齐失败：${error.message}") }
    }
    failures
}
