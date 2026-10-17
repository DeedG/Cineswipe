package io.github.deedg.cineswipe.data.repository

import io.github.deedg.cineswipe.data.db.Availability
import io.github.deedg.cineswipe.data.db.CineSwipeDatabase
import io.github.deedg.cineswipe.data.db.MovieEntity
import io.github.deedg.cineswipe.data.db.RecoLinkEntity
import io.github.deedg.cineswipe.data.db.RecoLinkKind
import io.github.deedg.cineswipe.data.db.RecoSeedEntity
import io.github.deedg.cineswipe.data.db.TasteRow
import io.github.deedg.cineswipe.data.db.WatchStatus
import io.github.deedg.cineswipe.data.tmdb.TmdbMapper
import io.github.deedg.cineswipe.data.tmdb.TmdbMovieSummaryDto
import io.github.deedg.cineswipe.data.tmdb.TmdbPageDto
import io.github.deedg.cineswipe.domain.reco.Affinity
import io.github.deedg.cineswipe.domain.reco.FilmFeatures
import io.github.deedg.cineswipe.domain.reco.ReasonKind
import io.github.deedg.cineswipe.domain.reco.RecoCandidate
import io.github.deedg.cineswipe.domain.reco.RecoRanker
import io.github.deedg.cineswipe.domain.reco.RecoReason
import io.github.deedg.cineswipe.domain.reco.RecoSource
import io.github.deedg.cineswipe.domain.reco.ScoredCandidate
import io.github.deedg.cineswipe.domain.reco.SeedLink
import io.github.deedg.cineswipe.domain.reco.SourceStats
import io.github.deedg.cineswipe.domain.reco.TasteFamily
import io.github.deedg.cineswipe.domain.reco.TasteFilm
import io.github.deedg.cineswipe.domain.reco.TasteProfile
import io.github.deedg.cineswipe.domain.reco.TmdbTag
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlin.random.Random

/** Carte des recos : la fiche du film et pourquoi il est proposé. */
/** Film proposé par « Ce soir ». [reason] : pourquoi il devrait plaire, s'il y a une raison précise. */
data class TonightPick(
    val movie: MovieEntity,
    val inWatchlist: Boolean,
    val available: Boolean,
    /** Demandé au homelab, pas encore disponible. */
    val requested: Boolean = false,
    val score: Double,
    val reason: RecoReason?,
    /** Série en cours à reprendre (Ce soir, monde des séries), et sa saison si elle est connue. */
    val resume: Boolean = false,
    val season: Int? = null,
)

data class RecoCard(
    val movie: MovieEntity,
    val reason: RecoReason,
)

/** Films repérés dans les listes TMDB mais pas encore montrés, gardés pour le lot suivant. */
class RecoCarry internal constructor(internal val items: List<CarriedCandidate>) {
    companion object {
        val EMPTY = RecoCarry(emptyList())
    }
}

internal data class CarriedCandidate(
    val summary: TmdbMovieSummaryDto,
    val source: RecoSource,
    val reason: RecoReason,
)

data class RecoBatch(
    val cards: List<RecoCard>,
    val carry: RecoCarry,
)

/**
 * Moteur des recos personnalisées.
 *
 * Chaque lot :
 * 1. explore quelques nouvelles « graines » (films vus ou à voir) : leurs recommandations TMDB
 *    sont gardées en base, ce qui construit au fil du temps un graphe des films proches de tes goûts ;
 * 2. interroge deux ou trois listes TMDB (classiques, pépites, tendances, réalisateurs et acteurs
 *    préférés, thèmes préférés), choisies selon ce qui t'a plu jusqu'ici ;
 * 3. note tous les candidats (graphe agrégé sur tous tes films notés + profil de goût + qualité)
 *    et compose le lot avec [RecoRanker].
 *
 * Les fiches des films notés sont complétées petit à petit (mots-clés, réalisateurs, acteurs).
 */
class RecoRepository(
    private val database: CineSwipeDatabase,
    private val tmdb: TmdbRepository,
    private val settings: SettingsRepository,
    private val random: Random = Random.Default,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val dao get() = database.recoDao()

    // Calculs lourds (profil de goût, graphe des recommandations) hors du fil principal.
    suspend fun nextBatch(sessionIds: Set<Long>, carry: RecoCarry?): RecoBatch = withContext(Dispatchers.Default) {
        val now = clock()
        val excludeAvailable = settings.homelabConnected.first()
        val excluded = HashSet<Long>(sessionIds).apply {
            addAll(database.userMovieDao().recosExcludedIds(FeedRepository.SHOWN_COOLDOWN_MS, now, excludeAvailable))
        }

        val taste = Taste(dao.tasteRows())
        val coldStart = taste.watchedCount < COLD_START_WATCHED
        val stats = dao.sources().associate { it.source to SourceStats(it.shown, it.liked, it.disliked) }
        val ranker = RecoRanker(taste.profile, taste.weights, taste.titles, random)

        // 1. Graines, listes TMDB et profil de goût, en parallèle.
        val seeds = pickSeeds(taste, dao.seeds(), now, coldStart)
        val sources = ranker.pickSources(discoverSources(taste), stats, if (coldStart) 3 else 2, coldStart)
        val seedJobs = seeds.map { seed -> async { attempt { fetchLinks(seed) } } }
        val sourceJobs = sources.map { source -> async { attempt { fetchSource(source, taste, coldStart) } } }
        val enrichJob = async { attempt { enrichTaste(TASTE_ENRICH_PER_BATCH) } }
        val seedResults = seedJobs.awaitAll()
        val sourceResults = sourceJobs.awaitAll()
        enrichJob.await()

        // 2. Candidats : le graphe des recommandations (tous les films notés) et les listes.
        val links = dao.openLinks(FeedRepository.SHOWN_COOLDOWN_MS, now, excludeAvailable)
        val collaborative = RecoRanker.collaborative(
            links.map { SeedLink(it.seedId, it.movieId, it.position, similar = it.kind == RecoLinkKind.SIMILAR) },
            taste.weights,
        )
        val forYouIds = collaborative.entries
            .filter { (id, score) -> score.value > 0.0 && id !in excluded && id !in taste.ids }
            .sortedByDescending { it.value.value }
            .take(FOR_YOU_POOL)
            .map { it.key }
        val discovered = (carry?.items.orEmpty() + sourceResults.flatMap { it.getOrNull().orEmpty() })
            .filter { it.summary.id !in excluded && it.summary.id !in taste.ids && isShowable(it.summary) }
            .distinctBy { it.summary.id to it.source }
        val cached = dao.movies((forYouIds + discovered.map { it.summary.id }).distinct()).associateBy { it.tmdbId }

        val candidates = ArrayList<RecoCandidate>()
        forYouIds.forEach { id ->
            val movie = cached[id] ?: return@forEach
            if (movie.posterPath == null) return@forEach
            candidates += RecoCandidate(
                tmdbId = id,
                source = RecoSource.FOR_YOU,
                reason = RecoReason(ReasonKind.CLOSE_TO, RecoSource.FOR_YOU),
                features = features(movie),
                voteAverage = movie.tmdbRating,
                voteCount = movie.voteCount,
            )
        }
        discovered.forEach { item ->
            val summary = item.summary
            candidates += RecoCandidate(
                tmdbId = summary.id,
                source = item.source,
                reason = item.reason,
                features = cached[summary.id]?.takeIf { it.detailsVersion >= TmdbMapper.TASTE_DETAILS_VERSION }?.let(::features)
                    ?: FilmFeatures(
                        genreIds = summary.genreIds,
                        year = TmdbMapper.year(summary.releaseDate),
                        language = TmdbMapper.language(summary.originalLanguage),
                    ),
                voteAverage = summary.voteAverage,
                voteCount = summary.voteCount,
            )
        }

        val picked = ranker.compose(candidates.map { ranker.score(it, collaborative[it.tmdbId]) }, stats, BATCH_SIZE, coldStart)
        if (picked.isEmpty()) {
            // Rien à montrer : si TMDB n'a jamais répondu, c'est l'erreur qu'il faut afficher.
            val failures = (seedResults + sourceResults).mapNotNull { it.exceptionOrNull() }
            if (failures.isNotEmpty() && failures.size == seedResults.size + sourceResults.size) {
                throw failures.firstOrNull { it is TmdbException.InvalidKey || it is TmdbException.MissingKey } ?: failures.first()
            }
        }

        // 3. Fiches complètes des cartes retenues (repli sur la fiche partielle).
        val summaries = discovered.associate { it.summary.id to it.summary }
        val cards = enrich(picked, cached, summaries)
        val pickedIds = picked.mapTo(HashSet()) { it.candidate.tmdbId }
        val rest = discovered.filter { it.summary.id !in pickedIds }.take(MAX_CARRY)
        RecoBatch(cards, RecoCarry(rest))
    }

    /**
     * « Ce soir » : les films à portée de main (liste À voir, et disponibles sur le serveur quand
     * le homelab est actif), du plus au moins susceptible de plaire. Même score que les recos
     * (graphe TMDB des films notés, profil de goût, note TMDB), calculé sur les films vus et
     * écartés seulement ; un film de la liste À voir passe un peu devant, et l'ordre varie
     * légèrement d'un jour à l'autre sans bouger dans la journée.
     */
    /** [series] : les séries en cours passent devant (« À reprendre »), de la plus récente à la plus ancienne. */
    suspend fun tonight(series: Boolean = false): List<TonightPick> = withContext(Dispatchers.Default) {
        val resume = if (series) resumePicks() else emptyList()
        resume + computeTonight().filter { pick -> resume.none { it.movie.tmdbId == pick.movie.tmdbId } }
    }

    private suspend fun resumePicks(): List<TonightPick> {
        val withServer = settings.homelabConnected.first()
        return dao.inProgress().mapIndexed { index, row ->
            TonightPick(
                movie = row.movie,
                inWatchlist = false,
                available = withServer && row.availability == Availability.AVAILABLE,
                requested = withServer && row.availability == Availability.REQUESTED,
                score = RESUME_SCORE - index,
                reason = null,
                resume = true,
                season = row.currentSeason,
            )
        }
    }

    private suspend fun computeTonight(): List<TonightPick> {
        val withServer = settings.homelabConnected.first()
        val rows = dao.tonight(withServer)
        if (rows.isEmpty()) return emptyList()
        val taste = Taste(dao.tasteRows().filter { it.status != WatchStatus.WATCHLIST })
        val ranker = RecoRanker(taste.profile, taste.weights, taste.titles, random)
        val collaborative = RecoRanker.collaborative(
            dao.linksTo(rows.map { it.movie.tmdbId }).map { SeedLink(it.seedId, it.movieId, it.position, similar = it.kind == RecoLinkKind.SIMILAR) },
            taste.weights,
        )
        val day = clock() / DAY_MS
        return rows.map { row ->
            val movie = row.movie
            val candidate = RecoCandidate(
                tmdbId = movie.tmdbId,
                source = RecoSource.FOR_YOU,
                reason = RecoReason(ReasonKind.CLOSE_TO, RecoSource.FOR_YOU),
                features = if (movie.detailsVersion >= TmdbMapper.TASTE_DETAILS_VERSION) {
                    features(movie)
                } else {
                    FilmFeatures(genreIds = movie.genreIds, year = movie.year, language = movie.originalLanguage)
                },
                voteAverage = movie.tmdbRating,
                voteCount = movie.voteCount,
            )
            val scored = ranker.score(candidate, collaborative[movie.tmdbId])
            val inWatchlist = row.status == WatchStatus.WATCHLIST
            val bonus = (if (inWatchlist) WATCHLIST_BONUS else 0.0) + DAILY_JITTER * dailyNoise(movie.tmdbId, day)
            TonightPick(
                movie = movie,
                inWatchlist = inWatchlist,
                available = withServer && row.availability == Availability.AVAILABLE,
                requested = withServer && row.availability == Availability.REQUESTED,
                score = scored.score + bonus,
                reason = scored.reason.takeIf { it.subject != null },
            )
        }.sortedByDescending { it.score }
    }

    /** Une carte des recos vient d'être montrée. */
    suspend fun recordShown(source: RecoSource) {
        dao.recordSource(source, shown = 1.0, liked = 0.0, disliked = 0.0, now = clock())
    }

    /**
     * Action sur une carte des recos ([undo] : annulation de cette action). « Vu » compte moitié :
     * la carte était juste, mais ce n'est pas une découverte.
     */
    suspend fun recordAction(source: RecoSource, action: CardAction, undo: Boolean = false) {
        val sign = if (undo) -1.0 else 1.0
        when (action) {
            CardAction.WATCHED -> dao.recordSource(source, shown = 0.0, liked = sign * WATCHED_CREDIT, disliked = 0.0, now = clock())
            CardAction.WATCHLIST, CardAction.REQUEST ->
                dao.recordSource(source, shown = 0.0, liked = sign, disliked = 0.0, now = clock())
            CardAction.DISMISS -> dao.recordSource(source, shown = 0.0, liked = 0.0, disliked = sign, now = clock())
            CardAction.UNWATCH -> Unit
        }
    }

    /**
     * Première page de l'Éclair : si le film de départ est dans le profil (vu, à voir), ses
     * recommandations rejoignent le graphe, sans appel de plus.
     */
    suspend fun learnFromPage(seedId: Long, page: TmdbPageDto) {
        if (page.page != 1 || page.results.isEmpty()) return
        val status = database.userMovieDao().get(seedId)?.status ?: return
        if (status == WatchStatus.NONE) return
        saveLinks(seedId, page.results, RecoLinkKind.RECOMMENDED)
    }

    /**
     * Complète les fiches des films du profil (mots-clés, réalisateurs, acteurs), les films vus
     * d'abord, les plus tranchés en tête, par petits groupes. S'arrête dès qu'un groupe échoue
     * entièrement faute de réseau ou de clé. Renvoie le nombre de fiches complétées.
     */
    suspend fun enrichTaste(limit: Int): Int {
        val ids = dao.tasteToEnrich(TmdbMapper.DETAILS_VERSION, limit)
        var done = 0
        for (chunk in ids.chunked(ENRICH_CONCURRENCY)) {
            val outcomes = coroutineScope {
                chunk.map { id ->
                    async {
                        try {
                            tmdb.refreshDetails(id)
                            null
                        } catch (error: TmdbException) {
                            error
                        }
                    }
                }.awaitAll()
            }
            val successes = outcomes.count { it == null }
            done += successes
            val blocked = outcomes.any { it is TmdbException.Network || it is TmdbException.MissingKey || it is TmdbException.InvalidKey }
            if (successes == 0 && blocked) break
        }
        return done
    }

    /** Les films vus, à voir et écartés, avec leur poids et le profil de goût qui en découle. */
    private class Taste(val rows: List<TasteRow>) {
        val ids: Set<Long> = rows.mapTo(HashSet()) { it.tmdbId }
        val watchedCount: Int = rows.count { it.status == WatchStatus.WATCHED }
        val titles: Map<Long, String> = rows.associate { it.tmdbId to it.title }
        val weights: Map<Long, Double> = buildMap {
            putAll(TasteProfile.watchedWeights(rows.filter { it.status == WatchStatus.WATCHED }.associate { it.tmdbId to it.rating }))
            rows.forEach { row ->
                when (row.status) {
                    WatchStatus.WATCHLIST -> put(row.tmdbId, TasteProfile.WATCHLIST_WEIGHT)
                    WatchStatus.DISMISSED -> put(row.tmdbId, TasteProfile.DISMISSED_WEIGHT)
                    else -> Unit
                }
            }
        }
        val profile = TasteProfile(rows.map { TasteFilm(it.tmdbId, it.title, weights[it.tmdbId] ?: 0.0, features(it)) })
    }

    /**
     * Graines à explorer : jamais explorées (ou il y a longtemps), les films aimés et les films
     * tout juste ajoutés d'abord ; parfois un film détesté, pour apprendre ce qu'il faut éviter.
     */
    private fun pickSeeds(taste: Taste, fetched: List<RecoSeedEntity>, now: Long, coldStart: Boolean): List<Long> {
        val fetchedAt = fetched.associate { it.seedId to it.fetchedAt }
        val staleBefore = now - SEED_REFRESH_MS
        val open = taste.rows.filter { (fetchedAt[it.tmdbId] ?: Long.MIN_VALUE) < staleBefore }
        fun recent(row: TasteRow): Boolean = now - (row.watchedAt ?: row.updatedAt) < RECENT_MS

        val positive = open.filter { (taste.weights[it.tmdbId] ?: 0.0) >= MIN_SEED_WEIGHT }
        val picks = pickWeighted(positive, if (coldStart) 3 else 2) { row ->
            val base = ((taste.weights[row.tmdbId] ?: 0.0) + 0.2).coerceAtLeast(0.05)
            val fresh = if (fetchedAt[row.tmdbId] == null) 3.0 else 1.0
            base * fresh + if (recent(row)) 2.0 else 0.0
        }.map { it.tmdbId }.toMutableList()
        if (random.nextDouble() < NEGATIVE_SEED_RATE) {
            val negative = open.filter { (taste.weights[it.tmdbId] ?: 0.0) <= NEGATIVE_SEED_WEIGHT }
            pickWeighted(negative, 1) { -(taste.weights[it.tmdbId] ?: 0.0) }.firstOrNull()?.let { picks += it.tmdbId }
        }
        return picks
    }

    private fun discoverSources(taste: Taste): List<RecoSource> = buildList {
        add(RecoSource.CLASSICS)
        add(RecoSource.GEMS)
        add(RecoSource.TRENDING)
        if (people(taste).isNotEmpty()) add(RecoSource.PEOPLE)
        if (themes(taste).isNotEmpty()) add(RecoSource.THEMES)
    }

    private fun people(taste: Taste): List<Affinity> =
        (taste.profile.top(TasteFamily.DIRECTOR, 8, minFilms = 2) + taste.profile.top(TasteFamily.CAST, 8, minFilms = 2))
            .filter { it.value >= MIN_PERSON_AFFINITY }

    private fun themes(taste: Taste): List<Affinity> =
        taste.profile.top(TasteFamily.KEYWORD, 12, minFilms = 3).filter { it.value >= MIN_THEME_AFFINITY }

    private suspend fun fetchSource(source: RecoSource, taste: Taste, coldStart: Boolean): List<CarriedCandidate> {
        fun genre(probability: Double): Int? {
            if (random.nextDouble() >= probability) return null
            return pickWeighted(taste.profile.top(TasteFamily.GENRE, 5), 1) { it.value }.firstOrNull()?.key?.toIntOrNull()
        }
        val (page, reason) = when (source) {
            RecoSource.CLASSICS -> {
                // Plus la bibliothèque grandit, plus on va chercher loin dans les films les plus votés.
                val query = DiscoverQuery.classics(if (coldStart) null else genre(0.5))
                discoverWithin(query, maxPage = (CLASSICS_PAGES + taste.watchedCount / 8).coerceAtMost(MAX_CLASSICS_PAGES)) to
                    RecoReason(ReasonKind.CLASSIC, source)
            }
            RecoSource.GEMS -> discoverWithin(DiscoverQuery.gems(genre(0.7)), maxPage = 5) to RecoReason(ReasonKind.GEM, source)
            RecoSource.TRENDING -> tmdb.trending(random.nextInt(1, 4)) to RecoReason(ReasonKind.TRENDING, source)
            RecoSource.PEOPLE -> {
                val person = pickWeighted(people(taste), 1) { it.value }.firstOrNull() ?: return emptyList()
                val id = person.key.toLongOrNull() ?: return emptyList()
                if (person.family == TasteFamily.DIRECTOR) {
                    tmdb.discover(DiscoverQuery(crewId = id, sortBy = "vote_count.desc"), 1) to RecoReason(ReasonKind.DIRECTOR, source, person.name)
                } else {
                    tmdb.discover(DiscoverQuery(castId = id, sortBy = "vote_count.desc"), 1) to RecoReason(ReasonKind.ACTOR, source, person.name)
                }
            }
            RecoSource.THEMES -> {
                val theme = pickWeighted(themes(taste), 1) { it.value }.firstOrNull() ?: return emptyList()
                val id = theme.key.toLongOrNull() ?: return emptyList()
                discoverWithin(DiscoverQuery(keywordId = id, minVotes = 100, sortBy = "vote_count.desc"), maxPage = 2) to
                    RecoReason(ReasonKind.THEME, source, theme.name)
            }
            RecoSource.FOR_YOU -> return emptyList()
        }
        return page.results.map { CarriedCandidate(it, source, reason) }
    }

    /**
     * Une page au hasard parmi les [maxPage] premières ; si elle est au-delà de la dernière page
     * (liste courte, genre rare), une page au hasard parmi celles qui existent.
     */
    private suspend fun discoverWithin(query: DiscoverQuery, maxPage: Int): TmdbPageDto {
        val first = tmdb.discover(query, random.nextInt(1, maxPage + 1))
        if (first.results.isNotEmpty() || first.totalPages <= 0 || first.page <= first.totalPages) return first
        return tmdb.discover(query, random.nextInt(1, minOf(first.totalPages, maxPage) + 1))
    }

    /**
     * Explore une graine : ses recommandations TMDB (à défaut, ses films similaires) rejoignent le
     * graphe. Renvoie les films liés (le recensement s'en sert aussi).
     */
    suspend fun explore(seedId: Long): List<TmdbMovieSummaryDto> {
        val recommended = tmdb.recommendations(seedId, 1)
        return if (recommended.results.isNotEmpty()) {
            saveLinks(seedId, recommended.results, RecoLinkKind.RECOMMENDED)
            recommended.results
        } else {
            val similar = tmdb.similar(seedId, 1).results
            saveLinks(seedId, similar, RecoLinkKind.SIMILAR)
            similar
        }
    }

    private suspend fun fetchLinks(seedId: Long) {
        explore(seedId)
    }

    private suspend fun saveLinks(seedId: Long, results: List<TmdbMovieSummaryDto>, kind: RecoLinkKind) {
        val genreNames = genreNames()
        val language = settings.language.first()
        val usable = results.withIndex().filter { (_, dto) -> dto.id != seedId && isShowable(dto) }
        val links = usable.map { (position, dto) -> RecoLinkEntity(seedId = seedId, movieId = dto.id, position = position, kind = kind) }
            .distinctBy { it.movieId }
        val movies = usable.map { (_, dto) -> TmdbMapper.summaryToEntity(dto, genreNames, language) }
        dao.saveLinks(seedId, links, movies, clock())
    }

    private suspend fun genreNames(): Map<Int, String> = try {
        tmdb.genres().associate { it.id to it.name }
    } catch (error: TmdbException) {
        emptyMap()
    }

    /** Fiches complètes des cartes, dans l'ordre (repli sur la fiche partielle en cas d'échec). */
    private suspend fun enrich(
        picked: List<ScoredCandidate>,
        cached: Map<Long, MovieEntity>,
        summaries: Map<Long, TmdbMovieSummaryDto>,
    ): List<RecoCard> {
        if (picked.isEmpty()) return emptyList()
        val language = settings.language.first()
        val genreNames = genreNames()
        val semaphore = Semaphore(ENRICH_CONCURRENCY)
        return coroutineScope {
            picked.map { item ->
                async {
                    semaphore.withPermit {
                        val id = item.candidate.tmdbId
                        val movie = try {
                            tmdb.ensureDetails(id)
                        } catch (error: TmdbException) {
                            cached[id] ?: summaries[id]?.let { TmdbMapper.summaryToEntity(it, genreNames, language) }
                        }
                        movie?.let { RecoCard(it, item.reason) }
                    }
                }
            }.awaitAll().filterNotNull()
        }
    }

    private fun <T> pickWeighted(items: List<T>, count: Int, weight: (T) -> Double): List<T> {
        val remaining = items.map { it to weight(it).coerceAtLeast(0.0) }.toMutableList()
        val picked = ArrayList<T>(count)
        while (picked.size < count && remaining.isNotEmpty()) {
            val total = remaining.sumOf { it.second }
            var target = random.nextDouble() * total
            var index = remaining.lastIndex
            if (total > 0.0) {
                for (i in remaining.indices) {
                    target -= remaining[i].second
                    if (target <= 0.0) {
                        index = i
                        break
                    }
                }
            } else {
                index = random.nextInt(remaining.size)
            }
            picked += remaining.removeAt(index).first
        }
        return picked
    }

    private suspend fun <T> attempt(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (error: CancellationException) {
        throw error
    } catch (error: TmdbException) {
        Result.failure(error)
    }

    companion object {
        /** Score des séries à reprendre : toujours devant les nouveautés. */
        const val RESUME_SCORE = 1_000_000.0
        const val BATCH_SIZE = 10

        /** « Ce soir » : un film qu'on a mis de côté passe un peu devant. */
        const val WATCHLIST_BONUS = 0.25

        /** Variation du jour (± cette valeur) pour ne pas proposer toujours le même film. */
        const val DAILY_JITTER = 0.12
        private const val DAY_MS = 24L * 60 * 60 * 1000

        /** Bruit déterministe dans [−1, 1] pour un film et un jour. */
        fun dailyNoise(tmdbId: Long, day: Long): Double {
            var x = tmdbId * -0x61c8864680b583ebL + day * -0x40a7b892e31b1a47L
            x = (x xor (x ushr 30)) * -0x40a7b892e31b1a47L
            x = (x xor (x ushr 27)) * -0x6b2fb644ecceee15L
            x = x xor (x ushr 31)
            return ((x ushr 11).toDouble() / (1L shl 53).toDouble()) * 2.0 - 1.0
        }

        /** Part de succès d'un « Vu » sur une carte des recos. */
        const val WATCHED_CREDIT = 0.5

        /** En dessous de 20 films vus, les recos misent sur les grands classiques. */
        const val COLD_START_WATCHED = 20

        /** Candidats du graphe des recommandations retenus pour un lot. */
        const val FOR_YOU_POOL = 40

        /** Candidats des listes gardés d'un lot sur l'autre. */
        const val MAX_CARRY = 60
        const val ENRICH_CONCURRENCY = 6

        /** Fiches du profil complétées à chaque lot, et au démarrage de l'appli. */
        const val TASTE_ENRICH_PER_BATCH = 6
        const val TASTE_ENRICH_AT_START = 60

        /** Les recommandations d'une graine sont reprises au bout de 4 mois. */
        const val SEED_REFRESH_MS = 120L * 24 * 60 * 60 * 1000
        private const val RECENT_MS = 3L * 24 * 60 * 60 * 1000
        private const val MIN_SEED_WEIGHT = 0.3
        private const val NEGATIVE_SEED_WEIGHT = -0.3
        private const val NEGATIVE_SEED_RATE = 0.25
        private const val CLASSICS_PAGES = 8
        private const val MAX_CLASSICS_PAGES = 40
        private const val MIN_PERSON_AFFINITY = 0.3
        private const val MIN_THEME_AFFINITY = 0.3

        fun isShowable(dto: TmdbMovieSummaryDto): Boolean =
            !dto.adult &&
                (TmdbMapper.cleanText(dto.title) != null || TmdbMapper.cleanText(dto.originalTitle) != null) &&
                TmdbMapper.imagePath(dto.posterPath) != null

        fun features(movie: MovieEntity): FilmFeatures = FilmFeatures(
            genreIds = movie.genreIds,
            keywords = movie.keywords,
            directors = movie.directors,
            cast = movie.cast.mapNotNull { member -> member.id?.let { TmdbTag(it, member.name) } },
            year = movie.year,
            language = movie.originalLanguage,
        )

        fun features(row: TasteRow): FilmFeatures = FilmFeatures(
            genreIds = row.genreIds,
            keywords = row.keywords,
            directors = row.directors,
            cast = row.cast.mapNotNull { member -> member.id?.let { TmdbTag(it, member.name) } },
            year = row.year,
            language = row.originalLanguage,
        )
    }
}
