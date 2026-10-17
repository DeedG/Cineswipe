package io.github.deedg.cineswipe

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.deedg.cineswipe.data.db.SeriesProgress
import io.github.deedg.cineswipe.data.demo.DemoSeeder
import io.github.deedg.cineswipe.data.model.MediaKind
import io.github.deedg.cineswipe.testing.FakeTmdbServer
import io.github.deedg.cineswipe.testing.TestCineSwipeApp
import io.github.deedg.cineswipe.ui.arena.ArenaTestTags
import io.github.deedg.cineswipe.ui.detail.SeriesTestTags
import io.github.deedg.cineswipe.ui.discover.DiscoverTestTags
import io.github.deedg.cineswipe.ui.feed.FeedTestTags
import io.github.deedg.cineswipe.ui.profile.ProfileTestTags
import io.github.deedg.cineswipe.ui.retro.RetroTestTags
import io.github.deedg.cineswipe.ui.tutorial.TutorialTestTags
import io.github.deedg.cineswipe.widget.WidgetLinks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Outil « Captures » (lancé à la main, `-Pcaptures=true`) : le tour de la démo photographié sur
 * plusieurs tailles d'écran, pour vérifier la mise en page sans les appareils. Ignoré sinon.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = TestCineSwipeApp::class)
class ScreenCaptureTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val app: CineSwipeApp
        get() = ApplicationProvider.getApplicationContext()

    private lateinit var folder: File
    private var index = 0

    @Before
    fun setUp() {
        assumeTrue(System.getProperty("cineswipe.captures") == "true")
        runBlocking { app.startDemo() }
    }

    @After
    fun tearDown() {
        if (System.getProperty("cineswipe.captures") != "true") return
        app.stopDemo()
        runBlocking {
            app.switchWorld(MediaKind.FILMS)
            app.mainContainer.settings.setTmdbApiKey(null)
        }
    }

    @Test
    @Config(qualifiers = "fr-w360dp-h640dp-xhdpi")
    fun smallPhone() = tour("1-petit-telephone-360x640")

    @Test
    @Config(qualifiers = "fr-w412dp-h915dp-xxhdpi")
    fun pixel() = tour("2-pixel-412x915")

    @Test
    @Config(qualifiers = "fr-w791dp-h820dp-xhdpi")
    fun foldOpen() = tour("3-pliant-ouvert-791x820")

    @Test
    @Config(qualifiers = "fr-w841dp-h701dp-land-xhdpi")
    fun foldOpenLandscape() = tour("4-pliant-ouvert-paysage-841x701")

    @Test
    @Config(qualifiers = "fr-w360dp-h380dp-xhdpi")
    fun flipCoverScreen() = tour("5-ecran-externe-clapet-360x380")

    /** Le monde des séries (pastille, violet, fiche en cours), sur un Pixel. */
    @Test
    @Config(qualifiers = "fr-w412dp-h915dp-xxhdpi")
    fun seriesPixel() = seriesTour("6-series-pixel-412x915")

    private fun seriesTour(size: String) {
        folder = File(System.getProperty("cineswipe.capturesDir"), size).apply { mkdirs() }
        // Hors démo, contre le faux TMDB des tests.
        app.stopDemo()
        runBlocking {
            app.mainContainer.settings.setTmdbApiKey(FakeTmdbServer.API_KEY)
            app.mainContainer.settings.setOnboardingDone(true)
        }
        // Profil des films d'abord, pour comparer.
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            this.scenario = scenario
            tab("Profil")
            waitForTag(ProfileTestTags.STATS)
            settle()
            shot("films-profil")
        }

        runBlocking(Dispatchers.IO) {
            app.switchWorld(MediaKind.SERIES)
            val library = app.container.library
            app.container.tmdb.ensureDetails(FakeTmdbServer.GUARDIANS.id)
            FakeTmdbServer.SHOWS.forEach { app.container.tmdb.ensureDetails(it.id) }
            library.setProgress(FakeTmdbServer.GUARDIANS.id, SeriesProgress.IN_PROGRESS, 2)
            // La première série des recos reste à découvrir (sinon plus aucune carte).
            library.setProgress(FakeTmdbServer.SHOWS[1].id, SeriesProgress.ABANDONED, 1)
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            this.scenario = scenario
            // Première arrivée dans le mode séries : son accueil (démo des séries ou commencer).
            waitUntil { compose.onAllNodesWithText("Commencer").fetchSemanticsNodes().isNotEmpty() }
            settle()
            shot("accueil")
            compose.onNodeWithText("Commencer").performScrollTo().performClick()
            waitUntil { compose.onAllNodes(tagStartsWith("feed-card-")).fetchSemanticsNodes().isNotEmpty() }
            settle()
            shot("recos")

            tab("Arène")
            waitForTag(ArenaTestTags.FIGHTER_A)
            settle()
            shot("arene")

            tab("Recherche")
            waitUntil { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
            shot("recherche")
            compose.onAllNodes(hasSetTextAction())[0].performTextInput("gardiens")
            compose.onNodeWithContentDescription("Lancer la recherche").performClick()
            waitForTag(FeedTestTags.card(FakeTmdbServer.GUARDIANS.id))
            settle()
            compose.onNodeWithTag(FeedTestTags.card(FakeTmdbServer.GUARDIANS.id)).performClick()
            waitForTag(SeriesTestTags.INFO)
            settle()
            shot("fiche")
            compose.onNodeWithTag(SeriesTestTags.season(2)).performScrollTo()
            settle()
            shot("fiche-avancement")
            compose.onNodeWithContentDescription("Retour").performClick()

            tab("Découverte")
            settle()
            shot("decouverte")

            // Recensement des séries : l'avancement d'un toucher (finie, en cours…).
            compose.onNodeWithTag(DiscoverTestTags.CENSUS).performScrollTo().performClick()
            waitUntil { compose.onAllNodes(tagStartsWith("census-cell-")).fetchSemanticsNodes().isNotEmpty() }
            settle()
            compose.onAllNodes(tagStartsWith("census-cell-")).fetchSemanticsNodes().forEachIndexed { index, node ->
                val tag = node.config[SemanticsProperties.TestTag]
                repeat(index % 4 + 1) { compose.onNodeWithTag(tag).performClick() }
            }
            settle()
            shot("recensement")
            compose.onAllNodesWithContentDescription("Retour")[0].performClick()

            tab("Profil")
            waitForTag(ProfileTestTags.STATS)
            settle()
            shot("profil")
        }

        // La démo des séries : 100 séries, un profil fictif, le tuto en violet.
        runBlocking { app.startDemo(MediaKind.SERIES) }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            this.scenario = scenario
            waitForTag(TutorialTestTags.step(0))
            settle()
            shot("demo-tuto")
            app.container.tutorial.stop()
            waitUntil { compose.onAllNodes(tagStartsWith("feed-card-")).fetchSemanticsNodes().isNotEmpty() }
            settle()
            shot("demo-recos")
            tab("Profil")
            waitForTag(ProfileTestTags.STATS)
            settle()
            shot("demo-profil")
        }
    }

    private fun tour(size: String) {
        folder = File(System.getProperty("cineswipe.capturesDir"), size).apply { mkdirs() }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            this.scenario = scenario
            waitForTag(TutorialTestTags.step(0))
            shot("tuto")
            app.container.tutorial.stop()
            waitUntil { compose.onAllNodes(tagStartsWith("feed-card-")).fetchSemanticsNodes().isNotEmpty() }
            settle()
            shot("recos")

            tab("Arène")
            waitForTag(ArenaTestTags.FIGHTER_A)
            settle()
            shot("arene")

            tab("Découverte")
            waitForTag(DiscoverTestTags.CENSUS)
            shot("decouverte")
            compose.onNodeWithTag(DiscoverTestTags.CENSUS).performScrollTo().performClick()
            waitUntil { compose.onAllNodes(tagStartsWith("census-cell-")).fetchSemanticsNodes().isNotEmpty() }
            settle()
            shot("recensement")
            compose.onAllNodesWithContentDescription("Retour")[0].performClick()

            tab("Recherche")
            waitUntil { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
            compose.onAllNodes(hasSetTextAction())[0].performTextInput("nolan")
            waitUntil { compose.onAllNodes(tagStartsWith("search_person_")).fetchSemanticsNodes().isNotEmpty() }
            shot("recherche-personnes")

            tab("Profil")
            waitForTag(ProfileTestTags.STATS)
            settle()
            shot("profil")
            compose.onNodeWithTag(ProfileTestTags.RETRO).performScrollTo().performClick()
            waitUntil {
                compose.onAllNodesWithTag(RetroTestTags.CARD).fetchSemanticsNodes().isNotEmpty() ||
                    compose.onAllNodesWithTag(RetroTestTags.EMPTY).fetchSemanticsNodes().isNotEmpty()
            }
            settle()
            shot("retro")
        }

        // Fiche d'un film (ouverte comme depuis un widget).
        val film = DemoSeeder.WATCHED.first()
        val intent = Intent(app, MainActivity::class.java).putExtra(WidgetLinks.EXTRA_OPEN, WidgetLinks.movie(film))
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            this.scenario = scenario
            app.container.tutorial.stop()
            waitUntil { compose.onAllNodes(hasText("Retirer des vus", substring = true)).fetchSemanticsNodes().isNotEmpty() }
            settle()
            shot("fiche")
        }
    }

    private fun tab(label: String) {
        compose.onNode(hasText(label) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)).performClick()
        settle()
    }

    /** Laisse finir les animations d'entrée. */
    private fun settle() {
        compose.mainClock.advanceTimeBy(1_500)
        compose.waitForIdle()
    }

    private var scenario: ActivityScenario<MainActivity>? = null

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

    private fun waitUntil(condition: () -> Boolean) = compose.waitUntil(TIMEOUT_MS, condition)

    private fun tagStartsWith(prefix: String) = SemanticsMatcher("tag commençant par $prefix") { node ->
        node.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(prefix) == true
    }

    private companion object {
        const val TIMEOUT_MS = 30_000L
    }
}
