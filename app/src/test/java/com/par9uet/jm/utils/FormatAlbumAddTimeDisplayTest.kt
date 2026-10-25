package com.par9uet.jm.utils

import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class FormatAlbumAddTimeDisplayTest(private val input: String, private val expected: String) {
    @Test fun formatsDate() {
        val previous = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))
        try {
            assertEquals(expected, formatAlbumAddTimeDisplay(input))
        } finally {
            TimeZone.setDefault(previous)
        }
    }

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{index}: [{0}] -> {1}")
        fun cases() = listOf(
            arrayOf("", ""),
            arrayOf("   ", ""),
            arrayOf("2024-03-15", "2024年3月15日更新"),
            arrayOf("2024-12-01 08:30:00", "2024年12月1日更新"),
            arrayOf("2023/07/09", "2023年7月9日更新"),
            arrayOf("1772762965", "2026年3月6日更新"),
            arrayOf("1772762965000", "2026年3月6日更新"),
            arrayOf("未知时间", "未知时间更新"),
        )
    }
}
