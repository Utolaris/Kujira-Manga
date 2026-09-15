package com.par9uet.jm.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * 全应用统一的搜索框外壳：16dp 圆角 Card + 左侧搜索图标，视觉边界全由它提供。
 *
 * 输入控件与尾部动作由调用方给 —— 各处的“搜索”语义并不相同（首页搜索页是提交式的
 * [androidx.compose.foundation.text.input.TextFieldState]，收藏筛选是边输边筛），
 * 所以不能直接复用一个输入控件实现；但它们的外观必须共用这一个外壳，
 * 否则圆角、边框、图标位置会各调一套，慢慢就长成两个样子。
 */
@Composable
fun SearchFieldSurface(
    modifier: Modifier = Modifier,
    field: @Composable RowScope.() -> Unit,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Rounded.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 12.dp),
            )
            field()
            trailing()
        }
    }
}

/**
 * 配合 [SearchFieldSurface] 用的输入配色：容器与指示线全部透明，
 * 边框交给外层 Card，聚焦时不会突然多出一条主色描边。
 */
@Composable
fun searchFieldColors(): TextFieldColors = TextFieldDefaults.colors(
    focusedContainerColor = Color.Transparent,
    unfocusedContainerColor = Color.Transparent,
    disabledContainerColor = Color.Transparent,
    errorContainerColor = Color.Transparent,
    focusedIndicatorColor = Color.Transparent,
    unfocusedIndicatorColor = Color.Transparent,
    disabledIndicatorColor = Color.Transparent,
    errorIndicatorColor = Color.Transparent,
    cursorColor = MaterialTheme.colorScheme.primary,
)
