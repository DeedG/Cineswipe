package io.github.deedg.cineswipe.data.update

import io.github.deedg.cineswipe.core.MemoryPreferencesStore
import io.github.deedg.cineswipe.core.PlainSecretCipher
import io.github.deedg.cineswipe.data.repository.SettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdateRepositoryTest {
    private fun release(tag: String) =
        """{"tag_name":"$tag","html_url":"https://github.com/DeedG/Cineswipe/releases/tag/$tag","body":"Nouveautés",""" +
            """"assets":[{"name":"CineSwipe-${tag.removePrefix("v")}.aab","browser_download_url":"https://x/aab"},""" +
            """{"name":"CineSwipe-${tag.removePrefix("v")}.apk","browser_download_url":"https://x/apk"}]}"""

    @Test
    fun aNewerPublicVersionIsOfferedOnceADay() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse.Builder().body(release("v0.1.80")).build())
            var now = 1_800_000_000_000L
            val settings = SettingsRepository(MemoryPreferencesStore(), MemoryPreferencesStore(), PlainSecretCipher)
            val updates = UpdateRepository(settings, installed = 75, latestUrl = server.url("/latest").toString(), clock = { now })

            val found = updates.check()
            assertEquals(80, found?.versionCode)
            assertEquals("https://x/apk", found?.apkUrl)
            assertEquals("0.1.80", updates.toAnnounce.first()?.versionName)
            // Écartée du profil, elle reste dans les réglages.
            updates.dismiss(found!!)
            assertNull(updates.toAnnounce.first())
            assertEquals(80, updates.available.first()?.versionCode)
            // Moins d'un jour après : pas de nouvelle requête.
            now += 60_000
            updates.check()
            assertEquals(1, server.requestCount)

            // Version installée plus récente (version de développement) : rien à proposer.
            val dev = UpdateRepository(settings, installed = 90, latestUrl = server.url("/latest").toString(), clock = { now })
            assertNull(dev.available.first())
        } finally {
            server.close()
        }
    }
}
