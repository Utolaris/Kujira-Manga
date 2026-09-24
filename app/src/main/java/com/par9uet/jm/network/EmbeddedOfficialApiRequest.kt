package com.par9uet.jm.network

import com.par9uet.jm.core.network.OfficialApiSignature
import okhttp3.FormBody
import okhttp3.MultipartBody
import okhttp3.Request

/** Align SDK API headers and form posts with the official WebView client. */
internal fun Request.withOfficialApiRequest(apiDomains: Collection<String>): Request {
    if (!url.isHttps || url.host !in apiDomains) return this
    val timestamp = header("tokenparam")?.substringBefore(',')?.takeIf { it.isNotEmpty() }
        ?: return this
    val builder = newBuilder()
        .header("Tokenparam", OfficialApiSignature.tokenParam(timestamp))
        .header("Token", OfficialApiSignature.token(timestamp))

    // SDK response decryption uses this same timestamp, so retain it while replacing its
    // older token secret/version. Official fetchPost sends FormData for these API calls.
    val form = body as? FormBody
    if (method == "POST" && form != null && url.encodedPath != "/tag_block") {
        val multipart = MultipartBody.Builder().setType(MultipartBody.FORM).apply {
            for (index in 0 until form.size) addFormDataPart(form.name(index), form.value(index))
        }.build()
        builder.removeHeader("Content-Type").removeHeader("Content-Length")
            .method(method, multipart)
    }
    return builder.build()
}
