package io.github.deedg.cineswipe.ui.components

import android.app.Activity
import android.app.ActivityOptions
import android.content.Intent
import android.os.Build
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.deedg.cineswipe.CineSwipeApp
import io.github.deedg.cineswipe.MainActivity
import io.github.deedg.cineswipe.R
import io.github.deedg.cineswipe.data.model.MediaKind
import io.github.deedg.cineswipe.ui.theme.Accent
import io.github.deedg.cineswipe.ui.theme.CineAccent
import io.github.deedg.cineswipe.ui.theme.CineColors
import kotlinx.coroutines.launch

object WorldSwitchTestTags {
    fun segment(kind: MediaKind) = "world_switch_${kind.name.lowercase()}"
}

/**
 * Pastille « Films | Séries » en haut des écrans principaux et dans les réglages. Toucher l'autre
 * moitié fait glisser la pastille, fond le rouge dans le violet (ou l'inverse), puis l'appli
 * repart dans l'autre monde, sur le même onglet. Absente pendant la démo.
 *
 * [tabName] : onglet à rouvrir (nom de l'onglet), null pour l'écran de départ.
 */
/** Vrai si la pastille « Films | Séries » s'affiche (pas pendant la démo). */
@Composable
fun worldSwitchShown(): Boolean = !(LocalContext.current.applicationContext as CineSwipeApp).inDemo

@Composable
fun WorldSwitch(tabName: String?, modifier: Modifier = Modifier) {
    val app = LocalContext.current.applicationContext as CineSwipeApp
    if (app.inDemo) return
    val activity = LocalActivity.current
    val scope = rememberCoroutineScope()
    val current = app.kind
    // Position de la pastille : 0 = Films, 1 = Séries.
    val thumb = remember { Animatable(if (current.isSeries) 1f else 0f) }
    var switching by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(16.dp)
    val segmentWidth = 64.dp

    fun switchTo(target: MediaKind) {
        if (switching || target == current) return
        switching = true
        scope.launch {
            val from = CineAccent.current
            val to = Accent.of(target)
            val end = if (target.isSeries) 1f else 0f
            thumb.animateTo(end, tween(SWITCH_MS, easing = FastOutSlowInEasing)) {
                val progress = if (target.isSeries) value else 1f - value
                CineAccent.current = Accent.lerp(from, to, progress)
            }
            app.switchWorld(target)
            app.pendingTab = tabName
            activity?.restartInWorld()
        }
    }

    Box(
        modifier
            .height(32.dp)
            .width(segmentWidth * 2 + 6.dp)
            .clip(shape)
            .background(CineColors.Glass)
            .border(1.dp, CineColors.BorderSoft, shape)
            .padding(3.dp),
    ) {
        Box(
            Modifier
                .offset(x = segmentWidth * thumb.value)
                .width(segmentWidth)
                .fillMaxHeight()
                .clip(RoundedCornerShape(13.dp))
                .background(CineColors.Red),
        )
        Row(Modifier.fillMaxHeight()) {
            MediaKind.entries.forEach { kind ->
                val selected = kind == current
                Box(
                    Modifier
                        .width(segmentWidth)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(13.dp))
                        .clickable(role = Role.Tab, onClick = { switchTo(kind) })
                        .semantics { this.selected = selected }
                        .testTag(WorldSwitchTestTags.segment(kind)),
                    contentAlignment = Alignment.Center,
                ) {
                    val onThumb = if (kind.isSeries) thumb.value > 0.5f else thumb.value < 0.5f
                    Text(
                        text = stringResource(if (kind.isSeries) R.string.world_series else R.string.world_films),
                        color = if (onThumb) Color.White else CineColors.TextSecondary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

private const val SWITCH_MS = 350

/** Relance l'écran principal en fondu : tous les écrans repartent sur le monde en service. */
fun Activity.restartInWorld() {
    val options = ActivityOptions.makeCustomAnimation(this, android.R.anim.fade_in, android.R.anim.fade_out)
    startActivity(
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        options.toBundle(),
    )
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        overrideActivityTransition(Activity.OVERRIDE_TRANSITION_CLOSE, android.R.anim.fade_in, android.R.anim.fade_out)
    }
    finish()
}
