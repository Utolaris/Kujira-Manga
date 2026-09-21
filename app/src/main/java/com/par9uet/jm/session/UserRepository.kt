package com.par9uet.jm.session
import com.par9uet.jm.core.model.SignInData
import com.par9uet.jm.core.network.AuthAttemptOrigin
import com.par9uet.jm.core.network.NetWorkResult
import com.par9uet.jm.data.models.ActionResult
import com.par9uet.jm.data.models.ComicPage
import com.par9uet.jm.data.models.CommentPage
import com.par9uet.jm.retrofit.model.LoginResponse
import okhttp3.Cookie

/** Isolated authenticated session that can be promoted after the caller validates its generation. */
data class CandidateSession(
    val loginResponse: LoginResponse,
    val embeddedCookies: List<Cookie> = emptyList(),
)

/**
 * 会话仓库。
 *
 * 对外方法只返回领域类型（`SignInData` / `ComicPage` / `CommentPage` / `ActionResult`），
 * 登录凭据 `LoginResponse` 只在本包内传递，不进表现层。
 */
interface UserRepository {
    suspend fun login(username: String, password: String): NetWorkResult<CandidateSession>

    /**
     * 验证凭据但不改变活动会话，返回候选认证结果（含 cookie 快照）。
     *
     * @param origin 触发来源。只进日志与登录密度统计 —— 服务端对高频 `/login` 的软拒绝
     *   与「凭据真的错误」报文完全一致，无法从 `code` 区分。
     */
    suspend fun verifyLogin(
        username: String,
        password: String,
        origin: AuthAttemptOrigin = AuthAttemptOrigin.UNSPECIFIED,
    ): NetWorkResult<CandidateSession> =
        login(username, password)

    /**
     * 冷启动的**廉价会话探活**：只用共享客户端里已持久化的 cookie 打一个需要鉴权的接口，
     * **不发送任何凭据、不产生登录**。
     *
     * 返回值语义：
     * - `Success` → 会话仍然可用
     * - `Error(kind = Authentication)` → 会话已失效（此时才值得动凭据）
     * - 其它 `Error` → 网络/服务端临时问题，不能据此判定会话失效
     *
     * 之所以需要它：`verifyLogin` 与 `login` 是同一个实现（`POST /login`），
     * 每次冷启动都用明文密码登录一次是撞库检测最容易命中的形态。
     * 官方 app 的 JWT 有效期 1 小时，冷启动根本不发登录请求。
     */
    suspend fun probeActiveSession(): NetWorkResult<Unit>

    /**
     * 把已验证的候选会话提升为活动会话（持久化 cookie 并同步活动客户端）。
     * 调用方必须确认 session generation 仍然有效。
     *
     * @return true 仅当会话可被认定为「真的可用」（cookie 非空且写入成功）。
     */
    fun activateVerifiedSession(verified: CandidateSession): Boolean

    /** Clears client-side session state without performing a network logout request. */
    fun clearSession()

    suspend fun getHistoryComicList(page: Int = 1): NetWorkResult<ComicPage>
    suspend fun deleteHistoryComic(id: Int): NetWorkResult<Unit>
    suspend fun getHistoryCommentList(
        page: Int = 1,
        userId: Int
    ): NetWorkResult<CommentPage>

    suspend fun getSignData(
        userId: Int,
    ): NetWorkResult<SignInData>

    suspend fun signIn(
        userId: Int,
        dailyId: Int,
    ): NetWorkResult<ActionResult>
}
