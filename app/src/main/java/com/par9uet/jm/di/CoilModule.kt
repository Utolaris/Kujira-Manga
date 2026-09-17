package com.par9uet.jm.di

import com.par9uet.jm.coil.CoverImageLoaderHolder
import org.koin.dsl.module

val coilModule = module {
    // 不注册 ImageLoader single：磁盘缓存上限变更后会换实例，
    // 注入方一律经 CoverImageLoaderHolder.current() / loader 取当前值。
    single { CoverImageLoaderHolder(get(), get(), get(), get()) }
}
