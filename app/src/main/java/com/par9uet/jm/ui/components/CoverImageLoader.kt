package com.par9uet.jm.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import org.koin.compose.getKoin
import coil.ImageLoader
import com.par9uet.jm.coil.CoverImageLoaderHolder

/** Compose 侧取当前封面 ImageLoader（磁盘缓存设置变更后自动换实例）。 */
@Composable
fun currentCoverImageLoader(): ImageLoader {
    val holder: CoverImageLoaderHolder = getKoin().get()
    val loader by holder.loader.collectAsState()
    return loader
}
