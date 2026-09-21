package com.par9uet.jm.ui.viewModel

import androidx.lifecycle.ViewModel
import com.par9uet.jm.core.ToastManager
import com.par9uet.jm.storage.AppSecurityEditor
import com.par9uet.jm.storage.AppSecurityPreferences
import com.par9uet.jm.storage.AppLockState
import kotlinx.coroutines.flow.StateFlow

/** L2：应用锁设置；失败提示由 VM 统一发 Toast，Screen 只渲染与提交。 */
class AppLockSettingViewModel(
    appSecurityPreferences: AppSecurityPreferences,
    private val appSecurityEditor: AppSecurityEditor,
    private val toastManager: ToastManager,
) : ViewModel() {
    val appLock: StateFlow<AppLockState> = appSecurityPreferences.appLock

    fun setPassword(password: String, length: Int): Boolean =
        if (appSecurityEditor.setPassword(password, length)) true
        else fail()

    fun setPattern(pattern: String): Boolean =
        if (appSecurityEditor.setPattern(pattern)) true else fail()

    fun removePassword(): Boolean =
        if (appSecurityEditor.removePassword()) true else fail()

    fun removePattern(): Boolean =
        if (appSecurityEditor.removePattern()) true else fail()

    fun selectUnlockMode(mode: String): Boolean =
        if (appSecurityEditor.selectUnlockMode(mode)) true else fail()

    fun setAppLockEnabled(enabled: Boolean): Boolean =
        if (appSecurityEditor.setAppLockEnabled(enabled)) true else fail()

    fun disableAndClearAppLock(): Boolean =
        if (appSecurityEditor.disableAndClearAppLock()) true else fail()

    private fun fail(): Boolean {
        toastManager.showAsync("应用锁设置保存失败，请重试")
        return false
    }
}
