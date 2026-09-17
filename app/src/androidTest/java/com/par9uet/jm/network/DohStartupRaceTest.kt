package com.par9uet.jm.network

import com.par9uet.jm.storage.DohPreferences
import com.par9uet.jm.storage.DohPreferencesEditor
import com.par9uet.jm.storage.DohSettingsState
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class DohStartupRaceTest {
    @Test
    fun concurrentFirstLookupsWaitForCompleteResolverConstruction() {
        val workers = Executors.newFixedThreadPool(8)
        try {
            repeat(20) {
                val prefs = object : DohPreferences {
                    override val doh = MutableStateFlow(DohSettingsState(enabled = true, autoStart = true))
                }
                val manager = DohManager(prefs, UnusedEditor)
                val start = CountDownLatch(1)
                val lookups = (1..8).map {
                    workers.submit<List<String>> {
                        check(start.await(5, TimeUnit.SECONDS))
                        manager.lookup("127.0.0.1").map { it.hostAddress!! }
                    }
                }
                start.countDown()
                lookups.forEach { assertEquals(listOf("127.0.0.1"), it.get(10, TimeUnit.SECONDS)) }
                assertTrue("Auto-start DoH must be active even before post-startup init", manager.status.value.active)
            }
        } finally {
            workers.shutdownNow()
        }
    }

    private object UnusedEditor : DohPreferencesEditor {
        override fun persistEnabled(enabled: Boolean): Boolean = error("unused")
        override fun persistAutoStart(enabled: Boolean): Boolean = error("unused")
        override fun persistAutoSelectFastest(enabled: Boolean): Boolean = error("unused")
        override fun persistServer(serverId: String): Boolean = error("unused")
        override fun persistCustomServer(name: String, url: String): Boolean = error("unused")
        override fun persistUseDeviceCertificates(enabled: Boolean): Boolean = error("unused")
        override fun persistPreferIpv6(enabled: Boolean): Boolean = error("unused")
    }
}
