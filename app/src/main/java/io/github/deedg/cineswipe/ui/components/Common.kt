package io.github.deedg.cineswipe.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.deedg.cineswipe.ui.theme.CineColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Grand titre d'écran (`.search-header` en V4), avec actions optionnelles. */
@Composable
fun ScreenTitle(
    text: String,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        leading?.invoke()
        Text(
            text = text,
            color = Color.White,
            fontSize = 30.sp,
            fontWeight = FontWeight.Black,
            modifier = Modifier.weight(1f),
        )
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
