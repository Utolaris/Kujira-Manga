package com.par9uet.jm.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import com.par9uet.jm.storage.LocalSettingManager
import com.par9uet.jm.ui.models.LocalTabletLayoutEnabled
import com.par9uet.jm.ui.models.LocalTabletLayoutPromptPending
import org.koin.compose.getKoin

/**
 * 平板（大屏）布局的宽度阈值。窗口宽度 >= 700dp 视为平板，这也是首次进入时的默认判定。
 */
internal val TabletLayoutMinWidth = 700.dp

/**
 * 平板模式的唯一判定点：`TabScreen`（左栏 + 顶栏）与 `ComicDetailScreen`（双栏 + 底栏）
 * 都从这里取值，避免两处各自写死阈值而漂移。`ui/glass` 通过 CompositionLocal 读取
 * （见 `ui/models/TabletLayout.kt`），禁止从玻璃层反向 import 本文件。
 *
 * - 手机：首个非零窗口宽度直接把 `false` 落盘，不需要询问。
 * - 平板：首个非零窗口宽度只产生检测结果，**不**自动落盘；由首次询问弹窗或
 *   设置开关写入。询问期间按检测值预览平板布局，避免先手机后平板的闪变。
 *
 * `containerSize` 在首帧测量之前是 0，此时既不判定也不写盘——否则会把"宽度未知"
 * 固化成"手机"，且再也回不来。
 */
@Composable
internal fun rememberTabletLayout(): Boolean {
    val density = LocalDensity.current
    val containerWidthPx = LocalWindowInfo.current.containerSize.width
    val localSettingManager: LocalSettingManager = getKoin().get()
    val misc by localSettingManager.misc.collectAsState()
    val stored = misc.tabletLayoutEnabled
    // containerSize 是像素，必须过密度换算；写成 containerSize.width.dp 会把像素当 dp。
    val detected = containerWidthPx > 0 &&
        with(density) { containerWidthPx.toDp() } >= TabletLayoutMinWidth
    LaunchedEffect(stored, containerWidthPx) {
        // 只有手机侧自动落盘；平板侧留给询问弹窗 / 设置开关，避免静默开启。
        if (stored == null && containerWidthPx > 0 && !detected) {
            localSettingManager.seedTabletLayoutEnabled(false)
        }
    }
    return stored ?: detected
}

/** 首次安装且检测为平板、尚未落盘时为 true；驱动「是否打开平板模式」询问。 */
@Composable
internal fun rememberTabletLayoutPromptPending(): Boolean {
    val density = LocalDensity.current
    val containerWidthPx = LocalWindowInfo.current.containerSize.width
    val localSettingManager: LocalSettingManager = getKoin().get()
    val misc by localSettingManager.misc.collectAsState()
    val stored = misc.tabletLayoutEnabled
    val detected = containerWidthPx > 0 &&
        with(density) { containerWidthPx.toDp() } >= TabletLayoutMinWidth
    return stored == null && containerWidthPx > 0 && detected
}

/**
 * 把平板布局状态注入 CompositionLocal，供 `ui/glass` / `ui/components` 读取。
 * 由 App 组合根包在主内容外层调用一次。
 */
@Composable
internal fun ProvideTabletLayout(content: @Composable () -> Unit) {
    val enabled = rememberTabletLayout()
    val promptPending = rememberTabletLayoutPromptPending()
    CompositionLocalProvider(
        LocalTabletLayoutEnabled provides enabled,
        LocalTabletLayoutPromptPending provides promptPending,
    ) {
        content()
    }
}
