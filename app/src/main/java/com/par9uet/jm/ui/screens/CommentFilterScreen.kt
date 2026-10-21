package com.par9uet.jm.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.par9uet.jm.ui.components.CommonScaffold
import com.par9uet.jm.ui.components.MotionCard as Card
import com.par9uet.jm.ui.components.MotionIconButton as IconButton
import com.par9uet.jm.ui.viewModel.SettingsViewModel
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun CommentFilterScreen(settingsViewModel: SettingsViewModel = koinViewModel()) {
    val keywords by settingsViewModel.commentFilterKeywords.collectAsState()
    CommentFilterContent(keywords, settingsViewModel::addCommentFilterKeyword, settingsViewModel::removeCommentFilterKeyword)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CommentFilterContent(
    keywords: List<String>,
    onAddKeyword: (String) -> Boolean,
    onRemoveKeyword: (String) -> Unit,
) {
    var input by rememberSaveable { mutableStateOf("") }
    fun addKeyword() {
        if (input.isNotBlank() && onAddKeyword(input)) input = ""
    }

    CommonScaffold(title = "评论过滤") { topContentPadding, bottomContentPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp,
                top = topContentPadding + 16.dp, bottom = bottomContentPadding + 32.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                ) {
                    Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Icon(Icons.Rounded.Block, null, Modifier.size(28.dp),
                            tint = MaterialTheme.colorScheme.onPrimaryContainer)
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("隐藏不想看到的评论", style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                            Text("规则为“或”：正文包含任意一个关键词即隐藏，忽略英文大小写。评论及回复均生效，修改后立即保存。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                    }
                }
            }
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("添加关键词", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = input, onValueChange = { input = it }, modifier = Modifier.weight(1f),
                                placeholder = { Text("输入过滤关键词") }, singleLine = true,
                                shape = RoundedCornerShape(14.dp),
                                colors = TextFieldDefaults.colors(
                                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                                ),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                keyboardActions = KeyboardActions(onDone = { addKeyword() }),
                            )
                            IconButton(onClick = ::addKeyword, enabled = input.isNotBlank()) {
                                Icon(Icons.Rounded.Add, contentDescription = "添加关键词")
                            }
                        }
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("已保存关键词", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text("${keywords.size} 个", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                ) {
                    if (keywords.isEmpty()) {
                        Text("未设置关键词，当前不过滤评论。", Modifier.padding(18.dp),
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        FlowRow(Modifier.fillMaxWidth().padding(14.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            keywords.forEach { keyword ->
                                InputChip(
                                    selected = false, onClick = {}, shape = RoundedCornerShape(8.dp),
                                    label = { Text(keyword, Modifier.widthIn(max = 240.dp), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                    trailingIcon = {
                                        IconButton(onClick = { onRemoveKeyword(keyword) }, modifier = Modifier.size(28.dp)) {
                                            Icon(Icons.Rounded.Close, "移除 $keyword", Modifier.size(16.dp))
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
