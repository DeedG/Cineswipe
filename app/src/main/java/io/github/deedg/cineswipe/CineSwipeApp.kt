package io.github.deedg.cineswipe

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.request.crossfade
import io.github.deedg.cineswipe.data.demo.DemoCatalog
import io.github.deedg.cineswipe.data.demo.DemoSeeder
import io.github.deedg.cineswipe.data.homelab.HomelabRepository
import io.github.deedg.cineswipe.data.model.MediaKind
import io.github.deedg.cineswipe.data.repository.RecoRepository
import io.github.deedg.cineswipe.data.series.SeasonWatchWorker
import io.github.deedg.cineswipe.ui.theme.Accent
import io.github.deedg.cineswipe.ui.theme.CineAccent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okio.Path.Companion.toOkioPath

open class CineSwipeApp : Application(), SingletonImageLoader.Factory {

    /** Conteneur en service : celui de l'appli, ou celui de la démo pendant qu'elle tourne. */
    lateinit var container: AppContainer
        private set

    /** Conteneur des vraies données des films (tâches de fond, homelab, widgets), même pendant la démo ou en mode séries. */
    lateinit var mainContainer: AppContainer
        private set

    /** Conteneur du monde des séries, créé au premier passage en mode séries. */
    private var seriesContainer: AppContainer? = null

    /** Monde en service (films ou séries), hors démo. */
    @Volatile
    var kind: MediaKind = MediaKind.FILMS
        private set

    /**
     * Le guide de la clé TMDB doit s'ouvrir (s'il n'y a toujours pas de clé) : juste après
     * l'accueil (« Commencer ») ou en quittant la démo.
     */
    @Volatile
    var keyGuidePending: Boolean = false

    /** Texte partagé depuis une autre appli (lien ou titre d'un film), en attente d'être ouvert. */
    val sharedInbox = MutableStateFlow<String?>(null)

    /** Onglet à rouvrir après le switch Films | Séries (nom de l'onglet), lu une fois par le nouvel écran. */
    @Volatile
    var pendingTab: String? = null

    fun takePendingTab(): String? = pendingTab.also { pendingTab = null }

    /** Écran demandé par un widget (« tonight », « stats », « top », « movie:<id> »). */
    val openInbox = MutableStateFlow<String?>(null)

    /** La démo est en cours : l'appli tourne sur un bac à sable, les vraies données sont à l'abri. */
    val inDemo: Boolean get() = container.isDemo

    override fun onCreate() {
        super.onCreate()
        mainContainer = createContainer()
        container = mainContainer
        container.crashLog.install()
        // L'appli rouvre dans le dernier monde ouvert.
        val lastKind = runCatching { runBlocking { mainContainer.settings.mediaKind.first() } }.getOrDefault(MediaKind.FILMS)
        if (lastKind.isSeries) useWorld(MediaKind.SERIES)
        mainContainer.homelab.start()
        val world = container
        mainContainer.appScope.launch { mainContainer.backup.ensureScheduled() }
        // Nouvelle version publiée sur GitHub ? (une fois par jour au plus)
        mainContainer.appScope.launch { mainContainer.updates.check() }
        // Nouvelles saisons des séries suivies : une vérification par jour.
        if (scheduleBackgroundWork) SeasonWatchWorker.schedule(this)
        // Classement recalculé (incertitudes après une mise à jour), puis profil de goût complété
        // (pour les séries, au moment où leur monde est ouvert).
        if (world === mainContainer) {
            world.appScope.launch {
                world.arena.refresh()
                world.recos.enrichTaste(RecoRepository.TASTE_ENRICH_AT_START)
            }
        }
    }

    /** Tâches de fond planifiées au démarrage (pas dans les tests). */
    protected open val scheduleBackgroundWork: Boolean = true

    /** Point d'extension pour les tests (faux serveurs, chiffrement en clair, pas de tâches de fond). */
    protected open fun createContainer(): AppContainer = AppContainer(this)

    /** Conteneur des vraies données des séries (créé au besoin : synchro du homelab, appli fermée). */
    @Synchronized
    fun seriesWorld(): AppContainer = seriesContainer ?: createSeriesContainer().also { seriesContainer = it }

    /** Homelab des deux mondes (vraies données) : films, puis séries. */
    fun homelabs(): List<HomelabRepository> = listOf(mainContainer.homelab, seriesWorld().homelab)

    /** Conteneur du monde des séries (point d'extension pour les tests). */
    protected open fun createSeriesContainer(): AppContainer = AppContainer(this, kind = MediaKind.SERIES)

    /**
     * Passe dans l'autre monde (switch « Films | Séries ») et s'en souvient. L'écran doit ensuite
     * être relancé pour s'en servir. Sans effet pendant la démo.
     */
    suspend fun switchWorld(target: MediaKind) {
        if (inDemo || target == kind) return
        useWorld(target)
        mainContainer.settings.setMediaKind(target)
    }

    private fun useWorld(target: MediaKind) {
        kind = target
        val fresh = target.isSeries && seriesContainer == null
        container = if (target.isSeries) seriesWorld() else mainContainer
        CineAccent.current = Accent.of(target)
        if (fresh) {
            val world = container
            world.appScope.launch {
                world.arena.refresh()
                world.recos.enrichTaste(RecoRepository.TASTE_ENRICH_AT_START)
            }
        }
    }

    /**
     * Lance la démo : un bac à sable tout neuf (base et réglages en mémoire, TMDB simulé à partir
     * des 100 films, ou séries ([kind]), les plus vus), prêt quand la fonction rend la main. L'écran doit ensuite être
     * relancé pour s'en servir.
     */
    suspend fun startDemo(kind: MediaKind = MediaKind.FILMS) {
        val demo = withContext(Dispatchers.IO) { createDemoContainer(kind) }
        withContext(Dispatchers.Default) { DemoSeeder(demo, checkNotNull(demo.demoCatalog)).seed() }
        // La démo commence par le tuto pas à pas.
        demo.tutorial.start()
        val previous = container
        container = demo
        // Démo des films en rouge, des séries en violet.
        CineAccent.current = Accent.of(kind)
        if (previous.isDemo) retire(previous)
    }

    /** Quitte la démo : retour aux vraies données (le monde ouvert avant la démo), le bac à sable disparaît. */
    fun stopDemo() {
        val demo = container
        if (!inDemo) return
        useWorld(kind)
        keyGuidePending = true
        retire(demo)
    }

    /**
     * Ferme un bac à sable un peu plus tard : les écrans qui lisaient encore sa base se ferment
     * d'abord (l'activité est relancée), sans requête sur une base déjà fermée.
     */
    private fun retire(demo: AppContainer) {
        mainContainer.appScope.launch {
            delay(RETIRE_DELAY_MS)
            demo.close()
        }
    }

    protected open fun createDemoContainer(kind: MediaKind = MediaKind.FILMS): AppContainer =
        AppContainer(this, AppConfig.demo(DemoCatalog.load(this, series = kind.isSeries)), kind = kind)

    private companion object {
        const val RETIRE_DELAY_MS = 10_000L
    }

    /** Affiches gardées sur le disque : la bibliothèque et l'Arène restent belles hors ligne. */
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .crossfade(true)
            .diskCache {
                DiskCache.Builder()
                    .directory(context.cacheDir.resolve("image_cache").toOkioPath())
                    .maxSizeBytes(256L * 1024 * 1024)
                    .build()
            }
            .build()
}
