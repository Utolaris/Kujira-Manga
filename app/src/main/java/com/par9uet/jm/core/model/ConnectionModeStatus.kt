package com.par9uet.jm.core.model

import kotlinx.coroutines.flow.StateFlow

/**
 * 连接模式只读投影。L3 用例只依赖本契约，不反向依赖 L2 的 LocalModeGate。
 */
interface ConnectionModeStatus {
    val isLocalMode: Boolean
    val isLocalModeFlow: StateFlow<Boolean>
}

/** 本地模式下需要登录态能力的统一提示。 */
const val LOCAL_MODE_UNAVAILABLE_MESSAGE = "本地模式下该功能不可用"

/** 切回网络模式并完成本地→远端补偿同步。实现在 session/LocalModeCoordinator。 */
interface LocalModeExit {
    suspend fun exitLocalMode()

    /** 手动登录已提交会话时，复用该账号与 generation，避免再次请求登录。 */
    suspend fun exitLocalModeAfterLogin(accountId: Int, generation: Long)
}

/** Starts the user-confirmed exit as durable foreground work. */
fun interface LocalModeExitScheduler {
    fun request(): Boolean
}

/** 强制收藏夹与远端对齐（丢弃未同步变更，可关闭本地模式）。 */
fun interface FavoriteForceAlign {
    suspend fun forceAlignFavoritesWithRemote()
}
