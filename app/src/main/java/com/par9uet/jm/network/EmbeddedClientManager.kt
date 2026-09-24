package com.par9uet.jm.network


import com.google.gson.JsonParser
import com.par9uet.jm.core.network.AuthAttemptOrigin
import com.par9uet.jm.storage.CookieStorage
import com.par9uet.jm.utils.log
import com.par9uet.jm.utils.logError
import com.par9uet.jm.utils.redactSensitiveJson
import io.github.jukomu.jmcomic.api.enums.ClientType
import io.github.jukomu.jmcomic.api.exception.ResponseException
import io.github.jukomu.jmcomic.api.model.JmUserInfo
import io.github.jukomu.jmcomic.core.client.impl.JmApiClient
import io.github.jukomu.jmcomic.core.config.JmConfiguration
import io.github.jukomu.jmcomic.core.net.OkHttpBuilder
import io.github.jukomu.jmcomic.core.net.provider.DomainProbe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.Cookie
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * 共享内置 API 客户端管理器。
 * 所有活动认证请求共享同一个 JmApiClient。登录本身在隔离 candidate client 上完成；
 * UserManager 通过 generation 校验后，才把完整认证 cookie 提升到活动客户端，避免登录
 * 网络阶段提前改写 A 的 shared client。这样 POST 请求仍能共享 AVS 会话，同时 transition
 * 期间不会把 shared client 悄悄切成另一个账号。
 *
 * 会话持久化不变量：
 * 1. CookieStorage 只保存“活动会话”的完整 cookie 快照（含 JMComic-Api-Java 登录时根据
 *    JSON 字段 "s" 构造的 AVS cookie），而不是响应 Set-Cookie 的子集。
 * 2. 活动客户端创建时通过 [JmApiClient.setCookies] 恢复会话；候选验证/隔离客户端
 *    （persistCookies = false）从不读写 CookieStorage。
 * 3. 所有可能改动存储/共享客户端的操作都带 session generation 守卫：
 *    clearSession() 使旧 generation 失效，迟到响应或陈旧候选结果无法恢复已清除的会话。
 * 4. 登录成功后的 cookie 提交由 UserManager 在会话锁内完成（activateCandidateSession），
 *    网络请求本身不持有该锁。
 *
 * The client is created on the first embedded-API request, never while the application shell is
 * starting. Domain probing therefore belongs to the request path and cannot delay TTID/TTI.
 */
class EmbeddedClientManager(
    private val cookieStorage: CookieStorage,
    private val dohManager: com.par9uet.jm.network.DohManager,
    private val userAgentProvider: EmbeddedUserAgentProvider,
    /**
     * 共享的、无 cookie 的 DoH 基座客户端（见 `di/AppModule.kt` 的 OkHttpClient 清单）。
     * 域名重赛探针由它 `.newBuilder()` 派生，避免再新造客户端、也避免绕开 DoH。
     */
    private val baseHttpClient: OkHttpClient,
    /** 内置 API 请求的 `lang` 取值来源（用户设置，默认简体）。 */
    private val languageProvider: EmbeddedRequestLanguageProvider,
) {
    sealed class EmbeddedLoginResult {
        /**
         * @param sessionCookies 登录完成后从客户端自身 CookieJar 读取的完整认证 cookie
         * 状态。JMComic-Api-Java 在 login() 内部解析 JSON 字段 "s" 并构造 AVS
         * cookie 写入 CookieJar，因此该快照包含 AVS，而响应头 Set-Cookie 不包含。
         */
        data class Success(
            val userInfo: JmUserInfo,
            val sessionCookies: List<Cookie>,
            val jwtToken: String?,
        ) : EmbeddedLoginResult()

        data class Failure(
            val exception: ResponseException,
            val businessCode: Int?,
        ) : EmbeddedLoginResult()
    }

    private data class SharedClient(
        val client: JmApiClient,
        val loginBusinessCode: ThreadLocal<Int?>,
        val clientSessionGeneration: Long,
    )

    @Volatile
    private var sharedClient: SharedClient? = null
    /** Last trusted API host actually used by the active session, not an independent race. */
    @Volatile
    private var activeApiHost: String? = null
    private val sharedSessionGeneration = AtomicLong(0L)

    /** `/login` 尝试的滑动窗口时间戳，仅供 [recordLoginAttempt] 的密度观测，不参与业务判断。 */
    private val loginAttemptTimestamps = ArrayDeque<Long>()
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // Process-owned: candidate clients share this executor and must not shut it down on close.
    // Idle workers expire so logging out leaves no permanent SDK worker threads behind.
    private val clientExecutor = EmbeddedTaskExecutor(
        ThreadPoolExecutor(2, 2, 30L, TimeUnit.SECONDS, LinkedBlockingQueue()).apply {
            allowCoreThreadTimeOut(true)
        },
        onFailure = { log("内置 API 后台初始化失败，可重试请求：${it.message}") },
    )

    fun getClient(): JmApiClient {
        return getSharedClient().client
    }

    private fun getSharedClient(): SharedClient {
        return sharedClient ?: synchronized(this) {
            sharedClient ?: run {
                val sessionGeneration = sharedSessionGeneration.get()
                val loginBusinessCode = ThreadLocal<Int?>()
                val client = createClient(
                    persistCookies = true,
                    loginBusinessCode = loginBusinessCode,
                    clientSessionGeneration = sessionGeneration,
                )
                SharedClient(
                    client = client,
                    loginBusinessCode = loginBusinessCode,
                    clientSessionGeneration = sessionGeneration,
                ).also { sharedClient = it }
            }
        }
    }

    /**
     * 候选会话验证：在隔离客户端上验证凭据。成功后返回候选会话的完整 cookie 快照
     * （含 AVS）。候选客户端不读写 CookieStorage、不影响共享客户端，验证完成后即关闭；
     * 是否提升为活动会话由 UserManager 按 generation 决定。
     *
     * @param origin 只用于日志与计数；拒绝原因需结合请求密度和错误上下文判断。
     */
    fun verifyCandidate(
        username: String,
        password: String,
        origin: AuthAttemptOrigin = AuthAttemptOrigin.UNSPECIFIED,
    ): EmbeddedLoginResult {
        val loginHost = activeApiHost
        val loginBusinessCode = ThreadLocal<Int?>()
        val loginJwtToken = ThreadLocal<String?>()
        loginBusinessCode.set(null)
        val candidate = createClient(
            persistCookies = false,
            loginBusinessCode = loginBusinessCode,
            loginJwtToken = loginJwtToken,
            clientSessionGeneration = null,
            loginHost = loginHost,
        )
        val attempt = recordLoginAttempt(origin)
        return try {
            // Preserve backend affinity without sharing the active authentication token.
            if (loginHost != null) {
                val loginUrl = okhttp3.HttpUrl.Builder().scheme("https").host(loginHost).build()
                candidate.setCookies(cookieStorage.getOrNull().orEmpty().filter {
                    it.name == "__cflb" && it.expiresAt > System.currentTimeMillis() && it.matches(loginUrl)
                })
            }
            val userInfo = candidate.login(username, password)
            val sessionCookies = candidate.getCookies()
            val cookieNames = sessionCookies.map { it.name }
            log(
                "Login",
                "verifyCandidate SUCCESS origin=$origin attempt=$attempt " +
                    "uid=${userInfo.uid} username=${userInfo.username} " +
                    "cookieCount=${sessionCookies.size} cookieNames=$cookieNames",
            )
            if (sessionCookies.isEmpty()) {
                logError("Login", "verifyCandidate: SDK login returned empty cookies — not a usable session")
            }
            EmbeddedLoginResult.Success(userInfo, sessionCookies, loginJwtToken.get())
        } catch (e: CancellationException) {
            throw e
        } catch (e: ResponseException) {
            logError(
                "Login",
                "verifyCandidate FAILURE origin=$origin attempt=$attempt " +
                    "businessCode=${loginBusinessCode.get()} " +
                    "httpCode=${e.errorCode} message=${e.message}",
            )
            EmbeddedLoginResult.Failure(e, loginBusinessCode.get())
        } finally {
            loginBusinessCode.remove()
            loginJwtToken.remove()
            closeAsync(candidate)
        }
    }

    /** Observe login density without inferring a server-side risk-control policy. */
    private fun recordLoginAttempt(origin: AuthAttemptOrigin): Int {
        val now = System.currentTimeMillis()
        val count = synchronized(loginAttemptTimestamps) {
            loginAttemptTimestamps.addLast(now)
            while (loginAttemptTimestamps.isNotEmpty() &&
                now - loginAttemptTimestamps.first() > LOGIN_ATTEMPT_WINDOW_MS
            ) {
                loginAttemptTimestamps.removeFirst()
            }
            loginAttemptTimestamps.size
        }
        val windowSeconds = LOGIN_ATTEMPT_WINDOW_MS / 1_000
        log("Login", "login attempt origin=$origin windowCount=$count/${windowSeconds}s")
        if (count >= LOGIN_ATTEMPT_BURST_THRESHOLD) {
            logError(
                "Login",
                "登录请求过于密集：${windowSeconds}s 内第 $count 次（本次 origin=$origin）。" +
                    "服务端可能把自动重登软拒绝成「無效的用戶名和/或密碼」，" +
                    "该报文与「凭据真的失效」在 code 上无法区分。",
            )
        }
        return count
    }

    /**
     * 把验证通过的候选会话提升为活动会话：持久化完整 cookie（含 AVS），并同步到已存在的
     * 共享客户端 CookieJar。
     *
     * **故意不把 username 写进共享客户端内存缓存。** JMComic-Api-Java 在 cookie 失效后会用
     * `login(username, decryptPasswordFromMemory())` 自动重登；共享客户端从不持有密码，
     * 一旦写入 username 就会在 `FormBody.Builder.add` 上 NPE（“Parameter specified as non-null”）。
     * 401 应由 [com.par9uet.jm.session.UserManager] 用本地保存的密码走恢复登录。
     * 评论成功后的 username 映射由 [AuthenticatedEmbeddedClient] 的 ParseResponseException 兜底。
     *
     * 调用方（UserManager）必须在持有会话锁、且确认用户 session generation 仍然有效之后
     * 调用；本方法内部再用内置会话 generation 做第二道守卫。
     *
     * @param username 仅用于日志核对，不写入 SDK 客户端。
     */
    fun activateCandidateSession(cookies: List<Cookie>, jwtToken: String?, username: String? = null): Boolean {
        val cookieNames = cookies.map { it.name }
        log(
            "Login",
            "activateCandidateSession cookieCount=${cookies.size} names=$cookieNames username=$username",
        )
        if (cookies.isEmpty()) {
            logError("Login", "activateCandidateSession: empty cookie snapshot, refuse promote")
            return false
        }
        val persistenceGeneration = sharedSessionGeneration.get()
        return synchronized(this) {
            runCatching {
                if (!isCurrentSession(persistenceGeneration)) return false
                val previous = cookieStorage.getOrNull() ?: return false
                val shared = getSharedClient()
                if (!isCurrentSession(shared.clientSessionGeneration)) {
                    logError(
                        "Login",
                        "activateCandidateSession: shared client generation stale " +
                            "(${shared.clientSessionGeneration} vs $persistenceGeneration)",
                    )
                    return false
                }
                try {
                    shared.client.setCookies(cookies)
                    if (!cookieStorage.setSession(cookies, jwtToken)) {
                        shared.client.setCookies(previous)
                        logError("Login", "activateCandidateSession: storage failed; retained previous cookies")
                        return false
                    }
                } catch (error: Exception) {
                    shared.client.setCookies(previous)
                    throw error
                }
                // 不写 cacheUsername：见方法注释（FormBody null / SDK 自动重登）。
                log("Login", "activateCandidateSession: shared client cookies applied ok")
                true
            }.getOrElse { error ->
                logError("Login", "activateCandidateSession: apply cookies failed: ${error.message}")
                false
            }
        }
    }

    /**
     * 清除内存中的内置 API 会话（不发送登出请求）。断开客户端也会丢弃 JMComic 私有的
     * username/加密密码缓存，并让旧 generation 失效，之后的迟到响应无法再写入存储。
     */
    fun clearSession() {
        val staleClient = synchronized(this) {
            sharedSessionGeneration.incrementAndGet()
            activeApiHost = null
            sharedClient?.also { sharedClient = null }
        }
        staleClient?.client?.setCookies(emptyList())
        staleClient?.client?.let(::closeAsync)
    }

    /**
     * 「被固定的域名疑似崩溃」时重新竞速用的可达性探针。
     * 只在累计到失败阈值时跑一次。
     *
     * **派生自注入的共享 DoH 基座**（`.newBuilder()`），沿用 `di/AppModule.kt` 里那张
     * OkHttpClient 清单要求的约定：不新造客户端、不绕开 DoH、不带 cookie。
     */
    private val domainProbeClient: OkHttpClient by lazy {
        baseHttpClient.newBuilder()
            .connectTimeout(DOMAIN_PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(DOMAIN_PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(DOMAIN_PROBE_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }

    private val domainProbe = DomainProbe { domain ->
        runCatching {
            val request = Request.Builder().url("https://$domain/").head().build()
            domainProbeClient.newCall(request).execute().use { true }
        }.getOrDefault(false)
    }

    private fun createClient(
        persistCookies: Boolean,
        loginBusinessCode: ThreadLocal<Int?>,
        loginJwtToken: ThreadLocal<String?>? = null,
        clientSessionGeneration: Long?,
        loginHost: String? = null,
    ): JmApiClient {
        val config = JmConfiguration.Builder()
            .clientType(ClientType.API)
            .executor(clientExecutor)
            .timeout(Duration.ofSeconds(20))
            // SDK 默认 retryTimes = 5（JmConfiguration.Builder 的字段初值），不设就是每个失败请求
            // 最多 6 次尝试，而 RetryAndDomainRedirectInterceptor 每次尝试都会
            // domainManager.getBestDomain() 重选域名（= 失败计数最少的那个）并 replaceHost。
            // 后端白天正常时无所谓；晚上后端一慢，6 次尝试 × 20s 就把单个请求放大到最多约 2 分钟，
            // 而收藏同步是成百个顺序请求 —— 总时长直接以分钟计。
            // 业务请求至多重试一次；凭据请求禁止 SDK 自动重试。
            .retryTimes(if (persistCookies) 1 else 0)
            .apply { if (loginHost != null) apiDomains(listOf(loginHost)) }
            .imageTimeout(Duration.ofSeconds(60))
            .downloadThreadPoolSize(2)
            .domainProbeTimeoutMs(3000)
            .build()
        val context = OkHttpBuilder.build(config)
        val domainManager = context.domainManager
        // 只在持久化的共享客户端上启用「冷启动竞速一次后固定域名」。
        // 登录候选通过 withEmbeddedLoginHost 沿用活动会话的实际请求域名。
        val domainPinning = if (persistCookies) {
            EmbeddedDomainPinning(
                domainManager = domainManager,
                scope = cleanupScope,
                probe = domainProbe,
            ).also { it.start() }
        } else {
            null
        }
        // Route every JMComic API request through the shared DoH resolver while keeping the
        // library's own domain manager, session generation, cookies and AVS handling intact.
        val clientWithCookieInjection = context.client.newBuilder()
            .dns(dohManager)
            .addInterceptor { chain ->
                // SDK 补不上的应用层差异：
                // 1. 官方 app 的 fetchGet 恒补 lang，SDK 只在少数方法发；
                // 2. SDK 把 User-Agent 硬编码成 Android 9 / Chrome 91（全设备同一串），
                //    这里换成设备真实的 WebView UA；
                // 3. SDK 用旧版 token 盐/版本及 URL 表单，改用官方签名和 FormData。
                val request = chain.request()
                    .withEmbeddedLoginHost(loginHost, domainManager.domains)
                    .withEmbeddedLang(domainManager.domains, languageProvider.language())
                    .withEmbeddedSearchDate(domainManager.domains)
                    .withEmbeddedUserAgent(userAgentProvider.userAgent())
                    .withOfficialApiRequest(domainManager.domains)
                if (domainPinning == null) return@addInterceptor chain.proceed(request)
                // 每次尝试都记一次结果，用于判断「被固定的域名是不是真的崩了」。
                try {
                    val response = chain.proceed(request)
                    domainPinning.onAttempt(
                        request.url.host,
                        EmbeddedDomainPinning.outcomeForStatus(response.code),
                    )
                    response
                } catch (error: IOException) {
                    domainPinning.onTransportFailure(request.url.host)
                    throw error
                }
            }
            .addInterceptor { chain ->
                val response = chain.proceed(chain.request())
                // Business-code inspection must run after BridgeInterceptor decompresses JSON.
                captureLoginBusinessCode(response.request, response, loginBusinessCode, loginJwtToken)
                response
            }
            .addNetworkInterceptor { chain ->
                // Reevaluate every redirect after BridgeInterceptor. The persisted active
                // snapshot is authoritative, including on a fresh SDK client with no loginHost.
                val original = chain.request()
                if (persistCookies && original.url.isHttps && original.url.host in domainManager.domains) {
                    synchronized(this) {
                        if (isCurrentSession(clientSessionGeneration)) activeApiHost = original.url.host
                    }
                }
                var sentCookieNames: List<String> = emptyList()
                val request = if (persistCookies) {
                    // getOrNull()==null（Keystore 暂不可读）时不注入也不 merge 写回，
                    // 避免空列表覆盖完整认证快照。
                    val allowed = if (isCurrentSession(clientSessionGeneration)) {
                        embeddedCookiesForRequest(
                            cookieStorage.getOrNull().orEmpty(),
                            original.url,
                            domainManager.domains,
                        )
                    } else emptyList()
                    sentCookieNames = allowed.map { it.name }
                    val jwt = if (isCurrentSession(clientSessionGeneration)) cookieStorage.bearerToken() else null
                    original.withEmbeddedAuthHeaders(allowed, jwt, domainManager.domains)
                } else original
                val response = chain.proceed(request)
                if (response.code == 401) {
                    // 晚间「登录成功但几秒后又 401」的排查里，最缺的就是这两项证据：
                    // 请求究竟打到了哪个 host，以及当时实际带上了哪些 cookie 名。
                    // 只有 cookie 名，没有值 —— 不要在这里泄露会话材料。
                    logError(
                        "AuthEmbedded",
                        "401 host=${request.url.host} path=${request.url.encodedPath} " +
                            "sentCookies=$sentCookieNames " +
                            "pinned=${domainPinning?.pinnedDomainOrNull()} " +
                            "domainCount=${domainManager.domains.size}",
                    )
                }
                if (persistCookies && clientSessionGeneration != null &&
                    request.url.isHttps && request.url.host in domainManager.domains
                ) {
                    val stored = cookieStorage.getOrNull()
                    if (stored != null) {
                        synchronized(this) {
                            if (isCurrentSession(clientSessionGeneration)) {
                                // mergeEmbeddedResponseCookies：响应可以更新 theme/__cflb/ipm5 这类
                                // 非会话 cookie，但**不能**改写或新增会话令牌 AVS。
                                // 原实现只判「打在可信域」，不判请求是否需要会话，等于让任意公开响应
                                // 都能污染持久化会话快照；而请求侧在同名 AVS 之间按顺序取第一个，
                                // 选到旧值就表现为「登录成功却持续 401」。
                                val merged = mergeEmbeddedResponseCookies(
                                    stored,
                                    Cookie.parseAll(request.url, response.headers),
                                )
                                if (stored.toSet() != merged.toSet()) cookieStorage.set(merged)
                            }
                        }
                    }
                }
                // BridgeInterceptor saves headers into the candidate jar after this returns.
                // Only SDK login's decoded JSON `s` may create AVS, including during redirects
                // and background domain probes; otherwise parent-domain AVS can win selection.
                if (persistCookies) response else response.newBuilder()
                    .headers(response.headers.withoutEmbeddedSessionCookie())
                    .build()
            }
            .build()
        val jmClient = JmApiClient(config, clientWithCookieInjection, context.cookieManager, domainManager)
        if (persistCookies && isCurrentSession(clientSessionGeneration)) {
            // 活动客户端创建时恢复完整持久化会话（含 AVS），进程重启后收藏等认证请求
            // 无需等待网络验证即可使用已恢复的会话。
            restoreSessionIntoClient(jmClient)
        }

        return jmClient
    }

    /**
     * 把持久化的完整会话（含 AVS）恢复到客户端自身的 CookieJar。
     *
     * 故意**不**把 UserStorage 里的 username 写进客户端内存缓存：
     * cookie 恢复没有加密密码，一旦服务端踢登录（多端互踢），库会在
     * `executePostRequest` 里用 `login(username, null)` 自动重登，
     * 直接在 `FormBody.Builder.add` 抛
     * "Parameter specified as non-null is null"。
     * 评论成功后的 username 映射已有 `AuthenticatedEmbeddedClient` 兜底。
     */
    private fun restoreSessionIntoClient(client: JmApiClient) {
        // null = Keystore 暂不可读，跳过恢复；不可当成空会话写入。
        val storedCookies = cookieStorage.getOrNull() ?: return
        if (storedCookies.isEmpty()) return
        runCatching { client.setCookies(storedCookies) }
            .onFailure { log("EmbeddedClientManager: 恢复内置 API 会话失败：" + it.message) }
    }

    private fun isCurrentSession(clientSessionGeneration: Long?): Boolean {
        return clientSessionGeneration != null &&
            sharedSessionGeneration.get() == clientSessionGeneration
    }

    private fun closeAsync(client: JmApiClient) {
        cleanupScope.launch {
            try {
                client.close()
            } catch (e: Exception) {
                log("关闭内置 API 客户端失败：" + e.message)
            }
        }
    }

    /**
     * JmApiResponse validates the JSON business code, while ResponseException.errorCode is built
     * from the HTTP status. Capture the former before the library consumes the response body.
     * JmApiClient.login() is synchronous, so the ThreadLocal associates the code with this login
     * call even when another login is using the shared client concurrently.
     */
    private fun captureLoginBusinessCode(
        request: okhttp3.Request,
        response: okhttp3.Response,
        businessCode: ThreadLocal<Int?>,
        loginJwtToken: ThreadLocal<String?>?,
    ) {
        if (request.url.pathSegments.lastOrNull() != "login") return
        try {
            val encoding = response.header("Content-Encoding").orEmpty()
            val contentType = response.header("Content-Type").orEmpty()
            val rawPeek = response.peekBody(LOGIN_RESPONSE_PEEK_BYTES).bytes()
            val text = decodeLoginBodyForLog(rawPeek, encoding)
            log(
                "Login",
                "login HTTP=${response.code} url=${request.url.encodedPath} " +
                    "contentEncoding=$encoding contentType=$contentType " +
                    "peekBytes=${rawPeek.size} body=${redactSensitiveJson(text)}",
            )
            val jsonCandidate = text.trim()
            if (jsonCandidate.startsWith("{") || jsonCandidate.startsWith("[")) {
                val json = JsonParser.parseString(jsonCandidate).asJsonObject
                val code = json.get("code")?.takeUnless { it.isJsonNull }?.asInt
                businessCode.set(code)
                loginJwtToken?.set(officialLoginJwt(json, request.header("Tokenparam")))
                log("Login", "login businessCode=$code")
            } else {
                logError("Login", "login response is not JSON after decode; businessCode not captured")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (error: Exception) {
            logError("Login", "login response parse/log failed: ${error.message}")
        }
    }

    /**
     * OkHttp 应用层拦截器看到的 body 仍可能是 gzip（业务解压在更内层 / peek 不触发透明解压）。
     * 日志侧自行识别 magic 并 gunzip，保证导出日志里能看到可读 JSON。
     */
    private fun decodeLoginBodyForLog(raw: ByteArray, contentEncoding: String): String {
        fun isGzip(bytes: ByteArray) =
            bytes.size >= 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()

        val gunzipped = if (isGzip(raw) || contentEncoding.contains("gzip", ignoreCase = true)) {
            runCatching {
                java.util.zip.GZIPInputStream(raw.inputStream()).use { it.readBytes() }
            }.getOrElse { raw }
        } else {
            raw
        }
        return runCatching { String(gunzipped, Charsets.UTF_8) }.getOrElse {
            "non-utf8 bytes=${gunzipped.size} headHex=${gunzipped.take(32).joinToString(" ") { b -> "%02x".format(b) }}"
        }
    }

    private companion object {
        private const val LOGIN_RESPONSE_PEEK_BYTES = 256L * 1024L
        private const val DOMAIN_PROBE_TIMEOUT_SECONDS = 3L
        private const val DOMAIN_PROBE_CALL_TIMEOUT_SECONDS = 4L

        /** Diagnostic window only; recovery uses its own 30s–300s cooldown. */
        private const val LOGIN_ATTEMPT_WINDOW_MS = 300_000L
        private const val LOGIN_ATTEMPT_BURST_THRESHOLD = 3
    }
}
