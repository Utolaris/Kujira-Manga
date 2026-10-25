package com.par9uet.jm.storage

import com.google.gson.Gson
import com.par9uet.jm.data.models.DEFAULT_COMMENT_FILTER_KEYWORDS
import com.par9uet.jm.data.models.LauncherDisguise
import com.par9uet.jm.data.models.LocalSetting
import com.par9uet.jm.launcher.LauncherIdentityApplier
import org.junit.Assert.*
import org.junit.Test

class CommentFilterPreferenceTest {
    private class Persistence : LocalSettingPersistence {
        var value: LocalSetting? = null
        var fail = false
        override fun load() = value?.let { LocalSettingLoadResult.Success(it) } ?: LocalSettingLoadResult.Missing
        override fun persist(localSetting: LocalSetting): StorageWriteResult {
            if (fail) return StorageWriteResult.TemporaryUnavailable
            value = localSetting
            return StorageWriteResult.Success
        }
    }
    private fun manager(persistence: Persistence) = LocalSettingManager(persistence, object : LauncherIdentityApplier {
        override fun apply(disguise: LauncherDisguise) = true
    })

    @Test fun `new installs legacy settings and explicit null use the defaults`() {
        assertEquals(DEFAULT_COMMENT_FILTER_KEYWORDS, manager(Persistence()).blockedCommentKeywords.value)
        listOf("{}", """{"blockedCommentKeywordList":null}""").forEach { json ->
            val decoded = Gson().fromJson(json, LocalSetting::class.java)
            assertEquals(DEFAULT_COMMENT_FILTER_KEYWORDS, normalizePersisted(json, decoded).blockedCommentKeywordList)
        }
    }

    @Test fun `custom keywords and deleting all defaults survive JSON round trip and reload`() {
        val persistence = Persistence()
        val manager = manager(persistence)
        DEFAULT_COMMENT_FILTER_KEYWORDS.forEach { assertTrue(manager.removeCommentFilterKeyword(it)) }
        assertTrue(manager.blockedCommentKeywords.value.isEmpty())
        val json = Gson().toJson(persistence.value)
        persistence.value = normalizePersisted(json, Gson().fromJson(json, LocalSetting::class.java))
        val restored = manager(persistence)
        assertTrue(restored.blockedCommentKeywords.value.isEmpty())
        assertTrue(restored.addCommentFilterKeyword(" 测试词 "))
        assertTrue(restored.addCommentFilterKeyword("VIP"))
        assertTrue(restored.addCommentFilterKeyword("vip"))
        assertFalse(restored.addCommentFilterKeyword("  "))
        assertEquals(listOf("测试词", "VIP"), manager(persistence).blockedCommentKeywords.value)
    }

    @Test fun `failed writes do not change active filtering or saved keywords`() {
        val persistence = Persistence()
        val manager = manager(persistence)
        persistence.fail = true
        assertFalse(manager.addCommentFilterKeyword("测试词"))
        assertFalse(manager.removeCommentFilterKeyword("VIP"))
        assertEquals(DEFAULT_COMMENT_FILTER_KEYWORDS, manager.blockedCommentKeywords.value)
        assertNull(persistence.value)
    }
}
