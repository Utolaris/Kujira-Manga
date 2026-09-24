package com.par9uet.jm.utils

/**
 * 登录返回体脱敏：日志可完整看到业务字段，但不落盘密码/token。
 * 仅作用于响应体，请求体（含密码）永远不要写入日志。
 */
fun redactSensitiveJson(raw: String): String {
    if (raw.isEmpty()) return raw
    return SENSITIVE_JSON_FIELD_REGEX.replace(raw) { match ->
        val key = match.groupValues[1]
        "$key\"***\""
    }
}

private val SENSITIVE_JSON_FIELD_REGEX = Regex(
    "(\"(?:password|passwd|pass|pwd|token|jwttoken|access_token|refresh_token|cookie|cookies|s)\"\\s*:\\s*\")[^\"]*(\")",
    RegexOption.IGNORE_CASE,
)
