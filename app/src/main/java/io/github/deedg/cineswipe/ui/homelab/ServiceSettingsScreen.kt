package io.github.deedg.cineswipe.ui.homelab

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.deedg.cineswipe.R
import io.github.deedg.cineswipe.data.homelab.ConnectionInfo
import io.github.deedg.cineswipe.data.homelab.HomelabService
import io.github.deedg.cineswipe.data.homelab.HomelabUrls
import io.github.deedg.cineswipe.data.homelab.JellyfinAuthMode
import io.github.deedg.cineswipe.data.homelab.PlexServer
import io.github.deedg.cineswipe.data.homelab.SeerrAuthMode
import io.github.deedg.cineswipe.data.homelab.ServiceConfig
import io.github.deedg.cineswipe.ui.common.cineViewModel
import io.github.deedg.cineswipe.ui.components.CineButton
import io.github.deedg.cineswipe.ui.components.CineChip
import io.github.deedg.cineswipe.ui.components.CineSecondaryButton
import io.github.deedg.cineswipe.ui.components.CineSwitchRow
import io.github.deedg.cineswipe.ui.components.CineTextField
import io.github.deedg.cineswipe.ui.components.GlassIconButton
import io.github.deedg.cineswipe.ui.components.LocalToaster
import io.github.deedg.cineswipe.ui.components.ScreenTitle
import io.github.deedg.cineswipe.ui.components.SectionCard
import io.github.deedg.cineswipe.ui.icons.CineIcons
import io.github.deedg.cineswipe.ui.theme.CineColors

/** Modification des réglages en cours de saisie. */
typealias ServiceEdit = ((ServiceConfig) -> ServiceConfig) -> Unit

/** Actions de l'écran de réglages d'un service. */
class ServiceActions(
    val onBack: () -> Unit,
    val onEdit: ServiceEdit,
    val onTest: () -> Unit,
    val onJellyfinLogin: (username: String, password: String) -> Unit,
    val onSeerrLogin: (identifier: String, password: String) -> Unit,
    val onPlexSignIn: () -> Unit,
    val onReopenPlexLogin: () -> Unit,
    val onChooseServer: (PlexServer) -> Unit,
    val onSave: () -> Unit,
    val onClear: () -> Unit,
)

@Composable
fun ServiceSettingsRoute(service: HomelabService, onBack: () -> Unit) {
    val viewModel = cineViewModel(key = "homelab-${service.name}") { ServiceSettingsViewModel(service, it.homelab) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val toaster = LocalToaster.current
    val resources = LocalResources.current
    val uriHandler = LocalUriHandler.current
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is ServiceSettingsEvent.OpenUrl -> runCatching { uriHandler.openUri(event.url) }
                is ServiceSettingsEvent.Saved -> {
                    toaster.show(
                        resources.getString(if (event.syncing) R.string.service_saved_sync_toast else R.string.service_saved_toast, service.displayName),
                    )
                    onBack()
                }
                ServiceSettingsEvent.Cleared -> {
                    toaster.show(resources.getString(R.string.service_cleared_toast, service.displayName))
                    onBack()
                }
            }
        }
    }
    ServiceSettingsScreen(
        state = state,
        actions = ServiceActions(
            onBack = onBack,
            onEdit = viewModel::edit,
            onTest = viewModel::test,
            onJellyfinLogin = viewModel::login,
            onSeerrLogin = viewModel::seerrLogin,
            onPlexSignIn = viewModel::plexSignIn,
            onReopenPlexLogin = viewModel::reopenPlexLogin,
            onChooseServer = viewModel::chooseServer,
            onSave = viewModel::save,
            onClear = viewModel::clear,
        ),
    )
}

@Composable
fun ServiceSettingsScreen(state: ServiceSettingsUiState, actions: ServiceActions) {
    val draft = state.draft
    val service = draft.service
    var confirmClear by rememberSaveable { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(15.dp),
    ) {
        ScreenTitle(
            text = service.displayName,
            leading = {
                GlassIconButton(icon = CineIcons.Back, contentDescription = stringResource(R.string.action_back), onClick = actions.onBack)
            },
        )
        if (!state.loaded) {
            BusyRow(stringResource(R.string.feed_loading_more))
            return@Column
        }

        ConnectionSection(state, actions)
        ChoicesSection(draft, state.info, state.test, actions.onEdit)
        AdvancedSection(draft, actions.onEdit)

        if (draft.enabled && draft.url.isNotBlank()) {
            missingSettings(LocalResources.current, draft)?.let { HintText(stringResource(R.string.service_missing, it), color = CineColors.Warning) }
        }
        CineButton(
            text = stringResource(R.string.service_save),
            icon = CineIcons.Check,
            onClick = actions.onSave,
            enabled = !state.saving && (draft.url.isNotBlank() || !draft.enabled),
            modifier = Modifier.fillMaxWidth(),
        )
        if (state.configured) {
            TextButton(onClick = { confirmClear = true }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text(stringResource(R.string.service_clear), color = CineColors.TextMuted)
            }
        }
        Spacer(Modifier.height(12.dp))
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            containerColor = CineColors.Surface,
            title = { Text(stringResource(R.string.service_clear_title, service.displayName), color = Color.White) },
            text = { Text(stringResource(R.string.service_clear_message), color = CineColors.TextSecondary) },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    actions.onClear()
                }) {
                    Text(stringResource(R.string.service_clear_confirm), color = CineColors.Red, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) {
                    Text(stringResource(R.string.action_cancel), color = CineColors.TextSecondary)
                }
            },
        )
    }
}

/** Activation, adresse, identification et test de connexion. */
@Composable
private fun ConnectionSection(state: ServiceSettingsUiState, actions: ServiceActions) {
    val draft = state.draft
    val service = draft.service
    val busy = state.test == TestState.Running || state.plexLoginUrl != null
    SectionCard {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            CineSwitchRow(
                title = stringResource(R.string.service_enable, service.displayName),
                checked = draft.enabled,
                onCheckedChange = { enabled -> actions.onEdit { it.copy(enabled = enabled) } },
            )
            CineTextField(
                value = draft.url,
                onValueChange = { url -> actions.onEdit { it.copy(url = url) } },
                label = stringResource(R.string.service_url_label),
                placeholder = "192.168.1.20:${service.defaultPort}",
                keyboardType = KeyboardType.Uri,
            )
            HintText(stringResource(R.string.service_url_hint, service.defaultPort))
            val parsed = HomelabUrls.parse(draft.url)
            if (parsed != null && HomelabUrls.isInsecurePublic(parsed)) {
                HintText(stringResource(R.string.service_url_insecure), color = CineColors.Warning)
            }
            if (state.secureUrlApplied) {
                HintText(stringResource(R.string.service_secure_applied), color = CineColors.Success)
            }

            when (service) {
                HomelabService.JELLYFIN -> JellyfinAuth(draft, actions, busy)
                HomelabService.PLEX -> PlexAuth(state, actions, busy)
                HomelabService.SEERR -> SeerrAuth(state, actions, busy)
                HomelabService.RADARR -> KeyField(draft, actions.onEdit, R.string.service_key_label_api, R.string.service_key_hint_radarr)
            }

            val loginOnly = (service == HomelabService.JELLYFIN && draft.jellyfinAuthMode == JellyfinAuthMode.LOGIN) ||
                (draft.usesSeerrSession && draft.apiKey.isNullOrBlank())
            if (!loginOnly) {
                CineSecondaryButton(
                    text = stringResource(R.string.service_test),
                    icon = CineIcons.Refresh,
                    onClick = actions.onTest,
                    enabled = !busy && draft.url.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            TestResult(state.test, state.info, service)
        }
    }
}

@Composable
private fun KeyField(draft: ServiceConfig, onEdit: ServiceEdit, @StringRes label: Int, @StringRes hint: Int) {
    CineTextField(
        value = draft.apiKey.orEmpty(),
        onValueChange = { key -> onEdit { it.copy(apiKey = key) } },
        label = stringResource(label),
        secret = true,
        imeAction = ImeAction.Done,
    )
    HintText(stringResource(hint))
}

/** Identifiant et mot de passe, avec le bouton de connexion. Le mot de passe ne survit pas à l'écran. */
@Composable
private fun LoginForm(
    @StringRes identifierLabel: Int,
    initialIdentifier: String,
    busy: Boolean,
    enabled: Boolean,
    keyboardType: KeyboardType,
    onLogin: (String, String) -> Unit,
) {
    var identifier by rememberSaveable { mutableStateOf(initialIdentifier) }
    var password by remember { mutableStateOf("") }
    CineTextField(value = identifier, onValueChange = { identifier = it }, label = stringResource(identifierLabel), keyboardType = keyboardType)
    CineTextField(
        value = password,
        onValueChange = { password = it },
        label = stringResource(R.string.service_password),
        secret = true,
        imeAction = ImeAction.Done,
    )
    CineButton(
        text = stringResource(R.string.service_login),
        onClick = {
            onLogin(identifier, password)
            password = ""
        },
        enabled = !busy && enabled && identifier.isNotBlank(),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ConnectedAs(name: String?) {
    if (name.isNullOrBlank()) return
    Text(stringResource(R.string.service_logged_in, name), color = CineColors.Success, fontWeight = FontWeight.Bold, fontSize = 14.sp)
}

@Composable
private fun JellyfinAuth(draft: ServiceConfig, actions: ServiceActions, busy: Boolean) {
    val mode = draft.jellyfinAuthMode
    val switchTo = { target: JellyfinAuthMode ->
        // Le jeton d'une connexion n'est pas une clé API (et inversement) : on repart de zéro.
        if (target != mode) actions.onEdit { it.copy(jellyfinAuthMode = target, apiKey = null, userId = null, userName = null) }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CineChip(stringResource(R.string.service_auth_api_key), selected = mode == JellyfinAuthMode.API_KEY, onClick = { switchTo(JellyfinAuthMode.API_KEY) })
        CineChip(stringResource(R.string.service_auth_login), selected = mode == JellyfinAuthMode.LOGIN, onClick = { switchTo(JellyfinAuthMode.LOGIN) })
    }
    if (mode == JellyfinAuthMode.API_KEY) {
        KeyField(draft, actions.onEdit, R.string.service_key_label_api, R.string.service_key_hint_jellyfin)
        return
    }
    if (!draft.apiKey.isNullOrBlank() && !draft.userId.isNullOrBlank()) ConnectedAs(draft.userName)
    LoginForm(
        identifierLabel = R.string.service_username,
        initialIdentifier = draft.userName.orEmpty(),
        busy = busy,
        enabled = draft.url.isNotBlank(),
        keyboardType = KeyboardType.Text,
        onLogin = actions.onJellyfinLogin,
    )
    HintText(stringResource(R.string.service_login_hint))
}

/** Bouton « Se connecter avec Plex » et attente de la validation dans le navigateur. */
@Composable
private fun PlexSignIn(state: ServiceSettingsUiState, actions: ServiceActions, busy: Boolean, enabled: Boolean = true) {
    if (state.plexLoginUrl != null) {
        BusyRow(stringResource(R.string.service_plex_waiting))
        TextButton(onClick = actions.onReopenPlexLogin) {
            Text(stringResource(R.string.service_plex_reopen), color = CineColors.TmdbBlue, fontWeight = FontWeight.Bold)
        }
    } else {
        CineSecondaryButton(
            text = stringResource(R.string.service_plex_signin),
            icon = CineIcons.Link,
            onClick = actions.onPlexSignIn,
            enabled = !busy && enabled,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlexAuth(state: ServiceSettingsUiState, actions: ServiceActions, busy: Boolean) {
    val draft = state.draft
    PlexSignIn(state, actions, busy)
    HintText(stringResource(R.string.service_plex_signin_hint))
    if (state.plexServers.size > 1) {
        GroupLabel(stringResource(R.string.service_plex_servers))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            state.plexServers.forEach { server ->
                CineChip(
                    label = if (server.owned) server.name else stringResource(R.string.service_plex_shared, server.name),
                    selected = server.accessToken != null && server.accessToken == draft.apiKey,
                    onClick = { actions.onChooseServer(server) },
                )
            }
        }
    }
    KeyField(draft, actions.onEdit, R.string.service_key_label_plex, R.string.service_key_hint_plex)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SeerrAuth(state: ServiceSettingsUiState, actions: ServiceActions, busy: Boolean) {
    val draft = state.draft
    val mode = draft.seerrAuthMode
    val switchTo = { target: SeerrAuthMode ->
        // Une clé API n'est pas une session (et inversement) : on repart de zéro.
        if (target != mode) actions.onEdit { it.copy(seerrAuthMode = target, apiKey = null, userId = null, userName = null) }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SeerrAuthMode.entries.forEach { option ->
            CineChip(label = stringResource(option.label), selected = mode == option, onClick = { switchTo(option) })
        }
    }
    when (mode) {
        SeerrAuthMode.API_KEY -> {
            KeyField(draft, actions.onEdit, R.string.service_key_label_api, R.string.service_key_hint_seerr)
            HintText(stringResource(R.string.service_seerr_api_hint))
        }
        SeerrAuthMode.LOCAL, SeerrAuthMode.JELLYFIN -> {
            if (!draft.apiKey.isNullOrBlank()) ConnectedAs(draft.userName)
            LoginForm(
                identifierLabel = if (mode == SeerrAuthMode.LOCAL) R.string.service_seerr_email else R.string.service_username,
                initialIdentifier = "",
                busy = busy,
                enabled = draft.url.isNotBlank(),
                keyboardType = if (mode == SeerrAuthMode.LOCAL) KeyboardType.Email else KeyboardType.Text,
                onLogin = actions.onSeerrLogin,
            )
            HintText(stringResource(R.string.service_seerr_login_hint))
        }
        SeerrAuthMode.PLEX -> {
            if (!draft.apiKey.isNullOrBlank()) ConnectedAs(draft.userName)
            PlexSignIn(state, actions, busy, enabled = draft.url.isNotBlank())
            HintText(stringResource(R.string.service_seerr_login_hint))
        }
    }
}

private val SeerrAuthMode.label: Int
    get() = when (this) {
        SeerrAuthMode.API_KEY -> R.string.service_auth_api_key
        SeerrAuthMode.LOCAL -> R.string.service_seerr_mode_local
        SeerrAuthMode.JELLYFIN -> R.string.service_seerr_mode_jellyfin
        SeerrAuthMode.PLEX -> R.string.service_seerr_mode_plex
    }

@Composable
private fun TestResult(test: TestState, info: ConnectionInfo?, service: HomelabService) {
    when (test) {
        TestState.Idle -> Unit
        TestState.Running -> BusyRow(stringResource(R.string.service_testing))
        TestState.Success -> {
            val name = info?.serverName ?: service.displayName
            val version = info?.version
            Text(
                text = if (version != null) stringResource(R.string.service_test_ok_version, name, version) else stringResource(R.string.service_test_ok, name),
                color = CineColors.Success,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
            )
            info?.account?.let { Text(stringResource(R.string.service_logged_in, it), color = CineColors.TextSecondary, fontSize = 13.sp) }
        }
        is TestState.Failed -> {
            Text(
                text = errorMessage(LocalResources.current, service, test.kind, test.code),
                color = CineColors.Red,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
            )
            test.detail?.let { HintText(it, color = CineColors.TextMuted) }
        }
    }
}

/** Choix propres au service : utilisateur Jellyfin, bibliothèques Plex, profil et dossier Radarr ou Seerr. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChoicesSection(draft: ServiceConfig, info: ConnectionInfo?, test: TestState, onEdit: ServiceEdit) {
    when (draft.service) {
        HomelabService.JELLYFIN -> if (draft.jellyfinAuthMode == JellyfinAuthMode.API_KEY) {
            SectionCard {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    GroupLabel(stringResource(R.string.service_jellyfin_user))
                    val users = info?.jellyfinUsers.orEmpty()
                    if (users.isEmpty()) {
                        draft.userName?.let { CardBody(stringResource(R.string.service_jellyfin_user_current, it)) }
                        HintText(stringResource(R.string.service_jellyfin_user_hint))
                    } else {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            users.forEach { user ->
                                CineChip(
                                    label = user.name ?: user.id,
                                    selected = user.id == draft.userId,
                                    onClick = { onEdit { it.copy(userId = user.id, userName = user.name) } },
                                )
                            }
                        }
                    }
                }
            }
        }
        HomelabService.PLEX -> SectionCard {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                GroupLabel(stringResource(R.string.service_plex_sections))
                val sections = info?.plexSections.orEmpty()
                if (sections.isEmpty()) {
                    HintText(stringResource(R.string.service_plex_choose_hint))
                } else {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        sections.forEach { section ->
                            val selected = section.key in draft.plexSections
                            CineChip(
                                label = section.title,
                                selected = selected,
                                onClick = {
                                    onEdit { it.copy(plexSections = if (selected) it.plexSections - section.key else it.plexSections + section.key) }
                                },
                            )
                        }
                    }
                }
                HintText(stringResource(R.string.service_plex_sections_hint))
            }
        }
        HomelabService.RADARR -> SectionCard {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val profiles = info?.radarrProfiles.orEmpty()
                val folders = info?.radarrRootFolders.orEmpty()
                if (profiles.isEmpty() && folders.isEmpty()) {
                    val profile = draft.qualityProfileId
                    val folder = draft.rootFolder
                    if (profile != null && folder != null) CardBody(stringResource(R.string.service_radarr_current, profile, folder))
                    HintText(stringResource(R.string.service_radarr_choose_hint))
                } else {
                    GroupLabel(stringResource(R.string.service_radarr_profile))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        profiles.forEach { profile ->
                            CineChip(
                                label = profile.name ?: "#${profile.id}",
                                selected = profile.id == draft.qualityProfileId,
                                onClick = { onEdit { it.copy(qualityProfileId = profile.id) } },
                            )
                        }
                    }
                    GroupLabel(stringResource(R.string.service_radarr_folder))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        folders.forEach { folder ->
                            CineChip(
                                label = folder.path,
                                selected = folder.path == draft.rootFolder,
                                onClick = { onEdit { it.copy(rootFolder = folder.path) } },
                            )
                        }
                    }
                }
                CineSwitchRow(
                    title = stringResource(R.string.service_radarr_search),
                    subtitle = stringResource(R.string.service_radarr_search_hint),
                    checked = draft.searchOnAdd,
                    onCheckedChange = { search -> onEdit { it.copy(searchOnAdd = search) } },
                )
            }
        }
        HomelabService.SEERR -> SeerrChoices(draft, info, test, onEdit)
    }
}

/** Serveur Radarr, profil de qualité et dossier envoyés avec chaque demande Seerr. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SeerrChoices(draft: ServiceConfig, info: ConnectionInfo?, test: TestState, onEdit: ServiceEdit) {
    SectionCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CardTitle(CineIcons.Download, stringResource(R.string.service_seerr_options_title))
            val targets = info?.seerrTargets.orEmpty()
            when {
                info == null || test != TestState.Success -> {
                    val profile = draft.qualityProfileId
                    if (profile != null) CardBody(stringResource(R.string.service_radarr_current, profile, draft.rootFolder ?: "–"))
                    HintText(stringResource(R.string.service_seerr_options_test))
                }
                targets.isEmpty() -> HintText(stringResource(R.string.service_seerr_options_unavailable))
                else -> {
                    val selected = targets.firstOrNull { it.id == draft.targetServerId }
                    val options = selected ?: targets.firstOrNull { it.isDefault && !it.is4k } ?: targets.first()
                    if (targets.size > 1) {
                        GroupLabel(stringResource(R.string.service_seerr_target))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CineChip(
                                label = stringResource(R.string.service_seerr_default),
                                selected = selected == null,
                                onClick = { onEdit { it.copy(targetServerId = null, targetServer4k = false, qualityProfileId = null, rootFolder = null) } },
                            )
                            targets.forEach { target ->
                                CineChip(
                                    label = if (target.is4k) stringResource(R.string.service_4k, target.name) else target.name,
                                    selected = target.id == draft.targetServerId,
                                    onClick = {
                                        onEdit { it.copy(targetServerId = target.id, targetServer4k = target.is4k, qualityProfileId = null, rootFolder = null) }
                                    },
                                )
                            }
                        }
                    }
                    GroupLabel(stringResource(R.string.service_radarr_profile))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val defaultProfile = options.profiles.firstOrNull { it.id == options.activeProfileId }?.name
                        CineChip(
                            label = defaultProfile?.let { stringResource(R.string.service_seerr_default_value, it) } ?: stringResource(R.string.service_seerr_default),
                            selected = draft.qualityProfileId == null,
                            onClick = { onEdit { it.copy(qualityProfileId = null) } },
                        )
                        options.profiles.forEach { profile ->
                            CineChip(
                                label = profile.name ?: "#${profile.id}",
                                selected = profile.id == draft.qualityProfileId,
                                onClick = { onEdit { it.copy(qualityProfileId = profile.id) } },
                            )
                        }
                    }
                    GroupLabel(stringResource(R.string.service_radarr_folder))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CineChip(
                            label = options.activeDirectory?.let { stringResource(R.string.service_seerr_default_value, it) } ?: stringResource(R.string.service_seerr_default),
                            selected = draft.rootFolder == null,
                            onClick = { onEdit { it.copy(rootFolder = null) } },
                        )
                        options.rootFolders.forEach { folder ->
                            CineChip(
                                label = folder.path,
                                selected = folder.path == draft.rootFolder,
                                onClick = { onEdit { it.copy(rootFolder = folder.path) } },
                            )
                        }
                    }
                    HintText(stringResource(R.string.service_seerr_options_hint))
                }
            }
        }
    }
}

/** En-têtes supplémentaires (Cloudflare Access, proxy), repliés par défaut. */
@Composable
private fun AdvancedSection(draft: ServiceConfig, onEdit: ServiceEdit) {
    var expanded by rememberSaveable { mutableStateOf(draft.headers.isNotBlank()) }
    SectionCard {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button) { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.service_advanced),
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    CineIcons.ChevronRight,
                    contentDescription = null,
                    tint = CineColors.TextMuted,
                    modifier = Modifier.size(20.dp).rotate(if (expanded) 90f else 0f),
                )
            }
            if (expanded) {
                CineTextField(
                    value = draft.headers,
                    onValueChange = { headers -> onEdit { it.copy(headers = headers) } },
                    label = stringResource(R.string.service_headers_label),
                    placeholder = "CF-Access-Client-Id: …",
                    singleLine = false,
                    minLines = 2,
                    imeAction = ImeAction.Default,
                )
                HintText(stringResource(R.string.service_headers_hint))
            }
        }
    }
}
