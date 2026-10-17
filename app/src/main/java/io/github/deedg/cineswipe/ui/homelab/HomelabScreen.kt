package io.github.deedg.cineswipe.ui.homelab

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.deedg.cineswipe.R
import io.github.deedg.cineswipe.data.db.OutboxEntity
import io.github.deedg.cineswipe.data.homelab.HomelabService
import io.github.deedg.cineswipe.data.homelab.RequestTarget
import io.github.deedg.cineswipe.data.homelab.ServiceConfig
import io.github.deedg.cineswipe.ui.common.cineViewModel
import io.github.deedg.cineswipe.ui.components.CineButton
import io.github.deedg.cineswipe.ui.components.CineChip
import io.github.deedg.cineswipe.ui.components.CineSecondaryButton
import io.github.deedg.cineswipe.ui.components.GlassIconButton
import io.github.deedg.cineswipe.ui.components.ScreenTitle
import io.github.deedg.cineswipe.ui.components.SectionCard
import io.github.deedg.cineswipe.ui.icons.CineIcons
import io.github.deedg.cineswipe.ui.theme.CineColors

/** Repères pour les tests d'interface. */
object HomelabTestTags {
    fun service(service: HomelabService): String = "homelab-service-${service.name.lowercase()}"
}

@Composable
fun HomelabRoute(onBack: () -> Unit, onOpenService: (HomelabService) -> Unit) {
    val viewModel = cineViewModel { HomelabViewModel(it.homelab) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    HomelabScreen(
        state = state,
        onBack = onBack,
        onOpenService = onOpenService,
        onSync = viewModel::sync,
        onRequestTarget = viewModel::setRequestTarget,
        onRetryFailed = viewModel::retryFailed,
        onDeleteFailed = viewModel::deleteFailed,
    )
}

@Composable
fun HomelabScreen(
    state: HomelabUiState,
    onBack: () -> Unit,
    onOpenService: (HomelabService) -> Unit,
    onSync: () -> Unit,
    onRequestTarget: (RequestTarget) -> Unit,
    onRetryFailed: () -> Unit,
    onDeleteFailed: (Long) -> Unit,
) {
    val config = state.config
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(15.dp),
    ) {
        ScreenTitle(
            text = stringResource(R.string.homelab_title),
            leading = {
                GlassIconButton(icon = CineIcons.Back, contentDescription = stringResource(R.string.action_back), onClick = onBack)
            },
        )
        CardBody(stringResource(R.string.homelab_intro))

        SectionCard {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                CardTitle(CineIcons.Server, stringResource(R.string.homelab_media_title))
                CardBody(stringResource(R.string.homelab_media_body))
                ServiceRow(config.jellyfin) { onOpenService(HomelabService.JELLYFIN) }
                ServiceRow(config.plex) { onOpenService(HomelabService.PLEX) }
            }
        }

        // Après un film vu sur le serveur : notification pour le placer dans le classement.
        if (config.jellyfin.isReady || config.plex.isReady) WatchedNotificationsSection()

        SectionCard {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                CardTitle(CineIcons.Download, stringResource(R.string.homelab_requests_title))
                CardBody(stringResource(R.string.homelab_requests_body))
                ServiceRow(config.seerr) { onOpenService(HomelabService.SEERR) }
                ServiceRow(config.radarr) { onOpenService(HomelabService.RADARR) }
                // Sonarr : les demandes de séries (Seerr sert les deux mondes).
                ServiceRow(config.sonarr) { onOpenService(HomelabService.SONARR) }
                // Avec Seerr et le service « *arr » du monde (Radarr ou Sonarr) : qui reçoit les demandes.
                if (config.seerr.isReady && config.arr.isReady) {
                    GroupLabel(stringResource(R.string.homelab_target_label))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CineChip(
                            label = HomelabService.SEERR.displayName,
                            selected = config.requestService == HomelabService.SEERR,
                            onClick = { onRequestTarget(RequestTarget.SEERR) },
                        )
                        CineChip(
                            label = config.arr.service.displayName,
                            selected = config.requestService == config.arr.service,
                            onClick = { onRequestTarget(RequestTarget.RADARR) },
                        )
                    }
                }
            }
        }

        SyncSection(state, onSync)

        if (state.pendingCount > 0 || state.failed.isNotEmpty()) {
            QueueSection(state.pendingCount, state.failed, onRetryFailed, onDeleteFailed)
        }

        SectionCard {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CardTitle(CineIcons.Link, stringResource(R.string.homelab_tip_title))
                CardBody(stringResource(R.string.homelab_tip_body))
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun ServiceRow(config: ServiceConfig, onClick: () -> Unit) {
    val resources = LocalResources.current
    val (status, color) = when {
        config.isReady -> {
            // Compte utilisé : l'utilisateur Jellyfin, ou celui connecté à Seerr.
            val account = config.userName?.takeIf { config.service == HomelabService.JELLYFIN || config.usesSeerrSession }
            val where = hostLabel(config.url) + (account?.let { " · $it" } ?: "")
            stringResource(R.string.homelab_status_ready, where) to CineColors.Success
        }
        !config.enabled && config.url.isBlank() -> stringResource(R.string.homelab_status_none) to CineColors.TextMuted
        !config.enabled -> stringResource(R.string.homelab_status_disabled) to CineColors.TextMuted
        else -> stringResource(R.string.homelab_status_incomplete, missingSettings(resources, config).orEmpty()) to CineColors.Warning
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .testTag(HomelabTestTags.service(config.service))
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Column(Modifier.weight(1f)) {
            Text(config.service.displayName, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text(status, color = color, fontSize = 13.sp, lineHeight = 18.sp)
        }
        Icon(CineIcons.ChevronRight, contentDescription = null, tint = CineColors.TextMuted, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun SyncSection(state: HomelabUiState, onSync: () -> Unit) {
    val resources = LocalResources.current
    val anyReady = state.config.anyReady
    SectionCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CardTitle(CineIcons.Refresh, stringResource(R.string.homelab_sync_title))
            val report = state.report
            if (report == null) {
                CardBody(stringResource(R.string.homelab_sync_never))
            } else {
                val ago = DateUtils.getRelativeTimeSpanString(report.finishedAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
                Text(stringResource(R.string.homelab_sync_last, ago), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                if (state.config.jellyfin.isReady || state.config.plex.isReady) {
                    CardBody(stringResource(R.string.homelab_sync_watched, report.watched, report.newlyWatched))
                }
                CardBody(stringResource(R.string.homelab_sync_available, report.available, report.requested))
                if (report.unmatched > 0) CardBody(stringResource(R.string.homelab_sync_unmatched, report.unmatched))
                report.failures.forEach { failure ->
                    CardBody(
                        text = stringResource(
                            R.string.homelab_sync_failure,
                            failure.service.displayName,
                            errorMessage(resources, failure.service, failure.kind),
                        ),
                        color = CineColors.Warning,
                    )
                }
            }
            if (state.syncing) {
                BusyRow(stringResource(R.string.homelab_syncing))
            } else {
                CineButton(
                    text = stringResource(R.string.homelab_sync_now),
                    icon = CineIcons.Refresh,
                    onClick = onSync,
                    enabled = anyReady,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
            }
            HintText(stringResource(if (anyReady) R.string.homelab_sync_auto else R.string.homelab_sync_none_ready))
        }
    }
}

@Composable
private fun QueueSection(
    pending: Int,
    failed: List<OutboxEntity>,
    onRetry: () -> Unit,
    onDelete: (Long) -> Unit,
) {
    val resources = LocalResources.current
    SectionCard {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            CardTitle(CineIcons.Upload, stringResource(R.string.homelab_queue_title))
            if (pending > 0) CardBody(pluralStringResource(R.plurals.homelab_queue_pending, pending, pending))
            failed.forEach { entry ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.homelab_queue_failed_item, entry.title, entry.service.displayName),
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        outboxErrorMessage(resources, entry)?.let { Text(it, color = CineColors.Warning, fontSize = 13.sp, lineHeight = 18.sp) }
                    }
                    GlassIconButton(
                        icon = CineIcons.Trash,
                        contentDescription = stringResource(R.string.homelab_queue_delete),
                        onClick = { onDelete(entry.id) },
                        buttonSize = 36.dp,
                    )
                }
            }
            if (failed.isNotEmpty()) {
                CineSecondaryButton(
                    text = stringResource(R.string.homelab_queue_retry),
                    icon = CineIcons.Refresh,
                    onClick = onRetry,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
