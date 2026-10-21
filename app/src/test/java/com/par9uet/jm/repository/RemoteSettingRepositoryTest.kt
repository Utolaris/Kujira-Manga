package com.par9uet.jm.repository

import com.google.gson.Gson
import com.par9uet.jm.core.model.RemoteSetting
import com.par9uet.jm.core.network.NetWorkResult
import com.par9uet.jm.core.network.ResponseWrapper
import com.par9uet.jm.repository.impl.RemoteSettingRepositoryImpl
import com.par9uet.jm.retrofit.model.RemoteSettingResponse
import com.par9uet.jm.retrofit.service.RemoteSettingService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test

class RemoteSettingRepositoryTest {
    @Test
    fun `repository maps the wire response and ignores unused server fields`() = runTest {
        val response = Gson().fromJson(
            """{"img_host":"cdn.example","app_shunts":[{"title":"old","key":"1"}]}""",
            RemoteSettingResponse::class.java,
        )
        val repository = repository { ResponseWrapper.Success(response) }

        assertEquals(NetWorkResult.Success(RemoteSetting("cdn.example")), repository.getRemoteSetting())
    }

    @Test
    fun `server failures preserve their message and code`() = runTest {
        val repository = repository { ResponseWrapper.Error("temporarily unavailable", 503) }

        val result = repository.getRemoteSetting() as NetWorkResult.Error

        assertEquals("temporarily unavailable", result.message)
        assertEquals(503, result.code)
    }

    @Test
    fun `empty response remains a failure`() = runTest {
        val repository = repository { ResponseWrapper(200) }

        assertEquals(NetWorkResult.Error("响应数据为空"), repository.getRemoteSetting())
    }

    @Test
    fun `cancellation propagates unchanged`() = runTest {
        val cancelled = CancellationException("cancel remote config")
        val repository = repository { throw cancelled }

        try {
            repository.getRemoteSetting()
            fail("Expected cancellation")
        } catch (actual: CancellationException) {
            assertSame(cancelled, actual)
        }
    }

    private fun repository(fetch: suspend () -> ResponseWrapper<RemoteSettingResponse>) =
        RemoteSettingRepositoryImpl(object : RemoteSettingService {
            override suspend fun getRemoteSetting() = fetch()
        })
}
