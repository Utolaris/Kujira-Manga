package com.par9uet.jm.session

import com.par9uet.jm.storage.ConnectionModeEditor
import com.par9uet.jm.storage.ConnectionModePreferences
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * 夜间风控 401 引导：本地时间 22:00 后认证 401 超过两次（第 3 次起）时发出一次弹窗事件。
 * 同一自然日最多弹一次；已在本地模式则不再统计。
 */
class NightLocalModePrompt(
    private val connectionMode: ConnectionModePreferences,
    private val connectionModeEditor: ConnectionModeEditor,
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    companion object {
        const val START_HOUR = 22
        const val THRESHOLD = 2
        private const val DEBOUNCE_MILLIS = 5_000L
    }

    private val promptEvents = Channel<Int>(Channel.BUFFERED)
    val prompt = promptEvents.receiveAsFlow()

    private var windowStartMillis = 0L
    private var failureCount = 0
    private var countDate: String = ""
    private var countAccountId = 0
    private var lastPromptDate: String = connectionModeEditor.nightLocalModePromptDate()

    /**
     * 记录一次认证 401 / 会话被踢。5 秒内连发计 1 次：并行接口的同一波踢登录不应算多次。
     * 「超过两次」按去重后的失败波次计。
     */
    @Synchronized
    fun recordAuthFailure(accountId: Int, now: Long = nowMillis()) {
        if (accountId <= 0 || accountId in connectionMode.localModeAccountIds.value) return
        if (!isNight(now)) return
        val today = LocalDate.ofInstant(Instant.ofEpochMilli(now), zone).toString()
        if (today != countDate || accountId != countAccountId) {
            // 每个自然日重新数：次日仍需「超过两次」才弹。
            countDate = today
            countAccountId = accountId
            windowStartMillis = 0L
            failureCount = 0
        }
        if (now - windowStartMillis >= DEBOUNCE_MILLIS) {
            windowStartMillis = now
            failureCount += 1
        }
        if (failureCount <= THRESHOLD) return
        if (today == lastPromptDate) return
        if (!connectionModeEditor.setNightLocalModePromptDate(today)) return
        lastPromptDate = today
        promptEvents.trySend(accountId)
    }

    internal fun isNight(now: Long = nowMillis()): Boolean {
        val local = LocalDateTime.ofInstant(Instant.ofEpochMilli(now), zone)
        return local.hour >= START_HOUR
    }
}
