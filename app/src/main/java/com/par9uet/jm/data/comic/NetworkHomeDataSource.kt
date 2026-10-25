package com.par9uet.jm.data.comic

import com.par9uet.jm.core.BaseRepository
import com.par9uet.jm.retrofit.model.HomeSwiperComicListItemResponse
import com.par9uet.jm.core.network.NetWorkResult
import com.par9uet.jm.retrofit.service.ComicService
import com.par9uet.jm.storage.ContentLanguagePreferences

/** The sole network business capability: optional Home recommendation. */
interface NetworkHomeDataSource {
    suspend fun getHomePage(): NetWorkResult<List<HomeSwiperComicListItemResponse>>
}

class RetrofitNetworkHomeDataSource(
    private val service: ComicService,
    private val languagePreferences: ContentLanguagePreferences,
) : BaseRepository(), NetworkHomeDataSource {
    override suspend fun getHomePage(): NetWorkResult<List<HomeSwiperComicListItemResponse>> =
        safeApiCall { service.getHomeSwiperComicList(languagePreferences.appLanguage.value) }
}
