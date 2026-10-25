package com.par9uet.jm.cache

import android.content.Context
import android.provider.DocumentsContract
import com.par9uet.jm.cache.atom.ExternalDownloadSize
import com.par9uet.jm.core.concurrent.backgroundIoDispatcher
import kotlinx.coroutines.withContext

/**
 * 用户把下载目录指到应用私有目录之外时，下载字节不再出现在 `cacheDir` 任何分区下。
 * 本实现把 SAF 树的实际占用接回统计口径，让「已下载的漫画」与饼图分母都不再漏算。
 *
 * 授权失效时**不**静默返回 0：那会让用户以为缓存凭空消失，实际是数不到。
 * 宁可抛给上层显示错误，也不要给一个看起来正常的假数字。
 */
class DocumentTreeDownloadSize(private val context: Context) : ExternalDownloadSize {
    override suspend fun sizeBytes(): Long = withContext(backgroundIoDispatcher()) {
        val treeUri = getDownloadTreeUri(context) ?: return@withContext 0L
        val root = DocumentsContract.buildDocumentUriUsingTree(
            treeUri,
            DocumentsContract.getTreeDocumentId(treeUri),
        )
        // cachePathSize 会把查询失败吞成 0（迁移时单本漫画读不到不该中断整趟），但在这里
        // 0 会被用户读成「下载占用为零」。先探一次可访问性，把「数不到」和「真的是空」分开。
        check(inspectCachePath(context, root.toString()) == CachePathAccess.READABLE) {
            "无法读取自定义下载目录，请检查目录授权"
        }
        cachePathSize(context, root.toString())
    }
}
