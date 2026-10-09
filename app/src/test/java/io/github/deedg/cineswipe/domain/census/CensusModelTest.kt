package io.github.deedg.cineswipe.domain.census

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class CensusModelTest {

    private val model = CensusModel()

    private val blockbuster = CensusFeatures(voteCount = 30_000, voteAverage = 7.8, year = 2010, genreIds = listOf(28, 878), language = "en")
    private val frenchHit = CensusFeatures(voteCount = 8_000, voteAverage = 7.5, year = 2011, genreIds = listOf(35), language = "fr")
    private val obscureDocumentary = CensusFeatures(voteCount = 60, voteAverage = 6.8, year = 1962, genreIds = listOf(99), language = "sv")

    @Test
    fun thePriorPutsWidelySeenFilmsFirst() {
        assertTrue(model.probability(blockbuster) > 0.6)
        assertTrue(model.probability(obscureDocumentary) < 0.05)
        assertTrue(model.probability(frenchHit) > model.probability(frenchHit.copy(language = "ko")))
        assertTrue(model.probability(frenchHit) > model.probability(frenchHit.copy(year = 1958)))
    }

    @Test
    fun aSagaAlreadyStartedAndLinksFromWatchedFilmsRaiseTheOdds() {
        val base = CensusFeatures(voteCount = 2_000, voteAverage = 6.9, year = 2005, genreIds = listOf(12), language = "en")
        assertTrue(model.probability(base.copy(sagaSeen = true)) > model.probability(base) + 0.2)
        assertTrue(model.probability(base.copy(graphLinks = 3)) > model.probability(base.copy(graphLinks = 1)))
        assertTrue(model.probability(base.copy(graphLinks = 1)) > model.probability(base))
    }

    @Test
    fun answersTeachTheModelWhatYouWatch() {
        // Aucun des films d'horreur montrés n'a été vu, tous les films d'animation l'ont été.
        val horror = (1..40).map { CensusFeatures(voteCount = 3_000 + it * 50, voteAverage = 6.5, year = 2012, genreIds = listOf(27), language = "en") }
        val animation = (1..40).map { CensusFeatures(voteCount = 3_000 + it * 50, voteAverage = 7.0, year = 2012, genreIds = listOf(16), language = "en") }
        val answers = horror.zip(animation).flatMap { (scary, cartoon) -> listOf(scary to false, cartoon to true) }

        val learned = model.learn(answers)

        assertEquals(80, learned.updates)
        val newHorror = CensusFeatures(voteCount = 4_000, voteAverage = 6.5, year = 2015, genreIds = listOf(27), language = "en")
        val newAnimation = newHorror.copy(genreIds = listOf(16), voteAverage = 7.0)
        assertTrue(learned.probability(newHorror) < model.probability(newHorror))
        assertTrue(learned.probability(newAnimation) > model.probability(newAnimation))
        assertTrue(learned.probability(newAnimation) > learned.probability(newHorror) + 0.2)
        // Le modèle appris se reconstruit à l'identique depuis ses poids (sauvegarde).
        val restored = CensusModel(learned.weights, learned.updates)
        assertEquals(learned.probability(newHorror), restored.probability(newHorror), 1e-12)
    }

    @Test
    fun manyAnswersConvergeToTheRealShareOfFilmsSeen() {
        // Pour ce genre de film, trois sur dix ont été vus (réponses réparties régulièrement) :
        // l'a priori, très optimiste, se corrige.
        val film = CensusFeatures(voteCount = 15_000, voteAverage = 7.2, year = 2014, genreIds = listOf(18), language = "en")
        assertTrue(model.probability(film) > 0.7)
        val answers = (0 until 600).map { film to ((it + 1) * 3 / 10 > it * 3 / 10) }
        assertEquals(180, answers.count { it.second })

        val learned = model.learn(answers)

        assertEquals(0.3, learned.probability(film), 0.1)
        assertTrue(learned.weights.values.all { abs(it) < 10.0 })
    }

    @Test
    fun encodingSharesGenresAndClampsDecades() {
        val x = CensusModel.encode(CensusFeatures(voteCount = 999, year = 1932, genreIds = listOf(18, 18, 35, 80, 53), language = "ja"))
        assertEquals(0.0, x.getValue(CensusModel.VOTES), 1e-9)
        assertEquals(1.0, x.getValue(CensusModel.decadeKey(1950)), 0.0)
        assertEquals(1.0, x.getValue(CensusModel.languageKey("other")), 0.0)
        // Quatre genres distincts : chacun compte pour 1/√4.
        assertEquals(0.5, x.getValue(CensusModel.genreKey(18)), 1e-9)
        assertFalse(CensusModel.RATING in x)
        assertFalse(CensusModel.SAGA in x)
        assertEquals(1.0, CensusModel.encode(CensusFeatures(year = 2031)).getValue(CensusModel.decadeKey(2020)), 0.0)
    }
}
