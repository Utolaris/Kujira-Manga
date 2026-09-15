package com.par9uet.jm.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DoH 列表的 id 收口规则。id 是 `LazyColumn` 的 key，重复会直接抛
 * `IllegalArgumentException: Key "..." was already used`。
 */
class DohServerMergeTest {

    private fun server(id: String, name: String = id) = DohServer(
        id = id,
        name = name,
        displayUrl = "https://$id.example/dns-query",
    )

    @Test
    fun `内置项 id 互不重复`() {
        val ids = builtinDohServers.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `自定义项追加在内置项之后且不改变原有顺序`() {
        val merged = mergeDohServers(builtinDohServers, server(DOH_SERVER_CUSTOM, "自定义"))

        assertEquals(builtinDohServers.size + 1, merged.size)
        assertEquals(builtinDohServers.map { it.id }, merged.dropLast(1).map { it.id })
        assertEquals(DOH_SERVER_CUSTOM, merged.last().id)
    }

    @Test
    fun `与内置项同名时自定义项覆盖内容但保留原位置`() {
        val merged = mergeDohServers(
            builtin = listOf(server("a"), server("custom", "旧的自定义")),
            custom = server("custom", "新的自定义"),
        )

        assertEquals(listOf("a", "custom"), merged.map { it.id })
        assertEquals("新的自定义", merged.last().name)
    }

    @Test
    fun `传入重复 id 的内置列表也会被收口`() {
        val merged = mergeDohServers(
            builtin = listOf(server("a"), server("a"), server("b")),
            custom = server(DOH_SERVER_CUSTOM),
        )

        val ids = merged.map { it.id }
        assertEquals(listOf("a", "b", DOH_SERVER_CUSTOM), ids)
        assertTrue(ids.size == ids.toSet().size)
    }
}
