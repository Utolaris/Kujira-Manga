package com.par9uet.jm.retrofit

import com.par9uet.jm.core.network.OfficialApiSignature

val API_TS = System.currentTimeMillis() / 1000
val API_TOKEN_HASH = OfficialApiSignature.token(API_TS.toString())
