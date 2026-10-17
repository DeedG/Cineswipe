package io.github.deedg.cineswipe.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.deedg.cineswipe.core.CrashLog
import io.github.deedg.cineswipe.data.homelab.HomelabService
import io.github.deedg.cineswipe.data.repository.DiagnosticsRepository
import io.github.deedg.cineswipe.data.repository.KeyCheck
import io.github.deedg.cineswipe.data.repository.LibraryRepository
import io.github.deedg.cineswipe.data.repository.SettingsRepository
import io.github.deedg.cineswipe.data.repository.TmdbRepository
import io.github.deedg.cineswipe.ui.common.ExportEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Remise à zéro demandée dans « Mes données ». */
enum class DataReset { RANKING, EVERYTHING }

/** Vérification de la clé TMDB au moment de l'enregistrer. */
enum class KeyCheckState { IDLE, CHECKING, SAVED, REJECTED, SAVED_UNVERIFIED }

data class SettingsUiState(
    val tmdbKeyMasked: String? = null,
    val keyCheck: KeyCheckState = KeyCheckState.IDLE,
    val language: String = SettingsRepository.DEFAULT_LANGUAGE,
    val crashLog: String? = null,
    /** Services du homelab prêts à servir. */
    val homelabReady: List<HomelabService> = emptyList(),
    /** Outils de diagnostic débloqués. */
    val devTools: Boolean = false,
    /** Un export JSON est en cours d'écriture. */
    val exporting: Boolean = false,
    /** Réglages de la démo (bac à sable) : clé TMDB et homelab masqués. */
    val demo: Boolean = false,
    /** Clé TMDB intégrée à l'appli : la sienne devient facultative. */
    val builtinKey: Boolean = false,
)

class SettingsViewModel(
    private val settings: SettingsRepository,
    private val tmdb: TmdbRepository,
    private val library: LibraryRepository,
    private val crashLog: CrashLog,
    private val diagnostics: DiagnosticsRepository,
    private val demo: Boolean = false,
) : ViewModel() {

    private val keyCheck = MutableStateFlow(KeyCheckState.IDLE)
    private val crash = MutableStateFlow<String?>(null)
    private val exporting = MutableStateFlow(false)
    private val exportEvents = Channel<ExportEvent>(Channel.BUFFERED)

    /** Exports prêts à partager. */
    val exports: Flow<ExportEvent> = exportEvents.receiveAsFlow()

    val state: StateFlow<SettingsUiState> = combine(
        combine(
            settings.userTmdbApiKey,
            keyCheck,
            settings.language,
            crash,
        ) { key, check, language, crashText ->
            SettingsUiState(
                tmdbKeyMasked = key?.let(::mask),
                keyCheck = check,
                language = language,
                crashLog = crashText,
            )
        },
        settings.savedHomelabConfig.map { config -> config.all.filter { it.isReady }.map { it.service } },
        settings.devToolsEnabled,
        exporting,
    ) { base, homelab, devTools, export -> base.copy(homelabReady = homelab, devTools = devTools, exporting = export, demo = demo, builtinKey = settings.hasBuiltinTmdbKey) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState(demo = demo))

    init {
        viewModelScope.launch { crash.value = withContext(Dispatchers.IO) { crashLog.read() } }
    }

    /** La clé est testée auprès de TMDB avant d'être enregistrée ; hors ligne, elle est gardée sans vérification. */
    fun saveTmdbKey(value: String) {
        if (keyCheck.value == KeyCheckState.CHECKING) return
        keyCheck.value = KeyCheckState.CHECKING
        viewModelScope.launch {
            keyCheck.value = when (tmdb.validateKey(value)) {
                KeyCheck.VALID -> {
                    settings.setTmdbApiKey(value)
                    KeyCheckState.SAVED
                }
                KeyCheck.UNREACHABLE -> {
                    settings.setTmdbApiKey(value)
                    KeyCheckState.SAVED_UNVERIFIED
                }
                KeyCheck.REJECTED -> KeyCheckState.REJECTED
            }
        }
    }

    /** Le message de vérification a été affiché. */
    fun acknowledgeKeyCheck() {
        if (keyCheck.value == KeyCheckState.SAVED) keyCheck.value = KeyCheckState.IDLE
    }

    fun removeTmdbKey() {
        keyCheck.value = KeyCheckState.IDLE
        viewModelScope.launch { settings.setTmdbApiKey(null) }
    }

    fun setLanguage(tag: String) {
        viewModelScope.launch { settings.setLanguage(tag) }
    }

    /** Remet à zéro le classement (films vus gardés) ou toutes les données de films. */
    fun reset(kind: DataReset) {
        viewModelScope.launch {
            when (kind) {
                DataReset.RANKING -> library.resetRanking()
                DataReset.EVERYTHING -> {
                    library.eraseAll()
                    // La prochaine synchro du homelab repartira de zéro elle aussi.
                    settings.setHomelabReport(null)
                    // Le recensement oublie ce qu'il avait appris de tes réponses (le catalogue reste).
                    settings.updateCensusState { it.copy(weights = emptyMap(), updates = 0) }
                }
            }
        }
    }

    /** Écrit toutes les données dans un fichier JSON, puis propose de le partager. */
    fun exportData() {
        if (exporting.value) return
        exporting.value = true
        viewModelScope.launch {
            val event = try {
                ExportEvent.Ready(diagnostics.export())
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                ExportEvent.Failed
            } finally {
                exporting.value = false
            }
            exportEvents.send(event)
        }
    }

    /** Sept touchers sur le numéro de version : les outils de diagnostic apparaissent. */
    fun unlockDevTools() {
        viewModelScope.launch { settings.setDevToolsEnabled(true) }
    }

    fun clearCrash() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { crashLog.clear() }
            crash.value = null
        }
    }

    private fun mask(key: String): String = if (key.length <= 4) "••••" else "••••••" + key.takeLast(4)
}
