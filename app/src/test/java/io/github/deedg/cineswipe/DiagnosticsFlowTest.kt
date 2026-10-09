package io.github.deedg.cineswipe

import android.content.ClipboardManager
import android.content.Intent
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.deedg.cineswipe.data.repository.DiagnosticsRepository
import io.github.deedg.cineswipe.testing.TestData
import io.github.deedg.cineswipe.ui.settings.DiagnosticsTestTags
import io.github.deedg.cineswipe.ui.settings.SettingsTestTags
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/**
 * Outils de diagnostic : sept touchers sur la version les débloquent, l'écran affiche l'état de
 * l'Arène, le résumé se copie et l'export JSON part dans le partage Android.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30], qualifiers = "w412dp-h915dp-xxhdpi")
class DiagnosticsFlowTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val app: CineSwipeApp
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun hiddenToolsShowTheStateAndExportTheData() {
        runBlocking {
            app.container.settings.setTmdbApiKey(null)
            TestData.seed(app.container.database)
            app.container.database.arenaDao().recordDuel(winnerId = 329865, loserId = 64690, draw = false, now = 10)
            app.container.database.arenaDao().recordDuel(winnerId = 157336, loserId = 64690, draw = true, now = 20)
        }

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.onNodeWithText("Profil").performClick()
            compose.waitForText("Hall of Fame")
            compose.onNodeWithContentDescription("Réglages").performClick()
            compose.waitForTag(SettingsTestTags.VERSION)
            assertTrue(compose.onAllNodesWithTag(SettingsTestTags.DEV_TOOLS).fetchSemanticsNodes().isEmpty())

            repeat(7) { compose.onNodeWithTag(SettingsTestTags.VERSION).performScrollTo().performClick() }
            compose.waitForTag(SettingsTestTags.DEV_TOOLS)
            compose.onNodeWithTag(SettingsTestTags.DEV_TOOLS).performScrollTo().performClick()

            // L'état de l'Arène : 3 films classés, 2 duels dont un nul.
            compose.waitForText("Fiabilité")
            compose.waitForText("Films classés")
            compose.waitForTag(DiagnosticsTestTags.film(64690))

            compose.onNodeWithTag(DiagnosticsTestTags.COPY).performClick()
            compose.waitUntil(TIMEOUT_MS) { scenario.clipboardText()?.contains("ARÈNE") == true }
            assertTrue(scenario.clipboardText()!!.contains("Films classés : 3 · duels : 2"))

            // L'export ouvre le partage Android avec le fichier JSON.
            compose.onNodeWithTag(DiagnosticsTestTags.EXPORT).performClick()
            var chooser: Intent? = null
            compose.waitUntil(TIMEOUT_MS) {
                scenario.onActivity { activity -> chooser = chooser ?: shadowOf(activity).nextStartedActivity }
                chooser != null
            }
            assertEquals(Intent.ACTION_CHOOSER, chooser!!.action)
            val send = chooser!!.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
            assertNotNull(send)
            assertEquals(Intent.ACTION_SEND, send!!.action)
            assertEquals("application/json", send.type)

            val export = File(app.cacheDir, DiagnosticsRepository.EXPORT_DIRECTORY).listFiles()!!.single()
            val json = Json.parseToJsonElement(export.readText()).jsonObject
            assertEquals("CineSwipe", json.getValue("app").jsonPrimitive.content)
            assertEquals(6, json.getValue("schema").jsonPrimitive.int)
            assertEquals(2, json.getValue("duels").jsonArray.size)
            val films = json.getValue("films").jsonArray.map { it.jsonObject }
            assertEquals(5, films.size)
            assertEquals(3, films.count { it.getValue("status").jsonPrimitive.content == "WATCHED" })
            assertTrue(json.getValue("catalog").jsonArray.isNotEmpty())

            // Masquer les outils : retour aux réglages, sans la ligne de diagnostic.
            compose.onNodeWithTag(DiagnosticsTestTags.HIDE).performScrollTo().performClick()
            compose.waitForTag(SettingsTestTags.VERSION)
            compose.waitUntil(TIMEOUT_MS) { compose.onAllNodesWithTag(SettingsTestTags.DEV_TOOLS).fetchSemanticsNodes().isEmpty() }
        }
    }

    private fun ActivityScenario<MainActivity>.clipboardText(): String? {
        var text: String? = null
        onActivity { activity ->
            val clip = activity.getSystemService(ClipboardManager::class.java).primaryClip
            text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
        }
        return text
    }

    private fun ComposeTestRule.waitForText(text: String) {
        waitUntil(TIMEOUT_MS) { onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun ComposeTestRule.waitForTag(tag: String) {
        waitUntil(TIMEOUT_MS) { onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    }

    private companion object {
        const val TIMEOUT_MS = 15_000L
    }
}
