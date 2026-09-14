package com.par9uet.jm.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class FormatAlbumAddTimeDisplayTest {

    @Test
    fun blankIsEmpty() {
        assertEquals("", formatAlbumAddTimeDisplay(""))
        assertEquals("", formatAlbumAddTimeDisplay("   "))
    }

    @Test
    fun isoDate() {
        assertEquals("2024年3月15日上架", formatAlbumAddTimeDisplay("2024-03-15"))
    }

    @Test
    fun isoDateTime() {
        assertEquals("2024年12月1日上架", formatAlbumAddTimeDisplay("2024-12-01 08:30:00"))
    }

    @Test
    fun slashDate() {
        assertEquals("2023年7月9日上架", formatAlbumAddTimeDisplay("2023/07/09"))
    }

    @Test
    fun unixSecondsTimestamp() {
        // 1772762965 = 2026-03-06 10:09:25 Asia/Shanghai
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Asia/Shanghai"))
        try {
            assertEquals("2026年3月6日上架", formatAlbumAddTimeDisplay("1772762965"))
        } finally {
            java.util.TimeZone.setDefault(null)
        }
    }

    @Test
    fun unixMillisTimestamp() {
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Asia/Shanghai"))
        try {
            assertEquals("2026年3月6日上架", formatAlbumAddTimeDisplay("1772762965000"))
        } finally {
            java.util.TimeZone.setDefault(null)
        }
    }

    @Test
    fun unknownShapeKeepsRawPlusSuffix() {
        assertEquals("未知时间上架", formatAlbumAddTimeDisplay("未知时间"))
    }
}
