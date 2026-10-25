package com.par9uet.jm.core.network

import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/** One recovery and one retry, always outside the operation's bound remote gate. */
suspend fun <T> withAuthenticationRecovery(
    isCurrent: () -> Boolean,
    recover: suspend () -> NetWorkResult<Unit>?,
    request: suspend () -> NetWorkResult<T>,
): NetWorkResult<T> {
    val result = request()
    coroutineContext.ensureActive()
    if (result !is NetWorkResult.Error || result.kind != NetworkErrorKind.Authentication || !isCurrent()) {
        return result
    }
    val recovery = recover()
    coroutineContext.ensureActive()
    return when (recovery) {
        is NetWorkResult.Success -> if (isCurrent()) request() else result
        is NetWorkResult.Error -> recovery
        null -> result
    }
}
