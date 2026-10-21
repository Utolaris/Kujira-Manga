package com.par9uet.jm.repository

import com.par9uet.jm.core.network.NetWorkResult
import com.par9uet.jm.core.model.RemoteSetting

interface RemoteSettingRepository {
    suspend fun getRemoteSetting(): NetWorkResult<RemoteSetting>
}
