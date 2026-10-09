package io.github.deedg.cineswipe

import android.content.Intent
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.deedg.cineswipe.testing.FakeTmdbServer
import io.github.deedg.cineswipe.testing.TestCineSwipeApp
import io.github.deedg.cineswipe.ui.lists.ListsTestTags
import io.github.deedg.cineswipe.widget.WidgetLinks
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Listes perso : créées depuis la fiche d'un film, retrouvées dans le profil, modifiées. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30], qualifiers = "w412dp-h915dp-xxhdpi", application = TestCineSwipeApp::class)
class ListsFlowTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val app: CineSwipeApp
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        runBlocking {
            app.container.settings.setTmdbApiKey(FakeTmdbServer.API_KEY)
            app.container.settings.setOnboardingDone(true)
        }
    }

    @After
    fun tearDown() {
        runBlocking { app.container.settings.setTmdbApiKey(null) }
    }

    private fun open(target: String): Intent = Intent(app, MainActivity::class.java).putExtra(WidgetLinks.EXTRA_OPEN, target)

    @Test
    fun aListIsCreatedFromAFilmAndEditedFromTheProfile() {
        val dune = FakeTmdbServer.DUNE
        ActivityScenario.launch<MainActivity>(open(WidgetLinks.movie(dune.id))).use {
            compose.waitForText("Marquer comme vu")
            // Aucune liste : le bouton propose tout de suite d'en créer une, avec ce film.
            compose.onNodeWithTag(ListsTestTags.ADD).performScrollTo().performClick()
            compose.waitForTag(ListsTestTags.NAME_FIELD)
            compose.onNodeWithTag(ListsTestTags.NAME_FIELD).performScrollTo().performTextInput("  Films   de Noël ")
            compose.onNodeWithTag(ListsTestTags.NAME_CONFIRM).performScrollTo().performClick()
            compose.waitForText("Dans 1 liste")
            val lists = runBlocking { app.container.database.listDao().lists() }
            assertEquals(listOf("Films de Noël"), lists.map { it.name })
            val id = lists.single().id

            // Le choix des listes : décocher retire le film.
            compose.onNodeWithTag(ListsTestTags.ADD).performScrollTo().performClick()
            compose.waitForTag(ListsTestTags.sheetRow(id))
            compose.onNodeWithTag(ListsTestTags.sheetRow(id)).performClick()
            compose.waitUntil(TIMEOUT_MS) { runBlocking { app.container.database.listDao().items() }.isEmpty() }
            compose.onNodeWithTag(ListsTestTags.sheetRow(id)).performClick()
            compose.waitUntil(TIMEOUT_MS) { runBlocking { app.container.database.listDao().items() }.size == 1 }
        }

        // Dans le profil : la liste et son film ; en mode modification, le film se retire.
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.onNodeWithText("Profil").performClick()
            val id = runBlocking { app.container.database.listDao().lists() }.single().id
            compose.waitForTag(ListsTestTags.card(id))
            compose.onNodeWithTag(ListsTestTags.card(id)).performScrollTo().performClick()
            compose.waitForTag(ListsTestTags.HEADER)
            compose.waitForTag(ListsTestTags.film(dune.id))
            compose.onNodeWithTag(ListsTestTags.EDIT).performClick()
            compose.waitForTag(ListsTestTags.remove(dune.id))
            compose.onNodeWithTag(ListsTestTags.remove(dune.id)).performClick()
            compose.waitUntil(TIMEOUT_MS) { compose.onAllNodesWithTag(ListsTestTags.film(dune.id)).fetchSemanticsNodes().isEmpty() }

            // Supprimer la liste ramène au profil.
            compose.onNodeWithTag(ListsTestTags.DELETE).performClick()
            compose.waitForTag(ListsTestTags.DELETE_CONFIRM)
            compose.onNodeWithTag(ListsTestTags.DELETE_CONFIRM).performClick()
            compose.waitForTag(ListsTestTags.NEW)
            assertTrue(runBlocking { app.container.database.listDao().lists() }.isEmpty())
        }
    }

    private fun ComposeTestRule.waitForText(text: String) {
        waitUntil(TIMEOUT_MS) { onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun ComposeTestRule.waitForTag(tag: String) {
        waitUntil(TIMEOUT_MS) { onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    }

    private companion object {
        const val TIMEOUT_MS = 20_000L
    }
}
