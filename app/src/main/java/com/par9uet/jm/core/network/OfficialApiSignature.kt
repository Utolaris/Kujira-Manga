package com.par9uet.jm.core.network

import com.par9uet.jm.utils.md5

/** JMComic3 v2.1.14 `HttpUtil` token headers. Keep the timestamp used by response decoding. */
object OfficialApiSignature {
    const val VERSION = "2.1.14"
    const val SECRET = "185Hcomic3PAPP7R"
    const val FALLBACK_SECRET = "18comicAPPContent"

    fun token(timestamp: String): String = md5(timestamp + SECRET)
    fun tokenParam(timestamp: String): String = "$timestamp,$VERSION"
}
