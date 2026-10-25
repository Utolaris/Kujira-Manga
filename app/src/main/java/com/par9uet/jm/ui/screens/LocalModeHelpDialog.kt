package com.par9uet.jm.ui.screens

import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import com.par9uet.jm.ui.components.MotionTextButton as TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.par9uet.jm.ui.glass.GlassModal

/** 每次手动开启本地收藏模式前都需要确认。 */
@Composable
fun LocalModeHelpDialog(
    visible: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    GlassModal(
        visible = visible,
        onDismissRequest = onDismiss,
        surfaceId = "local-mode-help-glass",
        modifier = Modifier.widthIn(max = 420.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "本地收藏模式",
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                text = "收藏更改暂存在本机，退出模式时尝试同步。" +
                    "点击收藏页左上角的未同步数量，可查看并确认同步；同步成功后退出本地收藏模式。",
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
                    onClick = onDismiss,
                    modifier = Modifier.heightIn(min = 44.dp),
                ) {
                    Text("取消")
                }
                TextButton(
                    onClick = onConfirm,
                    modifier = Modifier.heightIn(min = 44.dp),
                ) {
                    Text("确定")
                }
            }
        }
    }
}
