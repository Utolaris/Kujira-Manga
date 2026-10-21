package com.par9uet.jm.di

import com.par9uet.jm.dinner.DinnerRepository
import com.par9uet.jm.dinner.DinnerSource
import com.par9uet.jm.dinner.atom.ArticleHtmlParser
import com.par9uet.jm.network.EmbeddedDinnerApi
import com.par9uet.jm.network.EmbeddedClientManager
import com.par9uet.jm.storage.RemoteConfigPreferences
import com.par9uet.jm.ui.viewModel.DinnerViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val dinnerModule = module {
    single { EmbeddedDinnerApi(get<EmbeddedClientManager>()::getClient) }
    single<DinnerSource> {
        val api = get<EmbeddedDinnerApi>()
        val images = get<RemoteConfigPreferences>()
        DinnerRepository(api::get, { images.remoteImageHost.value }, ArticleHtmlParser())
    }
    viewModel { DinnerViewModel(get(), get(), get()) }
}
