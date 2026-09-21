package com.par9uet.jm.core.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「服务端是否明确说了凭据错误」的判定。
 *
 * 这是「自动掉登录态」的最后一道闸门：只有明确命中才计入连续拒绝计数，
 * 其余 401（软拒绝 / 风控 / 未知文案）一律算未确认，不清本地身份。
 */
class CredentialRejectionTest {

    /** 真实报文：`/login` 被拒时服务端逐字返回的形状（注意 JSON 把 `/` 转义成 `\/`）。 */
    private val realServerPayload =
        "内置API登录失败：Request failed with code: 401, error message: " +
            "{\"code\":401,\"data\":[],\"errorMsg\":\"無效的用戶名和\\/或密碼！\"}"

    @Test
    fun realServerPayloadIsRecognizedAsExplicitRejection() {
        assertTrue(realServerPayload.isExplicitCredentialRejection())
    }

    @Test
    fun simplifiedChineseAndEnglishFormsAreRecognized() {
        assertTrue("账号或密码错误".isExplicitCredentialRejection())
        assertTrue("用户名或密码错误".isExplicitCredentialRejection())
        assertTrue("invalid username or password".isExplicitCredentialRejection())
        assertTrue("Incorrect password".isExplicitCredentialRejection())
    }

    @Test
    fun genericSessionFailuresAreNotTreatedAsCredentialRejection() {
        // 会话被踢 / 未登录系的 401：说明会话没了，但**不构成「凭据错误」的证据**。
        assertFalse("請先登入會員".isExplicitCredentialRejection())
        assertFalse("登录会话已失效，请重新登录".isExplicitCredentialRejection())
        assertFalse("Request failed with code: 401, error message: {\"code\":401,\"data\":[]}".isExplicitCredentialRejection())
        // 登录门禁自己的错误（服务端没返回 uid / cookie），同样不算。
        assertFalse("内置API登录失败：登录未完成：服务端未返回有效账号 ID".isExplicitCredentialRejection())
        assertFalse("登录未完成：服务端未返回会话 Cookie".isExplicitCredentialRejection())
    }

    @Test
    fun blankOrMissingMessageIsUnconfirmed() {
        assertFalse((null as String?).isExplicitCredentialRejection())
        assertFalse("".isExplicitCredentialRejection())
        assertFalse("   ".isExplicitCredentialRejection())
    }
}
