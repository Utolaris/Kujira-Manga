package com.par9uet.jm.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.par9uet.jm.data.models.SearchComicDateFilter
import com.par9uet.jm.ui.glass.GlassModal
import java.util.Calendar

/**
 * 搜索结果的年/月筛选弹窗。
 *
 * 版面沿用每周推荐的 [SelectDialog]（GlassModal + 单选行 + 底部操作），
 * 只是官方搜索要**同时**定年与月，所以拆成左右两列；空选项对应官方的
 * 「全部年份 / 全部月份」（请求里省略该参数）。
 *
 * 确认才提交：两列是同一组筛选，点一下就打网会连打两次。
 */
@Composable
fun YearMonthSelectDialog(
    visible: Boolean,
    year: String,
    month: String,
    onSelect: (year: String, month: String) -> Unit,
    onClear: () -> Unit,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var draftYear by remember(visible, year, month) { mutableStateOf(year) }
    var draftMonth by remember(visible, month) { mutableStateOf(month) }
    val currentYear = remember { Calendar.getInstance().get(Calendar.YEAR) }
    val yearOptions = remember(currentYear) {
        listOf("" to "全部年份") + SearchComicDateFilter.yearValues(currentYear).map { it to it }
    }
    val monthOptions = remember {
        listOf("" to "全部月份") + SearchComicDateFilter.monthValues().map { it to "${it}月" }
    }

    GlassModal(
        visible = visible,
        onDismissRequest = onDismissRequest,
        surfaceId = "year-month-select-dialog-glass",
        modifier = modifier,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "选择年月",
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 16.dp),
            )
            Text(
                text = "留空表示不限，可与当前关键词和排序同时生效",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 12.dp),
            )
            HorizontalDivider()
            val density = LocalDensity.current
            val maxHeight = with(density) {
                LocalWindowInfo.current.containerSize.height.toDp() * 0.5f
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxHeight),
            ) {
                YearMonthOptionColumn(
                    title = "年份",
                    options = yearOptions,
                    selected = draftYear,
                    onSelect = { draftYear = it },
                    modifier = Modifier.weight(1f),
                )
                HorizontalDivider(
                    modifier = Modifier
                        .width(1.dp)
                        .fillMaxHeight(),
                )
                YearMonthOptionColumn(
                    title = "月份",
                    options = monthOptions,
                    selected = draftMonth,
                    onSelect = { draftMonth = it },
                    modifier = Modifier.weight(1f),
                )
            }
            HorizontalDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp, horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = {
                        draftYear = ""
                        draftMonth = ""
                        onClear()
                        onDismissRequest()
                    },
                ) {
                    Text("清除筛选")
                }
                Spacer(modifier = Modifier.weight(1f))
                TextButton(onClick = onDismissRequest) {
                    Text("取消")
                }
                TextButton(
                    onClick = {
                        onSelect(draftYear, draftMonth)
                        onDismissRequest()
                    },
                ) {
                    Text("确定")
                }
            }
        }
    }
}

@Composable
private fun YearMonthOptionColumn(
    title: String,
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        )
        LazyColumn {
            items(options, key = { it.first }) { (value, label) ->
                Row(
                    modifier = Modifier
                        .clickable { onSelect(value) }
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = value == selected,
                        onClick = { onSelect(value) },
                    )
                    Text(text = label, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
