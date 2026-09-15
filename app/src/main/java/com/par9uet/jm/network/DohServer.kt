package com.par9uet.jm.network

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

const val DOH_SERVER_TENCENT = "tencent"
const val DOH_SERVER_ALIDNS = "alidns"
const val DOH_SERVER_ALIDNS_223_5 = "alidns_223_5"
const val DOH_SERVER_ALIDNS_223_6 = "alidns_223_6"
const val DOH_SERVER_CUSTOM = "custom"

data class DohServer(
    val id: String,
    val name: String,
    val displayUrl: String,
    // The endpoint keeps a DNS name for TLS SNI when the selected route is an IP.
    val endpointUrl: String = displayUrl,
    val bootstrapHost: String? = null,
    val bootstrapIps: List<String> = emptyList(),
)

val builtinDohServers = listOf(
    DohServer(
        id = DOH_SERVER_TENCENT,
        name = "腾讯 DNSPod",
        displayUrl = "https://doh.pub/dns-query",
        bootstrapHost = "doh.pub",
        bootstrapIps = listOf("1.12.12.12", "120.53.53.53"),
    ),
    DohServer(
        id = DOH_SERVER_ALIDNS,
        name = "阿里 DNS",
        displayUrl = "https://dns.alidns.com/dns-query",
        bootstrapHost = "dns.alidns.com",
        bootstrapIps = listOf("223.5.5.5", "223.6.6.6"),
    ),
    DohServer(
        id = DOH_SERVER_ALIDNS_223_5,
        name = "阿里 DNS 223.5.5.5",
        displayUrl = "https://223.5.5.5/dns-query",
        endpointUrl = "https://dns.alidns.com/dns-query",
        bootstrapHost = "dns.alidns.com",
        bootstrapIps = listOf("223.5.5.5"),
    ),
    DohServer(
        id = DOH_SERVER_ALIDNS_223_6,
        name = "阿里 DNS 223.6.6.6",
        displayUrl = "https://223.6.6.6/dns-query",
        endpointUrl = "https://dns.alidns.com/dns-query",
        bootstrapHost = "dns.alidns.com",
        bootstrapIps = listOf("223.6.6.6"),
    ),
)

fun resolveDohServer(
    selectedId: String,
    customName: String,
    customUrl: String,
): DohServer {
    if (selectedId == DOH_SERVER_CUSTOM) {
        val normalizedUrl = customUrl.trim()
        val validUrl = normalizedUrl.toHttpUrlOrNull()
            ?.takeIf { it.isHttps }
            ?.toString()
            .orEmpty()
        if (validUrl.isNotBlank()) {
            return DohServer(
                id = DOH_SERVER_CUSTOM,
                name = customName.trim().ifBlank { "自定义 DoH" },
                displayUrl = validUrl,
            )
        }
    }
    return builtinDohServers.firstOrNull { it.id == selectedId } ?: builtinDohServers.first()
}

/**
 * DoH 设置页展示用的服务列表。
 *
 * 同一 `id` 只能出现一次：它是 `LazyColumn` 的 key（等同 `SubcomposeLayout` 的 slotId），
 * 重复即抛 `IllegalArgumentException`。自定义项覆盖同名内置项（自定义项带着用户填的地址），
 * 位置沿用该 id 首次出现的位置，避免开关设置后列表跳序。
 */
fun mergeDohServers(
    builtin: List<DohServer>,
    custom: DohServer,
): List<DohServer> = (builtin + custom)
    .associateBy({ it.id }, { it })
    .values
    .toList()

fun isValidDohUrl(value: String): Boolean = value.trim().toHttpUrlOrNull()?.isHttps == true
