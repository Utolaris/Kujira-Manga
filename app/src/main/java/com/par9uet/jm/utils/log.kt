package com.par9uet.jm.utils

import android.util.Log

inline fun <reified T> T.log(msg: String) {
    val tag = T::class.java.simpleName
    try {
        Log.d("[KUJIRA-MANGA] $tag", msg)
        LogBuffer.append(tag, msg)
    } catch (e: Throwable) {
        // LogBuffer 失败不应影响业务逻辑，尝试仅输出到 logcat
        try { Log.e("[KUJIRA-MANGA] $tag", "LogBuffer.append failed: ${e.message}") } catch (_: Throwable) {}
    }
}

fun log(tag: String, msg: String) {
    try {
        Log.d("[KUJIRA-MANGA] $tag", msg)
        LogBuffer.append(tag, msg)
    } catch (e: Throwable) {
        try { Log.e("[KUJIRA-MANGA] $tag", "LogBuffer.append failed: ${e.message}") } catch (_: Throwable) {}
    }
}

fun logError(tag: String, msg: String) {
    try {
        Log.e("[KUJIRA-MANGA] $tag", msg)
        LogBuffer.appendError(tag, msg)
    } catch (e: Throwable) {
        try { Log.e("[KUJIRA-MANGA] $tag", "LogBuffer.appendError failed: ${e.message}") } catch (_: Throwable) {}
    }
}
