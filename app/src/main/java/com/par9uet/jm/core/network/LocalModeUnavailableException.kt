package com.par9uet.jm.core.network

import com.par9uet.jm.core.model.LOCAL_MODE_UNAVAILABLE_MESSAGE

/**
 * 本地模式拦截：**不是**登录失效。
 * 独立类型以免靠 message 字符串反识别（文案一变就会误触发自动重登）。
 */
class LocalModeUnavailableException(
    message: String = LOCAL_MODE_UNAVAILABLE_MESSAGE,
) : AuthenticatedSessionRequiredException(message)
