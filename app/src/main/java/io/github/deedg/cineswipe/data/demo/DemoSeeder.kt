package io.github.deedg.cineswipe.data.demo

import io.github.deedg.cineswipe.AppContainer
import io.github.deedg.cineswipe.data.db.WatchStatus
import io.github.deedg.cineswipe.data.homelab.HomelabService
import io.github.deedg.cineswipe.data.homelab.ServiceConfig
import io.github.deedg.cineswipe.data.tmdb.TmdbMapper
import kotlinx.coroutines.flow.first
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Prépare le bac à sable de la démo : un faux profil qui a déjà vu une vingtaine de films très
 * connus et joué une soixantaine de duels (avec des goûts plausibles), et deux films à voir. Le
 * reste du catalogue se découvre dans les recos, la découverte et le recensement.
 */
class DemoSeeder(
    private val container: AppContainer,
    private val catalog: DemoCatalog,
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: Random = Random(SEED),
) {
    suspend fun seed() {
        val settings = container.settings
        settings.setTmdbApiKey(DEMO_KEY)
        settings.setOnboardingDone(true)
        val language = settings.language.first()
        val now = clock()

        // Toutes les fiches sont complètes d'emblée : rien n'est à recharger.
        val library = container.database.libraryDao()
        catalog.films.forEach { film ->
            library.upsertMovie(
                TmdbMapper.toEntity(
                    dto = catalog.details(film),
                    overview = film.overview,
                    overviewLanguage = TmdbMapper.primaryLanguage(language),
                    language = language,
                    now = now,
                ),
            )
        }
        val known = catalog.films.mapTo(HashSet()) { it.id }
        val watched = WATCHED.filter { it in known }
        watched.forEachIndexed { index, id -> library.setWatched(id, watched = true, now = now - (watched.size - index) * HOUR_MS) }
        WATCHLIST.filter { it in known }.forEach { library.applyStatus(it, WatchStatus.WATCHLIST, now) }
        playDuels(watched, now)
        connectHomelab()
    }

    /**
     * Homelab fictif : un Plex (films sur le serveur, regarder) et un Seerr (demandes d'un swipe
     * droit), servis par [DemoHomelabInterceptor]. Première synchro tout de suite.
     */
    private suspend fun connectHomelab() {
        val settings = container.settings
        settings.saveService(ServiceConfig(HomelabService.PLEX, enabled = true, url = DemoHomelab.PLEX_URL, apiKey = DemoHomelab.KEY))
        settings.saveService(ServiceConfig(HomelabService.SEERR, enabled = true, url = DemoHomelab.SEERR_URL, apiKey = DemoHomelab.KEY))
        runCatching { container.homelab.sync() }
    }

    /** Duels joués avec des goûts plausibles : la note TMDB, plus une bonne part de goût personnel. */
    private suspend fun playDuels(watched: List<Long>, now: Long) {
        if (watched.size < 2) return
        val ratings = catalog.films.associate { it.id to (it.rating ?: AVERAGE_RATING) }
        val strength = watched.associateWith { id -> (ratings.getValue(id) - AVERAGE_RATING) * POINTS_PER_STAR + gaussian() * TASTE_SPREAD }
        val arena = container.database.arenaDao()
        val pairs = HashSet<Pair<Long, Long>>()
        var played = 0
        var attempts = 0
        while (played < DUELS && attempts < DUELS * 20) {
            attempts++
            val a = watched.random(random)
            val b = watched.random(random)
            if (a == b || !pairs.add(minOf(a, b) to maxOf(a, b))) continue
            val chanceA = 1.0 / (1.0 + 10.0.pow((strength.getValue(b) - strength.getValue(a)) / 400.0))
            val (winner, loser) = if (random.nextDouble() < chanceA) a to b else b to a
            arena.recordDuel(winner, loser, draw = false, now = now - (DUELS - played) * MINUTE_MS)
            played++
        }
    }

    private fun gaussian(): Double {
        val u = random.nextDouble().coerceAtLeast(1e-12)
        val v = random.nextDouble()
        return sqrt(-2.0 * ln(u)) * cos(2.0 * PI * v)
    }

    companion object {
        /** Clé factice : la démo ne parle jamais à TMDB. */
        const val DEMO_KEY = "demo"

        private const val SEED = 2026
        private const val DUELS = 60
        private const val AVERAGE_RATING = 7.5
        private const val POINTS_PER_STAR = 400.0
        private const val TASTE_SPREAD = 150.0
        private const val HOUR_MS = 60L * 60 * 1000
        private const val MINUTE_MS = 60L * 1000

        /** Films vus du profil de démo (ceux absents du catalogue sont ignorés). */
        val WATCHED = listOf(
            157336L, // Interstellar
            27205L, // Inception
            155L, // The Dark Knight
            13L, // Forrest Gump
            550L, // Fight Club
            671L, // Harry Potter à l'école des sorciers
            120L, // Le Seigneur des anneaux : La Communauté de l'anneau
            597L, // Titanic
            8587L, // Le Roi lion
            24428L, // Avengers
            19995L, // Avatar
            98L, // Gladiator
            11324L, // Shutter Island
            68718L, // Django Unchained
            106646L, // Le Loup de Wall Street
            150540L, // Vice-versa
            14160L, // Là-haut
            354912L, // Coco
            77338L, // Intouchables
            862L, // Toy Story
            1726L, // Iron Man
        )

        /** Films à voir du profil de démo. */
        val WATCHLIST = listOf(
            1124L, // Le Prestige
            244786L, // Whiplash
        )
    }
}
