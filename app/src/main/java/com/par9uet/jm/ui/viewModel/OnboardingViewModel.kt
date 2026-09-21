package com.par9uet.jm.ui.viewModel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.par9uet.jm.core.ToastManager
import com.par9uet.jm.session.SessionReadiness
import com.par9uet.jm.session.UserManager
import com.par9uet.jm.storage.AppSecurityEditor
import com.par9uet.jm.storage.AppSecurityPreferences
import com.par9uet.jm.storage.AppLockState
import com.par9uet.jm.storage.LocalSettingManager
import com.par9uet.jm.storage.MiscSettingsState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * L2：首次启动引导。完成标记、剪贴板开关、应用锁迁移与登录态都在此判定；
 * Screen 只负责步骤 UI 与事件提交。
 */
class OnboardingViewModel(
    private val localSettingManager: LocalSettingManager,
    private val appSecurityEditor: AppSecurityEditor,
    private val appSecurityPreferences: AppSecurityPreferences,
    private val toastManager: ToastManager,
    userManager: UserManager,
) : ViewModel() {
    val appLock: StateFlow<AppLockState> = appSecurityPreferences.appLock
    val misc: StateFlow<MiscSettingsState> = localSettingManager.misc
    val isSignedIn: StateFlow<Boolean> = userManager.authState
        .map { it != SessionReadiness.Unauthenticated }
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            userManager.authState.value != SessionReadiness.Unauthenticated,
        )

    fun completeOnboarding() {
        localSettingManager.updateOnboardingCompleted(true)
    }

    fun setClipboardAutoDetect(enabled: Boolean): Boolean {
        if (localSettingManager.updateClipboardAutoDetectEnabled(enabled)) return true
        toastManager.showAsync("设置保存失败，请重试")
        return false
    }

    fun toggleAppLockFromOnboarding(
        requested: Boolean,
        alreadyEnabled: Boolean,
        passwordSet: Boolean,
        patternSet: Boolean,
    ): OnboardingAppLockResult? {
        if (!requested && alreadyEnabled) {
            toastManager.showAsync("已启用应用锁，引导中不能直接关闭，请先解锁后在设置中修改")
            return null
        }
        val ok = if (requested) {
            appSecurityEditor.setAppLockEnabled(true)
        } else {
            appSecurityEditor.disableAndClearAppLock()
        }
        if (!ok) {
            toastManager.showAsync("应用锁设置保存失败，请重试")
            return null
        }
        // 以 store 为准：无凭据时编辑器可能拒绝启用，不能用 requested 当结果。
        val actual = appSecurityPreferences.appLock.value
        return OnboardingAppLockResult(
            enabled = actual.enabled,
            passwordSet = actual.password.isNotEmpty(),
            patternSet = actual.pattern.isNotEmpty(),
        )
    }

    fun setOnboardingPassword(
        password: String,
        passwordLength: Int,
        patternSet: Boolean,
    ): OnboardingAppLockCredentialResult {
        return if (appSecurityEditor.setPassword(password, passwordLength)) {
            OnboardingAppLockCredentialResult(ok = true, passwordSet = true, patternSet = patternSet)
        } else {
            toastManager.showAsync("应用锁设置保存失败，请重试")
            OnboardingAppLockCredentialResult(ok = false, passwordSet = false, patternSet = patternSet)
        }
    }

    fun setOnboardingPattern(
        pattern: String,
        passwordSet: Boolean,
    ): OnboardingAppLockCredentialResult {
        return if (appSecurityEditor.setPattern(pattern)) {
            OnboardingAppLockCredentialResult(ok = true, passwordSet = passwordSet, patternSet = true)
        } else {
            toastManager.showAsync("应用锁设置保存失败，请重试")
            OnboardingAppLockCredentialResult(ok = false, passwordSet = passwordSet, patternSet = false)
        }
    }

    fun selectOnboardingUnlockMode(mode: String): Boolean {
        if (appSecurityEditor.selectUnlockMode(mode)) return true
        toastManager.showAsync("应用锁设置保存失败，请重试")
        return false
    }
}

data class OnboardingAppLockResult(
    val enabled: Boolean,
    val passwordSet: Boolean,
    val patternSet: Boolean,
)

data class OnboardingAppLockCredentialResult(
    val ok: Boolean,
    val passwordSet: Boolean,
    val patternSet: Boolean,
)
