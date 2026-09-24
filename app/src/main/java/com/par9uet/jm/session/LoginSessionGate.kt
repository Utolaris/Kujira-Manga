package com.par9uet.jm.session

import com.par9uet.jm.core.network.NetWorkResult
import com.par9uet.jm.core.network.NetworkErrorKind
import com.par9uet.jm.utils.log
import com.par9uet.jm.utils.logError
import okhttp3.Cookie

/**
 * 登录「真的成功」的最低标准：SDK 返回了账号身份，且带回了有效 AVS 会话 cookie。
 * 不满足时不得把 SessionReadiness 标成 Authenticated。
 */
object LoginSessionGate {
    const val TAG = "Login"

    fun validateCandidate(verified: CandidateSession): NetWorkResult.Error? {
        val uid = verified.loginResponse.uid
        val username = verified.loginResponse.username
        val cookieNames = verified.embeddedCookies.map { it.name }
        log(
            TAG,
            "validateCandidate uid=$uid username=$username cookieCount=${cookieNames.size} names=$cookieNames",
        )
        return when {
            uid <= 0 -> NetWorkResult.Error(
                message = "登录未完成：服务端未返回有效账号 ID",
                kind = NetworkErrorKind.Authentication,
            )
            username.isBlank() -> NetWorkResult.Error(
                message = "登录未完成：服务端未返回用户名",
                kind = NetworkErrorKind.Authentication,
            )
            verified.embeddedCookies.none {
                it.name.equals("AVS", ignoreCase = true) &&
                    it.value.isNotBlank() && it.expiresAt > System.currentTimeMillis()
            } -> NetWorkResult.Error(
                message = "登录未完成：服务端未返回有效 AVS 会话 Cookie",
                kind = NetworkErrorKind.Authentication,
            )
            else -> null
        }
    }

    /** cookie 名仅作诊断；值绝不进日志。 */
    fun cookieNames(cookies: List<Cookie>): List<String> = cookies.map { it.name }
}
