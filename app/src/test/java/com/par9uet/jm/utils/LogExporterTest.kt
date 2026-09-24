package com.par9uet.jm.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LogExporterTest {
    private val app = LogExporter.AppMeta(
        packageName = "com.par9uet.jm",
        versionName = "1.5.0",
        versionCode = 150L,
        debug = true,
    )

    private fun entry(seq: Int, level: String, tag: String, msg: String) = LogEntry(
        timestampMillis = 1_700_000_000_000L + seq,
        tag = tag,
        message = msg,
        level = level,
        seq = seq,
    )

    @Test
    fun `export is schema-first json with summary errors then full entries`() {
        val json = LogExporter.toJson(
            listOf(
                entry(0, "D", "Login", "login begin user=alice"),
                entry(1, "E", "LocalMode", "exit relogin failed: busy"),
                entry(2, "D", "Login", "login committed uid=7"),
            ),
            app,
            exportedAtMillis = 1_700_000_010_000L,
        )

        assertTrue(json.contains("\"schema\": \"kujira-log/1\""))
        assertTrue(json.contains("\"versionName\": \"1.5.0\""))
        assertTrue(json.contains("\"entryCount\": 3"))
        assertTrue(json.contains("\"errorCount\": 1"))
        assertTrue(json.contains("\"Login\": 2"))
        // errors 数组出现在 entries 之前，便于 AI 先读故障。
        val errorsAt = json.indexOf("\"errors\"")
        val entriesAt = json.indexOf("\"entries\"")
        assertTrue(errorsAt in 0 until entriesAt)
        assertTrue(json.contains("\"seq\": 1"))
        assertTrue(json.contains("exit relogin failed: busy"))
    }

    @Test
    fun `error block is a subset of entries with stable seq`() {
        val json = LogExporter.toJson(
            listOf(entry(4, "E", "Login", "boom \"quoted\"")),
            app,
        )
        assertTrue(json.contains("boom \\\"quoted\\\""))
        assertTrue(json.contains("\"seq\": 4"))
    }

    @Test
    fun `summary tags are sorted by frequency`() {
        val json = LogExporter.toJson(
            listOf(
                entry(0, "D", "A", "1"),
                entry(1, "D", "A", "2"),
                entry(2, "D", "B", "3"),
            ),
            app,
        )
        val aAt = json.indexOf("\"A\": 2")
        val bAt = json.indexOf("\"B\": 1")
        assertTrue(aAt in 0 until bAt)
    }

    @Test
    fun `log entry formatted keeps ui clock while timestamp is iso`() {
        val e = entry(0, "D", "T", "m")
        assertEquals(0, e.seq)
        assertTrue(e.timestamp.contains("T"))
        assertTrue(e.formatted.contains("[D][T] m"))
    }
}
