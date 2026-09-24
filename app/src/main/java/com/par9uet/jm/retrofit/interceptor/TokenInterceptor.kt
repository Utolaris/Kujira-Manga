package com.par9uet.jm.retrofit.interceptor

import com.par9uet.jm.core.network.OfficialApiSignature
import com.par9uet.jm.retrofit.API_TOKEN_HASH
import com.par9uet.jm.retrofit.API_TS
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response

class TokenInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest: Request = chain.request()
        val newRequest = originalRequest.newBuilder()
            .header("Tokenparam", OfficialApiSignature.tokenParam(API_TS.toString()))
            .header("Token", API_TOKEN_HASH)
            .build()
        return chain.proceed(newRequest)
    }
}
