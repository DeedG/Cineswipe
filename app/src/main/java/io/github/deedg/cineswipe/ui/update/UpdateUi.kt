package io.github.deedg.cineswipe.ui.update

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import io.github.deedg.cineswipe.BuildConfig
import io.github.deedg.cineswipe.R
import io.github.deedg.cineswipe.data.update.AvailableUpdate
import io.github.deedg.cineswipe.data.update.UpdateRepository
import io.github.deedg.cineswipe.ui.common.cineViewModel
import io.github.deedg.cineswipe.ui.components.CineButton
import io.github.deedg.cineswipe.ui.components.GlassIconButton
import io.github.deedg.cineswipe.ui.components.SectionCard
import io.github.deedg.cineswipe.ui.icons.CineIcons
import io.github.deedg.cineswipe.ui.theme.CineColors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

object UpdateTestTags {
    const val BANNER = "update_banner"
    const val SECTION = "update_section"
    const val CHECK = "update_check"
    const val DOWNLOAD = "update_download"
}

/** Résultat d'une vérification à la demande. */
enum class UpdateCheck { IDLE, CHECKING, UP_TO_DATE }

class UpdateViewModel(private val updates: UpdateRepository) : ViewModel() {
    val available: StateFlow<AvailableUpdate?> = updates.available.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val toAnnounce: StateFlow<AvailableUpdate?> = updates.toAnnounce.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val mutableCheck = MutableStateFlow(UpdateCheck.IDLE)
    val check: StateFlow<UpdateCheck> = mutableCheck.asStateFlow()

    fun checkNow() {
        if (mutableCheck.value == UpdateCheck.CHECKING) return
        mutableCheck.value = UpdateCheck.CHECKING
        viewModelScope.launch {
            val found = updates.check(force = true)
            mutableCheck.value = if (found == null) UpdateCheck.UP_TO_DATE else UpdateCheck.IDLE
        }
    }

    fun dismiss(update: AvailableUpdate) {
        viewModelScope.launch { updates.dismiss(update) }
    }
}

/** Ouvre l'APK (téléchargé par le navigateur), ou la page de la version. */
@Composable
private fun downloadAction(update: AvailableUpdate): () -> Unit {
    val uriHandler = LocalUriHandler.current
    return { runCatching { uriHandler.openUri(update.apkUrl ?: update.pageUrl) } }
}

/** Bandeau du profil : une nouvelle version, une fois (la croix l'écarte jusqu'à la suivante). */
@Composable
fun UpdateBanner() {
    val viewModel = cineViewModel { UpdateViewModel(it.updates) }
    val update by viewModel.toAnnounce.collectAsStateWithLifecycle()
    val current = update ?: return
    val shape = RoundedCornerShape(15.dp)
    val download = downloadAction(current)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(CineColors.Surface)
            .border(1.dp, CineColors.Red.copy(alpha = 0.6f), shape)
            .clickable(role = Role.Button, onClick = download)
            .padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 6.dp)
            .testTag(UpdateTestTags.BANNER),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(CineIcons.Download, contentDescription = null, tint = CineColors.Red, modifier = Modifier.size(22.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.update_banner_title, current.versionName), color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.update_banner_body), color = CineColors.TextSecondary, fontSize = 13.sp)
        }
        GlassIconButton(icon = CineIcons.Close, contentDescription = stringResource(R.string.update_dismiss), onClick = { viewModel.dismiss(current) }, buttonSize = 32.dp)
    }
}

/** Réglages : version installée, dernière version publiée, vérifier maintenant. */
@Composable
fun UpdateSection() {
    val viewModel = cineViewModel { UpdateViewModel(it.updates) }
    val update by viewModel.available.collectAsStateWithLifecycle()
    val check by viewModel.check.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current
    SectionCard(modifier = Modifier.testTag(UpdateTestTags.SECTION)) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(CineIcons.Download, contentDescription = null, tint = CineColors.Red, modifier = Modifier.size(20.dp))
                Text(stringResource(R.string.update_title), color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            }
            val current = update
            if (current != null) {
                Text(stringResource(R.string.update_available, current.versionName, BuildConfig.VERSION_NAME), color = CineColors.TextSoft, fontSize = 14.sp)
                CineButton(
                    text = stringResource(R.string.update_download),
                    icon = CineIcons.Download,
                    onClick = downloadAction(current),
                    modifier = Modifier.fillMaxWidth().testTag(UpdateTestTags.DOWNLOAD),
                )
                Text(stringResource(R.string.update_install_hint), color = CineColors.TextMuted, fontSize = 13.sp)
            } else {
                Text(
                    stringResource(if (check == UpdateCheck.UP_TO_DATE) R.string.update_up_to_date else R.string.update_installed, BuildConfig.VERSION_NAME),
                    color = CineColors.TextSecondary,
                    fontSize = 14.sp,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = viewModel::checkNow, enabled = check != UpdateCheck.CHECKING, modifier = Modifier.testTag(UpdateTestTags.CHECK)) {
                    Text(stringResource(R.string.update_check), color = CineColors.TmdbBlue, fontWeight = FontWeight.Bold)
                }
                if (check == UpdateCheck.CHECKING) CircularProgressIndicator(color = CineColors.Red, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                TextButton(onClick = { runCatching { uriHandler.openUri(UpdateRepository.RELEASES_PAGE) } }) {
                    Text(stringResource(R.string.update_all_versions), color = CineColors.TmdbBlue, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
