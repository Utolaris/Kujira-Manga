package com.par9uet.jm.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.par9uet.jm.ui.models.LocalTabletLayoutEnabled
import com.par9uet.jm.ui.models.LocalTabletLayoutPromptPending

/**
 * 平板（大屏）布局的宽度阈值。窗口宽度 >= 700dp 视为平板，这也是首次进入时的默认判定。
 */
internal val TabletLayoutMinWidth = 700.dp

/** 竖屏/窄分屏采用完整阅读列，只有足够宽的横向窗口使用固定封面双栏。 */
internal fun useTabletDetailColumns(tabletEnabled: Boolean, width: Dp, height: Dp): Boolean =
    tabletEnabled && width >= TabletLayoutMinWidth && width > height

/** [width] 是封面所在的阅读列宽度（平板上是居中阅读列，手机上即窗口宽度）。 */
internal fun compactDetailCoverWidth(tabletEnabled: Boolean, width: Dp): Dp =
    if (tabletEnabled) minOf(240.dp, (width - 46.dp).coerceAtLeast(0.dp) * .36f) else 180.dp

/**
 * 详情封面（3:4）的绝对上限；宽窗/窄窗的两条上限都不越过它。
 *
 * 3:4 比例下面积随宽度平方增长，320 → 392 正好是 +50% 面积（392/320 ≈ 1.225 = √1.5），
 * 横屏右列仍保有 [TabletDetailMinReadingColumn] 以上的正文宽度。
 */
private val TabletDetailMaxCoverWidth = 392.dp

/**
 * 宽窗的阅读列上限：竖屏/窄分屏也用它居中限宽。
 *
 * 浏览器风格的分屏会给每个窗格 900dp 以上的横向尺寸，只用「宽 > 高」判断会把
 * 半屏窄窗误判成双栏；因此宽窗还要求至少 [TabletLayoutMinWidth] + 阅读列下限。
 */
internal val TabletReadingColumnMaxWidth = 840.dp

/** 分屏窄窗里封面与正文的最小阅读列；宽窗右列必须至少这么宽才值得拆两栏。 */
private val TabletDetailMinReadingColumn = 420.dp

/**
 * 详情页是否拆成「左封面 + 右内容」双栏。
 *
 * - 平板未启用：始终单栏（手机布局）。
 * - 横向窗口 + 宽度够放阅读列：双栏。
 * - 竖屏 / 分屏窄窗：单栏紧凑头，但封面最多 240dp、阅读列居中限宽，不再是满屏小封面。
 */
internal fun useDetailColumns(tabletEnabled: Boolean, width: Dp, height: Dp): Boolean =
    tabletEnabled && width > height && width >= TabletLayoutMinWidth + TabletDetailMinReadingColumn

/** 平板上单栏（竖屏/分屏）也走居中阅读列，手机保持满宽。 */
internal fun detailReadingColumnWidth(tabletEnabled: Boolean, width: Dp): Dp =
    if (tabletEnabled) minOf(width, TabletReadingColumnMaxWidth) else width

/**
 * 骨架和正式封面共用目标尺寸，避开顶栏/底栏并保留右侧内容列。
 *
 * 宽窗时右列至少 [TabletDetailMinReadingColumn] 宽，避免封面吃掉内容区；
 * 窄窗（竖屏/分屏）改由 [detailReadingColumnWidth] 限制的阅读列推导。
 */
internal fun tabletDetailCoverWidth(
    viewportWidth: Dp,
    viewportHeight: Dp,
    headerTopPadding: Dp,
    bottomReserved: Dp,
): Dp = minOf(
    TabletDetailMaxCoverWidth,
    (viewportWidth - 50.dp).coerceAtLeast(0.dp) * .42f, // 左右各 16dp、列间距 18dp。
    (viewportWidth - TabletDetailMinReadingColumn).coerceAtLeast(0.dp),
    (viewportHeight - headerTopPadding - bottomReserved).coerceAtLeast(0.dp) * .75f,
)

/**
 * 把平板布局状态注入 CompositionLocal，供 `ui/glass` / `ui/components` 读取。
 *
 * 由 App 组合根包在主内容外层调用一次；`tabletLayoutEnabled` / `onSeedTabletLayout`
 * 由组合根从 LocalSettingManager 注入，本文件不再服务定位。
 *
 * - 手机：首个非零窗口宽度直接把 `false` 落盘，不需要询问。
 * - 平板：首个非零窗口宽度只产生检测结果，**不**自动落盘；由首次询问弹窗或
 *   设置开关写入。询问期间按检测值预览平板布局，避免先手机后平板的闪变。
 *
 * `containerSize` 在首帧测量之前是 0，此时既不判定也不写盘——否则会把"宽度未知"
 * 固化成"手机"，且再也回不来。
 */
@Composable
internal fun ProvideTabletLayout(
    tabletLayoutEnabled: Boolean?,
    onSeedTabletLayout: (Boolean) -> Unit,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val containerWidthPx = LocalWindowInfo.current.containerSize.width
    // containerSize 是像素，必须过密度换算；写成 containerSize.width.dp 会把像素当 dp。
    val detected = containerWidthPx > 0 &&
        with(density) { containerWidthPx.toDp() } >= TabletLayoutMinWidth
    LaunchedEffect(tabletLayoutEnabled, containerWidthPx) {
        // 只有手机侧自动落盘；平板侧留给询问弹窗 / 设置开关，避免静默开启。
        if (tabletLayoutEnabled == null && containerWidthPx > 0 && !detected) {
            onSeedTabletLayout(false)
        }
    }
    val enabled = tabletLayoutEnabled ?: detected
    val promptPending = tabletLayoutEnabled == null && containerWidthPx > 0 && detected
    CompositionLocalProvider(
        LocalTabletLayoutEnabled provides enabled,
        LocalTabletLayoutPromptPending provides promptPending,
    ) {
        content()
    }
}
