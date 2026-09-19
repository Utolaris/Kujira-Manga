package com.par9uet.jm

import android.app.Application
import com.par9uet.jm.di.appModule
import com.par9uet.jm.di.cacheModule
import com.par9uet.jm.di.coilModule
import com.par9uet.jm.di.comicModule
import com.par9uet.jm.di.databaseModule
import com.par9uet.jm.di.favoriteModule
import com.par9uet.jm.di.retrofitModule
import com.par9uet.jm.di.userModule
import com.par9uet.jm.network.DeviceWebViewUserAgent
import com.par9uet.jm.ui.haptics.AppHaptics
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.workmanager.koin.workManagerFactory
import org.koin.core.context.startKoin

private val moduleList = listOf(
    appModule,
    coilModule,
    comicModule,
    retrofitModule,
    userModule,
    databaseModule,
    favoriteModule,
    cacheModule,
)

class JmApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppHaptics.install(this)

        startKoin {
            androidContext(this@JmApplication)
            workManagerFactory()
            modules(moduleList)
        }

        // WebSettings.getDefaultUserAgent() 会初始化 WebView，必须在主线程跑一次；
        // 之后请求层只读缓存，把 SDK 硬编码的 Android 9 / Chrome 91 UA 换成设备真实的 WebView UA。
        DeviceWebViewUserAgent.warmUp(this)
    }
}
