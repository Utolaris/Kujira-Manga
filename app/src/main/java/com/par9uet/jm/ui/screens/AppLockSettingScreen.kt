package com.par9uet.jm.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Gesture
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.par9uet.jm.data.models.APP_LOCK_TYPE_PASSWORD
import com.par9uet.jm.data.models.APP_LOCK_TYPE_PATTERN
import com.par9uet.jm.data.models.APP_LOCK_UNLOCK_MODE_BOTH
import com.par9uet.jm.data.models.APP_LOCK_UNLOCK_MODE_PASSWORD
import com.par9uet.jm.data.models.APP_LOCK_UNLOCK_MODE_PATTERN
import com.par9uet.jm.ui.components.CommonScaffold
import com.par9uet.jm.ui.components.SelectDialog
import com.par9uet.jm.ui.components.SelectOption
import com.par9uet.jm.ui.viewModel.AppLockSettingViewModel
import org.koin.compose.viewmodel.koinViewModel

private val unlockModeTextMap = mapOf(
    APP_LOCK_UNLOCK_MODE_PASSWORD to "仅密码",
    APP_LOCK_UNLOCK_MODE_PATTERN to "仅图案",
    APP_LOCK_UNLOCK_MODE_BOTH to "两种都要",
)

@Composable
fun AppLockSettingScreen(
    viewModel: AppLockSettingViewModel = koinViewModel(),
) {
    val appLock by viewModel.appLock.collectAsState()

    val hasPassword = appLock.hasPassword
    val hasPattern = appLock.hasPattern
    val hasAnyMethod = appLock.hasCredential

    var showPasswordLengthDialog by remember { mutableStateOf(false) }
    var showSetPasswordDialog by remember { mutableStateOf(false) }
    var showSetPatternDialog by remember { mutableStateOf(false) }
    var pendingPasswordLength by remember { mutableIntStateOf(appLock.passwordLength) }

    CommonScaffold(
        title = "应用锁",
        overlayContent = {
            val lengthOptions = remember {
                (4..8).map { SelectOption("$it 位", it.toString()) }
            }

            SelectDialog(
                visible = showPasswordLengthDialog,
                title = "密码长度",
                value = pendingPasswordLength.toString(),
                modifier = Modifier.widthIn(max = 420.dp),
                selectOptionList = lengthOptions,
                onSelect = { value ->
                    pendingPasswordLength = value.toIntOrNull() ?: 4
                    showPasswordLengthDialog = false
                    showSetPasswordDialog = true
                },
                onDismissRequest = { showPasswordLengthDialog = false },
            )

            SetAppLockPasswordDialog(
                visible = showSetPasswordDialog,
                lockType = APP_LOCK_TYPE_PASSWORD,
                passwordLength = pendingPasswordLength,
                onConfirm = { pwd ->
                    if (viewModel.setPassword(pwd, pendingPasswordLength)) {
                        showSetPasswordDialog = false
                    }
                },
                onDismiss = { showSetPasswordDialog = false },
            )

            SetAppLockPasswordDialog(
                visible = showSetPatternDialog,
                lockType = APP_LOCK_TYPE_PATTERN,
                onConfirm = { pattern ->
                    if (viewModel.setPattern(pattern)) {
                        showSetPatternDialog = false
                    }
                },
                onDismiss = { showSetPatternDialog = false },
            )
        },
    ) { topContentPadding, bottomContentPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                top = topContentPadding + 16.dp,
                end = 16.dp,
                bottom = bottomContentPadding + 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                SettingsSection(title = "设置解锁方式") {
                    SettingsSwitchRow(
                        icon = Icons.Rounded.Key,
                        title = "密码",
                        value = hasPassword,
                        onCheckedChange = { enabled ->
                            if (enabled) {
                                pendingPasswordLength = appLock.passwordLength
                                showPasswordLengthDialog = true
                            } else {
                                viewModel.removePassword()
                            }
                        }
                    )
                    if (hasPassword) {
                        SettingsRow(
                            icon = Icons.Rounded.Key,
                            title = "密码长度",
                            value = "${appLock.passwordLength} 位"
                        ) {
                            pendingPasswordLength = appLock.passwordLength
                            showPasswordLengthDialog = true
                        }
                    }
                    SettingsSwitchRow(
                        icon = Icons.Rounded.Gesture,
                        title = "图案锁",
                        value = hasPattern,
                        onCheckedChange = { enabled ->
                            if (enabled) {
                                showSetPatternDialog = true
                            } else {
                                viewModel.removePattern()
                            }
                        }
                    )
                }
            }

            if (hasPassword && hasPattern) {
                item {
                    SettingsSection(title = "解锁模式") {
                        unlockModeTextMap.forEach { (mode, label) ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .selectable(
                                        selected = appLock.unlockMode == mode,
                                        onClick = { viewModel.selectUnlockMode(mode) }
                                    )
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = appLock.unlockMode == mode,
                                    onClick = { viewModel.selectUnlockMode(mode) }
                                )
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier.padding(start = 12.dp)
                                )
                            }
                        }
                    }
                }
            }

            item {
                SettingsSection(title = "启用") {
                    SettingsSwitchRow(
                        icon = Icons.Rounded.Lock,
                        title = "启用应用锁",
                        value = appLock.enabled,
                        onCheckedChange = { enabled ->
                            viewModel.setAppLockEnabled(enabled)
                        }
                    )
                    if (!hasAnyMethod) {
                        Text(
                            text = "请先设置至少一种解锁方式",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(modifier = Modifier.padding(vertical = 8.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            content()
        }
    }
}

@Composable
private fun SettingsSwitchRow(
    icon: ImageVector,
    title: String,
    value: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = value, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SettingsRow(
    icon: ImageVector,
    title: String,
    value: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
