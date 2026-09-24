package com.par9uet.jm.session

import com.par9uet.jm.storage.ConnectionModeEditor
import com.par9uet.jm.storage.ConnectionModePreferences
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NightLocalModePromptTest {
    @Test
    fun `prompt waits for the UI collector and a different account starts a new failure count`() = runTest {
        val mode = FakeMode()
        val prompt = NightLocalModePrompt(mode, mode, { at(22) }, ZoneId.of("Asia/Shanghai"))
        prompt.recordAuthFailure(7, at(22))
        prompt.recordAuthFailure(7, at(22) + 10_000)
        prompt.recordAuthFailure(8, at(22) + 20_000)
        assertEquals(0, mode.promptWrites)
        prompt.recordAuthFailure(8, at(22) + 30_000)
        prompt.recordAuthFailure(8, at(22) + 40_000)
        assertEquals(1, mode.promptWrites)
        assertEquals(8, prompt.prompt.first())
    }

    private class FakeMode : ConnectionModePreferences, ConnectionModeEditor {
        override val localModeAccountIds = MutableStateFlow<List<Int>>(emptyList())
        override val localModeEnteredAtByAccount = MutableStateFlow<Map<Int, Long>>(emptyMap())
        var promptDate = ""
        var promptWrites = 0
        override fun setLocalModeEnabled(accountId: Int, enabled: Boolean): Boolean {
            localModeAccountIds.value = if (enabled) listOf(accountId) else emptyList()
            return true
        }

        override fun nightLocalModePromptDate(): String = promptDate
        override fun setNightLocalModePromptDate(date: String): Boolean {
            promptDate = date
            promptWrites += 1
            return true
        }
    }

    private fun at(hour: Int, day: Int = 1): Long =
        LocalDateTime.of(2026, 3, day, hour, 0, 0)
            .atZone(ZoneId.of("Asia/Shanghai"))
            .toInstant()
            .toEpochMilli()

    @Test
    fun `night 401 over threshold prompts once per day`() {
        val mode = FakeMode()
        val prompt = NightLocalModePrompt(
            connectionMode = mode,
            connectionModeEditor = mode,
            nowMillis = { at(22) },
            zone = ZoneId.of("Asia/Shanghai"),
        )
        prompt.recordAuthFailure(7, at(22, 1))
        prompt.recordAuthFailure(7, at(22, 1) + 10_000)
        assertEquals(0, mode.promptWrites)
        prompt.recordAuthFailure(7, at(22, 1) + 20_000)
        assertEquals(1, mode.promptWrites)
        assertEquals("2026-03-01", mode.promptDate)
        prompt.recordAuthFailure(7, at(23, 1) + 30_000)
        assertEquals(1, mode.promptWrites)
        prompt.recordAuthFailure(7, at(22, 2))
        prompt.recordAuthFailure(7, at(22, 2) + 10_000)
        prompt.recordAuthFailure(7, at(22, 2) + 20_000)
        assertEquals(2, mode.promptWrites)
    }

    @Test
    fun `daytime 401 never counts toward night prompt`() {
        val mode = FakeMode()
        val prompt = NightLocalModePrompt(mode, mode, { at(10) }, ZoneId.of("Asia/Shanghai"))
        repeat(5) { prompt.recordAuthFailure(7, at(10) + it * 10_000L) }
        assertEquals("", mode.promptDate)
    }

    @Test
    fun `local mode ignores 401`() {
        val mode = FakeMode().apply { localModeAccountIds.value = listOf(7) }
        val prompt = NightLocalModePrompt(mode, mode, { at(22) }, ZoneId.of("Asia/Shanghai"))
        repeat(5) { prompt.recordAuthFailure(7, at(22) + it * 10_000L) }
        assertEquals("", mode.promptDate)
    }

    @Test
    fun `is night boundary`() {
        val mode = FakeMode()
        val prompt = NightLocalModePrompt(mode, mode, { at(21) }, ZoneId.of("Asia/Shanghai"))
        assertFalse(prompt.isNight(at(21)))
        assertTrue(prompt.isNight(at(22)))
        assertTrue(prompt.isNight(at(23)))
    }

    @Test
    fun `count resets each calendar day`() {
        val mode = FakeMode()
        val prompt = NightLocalModePrompt(mode, mode, { at(22) }, ZoneId.of("Asia/Shanghai"))
        // 第 3 次弹出后，次日仍需再来 3 次
        prompt.recordAuthFailure(7, at(22, 1))
        prompt.recordAuthFailure(7, at(22, 1) + 10_000)
        prompt.recordAuthFailure(7, at(22, 1) + 20_000)
        assertEquals(1, mode.promptWrites)
        prompt.recordAuthFailure(7, at(22, 2))
        assertEquals(1, mode.promptWrites)
        prompt.recordAuthFailure(7, at(22, 2) + 10_000)
        assertEquals(1, mode.promptWrites)
        prompt.recordAuthFailure(7, at(22, 2) + 20_000)
        assertEquals(2, mode.promptWrites)
    }
}
