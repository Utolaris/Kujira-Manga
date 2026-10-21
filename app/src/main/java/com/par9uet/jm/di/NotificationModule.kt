package com.par9uet.jm.di

import com.par9uet.jm.dinner.atom.ArticleHtmlParser
import com.par9uet.jm.network.EmbeddedNotificationApi
import com.par9uet.jm.notification.NotificationRepository
import com.par9uet.jm.notification.NotificationSource
import com.par9uet.jm.notification.NotificationUnreadCoordinator
import com.par9uet.jm.notification.model.NotificationUnreadStatus
import com.par9uet.jm.notification.model.NotificationUnreadUpdates
import com.par9uet.jm.session.AccountRequestScope
import com.par9uet.jm.session.UserManager
import com.par9uet.jm.session.UserSessionSnapshot
import com.par9uet.jm.storage.RemoteConfigPreferences
import com.par9uet.jm.ui.viewModel.ComicTrackingViewModel
import com.par9uet.jm.ui.viewModel.NotificationViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import org.koin.dsl.binds

val notificationModule = module {
    single { EmbeddedNotificationApi(get()) }
    single<NotificationSource> {
        val api = get<EmbeddedNotificationApi>()
        val images = get<RemoteConfigPreferences>()
        NotificationRepository(api::request, { images.remoteImageHost.value }, ArticleHtmlParser())
    }
    single<AccountRequestScope> {
        val manager = get<UserManager>()
        object : AccountRequestScope {
            override val sessions = manager.sessionState
            override suspend fun <T> run(owner: UserSessionSnapshot, block: suspend () -> T): T =
                requireNotNull(manager.withBoundRemoteSession(owner.accountId, owner.generation, block)) { "登录状态已变化，请重试" }
        }
    }
    single { NotificationUnreadCoordinator(get(), get(), get(), get()) } binds arrayOf(NotificationUnreadStatus::class, NotificationUnreadUpdates::class)
    viewModel { NotificationViewModel(get(), get(), get(), get()) }
    viewModel { ComicTrackingViewModel(get(), get(), get()) }
}
