package com.par9uet.jm.session

import com.par9uet.jm.core.model.ConnectionModeStatus
import com.par9uet.jm.storage.ConnectionModePreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** The current account's mode; another account never inherits this account's local mode. */
class LocalModeGate(
    private val connectionMode: ConnectionModePreferences,
    private val userManager: UserManager,
    applicationScope: CoroutineScope,
) : ConnectionModeStatus {
    override val isLocalMode: Boolean
        get() = userManager.userState.value.data?.id?.let { it in connectionMode.localModeAccountIds.value } == true

    override val isLocalModeFlow: StateFlow<Boolean> = combine(
        connectionMode.localModeAccountIds,
        userManager.userState,
    ) { accounts, user -> user.data?.id?.let { it in accounts } == true }
        .stateIn(applicationScope, SharingStarted.Eagerly, isLocalMode)
}
