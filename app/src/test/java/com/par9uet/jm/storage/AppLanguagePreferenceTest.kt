package com.par9uet.jm.storage

import com.par9uet.jm.data.models.APP_LANGUAGE_SIMPLIFIED
import com.par9uet.jm.data.models.APP_LANGUAGE_TRADITIONAL
import com.par9uet.jm.data.models.LocalSetting
import com.par9uet.jm.launcher.LauncherIdentityApplier
import com.par9uet.jm.data.models.LauncherDisguise
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 服务端内容语言（`lang`）的设置路径。
 *
 * 默认必须是**简体**（与官方默认的 TW 相反），并且任何非法写入都要收口而不是写坏持久化。
 */
class AppLanguagePreferenceTest {

    private class RecordingPersistence(
        initial: LocalSetting? = null,
    ) : LocalSettingPersistence {
        var durable: LocalSetting? = initial
        override fun load(): LocalSettingLoadResult =
            durable?.let { LocalSettingLoadResult.Success(it) } ?: LocalSettingLoadResult.Missing

        override fun persist(localSetting: LocalSetting): StorageWriteResult {
            durable = localSetting
            return StorageWriteResult.Success
        }
    }

    private class NoopApplier : LauncherIdentityApplier {
        override fun apply(disguise: LauncherDisguise): Boolean = true
    }

    private fun manager(initial: LocalSetting? = null) =
        LocalSettingManager(RecordingPersistence(initial), NoopApplier())

    @Test
    fun `new install defaults to simplified`() {
        assertEquals(APP_LANGUAGE_SIMPLIFIED, manager().appLanguage.value)
    }

    @Test
    fun `switching persists and publishes traditional`() {
        val manager = manager()
        assertTrue(manager.updateAppLanguage(APP_LANGUAGE_TRADITIONAL))
        assertEquals(APP_LANGUAGE_TRADITIONAL, manager.appLanguage.value)
    }

    @Test
    fun `unrecognized value coerces to simplified instead of persisting garbage`() {
        val manager = manager()
        manager.updateAppLanguage("zh-Hans")
        assertEquals(APP_LANGUAGE_SIMPLIFIED, manager.appLanguage.value)
    }

    @Test
    fun `blank persisted value reads as simplified`() {
        // normalizePersisted 在加载时就会收口，管理器只读不改；这里覆盖脏数据兜一层。
        assertEquals(APP_LANGUAGE_SIMPLIFIED, manager(LocalSetting(appLanguage = "")).appLanguage.value)
    }
}
