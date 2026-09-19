package com.par9uet.jm.network

import com.par9uet.jm.utils.log
import io.github.jukomu.jmcomic.core.client.impl.JmApiClient

/**
 * JMComic-Api-Java only fills `loggedInUserName` during an in-process `login()`.
 * Cookie restore (`setCookies`) leaves it blank, so `postComment`/`replyToComment` throw
 * "Username is required... Please login first." *after* the remote POST succeeded.
 *
 * **生产路径不再对共享客户端调用本函数**：写入 username 后，SDK 在 cookie 失效时会用
 * `login(username, null)` 自动重登并在 FormBody 上 NPE。评论映射失败由
 * [AuthenticatedEmbeddedClient] 把特定 ParseResponseException 当成功处理。
 * 保留此反射工具仅供测试/诊断。
 */
internal fun cacheEmbeddedLoggedInUserName(client: JmApiClient, username: String) {
    if (username.isBlank()) return
    runCatching {
        val method = Class.forName("io.github.jukomu.jmcomic.core.client.AbstractJmClient")
            .getDeclaredMethod("cacheUsername", String::class.java)
        method.isAccessible = true
        method.invoke(client, username)
    }.onFailure {
        log("EmbeddedUsernameCache", "写入内置 API username 缓存失败：" + it.message)
    }
}
