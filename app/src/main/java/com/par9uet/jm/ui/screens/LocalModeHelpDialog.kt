package com.par9uet.jm.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.par9uet.jm.ui.glass.GlassModal

/**
 * 手动开启本地模式前的说明弹窗。
 * 「不再显示」会记住不再弹；「确定」仅本次确认。两者都会继续开启本地模式。
 */
@Composable
fun LocalModeHelpDialog(
    visible: Boolean,
    onConfirm: (dontShowAgain: Boolean) -> Unit,
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
                .fillMaxWidth()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "本地模式",
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                text = "本地模式是面对夜间账号风控时的手段，本地模式下，历史记录单独处理，您收藏的漫画，将会在本地模式关闭时同步到云端。" +
                    "如果您手动点击收藏夹的同步按钮，这会结束本地模式，并且立刻同步到远端。",
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
                    onClick = { onConfirm(true) },
                    modifier = Modifier.heightIn(min = 44.dp),
                ) {
                    Text("不再显示")
                }
                TextButton(
                    onClick = { onConfirm(false) },
                    modifier = Modifier.heightIn(min = 44.dp),
                ) {
                    Text("确定")
                }
            }
        }
    }
}
