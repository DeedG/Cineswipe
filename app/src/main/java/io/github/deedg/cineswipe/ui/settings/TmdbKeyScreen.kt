package io.github.deedg.cineswipe.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import io.github.deedg.cineswipe.R
import io.github.deedg.cineswipe.data.repository.KeyCheck
import io.github.deedg.cineswipe.data.repository.SettingsRepository
import io.github.deedg.cineswipe.data.repository.TmdbRepository
import io.github.deedg.cineswipe.ui.common.cineViewModel
import io.github.deedg.cineswipe.ui.components.CineButton
import io.github.deedg.cineswipe.ui.components.CineSecondaryButton
import io.github.deedg.cineswipe.ui.components.GlassIconButton
import io.github.deedg.cineswipe.ui.components.LocalToaster
import io.github.deedg.cineswipe.ui.components.ScreenTitle
import io.github.deedg.cineswipe.ui.icons.CineIcons
import io.github.deedg.cineswipe.ui.theme.CineColors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

object TmdbKeyTestTags {
    const val FIELD = "tmdb_key_field"
    const val SAVE = "tmdb_key_save"
    const val LATER = "tmdb_key_later"
}

private const val TMDB_SIGNUP_URL = "https://www.themoviedb.org/signup"
private const val TMDB_API_URL = "https://www.themoviedb.org/settings/api"

class TmdbKeyViewModel(
    private val tmdb: TmdbRepository,
    private val settings: SettingsRepository,
) : ViewModel() {
    private val mutableCheck = MutableStateFlow(KeyCheckState.IDLE)
    val check: StateFlow<KeyCheckState> = mutableCheck.asStateFlow()

    /** La clé est vérifiée auprès de TMDB ; hors ligne, elle est gardée sans vérification. */
    fun save(value: String) {
        if (mutableCheck.value == KeyCheckState.CHECKING || value.isBlank()) return
        mutableCheck.value = KeyCheckState.CHECKING
        viewModelScope.launch {
            mutableCheck.value = when (tmdb.validateKey(value)) {
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
}

/**
 * Guide de la clé TMDB, la seule chose indispensable : créer un compte, demander une clé, la
 * copier puis la coller ici. Ouvert au premier lancement sans clé, en quittant la démo, et depuis
 * tous les écrans qui en ont besoin.
 */
@Composable
fun TmdbKeyRoute(onDone: () -> Unit) {
    val viewModel = cineViewModel { TmdbKeyViewModel(it.tmdb, it.settings) }
    val check by viewModel.check.collectAsStateWithLifecycle()
    val toaster = LocalToaster.current
    val saved = stringResource(R.string.settings_tmdb_saved_toast)
    LaunchedEffect(check) {
        if (check == KeyCheckState.SAVED || check == KeyCheckState.SAVED_UNVERIFIED) {
            if (check == KeyCheckState.SAVED) toaster.show(saved)
            onDone()
        }
    }
    TmdbKeyScreen(check = check, onSave = viewModel::save, onLater = onDone)
}

@Composable
fun TmdbKeyScreen(check: KeyCheckState, onSave: (String) -> Unit, onLater: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var input by rememberSaveable { mutableStateOf("") }
    val checking = check == KeyCheckState.CHECKING
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp)
            .navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ScreenTitle(
            text = stringResource(R.string.tmdb_key_title),
            leading = { GlassIconButton(icon = CineIcons.Back, contentDescription = stringResource(R.string.action_back), onClick = onLater) },
        )
        Text(stringResource(R.string.tmdb_key_intro), color = CineColors.TextSoft, fontSize = 15.sp, lineHeight = 21.sp)
        KeyStep(1, stringResource(R.string.tmdb_key_step1_title), stringResource(R.string.tmdb_key_step1_body)) {
            CineSecondaryButton(
                text = stringResource(R.string.tmdb_key_step1_action),
                icon = CineIcons.Link,
                onClick = { runCatching { uriHandler.openUri(TMDB_SIGNUP_URL) } },
            )
        }
        KeyStep(2, stringResource(R.string.tmdb_key_step2_title), stringResource(R.string.tmdb_key_step2_body)) {
            CineSecondaryButton(
                text = stringResource(R.string.tmdb_key_step2_action),
                icon = CineIcons.Link,
                onClick = { runCatching { uriHandler.openUri(TMDB_API_URL) } },
            )
        }
        KeyStep(3, stringResource(R.string.tmdb_key_step3_title), stringResource(R.string.tmdb_key_step3_body))
        KeyStep(4, stringResource(R.string.tmdb_key_step4_title), stringResource(R.string.tmdb_key_step4_body)) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it.trim() },
                modifier = Modifier.fillMaxWidth().testTag(TmdbKeyTestTags.FIELD),
                enabled = !checking,
                singleLine = true,
                placeholder = { Text(stringResource(R.string.settings_tmdb_hint), color = CineColors.TextFaint) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onSave(input) }),
                shape = RoundedCornerShape(10.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = CineColors.SurfaceHigh,
                    unfocusedContainerColor = CineColors.SurfaceHigh,
                    disabledContainerColor = CineColors.SurfaceHigh,
                    focusedBorderColor = CineColors.Red,
                    unfocusedBorderColor = CineColors.BorderStrong,
                    cursorColor = CineColors.Red,
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                ),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                CineSecondaryButton(
                    text = stringResource(R.string.tmdb_key_paste),
                    onClick = {
                        scope.launch {
                            val text = clipboard.getClipEntry()?.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
                            if (!text.isNullOrBlank()) input = text.trim()
                        }
                    },
                    enabled = !checking,
                )
                CineButton(
                    text = stringResource(R.string.settings_tmdb_save),
                    onClick = { onSave(input) },
                    enabled = input.isNotBlank() && !checking,
                    modifier = Modifier.weight(1f).testTag(TmdbKeyTestTags.SAVE),
                )
            }
            when (check) {
                KeyCheckState.CHECKING -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator(color = CineColors.Red, modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(stringResource(R.string.settings_tmdb_checking), color = CineColors.TextSecondary, fontSize = 14.sp)
                }
                KeyCheckState.REJECTED -> Text(stringResource(R.string.settings_tmdb_rejected), color = CineColors.Red, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                else -> Unit
            }
        }
        TextButton(onClick = onLater, modifier = Modifier.align(Alignment.CenterHorizontally).testTag(TmdbKeyTestTags.LATER)) {
            Text(stringResource(R.string.tmdb_key_later), color = CineColors.TextMuted)
        }
    }
}

@Composable
private fun KeyStep(number: Int, title: String, body: String, content: (@Composable () -> Unit)? = null) {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(
            Modifier
                .size(30.dp)
                .clip(CircleShape)
                .background(CineColors.Red),
            contentAlignment = Alignment.Center,
        ) {
            Text(number.toString(), color = Color.White, fontWeight = FontWeight.Black, fontSize = 15.sp)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold)
            Text(body, color = CineColors.TextSecondary, fontSize = 14.sp, lineHeight = 20.sp)
            content?.invoke()
        }
    }
}
