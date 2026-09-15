package com.par9uet.jm.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.par9uet.jm.ui.glass.GlassModal

/**
 * 新装 APK 且设备检测为平板时的首次询问：是否打开平板模式。
 * 任选一侧都会落盘，之后只由设置开关改写。
 */
@Composable
internal fun TabletLayoutPromptDialog(
    visible: Boolean,
    onEnable: () -> Unit,
    onDisable: () -> Unit,
) {
    GlassModal(
        visible = visible,
        // 首次询问不允许点外部关闭：必须显式选择，避免 tabletLayoutEnabled 一直为 null。
        onDismissRequest = {},
        dismissOnOutsideClick = false,
        dismissOnBack = false,
        surfaceId = "tablet-layout-prompt",
        modifier = Modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "检测到平板设备",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
            )
            Text(
                text = "是否打开平板模式？大屏会启用侧栏导航与更宽的布局。之后可在设置中随时切换。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                TextButton(
                    onClick = onDisable,
                    modifier = Modifier.padding(end = 4.dp),
                ) {
                    Text("保持手机布局")
                }
                TextButton(onClick = onEnable) {
                    Text("打开平板模式")
                }
            }
        }
    }
}
