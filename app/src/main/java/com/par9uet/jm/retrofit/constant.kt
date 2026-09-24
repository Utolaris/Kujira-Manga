package com.par9uet.jm.retrofit

import com.par9uet.jm.core.network.OfficialApiSignature

val API_TS = System.currentTimeMillis() / 1000
const val API_VERSION = OfficialApiSignature.VERSION
const val API_TOKEN_SECRET = OfficialApiSignature.SECRET
val API_TOKEN_HASH = OfficialApiSignature.token(API_TS.toString())
