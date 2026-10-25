package com.par9uet.jm.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import com.par9uet.jm.ui.components.MotionIconButton as IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.sp

@Composable
fun FavoriteSyncIconButton(
    isSyncing: Boolean,
    hasError: Boolean,
    onClick: () -> Unit,
    hasSynced: Boolean = false,
    isLocalMode: Boolean = false,
    pendingComicCount: Int? = null,
) {
    val rotationModifier = if (isSyncing && !isLocalMode) {
        val transition = rememberInfiniteTransition(label = "favoriteSync")
        val rotation by transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1000, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "favoriteSyncRotation",
        )
        Modifier.graphicsLayer { rotationZ = rotation }
    } else {
        Modifier
    }

    IconButton(
        onClick = onClick,
        enabled = !isSyncing,
    ) {
        if (isLocalMode) {
            Text(
                text = pendingComicCount?.toString() ?: "—",
                fontSize = if ((pendingComicCount ?: 0) >= 10_000) 12.sp else 16.sp,
                maxLines = 1,
                modifier = Modifier.semantics {
                    contentDescription = pendingComicCount?.let {
                        if (isSyncing) "正在同步收藏夹，剩余 $it 部漫画未同步到云端"
                        else "$it 部漫画未同步到云端，点击查看"
                    } ?: "未同步漫画数量暂时无法读取，点击查看"
                },
            )
        } else Icon(
            imageVector = if (!isSyncing && !hasError && hasSynced) Icons.Rounded.Check else Icons.Rounded.Sync,
            contentDescription = when {
                hasError -> "收藏夹同步失败，点击重试"
                isSyncing -> "正在同步收藏夹"
                hasSynced -> "收藏夹同步完成"
                else -> "同步收藏夹"
            },
            modifier = rotationModifier,
            tint = if (hasError) {
                MaterialTheme.colorScheme.error
            } else if (!isSyncing && hasSynced) {
                if (MaterialTheme.colorScheme.surface.luminance() < .5f) Color(0xFF81C784) else Color(0xFF2E7D32)
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
    }
}
