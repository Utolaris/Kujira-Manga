package com.par9uet.jm.repository.impl

import com.par9uet.jm.core.BaseRepository
import com.par9uet.jm.data.comic.mapper.toComicPage
import com.par9uet.jm.data.comic.mapper.toCommentPage
import com.par9uet.jm.data.models.ActionResult
import com.par9uet.jm.data.models.ComicPage
import com.par9uet.jm.data.models.CommentPage
import com.par9uet.jm.network.AuthenticatedEmbeddedClient
import com.par9uet.jm.network.EmbeddedClientManager
import com.par9uet.jm.session.CandidateSession
import com.par9uet.jm.session.LoginSessionGate
import com.par9uet.jm.session.UserRepository
import com.par9uet.jm.core.model.SignInData
import com.par9uet.jm.core.network.AuthFailure
import com.par9uet.jm.core.network.AuthAttemptOrigin
import com.par9uet.jm.utils.log
import com.par9uet.jm.utils.logError
import com.par9uet.jm.retrofit.model.LoginResponse
import com.par9uet.jm.core.network.NetWorkResult
import com.par9uet.jm.core.network.map
import com.par9uet.jm.retrofit.model.UserHistoryComicListResponse
import com.par9uet.jm.retrofit.model.UserHistoryCommentListResponse
import io.github.jukomu.jmcomic.api.exception.NetworkException
import io.github.jukomu.jmcomic.api.model.FavoriteQuery
import io.github.jukomu.jmcomic.api.model.ForumQuery
import io.github.jukomu.jmcomic.api.model.JmAlbumMeta
import io.github.jukomu.jmcomic.api.model.JmCategoryMeta
import io.github.jukomu.jmcomic.api.model.JmComment
import io.github.jukomu.jmcomic.api.model.JmDailyCheckInStatus
import io.github.jukomu.jmcomic.api.model.JmUserInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class UserRepositoryImpl(
    private val embeddedClientManager: EmbeddedClientManager,
    private val authenticatedEmbeddedClient: AuthenticatedEmbeddedClient,
) : BaseRepository(), UserRepository {
    override suspend fun login(username: String, password: String): NetWorkResult<CandidateSession> =
        authenticateCandidate(username, password, AuthAttemptOrigin.MANUAL)

    override suspend fun verifyLogin(
        username: String,
        password: String,
        origin: AuthAttemptOrigin,
    ): NetWorkResult<CandidateSession> = authenticateCandidate(username, password, origin)

    /**
     * 只读的鉴权探针：`GET /favorite?page=1`。
     *
     * 选这个端点的理由：它的 401 语义已经被 [AuthenticatedEmbeddedClient] +
     * `recoverExpiredSession` 在收藏同步链路里验证过，不会出现「匿名也能 200」的假阳性
     * （相比之下 `notifications/unreadCount`、`useredit/<uid>` 这类接口可能对匿名也返回数据，
     * 拿来做探针会永远判定"会话有效"）。
     *
     * 结果只用于判定，不落任何本地状态；返回的 payload 直接丢弃。
     */
    override suspend fun probeActiveSession(): NetWorkResult<Unit> =
        safeEmbeddedCall("校验登录状态失败") {
            requireNotNull(
                authenticatedEmbeddedClient.withClient { client ->
                    client.getFavorites(FavoriteQuery.Builder().folderId(0).page(1).build())
                }
            )
            Unit
        }

    private suspend fun authenticateCandidate(
        username: String,
        password: String,
        origin: AuthAttemptOrigin,
    ): NetWorkResult<CandidateSession> {
        return withContext(Dispatchers.IO) {
            try {
                // Authentication always runs in an isolated client. Only the generation-checked
                // commit in UserManager promotes these cookies to the shared session.
                when (val result = embeddedClientManager.verifyCandidate(username, password, origin)) {
                    is EmbeddedClientManager.EmbeddedLoginResult.Success -> {
                        val candidate = CandidateSession(
                            loginResponse = result.userInfo.toLoginResponse(),
                            embeddedCookies = result.sessionCookies,
                        )
                        log(
                            LoginSessionGate.TAG,
                            "authenticateCandidate SUCCESS origin=$origin uid=${candidate.loginResponse.uid} " +
                                "username=${candidate.loginResponse.username} " +
                                "cookieNames=${LoginSessionGate.cookieNames(candidate.embeddedCookies)}",
                        )
                        LoginSessionGate.validateCandidate(candidate)?.let { gateError ->
                            logError(LoginSessionGate.TAG, "authenticateCandidate gate failed: ${gateError.message}")
                            return@withContext gateError
                        }
                        NetWorkResult.Success(candidate)
                    }

                    is EmbeddedClientManager.EmbeddedLoginResult.Failure -> {
                        val exception = result.exception
                        logError(
                            LoginSessionGate.TAG,
                            "authenticateCandidate FAILURE origin=$origin businessCode=${result.businessCode} " +
                                "httpCode=${exception.errorCode} message=${exception.message}",
                        )
                        NetWorkResult.Error(
                            message = "内置API登录失败：" + (exception.message ?: "未知错误"),
                            code = result.businessCode ?: exception.errorCode,
                            authFailure = result.classifyAuthFailure()
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logError(LoginSessionGate.TAG, "authenticateCandidate EXCEPTION origin=$origin: ${e.message}")
                NetWorkResult.Error(
                    message = "内置API登录失败：" + (e.message ?: "未知错误"),
                    authFailure = e.classifyAuthFailure()
                )
            }
        }
    }

    /**
     * 把已验证的候选会话提升为活动会话。调用方（UserManager）已确认 generation 有效。
     * @return false 表示 cookie 未能写入活动会话，不得视为登录成功。
     */
    override fun activateVerifiedSession(verified: CandidateSession): Boolean {
        val gateError = LoginSessionGate.validateCandidate(verified)
        if (gateError != null) {
            logError(LoginSessionGate.TAG, "activateVerifiedSession gate failed: ${gateError.message}")
            return false
        }
        return embeddedClientManager.activateCandidateSession(
            cookies = verified.embeddedCookies,
            username = verified.loginResponse.username,
        )
    }

    override fun clearSession() {
        embeddedClientManager.clearSession()
    }

    private fun EmbeddedClientManager.EmbeddedLoginResult.Failure.classifyAuthFailure(): AuthFailure {
        return when {
            // This is the API JSON code captured before JmApiResponse consumed the body.
            businessCode == 401 -> AuthFailure.InvalidCredentials
            // ResponseException.errorCode is the HTTP status in JMComic-Api-Java 1.1.8.
            exception.errorCode == 401 -> AuthFailure.InvalidCredentials
            exception.errorCode in 500..599 -> AuthFailure.TemporaryFailure
            exception.cause is NetworkException || exception.cause is IOException -> AuthFailure.TemporaryFailure
            else -> AuthFailure.Unknown
        }
    }

    private fun Exception.classifyAuthFailure(): AuthFailure {
        return when {
            this is NetworkException || cause is NetworkException -> AuthFailure.TemporaryFailure
            this is SocketTimeoutException || this is ConnectException || this is UnknownHostException -> AuthFailure.TemporaryFailure
            this is IOException || cause is IOException -> AuthFailure.TemporaryFailure
            cause is SocketTimeoutException || cause is ConnectException || cause is UnknownHostException -> AuthFailure.TemporaryFailure
            else -> AuthFailure.Unknown
        }
    }

    override suspend fun getHistoryComicList(page: Int): NetWorkResult<ComicPage> {
        return safeEmbeddedCall("内置 API 获取历史漫画失败") {
            requireNotNull(
                authenticatedEmbeddedClient.withClient { client ->
                    val albumMetas = client.getWatchHistory(page)
                    UserHistoryComicListResponse(
                        list = albumMetas.map { it.toHistoryListItem() },
                    )
                }
            )
        }.map { it.toComicPage() }
    }

    override suspend fun deleteHistoryComic(id: Int): NetWorkResult<Unit> {
        return safeEmbeddedCall("删除历史记录失败") {
            authenticatedEmbeddedClient.withClient { client ->
                client.deleteWatchHistory(id.toString())
            }
            Unit
        }
    }

    override suspend fun getHistoryCommentList(
        page: Int,
        userId: Int
    ): NetWorkResult<CommentPage> {
        return safeEmbeddedCall("内置 API 获取评论历史失败") {
            requireNotNull(
                authenticatedEmbeddedClient.withClient { client ->
                    val query = ForumQuery.user(userId.toString())
                        .page(page)
                        .build()
                    val commentList = client.getComments(query)
                    UserHistoryCommentListResponse(
                        list = commentList.list.map { it.toHistoryCommentListItem() },
                        total = commentList.total
                    )
                }
            )
        }.map { it.toCommentPage() }
    }

    override suspend fun getSignData(userId: Int): NetWorkResult<SignInData> {
        return safeEmbeddedCall("内置 API 获取签到数据失败") {
            requireNotNull(
                authenticatedEmbeddedClient.withClient { client ->
                    val status = client.getDailyCheckInStatus(userId.toString())
                    status.toSignInData()
                }
            )
        }
    }

    override suspend fun signIn(userId: Int, dailyId: Int): NetWorkResult<ActionResult> {
        return safeEmbeddedCall("内置 API 签到失败") {
            authenticatedEmbeddedClient.withClient { client ->
                client.doDailyCheckin(userId.toString(), dailyId.toString())
            }
            ActionResult(isSuccess = true, message = "签到成功")
        }
    }

    private fun JmComment.toHistoryCommentListItem(): UserHistoryCommentListResponse.ListItem {
        return UserHistoryCommentListResponse.ListItem(
            AID = aid(),
            BID = bid(),
            CID = commentId(),
            UID = userId(),
            username = username(),
            nickname = nickname(),
            likes = likes().toString(),
            gender = gender(),
            update_at = updateAt(),
            addtime = postDate(),
            parent_CID = parentCommentId(),
            name = name(),
            content = content(),
            photo = photo() ?: "",
            spoiler = spoiler().toString(),
            replys = replys()?.map { it.toHistoryCommentListItem() }
        )
    }

    private fun JmAlbumMeta.toHistoryListItem(): UserHistoryComicListResponse.ListItem {
        return UserHistoryComicListResponse.ListItem(
            id = id().orEmpty(),
            author = authors().orEmpty().firstOrNull().orEmpty(),
            description = description(),
            name = title().orEmpty(),
            image = image().orEmpty(),
            category = category().toHistoryCategory(),
            category_sub = subCategory().toHistoryCategory()
        )
    }

    private fun JmCategoryMeta?.toHistoryCategory(): UserHistoryComicListResponse.ListItem.Category {
        return UserHistoryComicListResponse.ListItem.Category(
            id = this?.id(),
            title = this?.title()
        )
    }

    private fun JmUserInfo.toLoginResponse(): LoginResponse {
        return LoginResponse(
            uid = uid.toIntOrNull() ?: 0,
            username = username,
            email = email,
            photo = avatarUrl,
            coin = coin.toString(),
            album_favorites = albumFavorites,
            level_name = levelName,
            level = level,
            nextLevelExp = nextLevelExp.toInt(),
            exp = currentExp.toInt(),
            expPercent = expPercent,
            album_favorites_max = maxAlbumFavorites,
        )
    }

    /**
     * 内置 API 的签到状态直接映射成 `core.model.SignInData`。
     * 不再绕一层 wire 的 `SignInDataResponse`（已删除）：此前经它再 `toSignData()`
     * 会让映射规则分两处维护。
     */
    private fun JmDailyCheckInStatus.toSignInData(): SignInData {
        return SignInData(
            dailyId = dailyId,
            threeDaysCoin = threeDaysCoin.toIntOrNull() ?: 0,
            threeDaysExp = threeDaysExp.toIntOrNull() ?: 0,
            sevenDaysCoin = sevenDaysCoin.toIntOrNull() ?: 0,
            sevenDaysExp = sevenDaysExp.toIntOrNull() ?: 0,
            eventName = eventName,
            currentProgress = currentProgress,
            dateMap = record.flatten()
                .map { item ->
                    SignInData.SignInDataDateMapValue(
                        isSign = item.signed ?: false,
                        hasExtraBonus = item.bonus,
                    )
                }
                .foldIndexed(mutableMapOf<Int, SignInData.SignInDataDateMapValue>()) { index, acc, item ->
                    acc[index + 1] = item
                    acc
                },
        )
    }
}
