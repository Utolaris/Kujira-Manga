package com.par9uet.jm.core.network

/** Credential-login origin, used only for diagnostics and request-density observation. */
enum class AuthAttemptOrigin {
    /** 用户在登录页主动提交。 */
    MANUAL,

    /** 冷启动只读探活判定会话失效后，用本地凭据恢复（每个进程至多一次）。 */
    COLD_START_PROBE,

    /** 某个认证请求拿到 401，走 `UserManager.execute` 的恢复通道重登。 */
    REQUEST_RECOVERY,

    /** 收藏夹同步链路的 401 恢复（`FavoriteSyncController`）。 */
    SYNC_RECOVERY,

    /** 未标注来源（测试，或新增调用点忘了标）。计数与告警仍会生效。 */
    UNSPECIFIED,
}
