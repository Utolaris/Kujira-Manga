package com.par9uet.jm.core.network

/**
 * Matches OkHttp/Kotlin non-null assertion when `FormBody.Builder.add` receives a null value.
 *
 * JMComic 1.1.8 auto-relogin after a kicked session calls
 * `login(username, decryptPasswordFromMemory())`; a cookie-only restore has no encrypted
 * password, so `add("password", null)` throws this before the request leaves the device.
 */
fun Throwable.isFormBodyNullParameter(): Boolean {
    val causes = generateSequence(this) { it.cause }.take(8)
    val messageHit = causes.any { cause ->
        val message = cause.message.orEmpty()
        message.contains("Parameter specified as non-null", ignoreCase = true) ||
            message.contains("Parameter specified as non null", ignoreCase = true)
    }
    if (!messageHit) return false
    // Narrow to OkHttp form construction so unrelated Kotlin non-null NPEs are not swallowed.
    val formInMessage = causes.any { cause ->
        val message = cause.message.orEmpty()
        message.contains("FormBody", ignoreCase = true) ||
            message.contains("okhttp3", ignoreCase = true)
    }
    if (formInMessage) return true
    return causes.any { cause ->
        cause.stackTrace.any { element ->
            element.className.startsWith("okhttp3.FormBody")
        }
    }
}
