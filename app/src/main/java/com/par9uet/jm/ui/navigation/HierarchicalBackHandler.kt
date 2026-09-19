package com.par9uet.jm.ui.navigation

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.runtime.Composable
import kotlinx.coroutines.CancellationException

/**
 * 页内返回拦截，与 [NavigationMotion] 同一套手势语言。
 *
 * 普通 `BackHandler` 在侧滑进行中不消费 back progress，系统会套用
 * 「屏幕中部缩小淡出」的预测性返回窗口动画，看起来像整页在消失。
 * 这里用 [PredictiveBackHandler] 吃掉 progress：手势过程中页面保持完整，
 * 松手完成后再执行 [onBack]（popBackStack / 退出页内模式 / 关弹窗）。
 */
@Composable
fun HierarchicalBackHandler(
    enabled: Boolean = true,
    onBack: () -> Unit,
) {
    PredictiveBackHandler(enabled = enabled) { progress ->
        try {
            progress.collect { /* 页面保持完整，不跟随系统缩放淡出 */ }
            onBack()
        } catch (_: CancellationException) {
            // 手势取消：页内状态不变
        }
    }
}
