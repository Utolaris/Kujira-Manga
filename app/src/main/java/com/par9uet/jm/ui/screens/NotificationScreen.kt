package com.par9uet.jm.ui.screens

import com.par9uet.jm.ui.components.MotionCard as Card

import com.par9uet.jm.ui.components.MotionTextButton as TextButton
import com.par9uet.jm.ui.components.MotionIconButton as IconButton

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleStartEffect
import com.par9uet.jm.notification.model.NotificationFilter
import com.par9uet.jm.ui.components.ArticleBlockContent
import com.par9uet.jm.ui.components.BackIconButton
import com.par9uet.jm.ui.components.CommonScaffold
import com.par9uet.jm.ui.components.ReimuLoadingIndicator
import com.par9uet.jm.ui.glass.*
import com.par9uet.jm.ui.navigation.LocalMainNavController
import com.par9uet.jm.ui.navigation.articleNavigationRoute
import com.par9uet.jm.ui.viewModel.NotificationViewModel
import org.koin.compose.viewmodel.koinActivityViewModel

@Composable
fun NotificationScreen(viewModel: NotificationViewModel = koinActivityViewModel()) {
    val state by viewModel.state.collectAsState()
    val nav = LocalMainNavController.current
    val uri = LocalUriHandler.current
    val haptic = LocalHapticFeedback.current
    val menu = rememberGlassAnchoredMenuState()
    val scroll = key(state.filter) { rememberLazyListState() }
    LifecycleStartEffect(Unit) {
        viewModel.enter()
        onStopOrDispose { viewModel.leave() }
    }
    val openLink: (String) -> Unit = { link ->
        val route = articleNavigationRoute(link)
        if (route != null) nav.navigate(route) { launchSingleTop = true }
        else if (link.startsWith("https://") || link.startsWith("http://")) runCatching { uri.openUri(link) }
    }
    CommonScaffold(
        title = state.filter.label,
        navigationContent = {
            BackIconButton()
        },
        titleContent = {
            Text(state.filter.label, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.glassMenuAnchor(menu).clickable {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    menu.open()
                })
        },
        actions = {
            IconButton(onClick = { viewModel.selectFilter(state.filter) }, enabled = !state.loading && !state.markingAll) {
                Icon(Icons.Rounded.Sync, contentDescription = "刷新通知")
            }
            IconButton(onClick = viewModel::markAllRead,
                enabled = !state.loading && !state.markingAll && state.readingIds.isEmpty()) {
                Icon(Icons.Rounded.Check, contentDescription = "全部通知一键已读")
            }
        },
        overlayContent = {
            GlassAnchoredMenu(menu, surfaceId = "notification-filter", width = 240.dp, menuMaxHeight = 260.dp) {
                NotificationFilter.entries.forEach { filter ->
                    GlassMenuItem(filter.label, selected = state.filter == filter, onClick = {
                        menu.dismiss()
                        viewModel.selectFilter(filter)
                    })
                }
            }
        },
    ) { top, bottom ->
        LazyColumn(state = scroll, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(
            start = 20.dp, end = 20.dp, top = top + 20.dp, bottom = bottom + 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            items(state.items, key = { it.id }) { message ->
                Card(onClick = { viewModel.open(message.id) }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(if (message.type == "comic_follow") "漫画追踪" else "站内通知",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary)
                            if (!message.read) Text("未读", style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary)
                        }
                        Text(message.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        Text(message.date, style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (state.expandedId == message.id) {
                            HorizontalDivider()
                            message.blocks.forEach { ArticleBlockContent(it, onLink = openLink) }
                            if (message.blocks.isEmpty()) Text("暂无正文")
                        }
                    }
                }
            }
            item {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.loading || state.markingAll) ReimuLoadingIndicator()
                    state.error?.let {
                        Text(it, color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = { viewModel.selectFilter(state.filter) }) { Text("刷新通知") }
                        if (it.contains("请先登录")) TextButton(onClick = { nav.navigate("login") }) { Text("登录") }
                    }
                    if (!state.loading && !state.markingAll) {
                        when {
                            state.hasMore -> TextButton(onClick = viewModel::load) { Text("加载更多通知") }
                            state.items.isEmpty() && state.error == null -> Text("暂无通知")
                        }
                    }
                }
            }
        }
    }
}
