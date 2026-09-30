package com.par9uet.jm.favorites

import com.par9uet.jm.core.model.ConnectionModeStatus
import com.par9uet.jm.session.NightLocalModePrompt
import com.par9uet.jm.storage.ConnectionModeEditor
import com.par9uet.jm.storage.ConnectionModePreferences
import com.par9uet.jm.storage.LocalFavoriteChange
import com.par9uet.jm.storage.LocalFavoriteChangeManager
import com.par9uet.jm.storage.LocalFavoriteChangeStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** 多数收藏用例共用的账号；作为替身的默认「当前登录账号」。 */
internal const val TEST_ACCOUNT_ID = 7

/**
 * 连接模式替身。
 *
 * 真实 [com.par9uet.jm.session.LocalModeGate] 的判定是「**当前登录账号** ∈ 本地模式名单」，
 * 账号来自 `UserManager`。替身原先把这个号码写死成 7，于是「账号 42 处于本地模式」这种
 * 生产不可能出现的情形会在测试里成立，反之真实的账号归属 bug 也不会红。
 * 这里把 [activeAccount] 显式化：名单与当前账号都参与判定。
 */
internal class FakeConnectionMode(
    initialLocal: Boolean = false,
    /** 真实 gate 读 UserManager 的当前用户；替身用这个字段代替。 */
    var activeAccount: Int = TEST_ACCOUNT_ID,
    /** 进入本地模式的账号名单；默认与 [activeAccount] 一致，避免再写死某个数字。 */
    localModeAccounts: List<Int> = listOf(activeAccount),
) : ConnectionModePreferences, ConnectionModeEditor, ConnectionModeStatus {
    override val localModeAccountIds = MutableStateFlow(if (initialLocal) localModeAccounts else emptyList())
    override val localModeEnteredAtByAccount = MutableStateFlow<Map<Int, Long>>(emptyMap())
    override val isLocalMode: Boolean get() = isLocalModeFor(activeAccount)

    private val _isLocalModeFlow = MutableStateFlow(isLocalMode)
    override val isLocalModeFlow: StateFlow<Boolean> = _isLocalModeFlow

    var promptDate = ""
    var modeWritable = true

    private fun isLocalModeFor(accountId: Int): Boolean = accountId in localModeAccountIds.value

    /** 跟随「当前登录账号」变化，与真实 gate 依赖 userState 的行为一致。 */
    fun switchActiveAccount(accountId: Int) {
        activeAccount = accountId
        syncIsLocalModeFlow()
    }

    override fun setLocalModeEnabled(accountId: Int, enabled: Boolean): Boolean {
        if (!modeWritable) return false
        localModeAccountIds.value = if (enabled) (localModeAccountIds.value + accountId).distinct() else localModeAccountIds.value - accountId
        localModeEnteredAtByAccount.value = if (enabled) {
            localModeEnteredAtByAccount.value + (accountId to System.currentTimeMillis())
        } else {
            localModeEnteredAtByAccount.value - accountId
        }
        syncIsLocalModeFlow()
        return true
    }

    private fun syncIsLocalModeFlow() {
        _isLocalModeFlow.value = isLocalMode
    }

    override fun nightLocalModePromptDate(): String = promptDate
    override fun setNightLocalModePromptDate(date: String): Boolean {
        promptDate = date
        return true
    }
}

internal fun alwaysNetworkLocalModeStatus(): ConnectionModeStatus = FakeConnectionMode(false)

/** 本地模式开启**且名单与当前账号都指向 [accountId]**，与真实 gate 的判定保持一致。 */
internal fun localModeFor(accountId: Int): FakeConnectionMode = FakeConnectionMode(
    initialLocal = true,
    activeAccount = accountId,
    localModeAccounts = listOf(accountId),
)

/**
 * 本地模式开在 [localAccountId]，但当前登录的是 [activeAccountId]。
 * 用于验证「别人的本地模式不会套到我头上」——真实 gate 会判 false。
 */
internal fun localModeForOtherAccount(
    localAccountId: Int,
    activeAccountId: Int,
): FakeConnectionMode = FakeConnectionMode(
    initialLocal = true,
    activeAccount = activeAccountId,
    localModeAccounts = listOf(localAccountId),
)

/**
 * 内存版本地收藏变更存储。
 *
 * 生产实现是三态的：成功 / `Corrupted` / `TemporaryUnavailable`，后两者都映射成 `null`
 * 且**不允许调用方推断成空队列**（见 [LocalFavoriteChangeStore.getOrNull] 的契约）。
 * 写入也可能失败。默认全成功；用 [readUnavailable] / [writeFails] 把这两条真实分支暴露出来，
 * 否则「存储损坏 / 密钥库暂时不可用」时用户看到的错误路径永远是死代码。
 */
internal class InMemoryLocalChanges(
    initial: List<LocalFavoriteChange> = emptyList(),
) : LocalFavoriteChangeStore {
    var readUnavailable = false
    var writeFails = false
    var writeAttempts = 0
        private set

    private var items: List<LocalFavoriteChange> = initial

    override fun getOrNull(): List<LocalFavoriteChange>? = if (readUnavailable) null else items

    override fun set(items: List<LocalFavoriteChange>): Boolean {
        writeAttempts++
        if (writeFails) return false
        this.items = items
        return true
    }

    /** 当前落盘内容；不可读时为 null，便于断言「失败时什么都没写」。 */
    fun storedOrNull(): List<LocalFavoriteChange>? = getOrNull()
}

internal fun inMemoryLocalChanges(): LocalFavoriteChangeManager =
    LocalFavoriteChangeManager(InMemoryLocalChanges())

internal fun fakeNightPrompt(mode: FakeConnectionMode = FakeConnectionMode()): NightLocalModePrompt =
    NightLocalModePrompt(mode, mode)
