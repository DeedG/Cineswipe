package io.github.deedg.cineswipe

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import android.util.Log
import io.github.deedg.cineswipe.core.CrashLog
import io.github.deedg.cineswipe.core.KeystoreSecretCipher
import io.github.deedg.cineswipe.core.MemoryPreferencesStore
import io.github.deedg.cineswipe.core.PlainSecretCipher
import io.github.deedg.cineswipe.core.SecretCipher
import io.github.deedg.cineswipe.data.backup.BackupRepository
import io.github.deedg.cineswipe.data.backup.BackupScheduler
import io.github.deedg.cineswipe.data.backup.BackupStorage
import io.github.deedg.cineswipe.data.backup.SafBackupStorage
import io.github.deedg.cineswipe.data.backup.WorkManagerBackupScheduler
import io.github.deedg.cineswipe.data.db.CineSwipeDatabase
import io.github.deedg.cineswipe.data.demo.DemoCatalog
import io.github.deedg.cineswipe.data.demo.DemoHomelabInterceptor
import io.github.deedg.cineswipe.data.demo.DemoTmdbInterceptor
import io.github.deedg.cineswipe.data.homelab.AndroidWatchedNotifier
import io.github.deedg.cineswipe.data.homelab.HomelabHttp
import io.github.deedg.cineswipe.data.homelab.HomelabRepository
import io.github.deedg.cineswipe.data.homelab.HomelabScheduler
import io.github.deedg.cineswipe.data.homelab.JellyfinClient
import io.github.deedg.cineswipe.data.homelab.PlexClient
import io.github.deedg.cineswipe.data.homelab.RadarrClient
import io.github.deedg.cineswipe.data.homelab.SeerrClient
import io.github.deedg.cineswipe.data.homelab.WatchedNotifier
import io.github.deedg.cineswipe.data.homelab.WorkManagerScheduler
import io.github.deedg.cineswipe.data.importer.ImportRepository
import io.github.deedg.cineswipe.data.repository.ArenaRepository
import io.github.deedg.cineswipe.data.repository.CensusRepository
import io.github.deedg.cineswipe.data.repository.DiagnosticsRepository
import io.github.deedg.cineswipe.data.repository.FeedRepository
import io.github.deedg.cineswipe.data.repository.LibraryRepository
import io.github.deedg.cineswipe.data.repository.ListsRepository
import io.github.deedg.cineswipe.data.repository.PeopleRepository
import io.github.deedg.cineswipe.data.repository.RecoRepository
import io.github.deedg.cineswipe.data.repository.RetroRepository
import io.github.deedg.cineswipe.data.repository.SettingsRepository
import io.github.deedg.cineswipe.data.repository.StatsRepository
import io.github.deedg.cineswipe.data.repository.TmdbRepository
import io.github.deedg.cineswipe.data.share.SharedRepository
import io.github.deedg.cineswipe.data.tmdb.TmdbApi
import io.github.deedg.cineswipe.ui.tutorial.Tutorial
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import okhttp3.OkHttpClient

/**
 * Réglages techniques du conteneur, remplacés dans les tests (faux serveurs, chiffrement en
 * clair, pas de tâches de fond).
 */
class AppConfig(
    val tmdbBaseUrl: String = TmdbApi.BASE_URL,
    val cipher: () -> SecretCipher = { KeystoreSecretCipher() },
    val homelabScheduler: (Context) -> HomelabScheduler = { WorkManagerScheduler(it) },
    /** Délai avant l'envoi d'une demande au homelab (« Annuler » juste après ne laisse pas de trace). */
    val requestGraceMs: Long = HomelabRepository.REQUEST_GRACE_MS,
    /**
     * Bac à sable de la démo : base et réglages en mémoire, TMDB simulé à partir de ce catalogue
     * (aucune requête ne part sur le réseau), pas de homelab.
     */
    val demo: DemoCatalog? = null,
    /** Sauvegarde automatique : dossier choisi par l'utilisateur et tâche hebdomadaire. */
    val backupStorage: (Context) -> BackupStorage = { SafBackupStorage(it) },
    val backupScheduler: (Context) -> BackupScheduler = { WorkManagerBackupScheduler(it) },
    /** Notification après un film vu sur Plex ou Jellyfin. */
    val watchedNotifier: (Context) -> WatchedNotifier = { AndroidWatchedNotifier(it) },
) {
    companion object {
        fun demo(catalog: DemoCatalog) = AppConfig(
            cipher = { PlainSecretCipher },
            homelabScheduler = { HomelabScheduler.None },
            demo = catalog,
            watchedNotifier = { WatchedNotifier.None },
            backupScheduler = { BackupScheduler.None },
        )
    }
}

/** Injection de dépendances manuelle : une seule instance de chaque service pour l'appli. */
class AppContainer(context: Context, private val config: AppConfig = AppConfig()) {
    private val appContext = context.applicationContext

    /** Travaux qui survivent à l'écran qui les lance (synchro, envoi des demandes). Une erreur y est journalisée, sans planter l'appli. */
    val appScope: CoroutineScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, error -> Log.w(TAG, "Tâche de fond en échec", error) },
    )

    /** Démo en cours : ce conteneur est un bac à sable, les vraies données ne sont pas touchées. */
    val isDemo: Boolean get() = config.demo != null

    /** Catalogue de la démo (null hors démo). */
    val demoCatalog: DemoCatalog? get() = config.demo

    val crashLog: CrashLog by lazy { CrashLog(appContext) }
    private val databaseLazy = lazy {
        if (config.demo != null) CineSwipeDatabase.createInMemory(appContext) else CineSwipeDatabase.create(appContext)
    }
    val database: CineSwipeDatabase by databaseLazy
    val settings: SettingsRepository by lazy {
        if (config.demo != null) {
            SettingsRepository(MemoryPreferencesStore(), MemoryPreferencesStore(), config.cipher())
        } else {
            SettingsRepository(appContext, config.cipher())
        }
    }

    private val httpClient: OkHttpClient by lazy {
        val client = TmdbApi.httpClient()
        config.demo?.let { catalog -> client.newBuilder().addInterceptor(DemoTmdbInterceptor(catalog)).build() } ?: client
    }
    private val tmdbApi: TmdbApi by lazy { TmdbApi.create(config.tmdbBaseUrl, httpClient) }
    /** Pendant la démo, un Plex et un Seerr fictifs répondent depuis l'appli. */
    private val homelabHttp: HomelabHttp by lazy {
        config.demo?.let { catalog ->
            HomelabHttp(HomelabHttp.defaultClient().newBuilder().addInterceptor(DemoHomelabInterceptor(catalog)).build())
        } ?: HomelabHttp()
    }

    val tmdb: TmdbRepository by lazy { TmdbRepository(tmdbApi, database, settings) }
    val library: LibraryRepository by lazy { LibraryRepository(database, onWatchedChanged = { arena.requestRerank() }) }
    val arena: ArenaRepository by lazy { ArenaRepository(database, scope = appScope) }
    val homelab: HomelabRepository by lazy {
        val deviceId = deviceId()
        HomelabRepository(
            database = database,
            settings = settings,
            tmdb = tmdb,
            jellyfin = JellyfinClient(homelabHttp, deviceId, BuildConfig.VERSION_NAME),
            plex = PlexClient(homelabHttp, deviceId),
            seerr = SeerrClient(homelabHttp),
            radarr = RadarrClient(homelabHttp),
            scheduler = config.homelabScheduler(appContext),
            scope = appScope,
            graceMs = config.requestGraceMs,
            notifier = config.watchedNotifier(appContext),
            demo = config.demo != null,
        )
    }
    val recos: RecoRepository by lazy { RecoRepository(database, tmdb, settings) }
    val census: CensusRepository by lazy { CensusRepository(database, tmdb, settings, recos, appScope) }
    val diagnostics: DiagnosticsRepository by lazy {
        DiagnosticsRepository(appContext, database, tmdb, settings, BuildConfig.VERSION_NAME)
    }
    val shared: SharedRepository by lazy { SharedRepository(tmdb) }
    val people: PeopleRepository by lazy { PeopleRepository(database, tmdb, settings) }
    val stats: StatsRepository by lazy { StatsRepository(database) }
    val lists: ListsRepository by lazy { ListsRepository(database) }
    val retro: RetroRepository by lazy { RetroRepository(database) }
    val backup: BackupRepository by lazy {
        BackupRepository(diagnostics, settings, config.backupStorage(appContext), config.backupScheduler(appContext))
    }
    val importer: ImportRepository by lazy {
        ImportRepository(database, tmdb, settings, appScope, afterImport = { recos.enrichTaste(RecoRepository.TASTE_ENRICH_AT_START) })
    }

    /** Tuto pas à pas (utilisé pendant la démo). */
    val tutorial: Tutorial by lazy { Tutorial() }
    val feed: FeedRepository by lazy {
        FeedRepository(database, tmdb, settings, homelab, recos, onWatchedChanged = { arena.requestRerank() })
    }

    /** Fin de la démo : les tâches de fond s'arrêtent et la base en mémoire disparaît. */
    fun close() {
        appScope.cancel()
        if (databaseLazy.isInitialized()) database.close()
    }

    /**
     * Identifiant de l'appareil pour Jellyfin et Plex (liste des appareils connectés). Depuis
     * Android 8, ANDROID_ID est propre à l'appli et à l'utilisateur : ce n'est pas un identifiant matériel.
     */
    @SuppressLint("HardwareIds")
    private fun deviceId(): String {
        val androidId = runCatching { Settings.Secure.getString(appContext.contentResolver, Settings.Secure.ANDROID_ID) }.getOrNull()
        return "cineswipe-" + (androidId?.filter { it.isLetterOrDigit() }?.takeIf { it.isNotEmpty() } ?: "android")
    }

    private companion object {
        const val TAG = "CineSwipe"
    }
}
