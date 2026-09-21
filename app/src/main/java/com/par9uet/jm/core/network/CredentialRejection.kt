package com.par9uet.jm.core.network

/**
 * 服务端是否**明确**说了「用户名或密码错误」。
 *
 * 18comic 把两类语义压进同一个 401：
 *  - 真的凭据错误（用户改了密码）；
 *  - 对高频 `/login` 的软拒绝 / 风控。
 * 两者的 `code` 完全相同，报文也同为 `請先登入會員` 系的 401。
 *
 * 只有前者才允许注销本地身份。否则「一次限流」就等于把用户登出 ——
 * 这正是「已登录的 app 自动掉登录态」的成因。
 *
 * 判定只能靠文案，所以这里要求「明确命中」才算数：命中不了就当未确认，
 * 由调用方按连续次数决定是否升级为登出（见 `UserManager` 的
 * `CREDENTIAL_REJECTION_CONFIRMATIONS`）。
 *
 * 注意报文里的 `/` 会被 JSON 转义成 `\/`（真实报文：
 * `"errorMsg":"無效的用戶名和\/或密碼！"`），所以只匹配转义点之前的片段。
 */
private val EXPLICIT_CREDENTIAL_REJECTION_MARKERS = listOf(
    // 繁中（服务端实际返回的语言）
    "無效的用戶名",
    "用戶名或密碼",
    "帳號或密碼",
    "密碼錯誤",
    // 简中
    "无效的用户名",
    "用户名或密码",
    "账号或密码",
    "密码错误",
    // 英文
    "invalid username",
    "invalid password",
    "incorrect username",
    "incorrect password",
    "wrong password",
    "invalid credentials",
)

/** 见 [EXPLICIT_CREDENTIAL_REJECTION_MARKERS]。空/空白一律视为「未确认」。 */
fun String?.isExplicitCredentialRejection(): Boolean {
    val text = this?.takeIf { it.isNotBlank() } ?: return false
    return EXPLICIT_CREDENTIAL_REJECTION_MARKERS.any { text.contains(it, ignoreCase = true) }
}
