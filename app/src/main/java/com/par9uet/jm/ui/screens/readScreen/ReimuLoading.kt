package com.par9uet.jm.ui.screens.readScreen

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.par9uet.jm.R

/**
 * 阅读器加载动画：48x48 reimu 表情包 GIF，替代默认的转圈。
 * GIF 帧解码依赖全局默认 ImageLoader 上的 ImageDecoderDecoder（见 JmApplication），
 * 封面管线不经过该实例。
 */
@Composable
internal fun ReimuLoadingIndicator(modifier: Modifier = Modifier) {
    AsyncImage(
        model = R.drawable.reimu_loading,
        contentDescription = null,
        modifier = modifier.size(48.dp),
    )
}
