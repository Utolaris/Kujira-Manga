package com.par9uet.jm.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import com.par9uet.jm.ui.glass.GlassModal
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class SelectOption(val label: String, val value: String)

/**
 * Glass-backed radio selection dialog. Render from CommonScaffold overlayContent so the modal
 * samples live page content for its Gaussian backdrop.
 */
@Composable
fun SelectDialog(
    visible: Boolean,
    title: String,
    value: String?,
    modifier: Modifier = Modifier,
    selectOptionList: List<SelectOption> = listOf(),
    onSelect: (String) -> Unit = {},
    onDismissRequest: () -> Unit = {},
) {
    GlassModal(
        visible = visible,
        onDismissRequest = onDismissRequest,
        surfaceId = "select-dialog-glass",
        modifier = modifier,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = title,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 16.dp),
            )
            HorizontalDivider()
            // containerSize is in PIXELS, so `.dp` on it would treat pixels as dp and inflate this
            // cap by the display density. The list would then swallow the whole viewport: the
            // title slides up under the status bar, the option rows run past the bottom edge and
            // the footer is measured to zero height. Convert through LocalDensity, the same way
            // every other list-height cap in the app does it.
            val density = LocalDensity.current
            val maxHeight = with(density) {
                LocalWindowInfo.current.containerSize.height.toDp() * 0.6f
            }
            // 单选组的同一个 value 出现两次本身没有意义，但会让下面 `key = { it.value }`
            // 撞 key（LazyList 的 key 等同 SubcomposeLayout 的 slotId，重复即抛异常）。
            // 保留首次出现的顺序与文案。
            val options = remember(selectOptionList) { selectOptionList.distinctBy { it.value } }
            LazyColumn(
                modifier = Modifier.heightIn(max = maxHeight)
            ) {
                items(options, key = { it.value }) { option ->
                    Row(
                        modifier = Modifier
                            .clickable(onClick = {
                                onSelect(option.value)
                            })
                            .padding(horizontal = 24.dp, vertical = 14.dp)
                            .fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = option.value == value,
                            onClick = {
                                onSelect(option.value)
                            }
                        )
                        Text(text = option.label)
                    }
                }
            }
            HorizontalDivider()
            Row(
                modifier = Modifier.padding(vertical = 8.dp, horizontal = 16.dp)
            ) {
                Spacer(modifier = Modifier.weight(1f))
                TextButton(
                    onClick = {
                        onDismissRequest()
                    }
                ) {
                    Text("取消")
                }
            }
        }
    }
}
