package com.par9uet.jm.storage

import com.par9uet.jm.data.models.LauncherDisguise
import com.par9uet.jm.data.models.LocalSetting
import com.par9uet.jm.launcher.LauncherIdentityApplier
import org.junit.Assert.*
import org.junit.Test

class EnabledDefaultsTest {
    @Test fun `old disabled flags and restored backups use enabled defaults without losing other settings`() {
        var saved = LocalSetting(autoSignInEnabled = false, preferenceRecommendEnabled = false, homeGridColumns = 3)
        val manager = LocalSettingManager(object : LocalSettingPersistence {
            override fun load() = LocalSettingLoadResult.Success(saved)
            override fun persist(localSetting: LocalSetting): StorageWriteResult { saved = localSetting; return StorageWriteResult.Success }
        }, object : LauncherIdentityApplier { override fun apply(disguise: LauncherDisguise) = true })
        assertTrue(manager.currentAutoSignInEnabled())
        assertTrue(manager.preferenceRecommendEnabled.value)
        assertEquals(3, manager.misc.value.gridColumns.home)
        manager.applyLocalSetting(LocalSetting(autoSignInEnabled = false, preferenceRecommendEnabled = false, homeGridColumns = 5))
        assertTrue(saved.autoSignInEnabled)
        assertTrue(saved.preferenceRecommendEnabled)
        assertEquals(5, manager.misc.value.gridColumns.home)
        manager.reloadSettings()
        assertTrue(manager.currentAutoSignInEnabled())
        assertTrue(manager.preferenceRecommendEnabled.value)
    }
}
