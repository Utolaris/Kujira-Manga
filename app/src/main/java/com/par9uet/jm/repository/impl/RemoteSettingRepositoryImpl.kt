package com.par9uet.jm.repository.impl

import com.par9uet.jm.core.BaseRepository
import com.par9uet.jm.core.model.RemoteSetting
import com.par9uet.jm.repository.RemoteSettingRepository
import com.par9uet.jm.core.network.NetWorkResult
import com.par9uet.jm.core.network.map
import com.par9uet.jm.retrofit.service.RemoteSettingService

class RemoteSettingRepositoryImpl(
    private val service: RemoteSettingService,
) : BaseRepository(), RemoteSettingRepository {
    override suspend fun getRemoteSetting(): NetWorkResult<RemoteSetting> {
        return safeApiCall {
            service.getRemoteSetting()
        }.map { RemoteSetting(imgHost = it.img_host) }
    }
}
