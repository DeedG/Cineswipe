package io.github.deedg.cineswipe

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.request.crossfade
import io.github.deedg.cineswipe.data.demo.DemoCatalog
import io.github.deedg.cineswipe.data.demo.DemoSeeder
import io.github.deedg.cineswipe.data.repository.RecoRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okio.Path.Companion.toOkioPath

open class CineSwipeApp : Application(), SingletonImageLoader.Factory {

    /** Conteneur en service : celui de l'appli, ou celui de la démo pendant qu'elle tourne. */
    lateinit var container: AppContainer
        private set

    /** Conteneur des vraies données (tâches de fond, homelab), même pendant la démo. */
    lateinit var mainContainer: AppContainer
        private set

    /**
     * Le guide de la clé TMDB doit s'ouvrir (s'il n'y a toujours pas de clé) : juste après
     * l'accueil (« Commencer ») ou en quittant la démo.
     */
    @Volatile
    var keyGuidePending: Boolean = false

    /** Texte partagé depuis une autre appli (lien ou titre d'un film), en attente d'être ouvert. */
    val sharedInbox = MutableStateFlow<String?>(null)

    /** Écran demandé par un widget (« tonight », « stats », « top », « movie:<id> »). */
    val openInbox = MutableStateFlow<String?>(null)

    /** La démo est en cours : l'appli tourne sur un bac à sable, les vraies données sont à l'abri. */
    val inDemo: Boolean get() = container !== mainContainer

    override fun onCreate() {
        super.onCreate()
        mainContainer = createContainer()
        container = mainContainer
        container.crashLog.install()
        container.homelab.start()
        container.appScope.launch {
            // Classement recalculé (incertitudes après une mise à jour), puis profil de goût complété.
            container.backup.ensureScheduled()
            container.arena.refresh()
            container.recos.enrichTaste(RecoRepository.TASTE_ENRICH_AT_START)
        }
    }

    /** Point d'extension pour les tests (faux serveurs, chiffrement en clair, pas de tâches de fond). */
    protected open fun createContainer(): AppContainer = AppContainer(this)

    /**
     * Lance la démo : un bac à sable tout neuf (base et réglages en mémoire, TMDB simulé à partir
     * des 100 films les plus vus), prêt quand la fonction rend la main. L'écran doit ensuite être
     * relancé pour s'en servir.
     */
    suspend fun startDemo() {
        val demo = withContext(Dispatchers.IO) { createDemoContainer() }
        withContext(Dispatchers.Default) { DemoSeeder(demo, checkNotNull(demo.demoCatalog)).seed() }
        // La démo commence par le tuto pas à pas.
        demo.tutorial.start()
        val previous = container
        container = demo
        if (previous !== mainContainer) retire(previous)
    }

    /** Quitte la démo : retour aux vraies données, le bac à sable disparaît. */
    fun stopDemo() {
        val demo = container
        if (demo === mainContainer) return
        container = mainContainer
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

    protected open fun createDemoContainer(): AppContainer = AppContainer(this, AppConfig.demo(DemoCatalog.load(this)))

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
