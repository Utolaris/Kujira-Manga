package com.par9uet.jm.core.network

/**
 * 一次凭据登录（`POST /login`）的触发来源。**只用于日志与计数，不参与任何业务分支。**
 *
 * 存在的理由：服务端对高频 `/login` 会软拒绝，而软拒绝的报文与「真的输错密码」
 * 长得一模一样 —— 都是 `{"code":401,...,"errorMsg":"無效的用戶名和\/或密碼！"}`。
 * 于是「同一份密码 40 秒前成功、中间被拒、27 秒后又成功」这种现场只能靠
 * **登录频率 + 来源**定性，靠看 401 是看不出来的。
 *
 * 另外官方实现（JMComic3 v2.1.8）根本不做请求驱动的自动重登：
 * JWT 客户端有效期 1 小时，到期才由 `GlobalContext` 静默重登一次，
 * 且服务器 401 被明确排除在重试/登出之外（`api/HttpUtil.ts:160,215,269`）。
 * 本项目是「每个 401 都可能触发一次登录」，所以频率天然高一个量级。
 */
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
