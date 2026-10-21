package com.par9uet.jm.session

import kotlinx.coroutines.flow.StateFlow

/** UI 协调器把请求绑定到发起时的账号，切号后不得用新凭据提交旧操作。 */
interface AccountRequestScope {
    val sessions: StateFlow<UserSessionSnapshot>
    suspend fun <T> run(owner: UserSessionSnapshot, block: suspend () -> T): T
}
