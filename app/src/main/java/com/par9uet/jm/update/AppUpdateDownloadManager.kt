package com.par9uet.jm.update

import android.content.Context
import com.par9uet.jm.cache.getCommonCacheDir
import com.par9uet.jm.core.ToastManager
import com.par9uet.jm.network.applyAppHttpDefaults
import com.par9uet.jm.utils.APP_UPDATE_NOTIFICATION_ID
import com.par9uet.jm.utils.cancelProgressNotification
import com.par9uet.jm.utils.formatBytes
import com.par9uet.jm.utils.showProgressNotification
import com.par9uet.jm.utils.showUpdateDownloadedNotification
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import kotlin.math.roundToInt

enum class AppUpdateDownloadStatus {
    Idle,
    Downloading,
    Paused,
    Completed,
    Canceled,
    Error
}

data class AppUpdateDownloadState(
    val status: AppUpdateDownloadStatus = AppUpdateDownloadStatus.Idle,
    val version: String = "",
    val fileName: String = "",
    val downloadUrl: String = "",
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val speedBytesPerSecond: Long = 0L,
    val background: Boolean = false,
    val errorMessage: String = "",
    val savedPath: String = ""
) {
    val progress: Float
        get() = if (totalBytes > 0L) downloadedBytes.toFloat() / totalBytes else 0f
}

interface AppUpdateDownloads {
    val state: StateFlow<AppUpdateDownloadState>
    fun start(request: AppUpdateDownloadRequest)
    fun pause()
    fun resume()
    fun cancel()
    fun sendToBackground()
}

class AppUpdateDownloadManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val toastManager: ToastManager,
    private val dohManager: com.par9uet.jm.network.DohManager,
    connectionPool: ConnectionPool,
) : AppUpdateDownloads {
    // APK 大包给更长 callTimeout；readTimeout 负责卡死检测。
    private val client = OkHttpClient.Builder()
        .applyAppHttpDefaults(
            dns = dohManager,
            connectionPool = connectionPool,
            connectSeconds = 15,
            readSeconds = 60,
            writeSeconds = 30,
            callSeconds = 600,
        )
        .build()
    private val jobs = UpdateDownloadJobGate()
    private val activeCall = java.util.concurrent.atomic.AtomicReference<okhttp3.Call?>(null)

    private val _state = MutableStateFlow(AppUpdateDownloadState())
    override val state = _state.asStateFlow()

    override fun start(request: AppUpdateDownloadRequest) {
        if (request.downloadUrl.isBlank()) {
            toastManager.showAsync("未找到 APK 下载链接")
            return
        }
        jobs.paused = false
        activeCall.getAndSet(null)?.cancel()
        jobs.start(scope) {
            download(request)
        }
        _state.value = AppUpdateDownloadState(
            status = AppUpdateDownloadStatus.Downloading,
            version = request.version,
            fileName = request.fileName,
            downloadUrl = request.downloadUrl
        )
    }

    override fun pause() {
        jobs.paused = true
        _state.update {
            if (it.status == AppUpdateDownloadStatus.Downloading) {
                it.copy(status = AppUpdateDownloadStatus.Paused, speedBytesPerSecond = 0L)
            } else {
                it
            }
        }
    }

    override fun resume() {
        jobs.paused = false
        _state.update {
            if (it.status == AppUpdateDownloadStatus.Paused) {
                it.copy(status = AppUpdateDownloadStatus.Downloading)
            } else {
                it
            }
        }
    }

    override fun cancel() {
        activeCall.getAndSet(null)?.cancel()
        jobs.cancel()
        val path = _state.value.savedPath.ifBlank {
            File(getCommonCacheDir(context), "updates/${safeUpdateFileName(_state.value.fileName)}").absolutePath
        }
        runCatching { File(path).takeIf { it.isFile }?.delete() }
        _state.update { it.copy(status = AppUpdateDownloadStatus.Canceled, speedBytesPerSecond = 0L) }
        cancelProgressNotification(context, APP_UPDATE_NOTIFICATION_ID)
    }

    override fun sendToBackground() {
        _state.update { it.copy(background = true) }
        notifyProgress()
    }

    private suspend fun download(request: AppUpdateDownloadRequest) = withContext(Dispatchers.IO) {
        val file = File(getCommonCacheDir(context), "updates/${safeUpdateFileName(request.fileName)}")
        file.parentFile?.mkdirs()
        val existing = if (file.isFile && file.length() > 0L) file.length() else 0L
        val requestBuilder = Request.Builder()
            .url(request.downloadUrl)
            .header("User-Agent", "kujira-manga-android")
        if (existing > 0L) {
            requestBuilder.header("Range", "bytes=$existing-")
        }
        val call = client.newCall(requestBuilder.build())
        activeCall.set(call)
        currentCoroutineContext().job.invokeOnCompletion { cause ->
            if (cause != null) {
                call.cancel()
                activeCall.compareAndSet(call, null)
            }
        }
        try {
            call.execute().use { response ->
                if (response.code == 416) {
                    file.delete()
                    throw IllegalStateException("断点无效，已清除后请重试")
                }
                if (!response.isSuccessful) {
                    error("下载失败：HTTP ${response.code}")
                }
                val body = response.body ?: error("下载失败：响应体为空")
                val isPartial = response.code == 206
                if (!isPartial && existing > 0L) {
                    // 服务器不支持 Range，从头下载。
                    file.delete()
                }
                val resumeFrom = if (isPartial) existing else 0L
                val totalBytes = if (isPartial) {
                    body.contentLength().takeIf { it > 0L }?.plus(resumeFrom) ?: 0L
                } else {
                    body.contentLength().takeIf { it > 0L } ?: 0L
                }
                var downloaded = resumeFrom
                var windowBytes = 0L
                var lastTick = System.currentTimeMillis()
                body.byteStream().use { input ->
                    FileOutputStream(file, resumeFrom > 0L).use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            while (jobs.paused && !jobs.canceled) {
                                delay(250)
                            }
                            if (jobs.canceled) {
                                // 保留半截文件供 Range 续传由 cancel() 统一删除策略；
                                // 这里主动删除避免脏包。
                                file.delete()
                                return@withContext
                            }
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            downloaded += read
                            windowBytes += read
                            val now = System.currentTimeMillis()
                            if (now - lastTick >= 500L) {
                                val speed = (windowBytes * 1000f / (now - lastTick)).roundToInt().toLong()
                                _state.update {
                                    it.copy(
                                        downloadedBytes = downloaded,
                                        totalBytes = totalBytes,
                                        speedBytesPerSecond = speed,
                                        status = AppUpdateDownloadStatus.Downloading
                                    )
                                }
                                notifyProgress()
                                windowBytes = 0L
                                lastTick = now
                            }
                        }
                    }
                }
                if (jobs.canceled) {
                    file.delete()
                    return@withContext
                }
                _state.update {
                    it.copy(
                        status = AppUpdateDownloadStatus.Completed,
                        downloadedBytes = downloaded,
                        totalBytes = totalBytes,
                        speedBytesPerSecond = 0L,
                        savedPath = file.absolutePath
                    )
                }
                cancelProgressNotification(context, APP_UPDATE_NOTIFICATION_ID)
                showUpdateDownloadedNotification(
                    context = context,
                    version = request.version,
                    savedPath = file.absolutePath
                )
            }
        } catch (cancelled: CancellationException) {
            // 进程/任务取消：保留半截文件，便于下次 Range 续传。
            throw cancelled
        } catch (error: Exception) {
            if (!jobs.canceled) {
                _state.update {
                    it.copy(
                        status = AppUpdateDownloadStatus.Error,
                        speedBytesPerSecond = 0L,
                        errorMessage = error.message ?: "下载失败"
                    )
                }
                cancelProgressNotification(context, APP_UPDATE_NOTIFICATION_ID)
            }
        }
    }

    private fun notifyProgress() {
        val state = _state.value
        if (!state.background) return
        showProgressNotification(
            context = context,
            notificationId = APP_UPDATE_NOTIFICATION_ID,
            title = "正在下载更新 ${state.version}",
            text = "${(state.progress * 100).roundToInt()}% · ${formatBytes(state.speedBytesPerSecond)}/s",
            progressPercent = (state.progress * 100).roundToInt()
        )
    }
}

internal class UpdateDownloadJobGate {
    @Volatile
    var canceled = false

    @Volatile
    var paused = false

    private val writerMutex = Mutex()
    private var activeJob: Job? = null

    fun start(scope: CoroutineScope, block: suspend () -> Unit) {
        canceled = true
        activeJob?.cancel()
        val job = scope.launch {
            writerMutex.withLock {
                canceled = false
                block()
            }
        }
        activeJob = job
    }

    fun cancel() {
        canceled = true
        paused = false
        activeJob?.cancel()
    }
}

internal fun safeUpdateFileName(name: String): String {
    return name.substringAfterLast('/').substringAfterLast('\\')
        .replace("..", "_")
        .ifBlank { "update.apk" }
}

data class AppUpdateDownloadRequest(
    val version: String,
    val fileName: String,
    val downloadUrl: String
)
