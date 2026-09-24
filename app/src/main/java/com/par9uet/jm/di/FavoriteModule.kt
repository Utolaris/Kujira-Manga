package com.par9uet.jm.di

import com.par9uet.jm.download.coordinator.DownloadManager
import com.par9uet.jm.favorites.data.EmbeddedFavoriteRemoteMutation
import com.par9uet.jm.favorites.data.EmbeddedFavoriteRemoteQuery
import com.par9uet.jm.favorites.data.FavoriteDownloader
import com.par9uet.jm.favorites.data.FavoriteLocalMutation
import com.par9uet.jm.favorites.data.FavoriteLocalSync
import com.par9uet.jm.favorites.data.FavoriteRemoteMutation
import com.par9uet.jm.favorites.data.FavoriteRemoteQuery
import com.par9uet.jm.favorites.model.FavoriteLocalQuery
import com.par9uet.jm.favorites.model.FavoriteSession
import com.par9uet.jm.favorites.data.FavoriteStore
import com.par9uet.jm.favorites.presentation.FavoritesViewModel
import com.par9uet.jm.favorites.sync.FavoriteSyncController
import com.par9uet.jm.favorites.sync.FavoriteSyncRequester
import com.par9uet.jm.favorites.usecase.CollectFavorite
import com.par9uet.jm.favorites.usecase.CreateFavoriteFolder
import com.par9uet.jm.favorites.usecase.DeleteFavoriteFolder
import com.par9uet.jm.favorites.usecase.DownloadSelectedFavorites
import com.par9uet.jm.favorites.usecase.MoveFavorites
import com.par9uet.jm.favorites.usecase.ObserveLocalFavorite
import com.par9uet.jm.favorites.usecase.RenameFavoriteFolder
import com.par9uet.jm.favorites.usecase.SyncFavorites
import com.par9uet.jm.favorites.usecase.UncollectFavorites
import org.koin.core.module.dsl.viewModel
import org.koin.androidx.workmanager.dsl.worker
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.bind
import org.koin.dsl.module

val favoriteModule = module {
    single<FavoriteLocalQuery> { get<FavoriteStore>() }
    single<FavoriteLocalMutation> { get<FavoriteStore>() }
    single<FavoriteLocalSync> { get<FavoriteStore>() }
    single<FavoriteSession> { UserManagerFavoriteSession(get()) }
    single<FavoriteDownloader> {
        val downloadManager = get<DownloadManager>()
        FavoriteDownloader { comics -> downloadManager.downloadComics(comics) }
    }
    single { EmbeddedFavoriteRemoteMutation(get()) } bind FavoriteRemoteMutation::class
    single { EmbeddedFavoriteRemoteQuery(get()) } bind FavoriteRemoteQuery::class

    single { com.par9uet.jm.favorites.usecase.LocalFavoriteOperationGate() }
    single<com.par9uet.jm.core.model.LocalModeExitScheduler> {
        com.par9uet.jm.worker.WorkManagerLocalModeExitScheduler(androidContext(), get())
    }
    worker { com.par9uet.jm.worker.LocalModeExitWorker(get(), get(), get()) }
    single { UncollectFavorites(get(), get(), get(), get(), get(), get()) }
    single { CollectFavorite(get(), get(), get(), get(), get(), get()) }
    single { com.par9uet.jm.favorites.usecase.SyncLocalModeFavoritesOnExit(get(), get(), get(), get()) }
    single { MoveFavorites(get(), get(), get(), get(), get()) }
    single { CreateFavoriteFolder(get(), get(), get(), get()) }
    single { DeleteFavoriteFolder(get(), get(), get(), get(), get()) }
    single { RenameFavoriteFolder(get(), get(), get(), get(), get()) }
    single { DownloadSelectedFavorites(get(), get()) }
    single { ObserveLocalFavorite(get(), get()) }
    single { SyncFavorites(get(), get(), get()) }
    single {
        val toastManager = get<com.par9uet.jm.core.ToastManager>()
        FavoriteSyncController(
            session = get(),
            syncOperation = get<SyncFavorites>()::synchronize,
            applicationScope = get(),
            localMode = get(),
            hasFullSnapshot = get<FavoriteLocalSync>()::hasFullSnapshot,
            onCacheInitialized = { toastManager.showAsync("收藏夹初始化完成。") },
        )
    }
    single<FavoriteSyncRequester> { get<FavoriteSyncController>() }

    single<com.par9uet.jm.core.model.LocalModeExit> {
        object : com.par9uet.jm.core.model.LocalModeExit {
            override suspend fun exitLocalMode() {
                get<com.par9uet.jm.session.LocalModeCoordinator>().exitLocalMode()
            }

            override suspend fun exitLocalModeAfterLogin(accountId: Int, generation: Long) {
                get<com.par9uet.jm.session.LocalModeCoordinator>()
                    .exitLocalModeAfterLogin(accountId, generation)
            }
        }
    }
    viewModel {
        FavoritesViewModel(
            get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(),
        )
    }
}
