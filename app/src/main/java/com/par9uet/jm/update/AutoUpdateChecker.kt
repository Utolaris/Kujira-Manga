package com.par9uet.jm.update

import com.par9uet.jm.BuildConfig
import com.par9uet.jm.storage.LocalSettingManager
import com.par9uet.jm.utils.log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 冷启动后的静默更新检查（L2）。
 *
 * - 由 [com.par9uet.jm.startup.PostStartupCoordinator] 在进程首次启动时调用一次；
 *   从后台唤起不会再次进入。
 * - 设置关闭、网络失败、解析失败一律静默：不写 Error、不弹窗、不 toast。
 * - 仅有新版本时把 [GithubRelease] 写入 [prompt]，由 App 组合根展示引导弹窗。
 */
class AutoUpdateChecker(
    private val releaseSource: ReleaseSource,
    private val localSettingManager: LocalSettingManager,
) {
    private val _prompt = MutableStateFlow<GithubRelease?>(null)
    val prompt = _prompt.asStateFlow()

    suspend fun checkOnce() {
        if (!localSettingManager.currentAutoCheckUpdateEnabled()) return
        try {
            val release = releaseSource.latest()
            val hasUpdate = compareVersion(release.version, BuildConfig.VERSION_NAME) > 0
            if (hasUpdate) {
                _prompt.value = release
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            log("自动检查更新", "静默失败：${error.message}")
        }
    }

    fun dismiss() {
        _prompt.value = null
    }
}
