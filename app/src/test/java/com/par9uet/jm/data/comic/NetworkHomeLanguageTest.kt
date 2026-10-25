package com.par9uet.jm.data.comic

import com.par9uet.jm.core.network.NetWorkResult
import com.par9uet.jm.retrofit.service.ComicService
import com.par9uet.jm.storage.ContentLanguagePreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class NetworkHomeLanguageTest {
    @Test
    fun promoteSendsCurrentLanguageOnEveryRequest() = runTest {
        MockWebServer().use { server ->
            server.start()
            val preferences = object : ContentLanguagePreferences {
                override val appLanguage = MutableStateFlow("CN")
            }
            val service = Retrofit.Builder()
                .baseUrl(server.url("/"))
                .addConverterFactory(GsonConverterFactory.create())
                .build().create(ComicService::class.java)
            val source = RetrofitNetworkHomeDataSource(service, preferences)
            for (language in listOf("CN", "TW", "CN")) {
                preferences.appLanguage.value = language
                server.enqueue(MockResponse.Builder().body("""{"code":200,"data":[]}""").build())
                assertTrue(source.getHomePage() is NetWorkResult.Success)
                assertEquals("/promote?lang=$language", server.takeRequest().target)
            }
        }
    }
}
