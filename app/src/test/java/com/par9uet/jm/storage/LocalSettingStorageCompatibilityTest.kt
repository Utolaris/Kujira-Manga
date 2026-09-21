package com.par9uet.jm.storage

import com.par9uet.jm.data.models.APP_LANGUAGE_SIMPLIFIED
import com.par9uet.jm.data.models.APP_LANGUAGE_TRADITIONAL
import com.par9uet.jm.data.models.AVAILABLE_APIS
import com.par9uet.jm.data.models.AVAILABLE_THEMES
import com.par9uet.jm.data.models.LocalSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Old installs persist extra fields (apiList/themeList, retired values). The unified DTO must
 * keep reading their JSON without breaking selected api/theme; candidate lists now come from
 * the static catalog instead of storage.
 */
class LocalSettingStorageCompatibilityTest {
    private val gson = com.google.gson.Gson()

    @Test
    fun `legacy json without appLanguage reads the constructor default`() {
        // Kotlin 的全默认参数会生成无参构造，所以**字段缺失时 Gson 拿到的是默认值**，
        // 而不是 null。这条守住"新字段加了以后存量 JSON 不会变成未知值"。
        val json = """{"theme":"dark","blockedTagList":[]}"""
        val decoded = gson.fromJson(json, LocalSetting::class.java)
        assertEquals(APP_LANGUAGE_SIMPLIFIED, decoded.appLanguage)
        assertEquals(APP_LANGUAGE_SIMPLIFIED, normalizePersisted(json, decoded).appLanguage)
    }

    @Test
    fun `explicit null appLanguage normalizes to simplified`() {
        // 真正的风险是 JSON 里显式写了 null（`{"appLanguage":null}`）—— 那时默认值不生效。
        val json = """{"appLanguage":null}"""
        val decoded = gson.fromJson(json, LocalSetting::class.java)
        assertNull(decoded.appLanguage)
        assertEquals(APP_LANGUAGE_SIMPLIFIED, normalizePersisted(json, decoded).appLanguage)
    }

    @Test
    fun `persisted traditional language survives normalization`() {
        val json = """{"appLanguage":"TW"}"""
        val decoded = gson.fromJson(json, LocalSetting::class.java)
        assertEquals(APP_LANGUAGE_TRADITIONAL, normalizePersisted(json, decoded).appLanguage)
    }

    @Test
    fun `unrecognized language value falls back to simplified`() {
        val json = """{"appLanguage":"zh-Hans"}"""
        val decoded = gson.fromJson(json, LocalSetting::class.java)
        assertEquals(APP_LANGUAGE_SIMPLIFIED, normalizePersisted(json, decoded).appLanguage)
    }

    @Test
    fun `null legacy tag lists normalize to empty lists`() {
        val json = """{"blockedTagList":null,"blockedTagTemplateList":null}"""
        val decoded = gson.fromJson(json, LocalSetting::class.java)
        val normalized = normalizePersisted(json, decoded)
        assertTrue(normalized.blockedTagList.isEmpty())
        assertTrue(normalized.blockedTagTemplateList.isEmpty())
    }

    @Test
    fun `legacy json with apiList and themeList keeps selections`() {
        val legacyJson = """
            {
              "api": "https://www.cdnmhwscc.vip",
              "apiList": ["https://old-a.example", "https://old-b.example"],
              "theme": "dark",
              "themeList": ["legacy-theme"]
            }
        """.trimIndent()

        val decoded = gson.fromJson(legacyJson, LocalSetting::class.java)

        assertEquals("https://www.cdnmhwscc.vip", decoded.api)
        assertEquals("dark", decoded.theme)
    }

    @Test
    fun `candidate catalogs come from code not from old json`() {
        assertEquals(listOf("auto", "light", "dark"), AVAILABLE_THEMES)
        assertFalse(AVAILABLE_THEMES.contains("legacy-theme"))
        // Five built-in proxy endpoints remain the static catalog.
        assertTrue(com.par9uet.jm.data.models.AVAILABLE_APIS.isNotEmpty())
    }

    @Test
    fun `fresh settings enable recommendation and automatic sign in`() {
        val decoded = gson.fromJson("{}", LocalSetting::class.java)
        assertEquals(LocalSetting().api, decoded.api)
        assertNull(decoded.customColorPrimary)
        assertTrue(decoded.preferenceRecommendEnabled)
        assertTrue(decoded.autoSignInEnabled)
    }

    @Test
    fun `existing explicit opt outs remain disabled`() {
        val decoded = gson.fromJson(
            """{"preferenceRecommendEnabled":false,"autoSignInEnabled":false}""",
            LocalSetting::class.java,
        )

        assertFalse(decoded.preferenceRecommendEnabled)
        assertFalse(decoded.autoSignInEnabled)
    }

    @Test
    fun `retired values are still ignored`() {
        val decoded = gson.fromJson(
            """{"comicApiSource":"builtin","shunt":"4","theme":"dark","showComicScrollReadTip":true,"showComicPageReadTip":true}""",
            LocalSetting::class.java,
        )
        assertEquals("dark", decoded.theme)
        val migratedJson = gson.toJson(decoded)
        assertFalse(migratedJson.contains("comicApiSource"))
        assertFalse(migratedJson.contains("shunt"))
        assertFalse(migratedJson.contains("apiList"))
        assertFalse(migratedJson.contains("themeList"))
        assertFalse(migratedJson.contains("showComicScrollReadTip"))
        assertFalse(migratedJson.contains("showComicPageReadTip"))
    }

    @Test
    fun `cache budget unlimited sentinel survives json and coerce`() {
        val decoded = gson.fromJson("""{"cacheBudgetMb":-1}""", LocalSetting::class.java)
        assertEquals(com.par9uet.jm.cache.CacheBudget.UNLIMITED_MB, decoded.cacheBudgetMb)
        val normalized = com.par9uet.jm.cache.CacheBudget.coerceTotalMb(decoded.cacheBudgetMb)
        assertEquals(com.par9uet.jm.cache.CacheBudget.UNLIMITED_MB, normalized)
        assertTrue(com.par9uet.jm.cache.CacheBudget.isUnlimited(normalized))
    }

    @Test
    fun `legacy cache budget snaps to discrete stop on coerce`() {
        val decoded = gson.fromJson("""{"cacheBudgetMb":256}""", LocalSetting::class.java)
        assertEquals(256, decoded.cacheBudgetMb)
        assertEquals(512, com.par9uet.jm.cache.CacheBudget.coerceTotalMb(decoded.cacheBudgetMb))
        val unlimitedRoundTrip = gson.fromJson(
            gson.toJson(LocalSetting(cacheBudgetMb = com.par9uet.jm.cache.CacheBudget.UNLIMITED_MB)),
            LocalSetting::class.java,
        )
        assertEquals(
            com.par9uet.jm.cache.CacheBudget.UNLIMITED_MB,
            com.par9uet.jm.cache.CacheBudget.coerceTotalMb(unlimitedRoundTrip.cacheBudgetMb),
        )
    }

    @Test
    fun `download exempt default remains true in json`() {
        val decoded = gson.fromJson("""{"cacheBudgetMb":-1}""", LocalSetting::class.java)
        assertTrue(decoded.downloadExemptFromCacheLimit)
    }
}
