package io.github.deedg.cineswipe.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.deedg.cineswipe.R
import io.github.deedg.cineswipe.ui.icons.CineIcons
import io.github.deedg.cineswipe.ui.theme.CineColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Taille des titres : écran d'un onglet, ou écran ouvert par-dessus (avec un retour). */
val HeaderTitleSize = 26.sp
val HeaderSubTitleSize = 22.sp

/** Plus petite taille d'un titre qui rétrécit pour tenir sur une ligne (petit téléphone). */
private val HeaderMinTitleSize = 17.sp

/** Titre d'en-tête sur une ligne : il rétrécit plutôt que d'être coupé. */
@Composable
private fun HeaderTitle(text: String, size: TextUnit, modifier: Modifier = Modifier) {
    BasicText(
        text = text,
        style = TextStyle(color = Color.White, fontSize = size, fontWeight = FontWeight.Black),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        autoSize = TextAutoSize.StepBased(minFontSize = HeaderMinTitleSize, maxFontSize = size, stepSize = 1.sp),
        modifier = modifier,
    )
}

/** Hauteur commune des en-têtes : le contenu commence au même endroit sur tous les écrans. */
val HeaderHeight = 56.dp

/**
 * En-tête de tous les écrans : retour (écran ouvert par-dessus), titre à gauche, sous-titre
 * facultatif, actions à droite. Sur un onglet, [worldTab] place la pastille Films | Séries tout à
 * droite, toujours au même endroit.
 */
@Composable
fun CineHeader(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    subtitle: (@Composable () -> Unit)? = null,
    worldTab: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = HeaderHeight),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (onBack != null) {
            GlassIconButton(icon = CineIcons.Back, contentDescription = stringResource(R.string.action_back), onClick = onBack)
        }
        Column(Modifier.weight(1f)) {
            HeaderTitle(title, if (onBack == null) HeaderTitleSize else HeaderSubTitleSize)
            subtitle?.invoke()
        }
        actions()
        if (worldTab != null) WorldSwitch(tabName = worldTab)
    }
}

/** Ligne sous le titre d'un en-tête (fiabilité, progression…). */
@Composable
fun HeaderSubtitle(text: String, modifier: Modifier = Modifier, color: Color = CineColors.TextMuted) {
    Text(text, color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = modifier)
}

/** Grand titre d'écran, avec actions optionnelles : même rendu que [CineHeader]. */
@Composable
fun ScreenTitle(
    text: String,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = HeaderHeight),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        leading?.invoke()
        HeaderTitle(text, if (leading == null) HeaderTitleSize else HeaderSubTitleSize, Modifier.weight(1f))
        trailing?.invoke()
    }
}

/** État vide ou d'attente, centré, avec une action optionnelle. */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Box(modifier = modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.widthIn(max = 420.dp),
        ) {
            Icon(icon, contentDescription = null, tint = CineColors.Red, modifier = Modifier.size(48.dp))
            Text(title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Text(message, color = CineColors.TextMuted, fontSize = 15.sp, textAlign = TextAlign.Center, lineHeight = 21.sp)
            if (actionLabel != null && onAction != null) {
                Spacer(Modifier.height(8.dp))
                CineButton(text = actionLabel, onClick = onAction)
            }
        }
    }
}

/** Carte sombre de section (réglages, statistiques), cliquable si [onClick] est donné. */
@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(15.dp)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(CineColors.Surface)
            .border(1.dp, CineColors.Border, shape)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(20.dp),
    ) {
        content()
    }
}

/** Notifications brèves en haut de l'écran, comme les toasts de la V4. */
class Toaster(
    val hostState: SnackbarHostState,
    private val scope: CoroutineScope,
) {
    fun show(message: String) {
        scope.launch {
            hostState.currentSnackbarData?.dismiss()
            hostState.showSnackbar(message)
        }
    }
}

val LocalToaster = staticCompositionLocalOf<Toaster> { error("Toaster absent") }

@Composable
fun CineToast(message: String, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(30.dp)
    Text(
        text = message,
        color = Color.White,
        fontWeight = FontWeight.Bold,
        fontSize = 15.sp,
        modifier = modifier
            .padding(horizontal = 20.dp)
            .shadow(16.dp, shape)
            .clip(shape)
            .background(CineColors.Toast)
            .border(1.dp, CineColors.BorderStrong, shape)
            .padding(horizontal = 20.dp, vertical = 12.dp),
    )
}
