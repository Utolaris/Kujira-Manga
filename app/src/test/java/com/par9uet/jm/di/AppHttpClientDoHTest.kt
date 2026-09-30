package com.par9uet.jm.di

import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.Path
import okhttp3.CookieJar
import okhttp3.Dns
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R07: every app-owned OkHttpClient that probes or fetches CDN/API hosts must share the
 * DoH resolver. System DNS is only allowed for the documented DoH bootstrap exception.
 */
class AppHttpClientDoHTest {
    private val recordingDns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            return listOf(InetAddress.getByName("127.0.0.1"))
        }
    }

    @Test
    fun `shared factory sets DoH dns and disables cookies`() {
        val client = com.par9uet.jm.network.createSharedCookielessDohClient(
            dns = recordingDns,
            connectionPool = okhttp3.ConnectionPool(),
        )

        assertSame(recordingDns, client.dns)
        assertSame(CookieJar.NO_COOKIES, client.cookieJar)
    }

    @Test
    fun `static app module declaration names the shared DoH client for image probes`() {
        val source = readMainSource("di/AppModule.kt")
        assertTrue(
            "AppModule must pass a DoH client into JmImageHostHealthManager",
            source.contains("createSharedCookielessDohClient"),
        )
        assertTrue(
            "AppModule must name the health-manager baseHttpClient argument explicitly",
            Regex(
                """JmImageHostHealthManager\([\s\S]*?baseHttpClient\s*=\s*(get\(\)|createSharedCookielessDohClient)"""
            ).containsMatchIn(source),
        )
    }

    @Test
    fun `static inventory lists every OkHttpClient construction site in the AppModule inventory`() {
        val inventory = readMainSource("di/AppModule.kt")
        val constructionSites = findOkHttpClientConstructionSites()
        val notDocumented = constructionSites.filterNot { site ->
            inventory.contains(site.relativePath)
        }
        assertTrue(
            "OkHttpClient construction sites missing from AppModule inventory: $notDocumented\n" +
                "Update the inventory table when adding a new client.",
            notDocumented.isEmpty(),
        )
        // The only allowed system-DNS client is the DoH bootstrap.
        assertTrue(
            "Inventory must mark the bootstrap client as the system-DNS exception",
            inventory.contains("intentional system-DNS exception"),
        )
    }

    private data class ConstructionSite(val relativePath: String)

    private fun findOkHttpClientConstructionSites(): List<ConstructionSite> {
        val root = sourceRoot()
        val sites = mutableListOf<ConstructionSite>()
        Files.walk(root).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }
                .forEach { path ->
                    val text = Files.readString(path)
                    val constructs = Regex("""OkHttpClient(\.Builder)?\s*\(\s*\)""")
                        .containsMatchIn(text)
                    if (!constructs) return@forEach
                    var relative = root.relativize(path).toString().replace('\\', '/')
                    // Inventory documents this factory under the network/doc path name too.
                    if (relative.endsWith("network/OkHttpShared.kt")) {
                        relative = "network/OkHttpShared.kt"
                    }
                    sites += ConstructionSite(relative)
                }
        }
        return sites
    }

    private fun readMainSource(relative: String): String =
        Files.readString(sourceRoot().resolve(relative))

    private fun sourceRoot(): Path = sequenceOf(
        Path.of("src/main/java/com/par9uet/jm"),
        Path.of("app/src/main/java/com/par9uet/jm"),
    ).first(Files::exists)
}
