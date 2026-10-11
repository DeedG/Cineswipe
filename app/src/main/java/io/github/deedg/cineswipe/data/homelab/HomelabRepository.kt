package io.github.deedg.cineswipe.data.homelab

import io.github.deedg.cineswipe.data.db.CineSwipeDatabase
import io.github.deedg.cineswipe.data.db.MovieEntity
import io.github.deedg.cineswipe.data.db.OutboxEntity
import io.github.deedg.cineswipe.data.db.OutboxState
import io.github.deedg.cineswipe.data.db.OutboxType
import io.github.deedg.cineswipe.data.db.WatchSource
import io.github.deedg.cineswipe.data.repository.SettingsRepository
import io.github.deedg.cineswipe.data.repository.TmdbRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Mode homelab : synchronisation avec Jellyfin et Plex (films vus et disponibles), demandes à
 * Seerr ou Radarr. Chaque service est optionnel ; l'appli reste complète sans aucun.
 *
 * Les demandes passent par une file d'attente en base : une demande faite hors réseau part dès
 * que le service répond, et une demande annulée dans les secondes qui suivent ne part jamais.
 */
class HomelabRepository(
    private val database: CineSwipeDatabase,
    private val settings: SettingsRepository,
    private val tmdb: TmdbRepository,
    private val jellyfin: JellyfinClient,
    private val plex: PlexClient,
    private val seerr: SeerrClient,
    private val radarr: RadarrClient,
    private val scheduler: HomelabScheduler,
    /** Portée de l'appli : la synchro et les envois continuent quand on quitte l'écran. */
    private val scope: CoroutineScope,
    /** Délai avant l'envoi d'une demande, pendant lequel « Annuler » ne laisse aucune trace. */
    private val graceMs: Long = REQUEST_GRACE_MS,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Prévient après un film vu sur Plex ou Jellyfin. */
    private val notifier: WatchedNotifier = WatchedNotifier.None,
    /** Démo : les serveurs sont fictifs, « Regarder » ne fait que le dire. */
    private val demo: Boolean = false,
) {
    private val outbox = database.outboxDao()
    private val dao = database.homelabDao()

    /** Un seul envoi à la fois : une annulation attend la fin d'un envoi en cours. */
    private val outboxMutex = Mutex()
    private val syncMutex = Mutex()
    private val enrichMutex = Mutex()

    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing.asStateFlow()

    private val _events = MutableSharedFlow<HomelabEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<HomelabEvent> = _events.asSharedFlow()

    val config: Flow<HomelabConfig> get() = settings.homelabConfig
    val report: Flow<SyncReport?> get() = settings.homelabReport
    val pendingCount: Flow<Int> get() = outbox.observePendingCount()
    val failed: Flow<List<OutboxEntity>> get() = outbox.observeFailed()

    /** Synchro périodique tant qu'au moins un service est prêt. */
    fun start() {
        scope.launch {
            var scheduled = false
            settings.homelabConfig.map { it.anyReady }.distinctUntilChanged().collect { ready ->
                if (ready) {
                    scheduler.schedulePeriodicSync()
                    scheduled = true
                } else if (scheduled) {
                    scheduler.cancelPeriodicSync()
                    scheduled = false
                }
            }
        }
    }

    /** Appli au premier plan : demandes en attente envoyées, synchro si la dernière date un peu. */
    fun onForeground() {
        scope.launch {
            if (!settings.homelabConfig.first().anyReady) return@launch
            flushOutbox()
            val last = settings.homelabReport.first()
            if (last == null || clock() - last.finishedAt >= STALE_MS) sync()
        }
    }

    /** Bouton « Synchroniser » : la synchro continue même si on quitte l'écran. */
    fun syncInBackground() {
        scope.launch { sync() }
    }

    /**
     * Synchronise tous les services prêts, en parallèle. Renvoie null si rien n'est configuré ou
     * si une synchro est déjà en cours. Les fiches des nouveaux films sont ensuite complétées
     * depuis TMDB (affiches, genres), par lots.
     */
    suspend fun sync(): SyncReport? {
        if (!syncMutex.tryLock()) return null
        val report = try {
            val ready = settings.homelabConfig.first().all.filter { it.isReady }
            if (ready.isEmpty()) return null
            _syncing.value = true
            flushOutbox()
            val results = coroutineScope { ready.map { config -> async { fetchService(config) } }.awaitAll() }
            val now = clock()
            val merged = merge(results, pendingRequests = outbox.pendingRequestIds().toSet())
            val fresh = dao.applySync(merged.snapshot, now)
            notifyRecent(fresh, merged.snapshot, now)
            SyncReport(
                finishedAt = now,
                watched = merged.snapshot.watched.size,
                newlyWatched = fresh.size,
                available = merged.snapshot.available.size,
                requested = merged.snapshot.requested.size,
                unmatched = merged.unmatched,
                failures = results.mapNotNull { it.failure },
            ).also { settings.setHomelabReport(it) }
        } finally {
            _syncing.value = false
            syncMutex.unlock()
        }
        enrichMissing()
        return report
    }

    /**
     * Films tout juste vus sur Plex ou Jellyfin (dans les 2 derniers jours, d'après le serveur) :
     * une notification propose de les placer dans le classement. Les visionnages plus anciens,
     * repris à la première synchro, ne préviennent pas.
     */
    private suspend fun notifyRecent(fresh: List<FreshWatch>, snapshot: HomelabSnapshot, now: Long) {
        val recent = recentWatches(fresh, now)
        if (recent.isEmpty() || !settings.watchedNotifications.first()) return
        val movies = database.movieDao()
        val notices = recent.sortedByDescending { it.watchedAt }.map { watch ->
            val title = movies.get(watch.tmdbId)?.title?.takeIf { it != "?" } ?: snapshot.titles[watch.tmdbId]?.title ?: "?"
            WatchedNotice(watch.tmdbId, title, watch.source)
        }
        runCatching { notifier.show(notices) }
    }

    /**
     * Demande un film au service choisi (Seerr ou Radarr). Le film passe tout de suite dans la
     * liste À voir, marqué « demandé » ; l'envoi part après [graceMs]. Null sans service de demandes.
     */
    suspend fun requestMovie(movie: MovieEntity): RequestTicket? {
        val service = settings.homelabConfig.first().requestService ?: return null
        val now = clock()
        val previous = dao.applyRequest(movie, now)
        val id = outbox.insert(
            OutboxEntity(
                type = OutboxType.REQUEST,
                service = service,
                tmdbId = movie.tmdbId,
                title = movie.title,
                year = movie.year,
                createdAt = now,
                updatedAt = now,
            ),
        )
        scope.launch {
            delay(graceMs)
            flushOutbox()
        }
        scheduler.scheduleOutboxFlush(graceMs)
        return RequestTicket(outboxId = id, service = service, previous = previous)
    }

    /**
     * Annule une demande (bouton Annuler) : supprimée si elle n'est pas encore partie, sinon
     * retirée du service (demande Seerr supprimée, film retiré de Radarr sans toucher aux fichiers).
     */
    /**
     * Annulation sans attendre : un envoi en cours vers un service lent garde la file d'attente
     * verrouillée, et l'écran n'a pas à l'attendre. L'annulation suit dès que la file est libre.
     */
    fun cancelLater(outboxId: Long) {
        scope.launch { cancel(outboxId) }
    }

    suspend fun cancel(outboxId: Long) {
        var cancelQueued = false
        outboxMutex.withLock {
            val entry = outbox.get(outboxId) ?: return@withLock
            outbox.delete(entry.id)
            val remoteId = entry.remoteId
            if (entry.state == OutboxState.SENT && remoteId != null && remoteId > 0) {
                val now = clock()
                outbox.insert(entry.copy(id = 0, type = OutboxType.CANCEL, state = OutboxState.PENDING, attempts = 0, lastError = null, createdAt = now, updatedAt = now))
                cancelQueued = true
            }
        }
        if (cancelQueued) scope.launch { flushOutbox() }
    }

    /**
     * Envoie les demandes et annulations en attente. Renvoie le nombre d'envois encore à faire
     * (service injoignable, ou demande trop récente pour partir).
     */
    suspend fun flushOutbox(): Int = outboxMutex.withLock {
        val now = clock()
        outbox.purgeFinished(now - FINISHED_RETENTION_MS)
        val pending = outbox.pending()
        if (pending.isEmpty()) return@withLock 0
        val config = settings.homelabConfig.first()
        var remaining = 0
        var retryLater = false
        for (entry in pending) {
            if (entry.type == OutboxType.REQUEST && now - entry.createdAt < graceMs) {
                remaining++
                continue
            }
            if (!send(entry, config.of(entry.service))) {
                remaining++
                retryLater = true
            }
        }
        if (retryLater) scheduler.scheduleOutboxFlush()
        remaining
    }

    /** Relance les demandes refusées, vers le service de demandes actuel. */
    fun retryFailed() {
        scope.launch {
            val target = settings.homelabConfig.first().requestService
            val now = clock()
            outboxMutex.withLock {
                outbox.failed().forEach { entry ->
                    val service = if (entry.type == OutboxType.REQUEST) target ?: entry.service else entry.service
                    outbox.update(
                        entry.copy(
                            service = service,
                            state = OutboxState.PENDING,
                            attempts = 0,
                            lastError = null,
                            createdAt = now - graceMs,
                            updatedAt = now,
                        ),
                    )
                }
            }
            flushOutbox()
        }
    }

    fun deleteFailed(id: Long) {
        scope.launch { outboxMutex.withLock { outbox.delete(id) } }
    }

    /** Vérifie l'adresse et la clé d'un service, et récupère les choix à proposer. */
    suspend fun testConnection(config: ServiceConfig): ConnectionInfo {
        val service = config.service
        val url = HomelabUrls.parse(config.url) ?: throw HomelabException(service, HomelabException.Kind.BAD_URL, config.url)
        val headers = config.headerMap()
        val key = config.apiKey?.trim().orEmpty()
        fun missingKey() = HomelabException(service, HomelabException.Kind.NOT_CONFIGURED, "Clé absente")
        fun notThisService() = HomelabException(service, HomelabException.Kind.INVALID_RESPONSE, "Ce n'est pas ${service.displayName}")
        return when (service) {
            HomelabService.JELLYFIN -> {
                val info = jellyfin.publicInfo(url, headers)
                if (info.version == null && info.serverName == null) throw notThisService()
                if (key.isEmpty()) throw missingKey()
                when (config.jellyfinAuthMode) {
                    JellyfinAuthMode.API_KEY -> ConnectionInfo(info.serverName, info.version, jellyfinUsers = jellyfin.users(url, headers, key))
                    JellyfinAuthMode.LOGIN -> {
                        val me = jellyfin.me(url, headers, key)
                        ConnectionInfo(info.serverName, info.version, account = me.name, jellyfinUsers = listOf(me))
                    }
                }
            }
            HomelabService.PLEX -> {
                if (key.isEmpty()) throw missingKey()
                val (address, identity) = try {
                    url to plex.identity(url, headers, key)
                } catch (error: HomelabException) {
                    // Serveur qui exige le https (« Connexions sécurisées : requises ») : adresse plex.direct de la même IP.
                    val secure = if (error.kind == HomelabException.Kind.BAD_URL) null else plex.secureUrl(url)
                    if (secure == null) throw error
                    secure to plex.identity(secure, headers, key)
                }
                if (identity.version == null && identity.friendlyName == null) throw notThisService()
                ConnectionInfo(
                    serverName = identity.friendlyName,
                    version = identity.version,
                    plexSections = plex.sections(address, headers, key),
                    resolvedUrl = address.toString().trimEnd('/').takeIf { address != url },
                )
            }
            HomelabService.SEERR -> {
                val status = seerr.status(url, headers)
                if (status.version == null) throw notThisService()
                if (key.isEmpty()) throw missingKey()
                val auth = seerr.headers(config)
                val me = seerr.me(url, auth)
                // Serveurs Radarr de Seerr : facultatif (un utilisateur peut ne pas y avoir accès).
                val targets = try {
                    seerr.targets(url, auth)
                } catch (error: HomelabException) {
                    emptyList()
                }
                ConnectionInfo(service.displayName, status.version, account = me.label, seerrTargets = targets)
            }
            HomelabService.RADARR -> {
                if (key.isEmpty()) throw missingKey()
                val status = radarr.status(url, headers, key)
                if (status.version == null) throw notThisService()
                ConnectionInfo(
                    serverName = status.instanceName ?: status.appName,
                    version = status.version,
                    radarrProfiles = radarr.qualityProfiles(url, headers, key),
                    radarrRootFolders = radarr.rootFolders(url, headers, key),
                )
            }
        }
    }

    /** Connexion avec un compte Plex (navigateur), étape 1. */
    /** Index des films de chaque serveur média (tmdbId → identifiant sur le serveur), gardé quelques minutes. */
    private class ServerIndex(val loadedAt: Long, val items: Map<Long, String>, val machineIdentifier: String?)

    private val serverIndexes = HashMap<HomelabService, ServerIndex>()
    private val indexMutex = Mutex()

    /** Serveurs média configurés (Plex, Jellyfin), dans l'ordre des boutons « Regarder ». */
    val mediaServers: Flow<List<HomelabService>>
        get() = settings.homelabConfig.map { config -> listOf(config.plex, config.jellyfin).filter { it.isReady }.map { it.service } }

    /**
     * Pour chaque film, les serveurs média qui l'ont (d'après la liste des films de chaque
     * serveur, relue au plus toutes les 10 min). Un serveur injoignable n'est pas compté : null
     * pour lui, et l'interface garde son bouton (l'erreur viendra au toucher).
     */
    suspend fun mediaServersHaving(ids: Collection<Long>): Map<HomelabService, Set<Long>?> {
        val config = settings.homelabConfig.first()
        return listOf(config.plex, config.jellyfin).filter { it.isReady }.associate { service ->
            val index = indexOrNull(service)
            service.service to index?.items?.keys?.filterTo(HashSet()) { it in ids }
        }
    }

    /**
     * Où regarder un film sur [service] : dans l'appli Plex (sa fiche watch.plex.tv, qui propose
     * la lecture depuis le serveur) avec repli sur Plex Web, ou dans Jellyfin. Null si le serveur
     * n'a pas le film ou ne répond pas.
     */
    suspend fun watchLink(service: HomelabService, tmdbId: Long): WatchLink? {
        val config = settings.homelabConfig.first().of(service).takeIf { it.isReady } ?: return null
        val index = indexOrNull(config) ?: return null
        val itemId = index.items[tmdbId] ?: return null
        if (demo) return WatchLink(service, appUri = null, appPackage = null, webUrl = "", demo = true)
        return when (service) {
            HomelabService.PLEX -> {
                val machine = index.machineIdentifier ?: return null
                val slug = runCatching { plex.discoverSlug(tmdbId, config) }.getOrNull()
                WatchLink(
                    service = HomelabService.PLEX,
                    appUri = slug?.let(PlexClient::watchUrl),
                    appPackage = PlexClient.ANDROID_PACKAGE,
                    webUrl = PlexClient.webUrl(machine, itemId),
                )
            }
            HomelabService.JELLYFIN -> WatchLink(
                service = HomelabService.JELLYFIN,
                appUri = null,
                appPackage = null,
                webUrl = JellyfinClient.webUrl(config.url, itemId) ?: return null,
            )
            else -> null
        }
    }

    private suspend fun indexOrNull(config: ServiceConfig): ServerIndex? = try {
        serverIndex(config)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        null
    }

    private suspend fun serverIndex(config: ServiceConfig): ServerIndex = indexMutex.withLock {
        val now = clock()
        serverIndexes[config.service]?.takeIf { now - it.loadedAt < INDEX_TTL_MS }?.let { return@withLock it }
        val movies = when (config.service) {
            HomelabService.PLEX -> plex.movies(config)
            HomelabService.JELLYFIN -> jellyfin.movies(config)
            else -> emptyList()
        }
        val machine = if (config.service == HomelabService.PLEX) plex.machineIdentifier(config) else null
        val items = HashMap<Long, String>()
        movies.forEach { movie ->
            val id = movie.tmdbId ?: return@forEach
            val item = movie.itemId ?: return@forEach
            items.putIfAbsent(id, item)
        }
        ServerIndex(now, items, machine).also { serverIndexes[config.service] = it }
    }

    suspend fun plexCreatePin(): PlexPin = plex.createPin()

    /**
     * Étape 2 : attend que le code soit validé dans le navigateur (vérification toutes les 2 s,
     * 5 min au plus) et renvoie le jeton du compte.
     */
    suspend fun plexAwaitToken(pin: PlexPin): String {
        val deadline = clock() + PLEX_LOGIN_TIMEOUT_MS
        while (clock() < deadline) {
            delay(PLEX_POLL_MS)
            val token = try {
                plex.pinToken(pin.id)
            } catch (error: HomelabException) {
                if (error.kind == HomelabException.Kind.UNREACHABLE) null else throw error
            }
            if (token != null) return token
        }
        throw HomelabException(HomelabService.PLEX, HomelabException.Kind.LOGIN_TIMEOUT, "Code Plex non validé")
    }

    suspend fun plexServers(accountToken: String): List<PlexServer> = plex.servers(accountToken)

    /** Connexion d'un utilisateur à Seerr : ses demandes seront à son nom. */
    suspend fun seerrLogin(rawUrl: String, headers: String, mode: SeerrAuthMode, identifier: String, secret: String): SeerrSession {
        val url = HomelabUrls.parse(rawUrl) ?: throw HomelabException(HomelabService.SEERR, HomelabException.Kind.BAD_URL, rawUrl)
        return seerr.login(url, HomelabUrls.parseHeaders(headers), mode, identifier, secret)
    }

    /** Connexion Jellyfin par identifiant : le mot de passe sert une fois, seul le jeton est gardé. */
    suspend fun jellyfinLogin(rawUrl: String, headers: String, username: String, password: String): JellyfinLogin {
        val url = HomelabUrls.parse(rawUrl) ?: throw HomelabException(HomelabService.JELLYFIN, HomelabException.Kind.BAD_URL, rawUrl)
        return jellyfin.login(url, HomelabUrls.parseHeaders(headers), username.trim(), password)
    }

    /** Enregistre les réglages d'un service ; une synchro part aussitôt s'il est prêt. */
    suspend fun saveService(config: ServiceConfig) {
        settings.saveService(config)
        if (config.isReady) syncInBackground()
    }

    suspend fun clearService(service: HomelabService) {
        settings.clearService(service)
    }

    suspend fun setRequestTarget(target: RequestTarget) {
        settings.setRequestTarget(target)
    }

    /** Envoie une demande ou une annulation. Faux si c'est à refaire plus tard. */
    private suspend fun send(entry: OutboxEntity, config: ServiceConfig): Boolean {
        val now = clock()
        try {
            if (!config.isReady) throw HomelabException(entry.service, HomelabException.Kind.NOT_CONFIGURED, "${entry.service.displayName} n'est pas configuré")
            when (entry.type) {
                OutboxType.REQUEST -> {
                    val (remoteId, already) = when (entry.service) {
                        HomelabService.SEERR -> seerr.request(config, entry.tmdbId).let { it.requestId to it.alreadyRequested }
                        HomelabService.RADARR -> radarr.addMovie(config, entry.tmdbId).let { it.radarrId to it.alreadyPresent }
                        HomelabService.JELLYFIN, HomelabService.PLEX ->
                            throw HomelabException(entry.service, HomelabException.Kind.NOT_CONFIGURED, "Pas de demandes sur ${entry.service.displayName}")
                    }
                    // Un film déjà demandé ou déjà présent ne doit pas être retiré par « Annuler ».
                    outbox.update(
                        entry.copy(
                            state = OutboxState.SENT,
                            remoteId = remoteId.takeIf { !already && it > 0 },
                            attempts = entry.attempts + 1,
                            lastError = null,
                            updatedAt = now,
                        ),
                    )
                    if (already) _events.tryEmit(HomelabEvent.AlreadyRequested(entry.service, entry.title))
                }
                OutboxType.CANCEL -> {
                    val remoteId = entry.remoteId
                    if (remoteId != null && remoteId > 0) {
                        when (entry.service) {
                            HomelabService.SEERR -> seerr.deleteRequest(config, remoteId)
                            HomelabService.RADARR -> radarr.deleteMovie(config, remoteId)
                            HomelabService.JELLYFIN, HomelabService.PLEX -> Unit
                        }
                    }
                    outbox.delete(entry.id)
                }
            }
            return true
        } catch (error: CancellationException) {
            throw error
        } catch (error: HomelabException) {
            return onSendError(entry, error, now)
        }
    }

    private suspend fun onSendError(entry: OutboxEntity, error: HomelabException, now: Long): Boolean {
        val attempts = entry.attempts + 1
        val reason = error.code?.let { "${error.kind.name}:$it" } ?: error.kind.name
        if (entry.type == OutboxType.CANCEL && error.kind == HomelabException.Kind.NOT_FOUND) {
            // Déjà retiré du service : rien à annuler.
            outbox.delete(entry.id)
            return true
        }
        if (error.isTransient && attempts < MAX_ATTEMPTS) {
            outbox.update(entry.copy(attempts = attempts, lastError = reason, updatedAt = now))
            if (attempts == 1 && entry.type == OutboxType.REQUEST) _events.tryEmit(HomelabEvent.Queued(entry.service, entry.title))
            return false
        }
        outbox.update(entry.copy(state = OutboxState.FAILED, attempts = attempts, lastError = reason, updatedAt = now))
        _events.tryEmit(
            when (entry.type) {
                OutboxType.REQUEST -> HomelabEvent.Failed(entry.service, entry.title, error.kind, error.serverMessage)
                OutboxType.CANCEL -> HomelabEvent.CancelFailed(entry.service, entry.title)
            },
        )
        return true
    }

    /** Ce qu'un service a renvoyé pendant une synchro. */
    private class ServiceData(
        val watched: Map<Long, WatchedOnServer> = emptyMap(),
        val available: Set<Long> = emptySet(),
        val requested: Set<Long> = emptySet(),
        val titles: Map<Long, ServerTitle> = emptyMap(),
        val unmatched: Int = 0,
    )

    private class ServiceResult(val data: ServiceData?, val failure: SyncFailure?)

    private class Merged(val snapshot: HomelabSnapshot, val unmatched: Int)

    private suspend fun fetchService(config: ServiceConfig): ServiceResult = try {
        ServiceResult(fetchData(config), failure = null)
    } catch (error: CancellationException) {
        throw error
    } catch (error: HomelabException) {
        ServiceResult(null, SyncFailure(config.service, error.kind, error.message))
    } catch (error: Exception) {
        ServiceResult(null, SyncFailure(config.service, HomelabException.Kind.INVALID_RESPONSE, error.message ?: error.javaClass.simpleName))
    }

    private suspend fun fetchData(config: ServiceConfig): ServiceData = when (config.service) {
        HomelabService.JELLYFIN -> mediaServerData(jellyfin.movies(config), WatchSource.JELLYFIN)
        HomelabService.PLEX -> mediaServerData(plex.movies(config), WatchSource.PLEX)
        HomelabService.RADARR -> {
            val movies = radarr.movies(config).filter { it.tmdbId > 0 }
            ServiceData(
                available = movies.filter { it.hasFile }.mapTo(HashSet()) { it.tmdbId },
                // Surveillé sans fichier : demandé, en attente de téléchargement.
                requested = movies.filter { it.monitored && !it.hasFile }.mapTo(HashSet()) { it.tmdbId },
                titles = movies.associate { it.tmdbId to ServerTitle(it.title.orEmpty(), it.year?.takeIf { year -> year > 0 }) },
            )
        }
        HomelabService.SEERR -> {
            val requested = seerr.movieRequests(config)
                .filter { it.status == SeerrClient.REQUEST_PENDING || it.status == SeerrClient.REQUEST_APPROVED }
                .mapNotNullTo(HashSet()) { request -> request.media?.takeIf { it.status != SeerrClient.MEDIA_AVAILABLE }?.tmdbId }
            ServiceData(available = seerr.availableMovies(config), requested = requested)
        }
    }

    private fun mediaServerData(movies: List<ServerMovie>, source: WatchSource): ServiceData {
        val watched = HashMap<Long, WatchedOnServer>()
        val available = HashSet<Long>()
        val titles = HashMap<Long, ServerTitle>()
        var unmatched = 0
        for (movie in movies) {
            val id = movie.tmdbId
            if (id == null) {
                unmatched++
                continue
            }
            available += id
            if (id !in titles) titles[id] = ServerTitle(movie.title, movie.year)
            // Plusieurs versions du même film (4K, 1080p) : vu si l'une l'est.
            if (movie.watched) watched[id] = latest(watched[id], WatchedOnServer(source, movie.lastWatchedAt))
        }
        return ServiceData(watched = watched, available = available, titles = titles, unmatched = unmatched)
    }

    private fun merge(results: List<ServiceResult>, pendingRequests: Set<Long>): Merged {
        val watched = HashMap<Long, WatchedOnServer>()
        val available = HashSet<Long>()
        val requested = HashSet<Long>()
        val titles = HashMap<Long, ServerTitle>()
        var unmatched = 0
        for (data in results.mapNotNull { it.data }) {
            data.watched.forEach { (id, info) -> watched[id] = latest(watched[id], info) }
            available += data.available
            requested += data.requested
            data.titles.forEach { (id, title) -> if (id !in titles) titles[id] = title }
            unmatched += data.unmatched
        }
        requested -= available
        val snapshot = HomelabSnapshot(
            watched = watched,
            available = available,
            requested = requested,
            titles = titles,
            complete = results.all { it.failure == null },
            pendingRequests = pendingRequests,
        )
        return Merged(snapshot, unmatched)
    }

    /** Visionnage le plus récent connu. */
    private fun latest(current: WatchedOnServer?, candidate: WatchedOnServer): WatchedOnServer {
        if (current == null) return candidate
        val currentAt = current.watchedAt ?: return if (candidate.watchedAt != null) candidate else current
        val candidateAt = candidate.watchedAt ?: return current
        return if (candidateAt > currentAt) candidate else current
    }

    /** Complète depuis TMDB les fiches partielles des films du homelab, par lots. */
    private suspend fun enrichMissing() {
        if (!enrichMutex.tryLock()) return
        try {
            if (settings.tmdbApiKey.first() == null) return
            for (chunk in dao.moviesToEnrich(ENRICH_LIMIT).chunked(ENRICH_CHUNK)) {
                val done = coroutineScope { chunk.map { id -> async { enrichOne(id) } }.awaitAll() }
                // Rien n'a marché (TMDB injoignable) : on reprendra à la prochaine synchro.
                if (done.none { it }) break
            }
        } finally {
            enrichMutex.unlock()
        }
    }

    private suspend fun enrichOne(tmdbId: Long): Boolean = try {
        tmdb.ensureDetails(tmdbId).fetchedAt != null
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        false
    }

    companion object {
        /** Liste des films d'un serveur gardée 10 min pour ouvrir un film dans Plex ou Jellyfin. */
        const val INDEX_TTL_MS = 10L * 60 * 1000

        /** Une demande part 6 s après le swipe : « Annuler » juste après ne laisse aucune trace. */
        const val REQUEST_GRACE_MS = 6_000L

        /** Un film vu sur le serveur depuis moins longtemps que ça déclenche une notification. */
        const val RECENT_WATCH_MS = 2L * 24 * 60 * 60 * 1000

        /** Visionnages récents d'après le serveur (sans date : on ne sait pas, pas de notification). */
        fun recentWatches(fresh: List<FreshWatch>, now: Long): List<FreshWatch> =
            fresh.filter { watch -> watch.watchedAt?.let { now - it in 0..RECENT_WATCH_MS } == true }

        /** À l'ouverture de l'appli, synchro si la dernière a plus de 15 min. */
        const val STALE_MS = 15L * 60 * 1000

        /** Les demandes envoyées restent annulables une journée. */
        const val FINISHED_RETENTION_MS = 24L * 60 * 60 * 1000

        /** Au-delà, une demande qui n'a jamais pu partir passe en échec. */
        const val MAX_ATTEMPTS = 20

        const val ENRICH_LIMIT = 400
        const val ENRICH_CHUNK = 8
        const val PLEX_LOGIN_TIMEOUT_MS = 5L * 60 * 1000
        const val PLEX_POLL_MS = 2_000L
    }
}
