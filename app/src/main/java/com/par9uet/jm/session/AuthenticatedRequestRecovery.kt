package com.par9uet.jm.session
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Registered by UserManager without creating a repository -> UserManager DI cycle. */
interface AuthenticatedRequestExecutor {
    suspend fun <T> execute(block: suspend () -> T): T
}

/** Nested SDK calls must leave recovery to the owner after it releases the session gate. */
internal object BoundAuthenticatedRequest : AbstractCoroutineContextElement(Key) {
    object Key : CoroutineContext.Key<BoundAuthenticatedRequest>
}
