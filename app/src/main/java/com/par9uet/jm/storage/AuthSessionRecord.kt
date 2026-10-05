package com.par9uet.jm.storage

import com.par9uet.jm.core.model.User
import okhttp3.Cookie

/** Identity and credentials from one login are committed as one encrypted startup value. */
internal data class AuthSessionRecord(
    val cookies: List<Cookie>,
    val bearerToken: String? = null,
    val bearerExpiryMillis: Long = 0L,
    val identity: User? = null,
)

internal const val AUTH_SESSION_KEY = "auth_session_v2"
internal const val AUTH_LOGGED_OUT_KEY = "auth_logged_out"
