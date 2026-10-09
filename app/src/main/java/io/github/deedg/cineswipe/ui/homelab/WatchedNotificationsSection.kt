package io.github.deedg.cineswipe.ui.homelab

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import io.github.deedg.cineswipe.R
import io.github.deedg.cineswipe.data.repository.SettingsRepository
import io.github.deedg.cineswipe.ui.common.cineViewModel
import io.github.deedg.cineswipe.ui.components.CineButton
import io.github.deedg.cineswipe.ui.components.CineSecondaryButton
import io.github.deedg.cineswipe.ui.components.CineSwitchRow
import io.github.deedg.cineswipe.ui.components.SectionCard
import io.github.deedg.cineswipe.ui.icons.CineIcons
import io.github.deedg.cineswipe.ui.theme.CineColors
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

object WatchedNotificationsTags {
    const val SWITCH = "watched_notifications_switch"
    const val ALLOW = "watched_notifications_allow"
}

class WatchedNotificationsViewModel(private val settings: SettingsRepository) : ViewModel() {
    val enabled: StateFlow<Boolean> = settings.watchedNotifications.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    fun setEnabled(enabled: Boolean) {
        viewModelScope.launch { settings.setWatchedNotifications(enabled) }
    }
}

private fun Context.notificationsAllowed(): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

/**
 * Notification après un film vu sur Plex ou Jellyfin : réglage, et autorisation d'Android
 * demandée au besoin (Android 13 et plus).
 */
@Composable
fun WatchedNotificationsSection() {
    val viewModel = cineViewModel { WatchedNotificationsViewModel(it.settings) }
    val enabled by viewModel.enabled.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var allowed by remember { mutableStateOf(context.notificationsAllowed()) }
    var refused by remember { mutableStateOf(false) }
    // De retour des réglages d'Android : l'autorisation a pu changer.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { allowed = context.notificationsAllowed() }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        allowed = granted
        refused = !granted
    }
    val ask = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) runCatching { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) }
    }
    SectionCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CardTitle(CineIcons.Bell, stringResource(R.string.homelab_notify_title))
            CardBody(stringResource(R.string.homelab_notify_body))
            CineSwitchRow(
                title = stringResource(R.string.homelab_notify_switch),
                checked = enabled,
                onCheckedChange = { checked ->
                    viewModel.setEnabled(checked)
                    if (checked && !allowed) ask()
                },
                modifier = Modifier.testTag(WatchedNotificationsTags.SWITCH),
            )
            if (enabled && !allowed) {
                if (refused) {
                    CardBody(stringResource(R.string.homelab_notify_blocked), color = CineColors.Warning)
                    CineSecondaryButton(
                        text = stringResource(R.string.homelab_notify_open_settings),
                        icon = CineIcons.Settings,
                        onClick = {
                            val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            runCatching { context.startActivity(intent) }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    CineButton(
                        text = stringResource(R.string.homelab_notify_allow),
                        icon = CineIcons.Bell,
                        onClick = ask,
                        modifier = Modifier.fillMaxWidth().testTag(WatchedNotificationsTags.ALLOW),
                    )
                }
            }
        }
    }
}
