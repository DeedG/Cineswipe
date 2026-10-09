package io.github.deedg.cineswipe.domain.reco

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Profil de goût, score collaboratif et composition des lots de recos. */
class RecoEngineTest {

    private val villeneuve = TmdbTag(137427, "Denis Villeneuve")
    private val bay = TmdbTag(865, "Michael Bay")
    private val timeTravel = TmdbTag(4379, "voyage dans le temps")
    private val gosling = TmdbTag(30614, "Ryan Gosling")

    private fun film(id: Long, weight: Double, features: FilmFeatures) = TasteFilm(id, "Film $id", weight, features)

    private val profile = TasteProfile(
        listOf(
            film(1, 1.6, FilmFeatures(genreIds = listOf(878), directors = listOf(villeneuve), cast = listOf(gosling), keywords = listOf(timeTravel), year = 2017, language = "en")),
            film(2, 1.4, FilmFeatures(genreIds = listOf(878, 18), directors = listOf(villeneuve), keywords = listOf(timeTravel), year = 2016, language = "en")),
            film(3, 1.1, FilmFeatures(genreIds = listOf(18), cast = listOf(gosling), year = 2011, language = "en")),
            film(4, -0.6, FilmFeatures(genreIds = listOf(28), directors = listOf(bay), year = 2007, language = "en")),
            film(5, -1.0, FilmFeatures(genreIds = listOf(28), directors = listOf(bay), year = 2014, language = "en")),
        ),
    )

    @Test
    fun watchedWeightsFollowTheRanking() {
        val weights = TasteProfile.watchedWeights(mapOf(1L to 1800.0, 2L to 1500.0, 3L to 1200.0))
        assertEquals(TasteProfile.NEUTRAL_WATCHED, weights.getValue(2), 1e-9)
        assertTrue(weights.getValue(1) > 1.0)
        assertTrue(weights.getValue(3) < 0.0)
        // Un classement encore plat ne dit rien : tous neutres.
        val flat = TasteProfile.watchedWeights(mapOf(1L to 1500.0, 2L to 1500.0))
        assertTrue(flat.values.all { it == TasteProfile.NEUTRAL_WATCHED })
        assertTrue(TasteProfile.watchedWeights(emptyMap()).isEmpty())
    }

    @Test
    fun likedDirectorsAndDislikedOnesShapeTheProfile() {
        assertTrue(profile.affinity(TasteFamily.DIRECTOR, villeneuve.id.toString())!!.value > 0.8)
        assertTrue(profile.affinity(TasteFamily.DIRECTOR, bay.id.toString())!!.value < 0.0)
        assertTrue(profile.affinity(TasteFamily.GENRE, "28")!!.value < 0.0)
        assertEquals(listOf(villeneuve.name), profile.top(TasteFamily.DIRECTOR, 5, minFilms = 2).map { it.name })

        val byVilleneuve = profile.score(FilmFeatures(genreIds = listOf(878), directors = listOf(villeneuve), year = 2021, language = "en"))
        val byBay = profile.score(FilmFeatures(genreIds = listOf(28), directors = listOf(bay), year = 2021, language = "en"))
        val unknown = profile.score(FilmFeatures(genreIds = listOf(35), year = 1960, language = "it"))
        assertTrue(byVilleneuve.value > unknown.value)
        assertTrue(unknown.value > byBay.value)
        assertEquals(villeneuve.name, byVilleneuve.highlight?.name)
        assertEquals(TasteFamily.DIRECTOR, byVilleneuve.highlight?.family)
        assertNull(byBay.highlight)
    }

    @Test
    fun anEmptyProfileIsNeutral() {
        assertEquals(0.0, TasteProfile(emptyList()).score(FilmFeatures(genreIds = listOf(18))).value, 0.0)
    }

    @Test
    fun collaborativeScoreAddsUpLikedSeedsAndSubtractsDislikedOnes() {
        val weights = mapOf(1L to 1.5, 2L to 1.0, 4L to -1.0)
        val scores = RecoRanker.collaborative(
            listOf(
                SeedLink(seedId = 1, movieId = 100, rank = 0),
                SeedLink(seedId = 2, movieId = 100, rank = 3),
                SeedLink(seedId = 1, movieId = 101, rank = 10),
                SeedLink(seedId = 4, movieId = 101, rank = 0),
                SeedLink(seedId = 2, movieId = 102, rank = 0, similar = true),
                SeedLink(seedId = 99, movieId = 103, rank = 0),
            ),
            weights,
        )
        val first = scores.getValue(100)
        assertEquals(1.5 + 1.0 * RecoRanker.rankWeight(3), first.value, 1e-9)
        assertEquals(1L, first.bestSeed)
        assertEquals(2, first.seeds)
        assertTrue(scores.getValue(101).value < 0.0)
        // « Similaire » compte moitié moins que « recommandé ».
        assertEquals(0.5, scores.getValue(102).value, 1e-9)
        // Graine inconnue du profil : ignorée.
        assertNull(scores[103])
    }

    private fun candidate(
        id: Long,
        source: RecoSource,
        reason: RecoReason = RecoReason(defaultKind(source), source),
        features: FilmFeatures = FilmFeatures(genreIds = listOf(18)),
        votes: Int = 5_000,
        average: Double = 7.0,
    ) = RecoCandidate(id, source, reason, features, average, votes)

    private fun defaultKind(source: RecoSource) = when (source) {
        RecoSource.CLASSICS -> ReasonKind.CLASSIC
        RecoSource.GEMS -> ReasonKind.GEM
        RecoSource.TRENDING -> ReasonKind.TRENDING
        else -> ReasonKind.CLOSE_TO
    }

    private val ranker = RecoRanker(
        profile,
        weights = mapOf(1L to 1.6, 2L to 1.4, 3L to 1.1, 6L to 0.5),
        titles = mapOf(1L to "Blade Runner 2049", 2L to "Premier Contact", 3L to "Drive", 6L to "Un film moyen"),
        random = Random(1),
    )

    @Test
    fun reasonsSayWhyAFilmIsProposed() {
        val liked = CollaborativeScore(value = 1.6, bestSeed = 1, bestContribution = 1.6, seeds = 1)
        val forYou = ranker.score(candidate(100, RecoSource.FOR_YOU), liked)
        assertEquals(RecoReason(ReasonKind.LIKED, RecoSource.FOR_YOU, "Blade Runner 2049"), forYou.reason)

        val neutral = CollaborativeScore(value = 0.5, bestSeed = 6, bestContribution = 0.5, seeds = 1)
        assertEquals(ReasonKind.CLOSE_TO, ranker.score(candidate(101, RecoSource.FOR_YOU), neutral).reason.kind)

        // Un classique sans lien fort reste un classique…
        assertEquals(ReasonKind.CLASSIC, ranker.score(candidate(102, RecoSource.CLASSICS), null).reason.kind)
        // … mais une tendance du réalisateur préféré le dit.
        val trending = ranker.score(candidate(103, RecoSource.TRENDING, features = FilmFeatures(directors = listOf(villeneuve))), null)
        assertEquals(RecoReason(ReasonKind.DIRECTOR, RecoSource.TRENDING, villeneuve.name), trending.reason)
    }

    @Test
    fun scoresRewardTasteLinksAndQuality() {
        val linked = ranker.score(candidate(100, RecoSource.FOR_YOU), CollaborativeScore(2.0, 1, 1.6, 2))
        val unlinked = ranker.score(candidate(101, RecoSource.FOR_YOU), null)
        assertTrue(linked.score > unlinked.score)
        assertTrue(RecoRanker.quality(8.4, 20_000) > RecoRanker.quality(8.4, 30))
        assertEquals(0.0, RecoRanker.quality(null, null), 0.0)
        assertTrue(RecoRanker.quality(4.0, 10_000) < 0.0)
    }

    @Test
    fun aBatchMixesSourcesWithoutDuplicatesOrADirectorTwice() {
        val stats = RecoSource.entries.associateWith { SourceStats() }
        val scored = buildList {
            (1L..12L).forEach { add(ranker.score(candidate(it, RecoSource.FOR_YOU), CollaborativeScore(3.0 - it * 0.1, 1, 1.0, 1))) }
            (1L..12L).forEach { add(ranker.score(candidate(it, RecoSource.CLASSICS), null)) }
            (20L..25L).forEach { add(ranker.score(candidate(it, RecoSource.GEMS), null)) }
        }
        val batch = ranker.compose(scored, stats, count = 10, coldStart = false)
        assertEquals(10, batch.size)
        assertEquals(10, batch.map { it.candidate.tmdbId }.toSet().size)
        // Une source ne donne pas plus de 60 % du lot quand les autres ont des candidats.
        assertTrue(batch.groupingBy { it.candidate.source }.eachCount().values.all { it <= 6 })

        // Deux films du même réalisateur (absent du profil) ne se suivent pas si un autre peut s'intercaler.
        val sameDirector = FilmFeatures(directors = listOf(TmdbTag(999, "Réalisateur inconnu du profil")))
        val diverse = listOf(
            ranker.score(candidate(30, RecoSource.FOR_YOU, features = sameDirector), CollaborativeScore(2.0, 1, 1.0, 1)),
            ranker.score(candidate(31, RecoSource.FOR_YOU, features = sameDirector), CollaborativeScore(1.95, 1, 1.0, 1)),
            ranker.score(candidate(32, RecoSource.FOR_YOU, features = FilmFeatures()), CollaborativeScore(1.9, 1, 1.0, 1)),
        )
        val ordered = ranker.compose(diverse, stats, count = 3, coldStart = false).map { it.candidate.tmdbId }
        assertEquals(listOf(30L, 32L, 31L), ordered)
    }

    @Test
    fun sourcesThatPleaseAreDrawnMoreOften() {
        val stats = mapOf(
            RecoSource.GEMS to SourceStats(shown = 60.0, liked = 30.0, disliked = 2.0),
            RecoSource.TRENDING to SourceStats(shown = 60.0, liked = 1.0, disliked = 20.0),
        )
        val picks = (1..400).map { ranker.pickSources(listOf(RecoSource.GEMS, RecoSource.TRENDING), stats, 1, coldStart = false).single() }
        assertTrue(picks.count { it == RecoSource.GEMS } > 360)
        // Au démarrage (peu de films vus), les grands classiques passent d'abord.
        val cold = (1..400).map { ranker.pickSources(listOf(RecoSource.CLASSICS, RecoSource.TRENDING), emptyMap(), 1, coldStart = true).single() }
        assertTrue(cold.count { it == RecoSource.CLASSICS } > 260)
    }
}
