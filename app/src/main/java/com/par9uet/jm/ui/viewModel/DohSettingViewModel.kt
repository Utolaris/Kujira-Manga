package com.par9uet.jm.ui.viewModel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.par9uet.jm.network.DOH_SERVER_CUSTOM
import com.par9uet.jm.network.DohLatencyResult
import com.par9uet.jm.network.DohManager
import com.par9uet.jm.network.DohRuntimeStatus
import com.par9uet.jm.network.DohServer
import com.par9uet.jm.network.builtinDohServers
import com.par9uet.jm.network.isValidDohUrl
import com.par9uet.jm.network.mergeDohServers
import com.par9uet.jm.storage.DohPreferences
import com.par9uet.jm.storage.DohSettingsState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class DohServerUi(
    val id: String,
    val name: String,
    val displayUrl: String,
)

data class DohLatencyUi(
    val elapsedMs: Long?,
    val failed: Boolean,
)

data class DohSettingUiState(
    val doh: DohSettingsState = DohSettingsState(),
    val status: DohRuntimeStatus = DohRuntimeStatus(),
    val latency: Map<String, DohLatencyUi> = emptyMap(),
    val servers: List<DohServerUi> = emptyList(),
    val testingAll: Boolean = false,
)

/** L2：DoH 设置页业务（线路合并、校验、测速、开关）；Screen 只渲染与提交事件。 */
class DohSettingViewModel(
    dohPreferences: DohPreferences,
    private val dohManager: DohManager,
) : ViewModel() {
    private val _testingAll = MutableStateFlow(false)
    val testingAll: StateFlow<Boolean> = _testingAll.asStateFlow()

    private val serversById: MutableMap<String, DohServer> = mutableMapOf()

    val doh: StateFlow<DohSettingsState> = dohPreferences.doh
    val status: StateFlow<DohRuntimeStatus> = dohManager.status

    val latency: StateFlow<Map<String, DohLatencyUi>> = dohManager.latencyState
        .map { map ->
            map.mapValues { (_, result) ->
                DohLatencyUi(elapsedMs = result.elapsedMs, failed = result.elapsedMs == null)
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    fun serversFor(doh: DohSettingsState): List<DohServerUi> {
        val custom = DohServer(
            id = DOH_SERVER_CUSTOM,
            name = doh.customServerName.ifBlank { "自定义 DoH" },
            displayUrl = doh.customServerUrl,
        )
        val merged = mergeDohServers(builtinDohServers, custom)
        serversById.clear()
        merged.forEach { serversById[it.id] = it }
        return merged.map { DohServerUi(id = it.id, name = it.name, displayUrl = it.displayUrl) }
    }

    fun isCustomUrlValid(url: String): Boolean = isValidDohUrl(url)

    fun setEnabled(enabled: Boolean) = dohManager.setEnabled(enabled)
    fun setAutoStart(enabled: Boolean) = dohManager.setAutoStart(enabled)
    fun setAutoSelectFastest(enabled: Boolean) = dohManager.setAutoSelectFastest(enabled)
    fun setUseDeviceCertificates(enabled: Boolean) = dohManager.setUseDeviceCertificates(enabled)
    fun setPreferIpv6(enabled: Boolean) = dohManager.setPreferIpv6(enabled)
    fun clearCache() = dohManager.clearCache()
    fun selectServer(serverId: String): Boolean = dohManager.selectServer(serverId)

    fun selectServerOrPromptCustom(serverId: String, customUrl: String): Boolean {
        if (serverId == DOH_SERVER_CUSTOM && !isValidDohUrl(customUrl)) {
            return false
        }
        return dohManager.selectServer(serverId)
    }

    /** @return error message, or null on success. */
    fun saveCustomServer(name: String, url: String): String? {
        if (!isValidDohUrl(url)) return "请输入有效的 HTTPS DoH 地址"
        if (!dohManager.saveCustomServer(name, url)) return "设置保存失败，请重试"
        return null
    }

    fun testServer(serverId: String) {
        val server = serversById[serverId] ?: return
        viewModelScope.launch { dohManager.testServer(server) }
    }

    fun testAllServers(serverIds: List<String>) {
        if (_testingAll.value) return
        val targets = serverIds.mapNotNull { serversById[it] }
        if (targets.isEmpty()) return
        _testingAll.value = true
        viewModelScope.launch {
            try {
                targets.forEach { dohManager.testServer(it) }
            } finally {
                _testingAll.value = false
            }
        }
    }
}
