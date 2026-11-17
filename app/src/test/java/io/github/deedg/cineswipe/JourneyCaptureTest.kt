package io.github.deedg.cineswipe

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.deedg.cineswipe.domain.journey.Journey
import io.github.deedg.cineswipe.testing.JourneyTestApp
import io.github.deedg.cineswipe.testing.TestData
import io.github.deedg.cineswipe.ui.arena.ArenaTestTags
import io.github.deedg.cineswipe.ui.census.CensusTestTags
import io.github.deedg.cineswipe.ui.journey.JourneyTestTags
import io.github.deedg.cineswipe.ui.navigation.BottomBarTestTags
import io.github.deedg.cineswipe.ui.navigation.Tab
import io.github.deedg.cineswipe.ui.profile.ProfileTestTags
import io.github.deedg.cineswipe.ui.welcome.WelcomeTestTags
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Outil « Captures » (lancé à la main, `-Pcaptures=true`) : le parcours d'un nouvel utilisateur
 * photographié étape par étape, de l'accueil aux premières recos. Ignoré sinon.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = JourneyTestApp::class)
class JourneyCaptureTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val app: CineSwipeApp
        get() = ApplicationProvider.getApplicationContext()

    private lateinit var folder: File
    private var index = 0
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before
    fun setUp() {
        assumeTrue(System.getProperty("cineswipe.captures") == "true")
    }

    /** Le parcours d'un nouvel utilisateur : accueil, recensement, parcours, Arène, premières recos. */
    @Test
    @Config(qualifiers = "fr-w412dp-h915dp-xxhdpi")
    fun journeyPixel() = journeyTour("7-parcours-pixel-412x915")

    @Test
    @Config(qualifiers = "fr-w360dp-h640dp-xhdpi")
    fun journeySmallPhone() = journeyTour("8-parcours-petit-telephone-360x640")

    private fun journeyTour(size: String) {
        folder = File(System.getProperty("cineswipe.capturesDir"), size).apply { mkdirs() }
        runBlocking {
            app.mainContainer.settings.setTmdbApiKey(null)
            app.mainContainer.settings.setOnboardingDone(false)
            app.mainContainer.settings.setJourneySkipped(false)
            app.mainContainer.settings.setRecosHintSeen(false)
            app.mainContainer.settings.setCensusCardsMode(false)
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            this.scenario = scenario
            waitForTag(WelcomeTestTags.START)
            settle()
            shot("accueil")
            compose.onNodeWithTag(WelcomeTestTags.START).performScrollTo().performClick()
            waitUntil { compose.onAllNodes(tagStartsWith("census-cell-")).fetchSemanticsNodes().isNotEmpty() }
            settle()
            shot("recensement-etape-1")
            compose.onNodeWithTag(CensusTestTags.MODE).performClick()
            settle()
            shot("recensement-cartes")
            compose.onNodeWithTag(CensusTestTags.MODE).performClick()
            settle()

            compose.onNodeWithTag(BottomBarTestTags.tab(Tab.RECOS)).performClick()
            waitForTag(JourneyTestTags.SCREEN)
            settle()
            shot("recos-verrouillees")

            runBlocking { TestData.seedWatched(app.container.database, Journey.FILMS_WATCHED, duels = 0) }
            waitUntil { compose.onAllNodesWithText("Continuer le recensement").fetchSemanticsNodes().isEmpty() }
            settle()
            shot("recos-verrouillees-etape-2")
            compose.onNodeWithTag(BottomBarTestTags.tab(Tab.CENSUS)).performClick()
            waitForTag(JourneyTestTags.BANNER_ACTION)
            settle()
            shot("recensement-etape-terminee")
            compose.onNodeWithTag(JourneyTestTags.BANNER_ACTION).performClick()
            waitForTag(ArenaTestTags.FIGHTER_A)
            settle()
            shot("arene-etape-2")

            runBlocking { TestData.seedWatched(app.container.database, Journey.FILMS_WATCHED, duels = 2) }
            waitForTag(JourneyTestTags.BANNER_ACTION)
            settle()
            shot("arene-recos-pretes")
            compose.onNodeWithTag(JourneyTestTags.BANNER_ACTION).performClick()
            waitUntil { compose.onAllNodes(tagStartsWith("feed-card-")).fetchSemanticsNodes().isNotEmpty() }
            settle()
            shot("recos-ouvertes")
            compose.onNodeWithTag(BottomBarTestTags.tab(Tab.PROFILE)).performClick()
            waitForTag(ProfileTestTags.RECO_QUALITY)
            settle()
            shot("profil-finesse-des-recos")
        }
    }

    private fun settle() {
        compose.mainClock.advanceTimeBy(1_500)
        compose.waitForIdle()
    }

    /** La fenêtre dessinée dans une image (rendu logiciel de Robolectric). */
    private fun shot(name: String) {
        compose.waitForIdle()
        var image: Bitmap? = null
        checkNotNull(scenario).onActivity { activity ->
            val view = activity.window.decorView
            image = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
        }
        index++
        File(folder, "%02d-%s.png".format(index, name)).outputStream().use { checkNotNull(image).compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun waitForTag(tag: String) = waitUntil { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }

    /** Un échec laisse quand même une image de l'écran, pour comprendre. */
    private fun waitUntil(condition: () -> Boolean) = try {
        compose.waitUntil(TIMEOUT_MS, condition)
    } catch (error: Throwable) {
        runCatching { shot("echec") }
        throw error
    }

    private fun tagStartsWith(prefix: String) = SemanticsMatcher("tag commençant par $prefix") { node ->
        node.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(prefix) == true
    }

    private companion object {
        const val TIMEOUT_MS = 30_000L
    }
}
