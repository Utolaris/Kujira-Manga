package com.par9uet.jm.retrofit

import com.par9uet.jm.core.ToastManager
import com.par9uet.jm.network.DohManager
import com.par9uet.jm.retrofit.converter.PrimitiveToRequestBodyConverterFactory
import com.par9uet.jm.retrofit.converter.ResponseConverterFactory
import com.par9uet.jm.retrofit.interceptor.BaseUrlInterceptor
import com.par9uet.jm.retrofit.interceptor.ToastInterceptor
import com.par9uet.jm.retrofit.interceptor.TokenInterceptor
import com.par9uet.jm.storage.ApiEndpointPreference
import com.par9uet.jm.storage.DohPreferences
import com.par9uet.jm.storage.DohPreferencesEditor
import com.par9uet.jm.storage.DohSettingsState

import kotlinx.coroutines.flow.MutableStateFlow
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test
import retrofit2.converter.scalars.ScalarsConverterFactory

class RetrofitCookieIsolationTest {
    @Test fun publicRequestsIgnoreSetCookieAcrossPathsHostsAndLogout() {
        MockWebServer().use { server ->
            server.start()
            val endpoint = object : ApiEndpointPreference {
                override val apiEndpoint = MutableStateFlow("http://localhost")
            }
            val prefs = object : DohPreferences { override val doh = MutableStateFlow(DohSettingsState(enabled = false)) }
            val editor = object : DohPreferencesEditor {
                override fun persistEnabled(enabled: Boolean) = true
                override fun persistAutoStart(enabled: Boolean) = true
                override fun persistAutoSelectFastest(enabled: Boolean) = true
                override fun persistServer(serverId: String) = true
                override fun persistCustomServer(name: String, url: String) = true
                override fun persistUseDeviceCertificates(enabled: Boolean) = true
                override fun persistPreferIpv6(enabled: Boolean) = true
            }
            val toast = ToastManager()
            val retrofit = Retrofit(BaseUrlInterceptor(endpoint), ToastInterceptor(toast), TokenInterceptor(),
                ScalarsConverterFactory.create(), ResponseConverterFactory(toast), PrimitiveToRequestBodyConverterFactory(),
                DohManager(prefs, editor))
            for ((index, path) in listOf("/promote", "/setting", "/promote").withIndex()) {
                if (index == 2) { retrofit.clearCookie(); endpoint.apiEndpoint.value = "http://127.0.0.1" }
                server.enqueue(
                    MockResponse.Builder()
                        .body("public data")
                        .addHeader("Set-Cookie", "AVS=foreign; Path=/")
                        .build(),
                )
                retrofit.okHttpClient.newCall(Request.Builder().url(server.url(path)).build()).execute().use {
                    assertEquals("public data", it.body.string())
                }
                val request = server.takeRequest()
                assertNull(request.headers["Cookie"])
                assertEquals("${API_TS},2.1.14", request.headers["Tokenparam"])
                assertEquals(API_TOKEN_HASH, request.headers["Token"])
            }
        }
    }

    @Test fun baseUrlInterceptorFallsBackToNextCandidateOnServerError() {
        MockWebServer().use { primaryServer ->
            MockWebServer().use { backupServer ->
                primaryServer.start()
                backupServer.start()
                primaryServer.enqueue(MockResponse.Builder().code(500).body("boom").build())
                backupServer.enqueue(MockResponse.Builder().code(200).body("ok-from-backup").build())

                val primaryUrl = primaryServer.url("/").toString().removeSuffix("/")
                val backupUrl = backupServer.url("/").toString().removeSuffix("/")
                val pref = object : ApiEndpointPreference {
                    override val apiEndpoint = MutableStateFlow(primaryUrl)
                }
                val client = okhttp3.OkHttpClient.Builder()
                    .addInterceptor(BaseUrlInterceptor(pref, listOf(primaryUrl, backupUrl)))
                    .build()

                client.newCall(Request.Builder().url("http://example.invalid/promote?lang=CN").build())
                    .execute().use { response ->
                        assertEquals(200, response.code)
                        assertEquals("ok-from-backup", response.body.string())
                    }
                assertEquals(1, primaryServer.requestCount)
                assertEquals(1, backupServer.requestCount)
            }
        }
    }

    @Test fun baseUrlInterceptorDoesNotFallBackForUnlistedCustomEndpoint() {
        MockWebServer().use { customServer ->
            MockWebServer().use { otherServer ->
                customServer.start()
                otherServer.start()
                customServer.enqueue(MockResponse.Builder().code(503).body("unavailable").build())

                val customUrl = customServer.url("/").toString().removeSuffix("/")
                val otherUrl = otherServer.url("/").toString().removeSuffix("/")
                val pref = object : ApiEndpointPreference {
                    override val apiEndpoint = MutableStateFlow(customUrl)
                }
                val client = okhttp3.OkHttpClient.Builder()
                    .addInterceptor(BaseUrlInterceptor(pref, listOf(otherUrl)))
                    .build()

                client.newCall(Request.Builder().url("http://example.invalid/promote").build())
                    .execute().use { response ->
                        assertEquals(503, response.code)
                    }
                assertEquals(1, customServer.requestCount)
                assertEquals(0, otherServer.requestCount)
            }
        }
    }
}
