package io.github.deedg.cineswipe.ui.homelab

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.deedg.cineswipe.data.db.OutboxEntity
import io.github.deedg.cineswipe.data.homelab.HomelabConfig
import io.github.deedg.cineswipe.data.homelab.HomelabRepository
import io.github.deedg.cineswipe.data.homelab.RequestTarget
import io.github.deedg.cineswipe.data.homelab.SyncReport
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class HomelabUiState(
    val config: HomelabConfig = HomelabConfig(),
    val report: SyncReport? = null,
    val syncing: Boolean = false,
    val pendingCount: Int = 0,
    val failed: List<OutboxEntity> = emptyList(),
)

class HomelabViewModel(private val homelab: HomelabRepository) : ViewModel() {

    val state: StateFlow<HomelabUiState> = combine(
        homelab.config,
        homelab.report,
        homelab.syncing,
        homelab.pendingCount,
        homelab.failed,
    ) { config, report, syncing, pending, failed ->
        HomelabUiState(config = config, report = report, syncing = syncing, pendingCount = pending, failed = failed)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomelabUiState())

    fun sync() = homelab.syncInBackground()

    fun setRequestTarget(target: RequestTarget) {
        viewModelScope.launch { homelab.setRequestTarget(target) }
    }

    fun retryFailed() = homelab.retryFailed()

    fun deleteFailed(id: Long) = homelab.deleteFailed(id)
}
