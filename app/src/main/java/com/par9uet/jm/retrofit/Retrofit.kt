package com.par9uet.jm.retrofit

import com.par9uet.jm.network.applyAppHttpDefaults
import com.par9uet.jm.network.applyHttpLogging
import com.par9uet.jm.retrofit.converter.PrimitiveToRequestBodyConverterFactory
import com.par9uet.jm.retrofit.converter.ResponseConverterFactory
import com.par9uet.jm.retrofit.interceptor.BaseUrlInterceptor
import com.par9uet.jm.retrofit.interceptor.ToastInterceptor
import com.par9uet.jm.retrofit.interceptor.TokenInterceptor
import java.util.concurrent.TimeUnit
import okhttp3.Cache
import okhttp3.ConnectionPool
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.scalars.ScalarsConverterFactory

/**
 * 活动网络会话 cookie 的清除入口。Retrofit 客户端使用 CookieJar.NO_COOKIES，
 * 此接口已无真实能力，保留仅为兼容旧绑定，不应被会话层依赖。
 */
interface ActiveSessionCookieStore {
    fun clearCookie()
}

class Retrofit(
    baseUrlInterceptor: BaseUrlInterceptor,
    toastInterceptor: ToastInterceptor,
    tokenInterceptor: TokenInterceptor,
    private val scalarsConverterFactory: ScalarsConverterFactory,
    private val responseConverterFactory: ResponseConverterFactory,
    private val primitiveToRequestBodyConverterFactory: PrimitiveToRequestBodyConverterFactory,
    // OkHttp's Dns interface keeps this layer free of the network package; the
    // composition root supplies the app-wide DoH resolver.
    dns: Dns,
    connectionPool: ConnectionPool = ConnectionPool(5, 5, TimeUnit.MINUTES),
    httpCache: Cache? = null,
) : ActiveSessionCookieStore {
    internal val okHttpClient by lazy {
        OkHttpClient.Builder()
            .applyAppHttpDefaults(
                dns = dns,
                connectionPool = connectionPool,
                cache = httpCache,
                connectSeconds = 10,
                readSeconds = 15,
                writeSeconds = 15,
                callSeconds = 20,
            )
            .addInterceptor(baseUrlInterceptor)
            .addInterceptor(tokenInterceptor)
            .addInterceptor(toastInterceptor)
            .applyHttpLogging()
            .cookieJar(CookieJar.NO_COOKIES)
            .build()
    }
    private val retrofit: Retrofit by lazy {
        Retrofit.Builder()
            .baseUrl("https://placeholder.com/") // 占位，会在 okhttp 的拦截器中进行动态替换
            .client(okHttpClient)
            .addConverterFactory(scalarsConverterFactory)
            .addConverterFactory(responseConverterFactory)
            .addConverterFactory(primitiveToRequestBodyConverterFactory)
            .build()
    }

    fun <T> createService(cls: Class<T>): T {
        val service = retrofit.create(cls)
        return service
    }

    // Retrofit only serves public promote/setting endpoints. It owns no authentication state;
    // logout still clears the sole Embedded session through UserManager's existing boundary.
    override fun clearCookie() = Unit
}
