package io.github.deedg.cineswipe.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.deedg.cineswipe.core.SecretCipher
import io.github.deedg.cineswipe.data.homelab.HomelabConfig
import io.github.deedg.cineswipe.data.homelab.HomelabService
import io.github.deedg.cineswipe.data.homelab.JellyfinAuthMode
import io.github.deedg.cineswipe.data.homelab.RequestTarget
import io.github.deedg.cineswipe.data.homelab.SeerrAuthMode
import io.github.deedg.cineswipe.data.homelab.ServiceConfig
import io.github.deedg.cineswipe.data.homelab.SyncReport
import io.github.deedg.cineswipe.data.model.MediaKind
import io.github.deedg.cineswipe.domain.SeasonWatchState
import io.github.deedg.cineswipe.domain.reco.RecoNudge
import io.github.deedg.cineswipe.domain.reco.RecoNudges
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

// Deux fichiers séparés : les clés chiffrées sont exclues des sauvegardes Android.
private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(name = "settings")
private val Context.secretsStore: DataStore<Preferences> by preferencesDataStore(name = "secrets")

/**
 * Réglages de l'appli. [settings] et [secrets] sont les fichiers DataStore du téléphone, ou des
 * réglages en mémoire pour la démo.
 */
class SettingsRepository(
    private val settings: DataStore<Preferences>,
    private val secrets: DataStore<Preferences>,
    private val cipher: SecretCipher,
    /** Monde de ces réglages : l'état du recensement et le compte rendu de synchro lui sont propres, le reste est commun. */
    private val world: MediaKind = MediaKind.FILMS,
    /** Clé TMDB intégrée à l'appli (build officiel), utilisée tant que l'utilisateur n'a pas mis la sienne. */
    private val builtinTmdbKey: String? = null,
) {
    constructor(context: Context, cipher: SecretCipher, world: MediaKind = MediaKind.FILMS, builtinTmdbKey: String? = null) :
        this(context.applicationContext.settingsStore, context.applicationContext.secretsStore, cipher, world, builtinTmdbKey)

    private val homelabReportKey = worldKey("homelab_last_report")
    private val censusStateKey = worldKey("census_state")

    /** Invitations des recos (recensement, duels) refusées ou suivies, propres à chaque monde. */
    private val recoNudgesKey = worldKey("reco_nudges")

    /** Accueil (et démo) vu : une fois par monde, le mode séries a le sien. */
    private val onboardingKey = booleanPreferencesKey(if (world.isSeries) "series_onboarding_done" else "onboarding_done")

    private fun worldKey(name: String) = stringPreferencesKey(if (world.isSeries) "series_$name" else name)

    /** Dernier monde ouvert (l'appli y rouvre). Commun aux deux mondes. */
    val mediaKind: Flow<MediaKind> = settings.data.map { prefs -> MediaKind.fromName(prefs[MEDIA_KIND]) }.distinctUntilChanged()

    suspend fun setMediaKind(kind: MediaKind) {
        settings.edit { prefs -> prefs[MEDIA_KIND] = kind.name }
    }

    private val json = Json { ignoreUnknownKeys = true }

    /** Clé TMDB de l'utilisateur en clair, ou null si absente ou illisible (ex. restaurée sur un autre téléphone). */
    val userTmdbApiKey: Flow<String?> = secrets.data.map { prefs -> prefs[TMDB_API_KEY]?.let(::decryptKey) }

    /** Clé TMDB en service : celle de l'utilisateur, sinon la clé intégrée (null si aucune). */
    val tmdbApiKey: Flow<String?> = userTmdbApiKey.map { it ?: builtinTmdbKey?.takeIf(String::isNotBlank) }

    /** L'appli a une clé intégrée : personne n'a besoin de créer la sienne. */
    val hasBuiltinTmdbKey: Boolean get() = !builtinTmdbKey.isNullOrBlank()

    /**
     * Dernière clé déchiffrée, gardée en mémoire : chaque appel TMDB lit la clé, et le Keystore
     * n'a pas à déchiffrer la même valeur des centaines de fois (import, enrichissement).
     */
    @Volatile
    private var decryptedKey: Pair<String, String>? = null

    private fun decryptKey(encoded: String): String? {
        decryptedKey?.takeIf { it.first == encoded }?.let { return it.second }
        // Une erreur passagère du Keystore ne doit pas passer pour une clé absente : un second essai.
        val plain = cipher.decrypt(encoded) ?: cipher.decrypt(encoded) ?: return null
        decryptedKey = encoded to plain
        return plain
    }

    val language: Flow<String> = settings.data.map { prefs -> prefs[LANGUAGE] ?: DEFAULT_LANGUAGE }

    /**
     * Réglages du homelab tels qu'enregistrés (communs aux deux mondes), pour les écrans de réglage.
     * Les clés et en-têtes sont déchiffrés.
     */
    val savedHomelabConfig: Flow<HomelabConfig> = combine(settings.data, secrets.data) { prefs, secretPrefs ->
        HomelabConfig(
            jellyfin = readService(HomelabService.JELLYFIN, prefs, secretPrefs),
            plex = readService(HomelabService.PLEX, prefs, secretPrefs),
            seerr = readService(HomelabService.SEERR, prefs, secretPrefs),
            radarr = readService(HomelabService.RADARR, prefs, secretPrefs),
            requestTarget = prefs[REQUEST_TARGET]?.let { runCatching { RequestTarget.valueOf(it) }.getOrNull() } ?: RequestTarget.AUTO,
            sonarr = readService(HomelabService.SONARR, prefs, secretPrefs),
            series = world.isSeries,
        )
    }.distinctUntilChanged()

    /** Homelab en service dans ce monde (Radarr pour les films, Sonarr pour les séries). */
    val homelabConfig: Flow<HomelabConfig> get() = savedHomelabConfig

    /**
     * Vrai quand au moins un service du homelab est prêt. Sans homelab, la disponibilité des films
     * n'a pas de sens : badges « Dispo », filtre et compteur sont masqués, et le feed ne s'en sert pas.
     */
    val homelabConnected: Flow<Boolean> = homelabConfig.map { it.anyReady }.distinctUntilChanged()

    val homelabReport: Flow<SyncReport?> = settings.data.map { prefs ->
        prefs[homelabReportKey]?.let { runCatching { json.decodeFromString(SyncReport.serializer(), it) }.getOrNull() }
    }.distinctUntilChanged()

    /** Mémoire du recensement (modèle appris, avancement dans les listes TMDB). */
    val censusState: Flow<CensusState> = settings.data.map { prefs ->
        prefs[censusStateKey]?.let { runCatching { json.decodeFromString(CensusState.serializer(), it) }.getOrNull() } ?: CensusState()
    }.distinctUntilChanged()

    /** Le recensement s'ouvre en cartes plutôt qu'en grille. */
    val censusCardsMode: Flow<Boolean> = settings.data.map { prefs -> prefs[CENSUS_CARDS] ?: false }.distinctUntilChanged()

    /** Outils de diagnostic débloqués (sept touchers sur le numéro de version). */
    val devToolsEnabled: Flow<Boolean> = settings.data.map { prefs -> prefs[DEV_TOOLS] ?: false }.distinctUntilChanged()

    /** Dossier de la sauvegarde automatique (arborescence choisie par l'utilisateur), ou null si désactivée. */
    val backupFolder: Flow<String?> = settings.data.map { prefs -> prefs[BACKUP_FOLDER] }.distinctUntilChanged()

    /** Dernière sauvegarde automatique : date, fichier, ou erreur. */
    val backupStatus: Flow<BackupStatus> = settings.data.map { prefs ->
        BackupStatus(lastAt = prefs[BACKUP_LAST_AT], lastFile = prefs[BACKUP_LAST_FILE], error = prefs[BACKUP_ERROR])
    }.distinctUntilChanged()

    suspend fun setBackupFolder(uri: String?) {
        settings.edit { prefs ->
            prefs.setOrRemove(BACKUP_FOLDER, uri)
            prefs.remove(BACKUP_ERROR)
        }
    }

    suspend fun recordBackup(at: Long, file: String?, error: String?) {
        settings.edit { prefs ->
            if (error == null) {
                prefs[BACKUP_LAST_AT] = at
                prefs.setOrRemove(BACKUP_LAST_FILE, file)
                prefs.remove(BACKUP_ERROR)
            } else {
                prefs[BACKUP_ERROR] = error
            }
        }
    }

    /** L'écran d'accueil du premier lancement a été passé (démo essayée ou « Commencer »). */
    val onboardingDone: Flow<Boolean> = settings.data.map { prefs -> prefs[onboardingKey] ?: false }.distinctUntilChanged()

    /** Prévenir après un film vu sur Plex ou Jellyfin, pour le placer dans le classement (oui par défaut). */
    val watchedNotifications: Flow<Boolean> = settings.data.map { prefs -> prefs[WATCHED_NOTIFICATIONS] ?: true }.distinctUntilChanged()

    suspend fun setWatchedNotifications(enabled: Boolean) {
        settings.edit { prefs -> prefs[WATCHED_NOTIFICATIONS] = enabled }
    }

    /** Dernière version publiée connue (JSON), date de la dernière vérification, version écartée du profil. */
    val latestUpdate: Flow<String?> = settings.data.map { prefs -> prefs[LATEST_UPDATE] }.distinctUntilChanged()
    val updateCheckedAt: Flow<Long> = settings.data.map { prefs -> prefs[UPDATE_CHECKED_AT] ?: 0L }.distinctUntilChanged()
    val dismissedUpdate: Flow<Int> = settings.data.map { prefs -> prefs[DISMISSED_UPDATE] ?: 0 }.distinctUntilChanged()

    suspend fun setLatestUpdate(value: String) {
        settings.edit { prefs -> prefs[LATEST_UPDATE] = value }
    }

    suspend fun setUpdateCheckedAt(at: Long) {
        settings.edit { prefs -> prefs[UPDATE_CHECKED_AT] = at }
    }

    suspend fun setDismissedUpdate(versionCode: Int) {
        settings.edit { prefs -> prefs[DISMISSED_UPDATE] = versionCode }
    }

    /** Prénom affiché dans le QR code de goûts (comparer avec un ami). */
    val tasteName: Flow<String> = settings.data.map { prefs -> prefs[TASTE_NAME].orEmpty() }.distinctUntilChanged()

    suspend fun setTasteName(name: String) {
        settings.edit { prefs -> prefs[TASTE_NAME] = name.take(24) }
    }

    /** Prévenir quand une série suivie a une nouvelle saison (oui par défaut). */
    val newSeasonNotifications: Flow<Boolean> = settings.data.map { prefs -> prefs[NEW_SEASON_NOTIFICATIONS] ?: true }.distinctUntilChanged()

    suspend fun setNewSeasonNotifications(enabled: Boolean) {
        settings.edit { prefs -> prefs[NEW_SEASON_NOTIFICATIONS] = enabled }
    }

    /** Suivi des nouvelles saisons : saisons connues de chaque série et alertes déjà envoyées. */
    val seasonWatch: Flow<SeasonWatchState> = settings.data.map { prefs ->
        prefs[SEASON_WATCH]?.let { runCatching { json.decodeFromString(SeasonWatchState.serializer(), it) }.getOrNull() } ?: SeasonWatchState()
    }

    suspend fun setSeasonWatch(state: SeasonWatchState) {
        settings.edit { prefs -> prefs[SEASON_WATCH] = json.encodeToString(SeasonWatchState.serializer(), state) }
    }

    /** Jusqu'à quand chaque invitation des recos reste cachée. */
    val recoNudgesHidden: Flow<Map<RecoNudge, Long>> =
        settings.data.map { prefs -> RecoNudges.decode(prefs[recoNudgesKey]) }.distinctUntilChanged()

    suspend fun hideRecoNudge(nudge: RecoNudge, until: Long) {
        settings.edit { prefs -> prefs[recoNudgesKey] = RecoNudges.encode(RecoNudges.decode(prefs[recoNudgesKey]) + (nudge to until)) }
    }

    suspend fun setOnboardingDone(done: Boolean) {
        settings.edit { prefs -> prefs[onboardingKey] = done }
    }

    suspend fun updateCensusState(transform: (CensusState) -> CensusState) {
        settings.edit { prefs ->
            val current = prefs[censusStateKey]?.let { runCatching { json.decodeFromString(CensusState.serializer(), it) }.getOrNull() }
                ?: CensusState()
            prefs[censusStateKey] = json.encodeToString(CensusState.serializer(), transform(current))
        }
    }

    suspend fun setCensusCardsMode(cards: Boolean) {
        settings.edit { prefs -> prefs[CENSUS_CARDS] = cards }
    }

    suspend fun setDevToolsEnabled(enabled: Boolean) {
        settings.edit { prefs -> prefs[DEV_TOOLS] = enabled }
    }

    suspend fun setTmdbApiKey(value: String?) {
        secrets.edit { prefs ->
            val key = value?.trim().orEmpty()
            if (key.isEmpty()) prefs.remove(TMDB_API_KEY) else prefs[TMDB_API_KEY] = cipher.encrypt(key)
        }
    }

    suspend fun setLanguage(tag: String) {
        settings.edit { prefs -> prefs[LANGUAGE] = tag }
    }

    suspend fun saveService(config: ServiceConfig) {
        val keys = ServiceKeys(config.service)
        settings.edit { prefs ->
            prefs[keys.enabled] = config.enabled
            prefs[keys.url] = config.url.trim()
            prefs[keys.authMode] = when (config.service) {
                HomelabService.JELLYFIN -> config.jellyfinAuthMode.name
                HomelabService.SEERR -> config.seerrAuthMode.name
                HomelabService.PLEX, HomelabService.RADARR, HomelabService.SONARR -> JellyfinAuthMode.API_KEY.name
            }
            prefs.setOrRemove(keys.userId, config.userId)
            prefs.setOrRemove(keys.userName, config.userName)
            prefs[keys.sections] = config.plexSections.joinToString(",")
            val profile = config.qualityProfileId
            if (profile == null) prefs.remove(keys.qualityProfile) else prefs[keys.qualityProfile] = profile
            prefs.setOrRemove(keys.rootFolder, config.rootFolder)
            prefs[keys.searchOnAdd] = config.searchOnAdd
            val server = config.targetServerId
            if (server == null) prefs.remove(keys.targetServer) else prefs[keys.targetServer] = server
            prefs[keys.target4k] = config.targetServer4k
        }
        secrets.edit { prefs ->
            val key = config.apiKey?.trim().orEmpty()
            if (key.isEmpty()) prefs.remove(keys.apiKey) else prefs[keys.apiKey] = cipher.encrypt(key)
            val headers = config.headers.trim()
            if (headers.isEmpty()) prefs.remove(keys.headers) else prefs[keys.headers] = cipher.encrypt(headers)
        }
    }

    suspend fun clearService(service: HomelabService) {
        val keys = ServiceKeys(service)
        settings.edit { prefs -> keys.settingsKeys.forEach { prefs.remove(it) } }
        secrets.edit { prefs ->
            prefs.remove(keys.apiKey)
            prefs.remove(keys.headers)
        }
    }

    suspend fun setRequestTarget(target: RequestTarget) {
        settings.edit { prefs -> prefs[REQUEST_TARGET] = target.name }
    }

    suspend fun setHomelabReport(report: SyncReport?) {
        settings.edit { prefs ->
            if (report == null) prefs.remove(homelabReportKey) else prefs[homelabReportKey] = json.encodeToString(SyncReport.serializer(), report)
        }
    }

    private fun readService(service: HomelabService, prefs: Preferences, secretPrefs: Preferences): ServiceConfig {
        val keys = ServiceKeys(service)
        return ServiceConfig(
            service = service,
            enabled = prefs[keys.enabled] ?: false,
            url = prefs[keys.url].orEmpty(),
            apiKey = secretPrefs[keys.apiKey]?.let(cipher::decrypt),
            headers = secretPrefs[keys.headers]?.let(cipher::decrypt).orEmpty(),
            jellyfinAuthMode = prefs[keys.authMode]?.takeIf { service == HomelabService.JELLYFIN }
                ?.let { runCatching { JellyfinAuthMode.valueOf(it) }.getOrNull() } ?: JellyfinAuthMode.API_KEY,
            seerrAuthMode = prefs[keys.authMode]?.takeIf { service == HomelabService.SEERR }
                ?.let { runCatching { SeerrAuthMode.valueOf(it) }.getOrNull() } ?: SeerrAuthMode.API_KEY,
            userId = prefs[keys.userId],
            userName = prefs[keys.userName],
            plexSections = prefs[keys.sections].orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet(),
            qualityProfileId = prefs[keys.qualityProfile],
            rootFolder = prefs[keys.rootFolder],
            searchOnAdd = prefs[keys.searchOnAdd] ?: true,
            targetServerId = prefs[keys.targetServer],
            targetServer4k = prefs[keys.target4k] ?: false,
        )
    }

    private fun MutablePreferences.setOrRemove(key: Preferences.Key<String>, value: String?) {
        if (value.isNullOrBlank()) remove(key) else this[key] = value
    }

    /** Clés de stockage d'un service (« homelab_jellyfin_url »…). */
    private class ServiceKeys(service: HomelabService) {
        private val prefix = "homelab_${service.name.lowercase()}_"
        val enabled = booleanPreferencesKey(prefix + "enabled")
        val url = stringPreferencesKey(prefix + "url")
        val authMode = stringPreferencesKey(prefix + "auth_mode")
        val userId = stringPreferencesKey(prefix + "user_id")
        val userName = stringPreferencesKey(prefix + "user_name")
        val sections = stringPreferencesKey(prefix + "sections")
        val qualityProfile = intPreferencesKey(prefix + "quality_profile")
        val rootFolder = stringPreferencesKey(prefix + "root_folder")
        val searchOnAdd = booleanPreferencesKey(prefix + "search_on_add")
        val targetServer = intPreferencesKey(prefix + "target_server")
        val target4k = booleanPreferencesKey(prefix + "target_4k")
        val apiKey = stringPreferencesKey(prefix + "key")
        val headers = stringPreferencesKey(prefix + "headers")
        val settingsKeys: List<Preferences.Key<*>> =
            listOf(enabled, url, authMode, userId, userName, sections, qualityProfile, rootFolder, searchOnAdd, targetServer, target4k)
    }

    companion object {
        const val DEFAULT_LANGUAGE = "fr-FR"
        val SUPPORTED_LANGUAGES = listOf("fr-FR", "en-US")
        private val TMDB_API_KEY = stringPreferencesKey("tmdb_api_key")
        private val LANGUAGE = stringPreferencesKey("tmdb_language")
        private val REQUEST_TARGET = stringPreferencesKey("homelab_request_target")
        private val MEDIA_KIND = stringPreferencesKey("media_kind")
        private val CENSUS_CARDS = booleanPreferencesKey("census_cards")
        private val DEV_TOOLS = booleanPreferencesKey("dev_tools")
        private val WATCHED_NOTIFICATIONS = booleanPreferencesKey("watched_notifications")
        private val NEW_SEASON_NOTIFICATIONS = booleanPreferencesKey("new_season_notifications")
        private val TASTE_NAME = stringPreferencesKey("taste_name")
        private val LATEST_UPDATE = stringPreferencesKey("latest_update")
        private val UPDATE_CHECKED_AT = longPreferencesKey("update_checked_at")
        private val DISMISSED_UPDATE = intPreferencesKey("dismissed_update")
        private val SEASON_WATCH = stringPreferencesKey("series_season_watch")
        private val BACKUP_FOLDER = stringPreferencesKey("backup_folder")
        private val BACKUP_LAST_AT = longPreferencesKey("backup_last_at")
        private val BACKUP_LAST_FILE = stringPreferencesKey("backup_last_file")
        private val BACKUP_ERROR = stringPreferencesKey("backup_error")
    }
}

/** Dernière sauvegarde automatique ; [error] : la dernière tentative a échoué (dossier inaccessible…). */
data class BackupStatus(
    val lastAt: Long? = null,
    val lastFile: String? = null,
    val error: String? = null,
)
