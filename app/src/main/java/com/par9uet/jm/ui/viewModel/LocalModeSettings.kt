package com.par9uet.jm.ui.viewModel

import com.par9uet.jm.session.LocalModeTransition
import kotlinx.coroutines.flow.StateFlow

/**
 * L1 设置页需要的本地模式状态与动作。实现放在组合根：Screen 不服务定位，
 * 也不直接依赖 `LocalModeCoordinator` / `LocalFavoriteOperationGate`。
 */
interface LocalModeSettings {
    val isLocalMode: StateFlow<Boolean>
    val transition: StateFlow<LocalModeTransition>
    val isTransitioning: StateFlow<Boolean>

    fun enter()

    /** Starts the durable exit job; false means the scheduler could not enqueue it. */
    fun requestExit(): Boolean
}
