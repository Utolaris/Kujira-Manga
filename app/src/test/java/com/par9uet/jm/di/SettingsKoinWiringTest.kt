package com.par9uet.jm.di

import com.par9uet.jm.core.network.NetWorkResult
import com.par9uet.jm.network.DohManager
import com.par9uet.jm.repository.RemoteSettingRepository
import com.par9uet.jm.retrofit.interceptor.BaseUrlInterceptor
import com.par9uet.jm.storage.ApiEndpointPreference
import com.par9uet.jm.storage.AppExperiencePreferences
import com.par9uet.jm.storage.AppSecurityEditor
import com.par9uet.jm.storage.AppSecurityPreferences
import com.par9uet.jm.storage.AppearanceEditor
import com.par9uet.jm.storage.AppearancePreferences
import com.par9uet.jm.storage.CacheNotificationPreferences
import com.par9uet.jm.storage.BlockedTagTemplatePreferences
import com.par9uet.jm.storage.ContentLanguagePreferences
import com.par9uet.jm.storage.ContentPreferences
import com.par9uet.jm.storage.DohPreferences
import com.par9uet.jm.storage.DohPreferencesEditor
import com.par9uet.jm.storage.LocalSettingManager
import com.par9uet.jm.storage.LocalSettingSnapshotProvider
import com.par9uet.jm.storage.MiscSettingsPreferences
import com.par9uet.jm.storage.ReaderPreferences
import com.par9uet.jm.storage.RecommendationPreferences
import com.par9uet.jm.network.RemoteConfigManager
import com.par9uet.jm.storage.RemoteConfigPreferences
import com.par9uet.jm.launcher.LauncherIdentityApplier
import org.junit.Assert.assertSame
import org.junit.Test
import org.koin.dsl.module

/**
 * Resolves the real Koin graph for the settings boundary. Plain unit tests missed broken
 * interface wiring, so this test actually resolves every alias and asserts all of them point
 * at the same LocalSettingManager singleton.
 */
class SettingsKoinWiringTest {
    private fun koinWithSettingsGraph() = org.koin.dsl.koinApplication {
        modules(
            appModule,
            retrofitModule,
            module {
                // Replace only external IO; manager, aliases and consumers come from production.
                single {
                    com.par9uet.jm.storage.SecureStorage(
                        com.par9uet.jm.storage.InMemorySharedPreferences(),
                        com.par9uet.jm.storage.InMemorySharedPreferences(),
                        cryptoManager = com.par9uet.jm.storage.CryptoManager {
                            javax.crypto.spec.SecretKeySpec(ByteArray(32) { 3 }, "AES")
                        },
                    )
                }
                single<LauncherIdentityApplier> { LauncherDisguiseApplierFake() }
                single<com.par9uet.jm.network.RemoteConfigStore> { InMemoryRemoteConfigStore() }
                single<RemoteSettingRepository> { NoOpRemoteSettingRepository() }
            },
        )
    }

    @Test
    fun `all narrow settings interfaces resolve to the same LocalSettingManager`() {
        val application = koinWithSettingsGraph()
        val koin = application.koin
        try {
            val manager = koin.get<LocalSettingManager>()
            assertSame(manager, koin.get<ContentPreferences>())
            assertSame(manager, koin.get<BlockedTagTemplatePreferences>())
            assertSame(manager, koin.get<RecommendationPreferences>())
            assertSame(manager, koin.get<ReaderPreferences>())
            assertSame(manager, koin.get<CacheNotificationPreferences>())
            assertSame(manager, koin.get<AppSecurityPreferences>())
            assertSame(manager, koin.get<AppSecurityEditor>())
            assertSame(manager, koin.get<DohPreferences>())
            assertSame(manager, koin.get<DohPreferencesEditor>())
            assertSame(manager, koin.get<AppearancePreferences>())
            assertSame(manager, koin.get<AppearanceEditor>())
            assertSame(manager, koin.get<ApiEndpointPreference>())
            assertSame(manager, koin.get<ContentLanguagePreferences>())
            assertSame(manager, koin.get<MiscSettingsPreferences>())
            assertSame(manager, koin.get<AppExperiencePreferences>())
            assertSame(manager, koin.get<LocalSettingSnapshotProvider>())
            assertSame(manager, koin.get<com.par9uet.jm.storage.ConnectionModePreferences>())
            assertSame(manager, koin.get<com.par9uet.jm.storage.ConnectionModeEditor>())

            // Interface-consumers receive the same instances as concrete registrations.
            koin.get<LauncherIdentityApplier>()
            val remoteConfigPrefs = koin.get<RemoteConfigPreferences>()
            assertSame(koin.get<RemoteConfigManager>().remoteImageHost, remoteConfigPrefs.remoteImageHost)

            // Network-layer consumers resolve through the graph.
            koin.get<DohManager>()
            koin.get<BaseUrlInterceptor>()
        } finally {
            application.close()
        }
    }

    private class LauncherDisguiseApplierFake : LauncherIdentityApplier {
        override fun apply(disguise: com.par9uet.jm.data.models.LauncherDisguise) = true
    }

    private class InMemoryRemoteConfigStore : com.par9uet.jm.network.RemoteConfigStore {
        private val map = mutableMapOf<String, Any>()
        @Suppress("UNCHECKED_CAST") // Fake stores Any and returns it as T; Type token is unused.
        override fun <T> get(key: String, type: java.lang.reflect.Type): T? = map[key] as? T
        override fun <T> set(key: String, value: T) { map[key] = value as Any }
    }

    private class NoOpRemoteSettingRepository : RemoteSettingRepository {
        override suspend fun getRemoteSetting() =
            com.par9uet.jm.core.network.NetWorkResult.Error("unused in wiring test")
    }
}
