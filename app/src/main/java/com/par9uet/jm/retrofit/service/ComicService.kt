package com.par9uet.jm.retrofit.service

import com.par9uet.jm.retrofit.model.HomeSwiperComicListItemResponse
import com.par9uet.jm.core.network.ResponseWrapper
import retrofit2.http.GET
import retrofit2.http.Query

interface ComicService {
    @GET("promote")
    suspend fun getHomeSwiperComicList(
        @Query("lang") language: String,
    ): ResponseWrapper<List<HomeSwiperComicListItemResponse>>
}
