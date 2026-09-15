package com.par9uet.jm.ui.models

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * 平板（大屏）布局是否启用。由组合根提供，供 `ui/glass` / `ui/components` 等
 * 支撑层读取——这些包禁止反向 import `ui.screens`。
 */
val LocalTabletLayoutEnabled = staticCompositionLocalOf { false }

/**
 * 是否仍需要弹出「首次平板模式询问」。true 时设置里还没有落盘的值，
 * 且当前窗口已检测为平板。
 */
val LocalTabletLayoutPromptPending = staticCompositionLocalOf { false }
