package com.par9uet.jm.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import coil.ImageLoader
import com.par9uet.jm.coil.CoverImageHostResolver
import com.par9uet.jm.coil.CoverImageLoaderHolder
import com.par9uet.jm.core.concurrent.ForegroundWork
import org.koin.compose.getKoin

/**
 * 网格/App 级封面共享环境：在根部统一收集一次 loader 与 networkGeneration，
 * 避免 6 列高密度网格里每张封面卡片重复执行 Koin 查找和 Flow 订阅。
 */
@Stable
internal data class CoverPipelineEnvironment(
    val imageLoader: ImageLoader,
    val resolver: CoverImageHostResolver,
    val networkGeneration: Long,
    val foregroundWork: ForegroundWork,
)

internal val LocalCoverPipelineEnvironment = compositionLocalOf<CoverPipelineEnvironment?> { null }

/** Compose 侧取当前封面 ImageLoader（优先走根级共享环境，测试等孤立环境回退 Koin）。 */
@Composable
fun currentCoverImageLoader(): ImageLoader {
    LocalCoverPipelineEnvironment.current?.let { return it.imageLoader }
    val holder: CoverImageLoaderHolder = getKoin().get()
    val loader by holder.loader.collectAsState()
    return loader
}
