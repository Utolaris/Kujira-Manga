package com.par9uet.jm.favorites

import com.par9uet.jm.core.model.ConnectionModeStatus
import com.par9uet.jm.session.NightLocalModePrompt
import com.par9uet.jm.storage.ConnectionModeEditor
import com.par9uet.jm.storage.ConnectionModePreferences
import com.par9uet.jm.storage.LocalFavoriteChange
import com.par9uet.jm.storage.LocalFavoriteChangeManager
import com.par9uet.jm.storage.LocalFavoriteChangeStore
import kotlinx.coroutines.flow.MutableStateFlow

internal class FakeConnectionMode(
    initialLocal: Boolean = false,
) : ConnectionModePreferences, ConnectionModeEditor, ConnectionModeStatus {
    override val localModeAccountIds = MutableStateFlow(if (initialLocal) listOf(7) else emptyList())
    override val localModeEnteredAtByAccount = MutableStateFlow<Map<Int, Long>>(emptyMap())
    override val isLocalMode: Boolean get() = 7 in localModeAccountIds.value
    override val isLocalModeFlow = MutableStateFlow(initialLocal)
    var promptDate = ""
    var modeWritable = true

    override fun setLocalModeEnabled(accountId: Int, enabled: Boolean): Boolean {
        if (!modeWritable) return false
        localModeAccountIds.value = if (enabled) (localModeAccountIds.value + accountId).distinct() else localModeAccountIds.value - accountId
        localModeEnteredAtByAccount.value = if (enabled) {
            localModeEnteredAtByAccount.value + (accountId to System.currentTimeMillis())
        } else {
            localModeEnteredAtByAccount.value - accountId
        }
        isLocalModeFlow.value = isLocalMode
        return true
    }

    override fun nightLocalModePromptDate(): String = promptDate
    override fun setNightLocalModePromptDate(date: String): Boolean {
        promptDate = date
        return true
    }
}

internal fun alwaysNetworkLocalModeStatus(): ConnectionModeStatus = FakeConnectionMode(false)

internal fun inMemoryLocalChanges(): LocalFavoriteChangeManager = LocalFavoriteChangeManager(
    object : LocalFavoriteChangeStore {
        private var items: List<LocalFavoriteChange> = emptyList()
        override fun getOrNull(): List<LocalFavoriteChange> = items
        override fun set(items: List<LocalFavoriteChange>): Boolean {
            this.items = items
            return true
        }
    }
)

internal fun fakeNightPrompt(mode: FakeConnectionMode = FakeConnectionMode()): NightLocalModePrompt =
    NightLocalModePrompt(mode, mode)
