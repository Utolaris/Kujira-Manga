package com.par9uet.jm.di

import com.par9uet.jm.core.model.ConnectionModeStatus
import com.par9uet.jm.core.model.LocalModeExitScheduler
import com.par9uet.jm.favorites.usecase.LocalFavoriteOperationGate
import com.par9uet.jm.session.LocalModeCoordinator
import com.par9uet.jm.ui.viewModel.LocalModeSettings

/** Composition-root adapter; the settings screen only sees the narrow [LocalModeSettings] port. */
internal class LocalModeSettingsAdapter(
    private val coordinator: LocalModeCoordinator,
    private val exitScheduler: LocalModeExitScheduler,
    private val operationGate: LocalFavoriteOperationGate,
    private val status: ConnectionModeStatus,
) : LocalModeSettings {
    override val isLocalMode = status.isLocalModeFlow
    override val transition = coordinator.transition
    override val isTransitioning = operationGate.isTransitioning

    override fun enter() = coordinator.enterLocalMode()

    override fun requestExit(): Boolean = exitScheduler.request()
}
