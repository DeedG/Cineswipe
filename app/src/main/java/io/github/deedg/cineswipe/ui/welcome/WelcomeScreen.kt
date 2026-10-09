package io.github.deedg.cineswipe.ui.welcome

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.deedg.cineswipe.R
import io.github.deedg.cineswipe.ui.components.CineButton
import io.github.deedg.cineswipe.ui.components.CineSecondaryButton
import io.github.deedg.cineswipe.ui.icons.CineIcons
import io.github.deedg.cineswipe.ui.theme.CineColors

object WelcomeTestTags {
    const val DEMO = "welcome_demo"
    const val START = "welcome_start"
}

/**
 * Accueil du premier lancement : ce que fait l'appli, puis la démo (bac à sable avec les 100
 * films les plus vus) ou le vrai départ (clé TMDB).
 */
@Composable
fun WelcomeScreen(starting: Boolean, onDemo: () -> Unit, onStart: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(CineColors.Black)
            .statusBarsPadding()
            .navigationBarsPadding(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .widthIn(max = 480.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Spacer(Modifier.height(24.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    Modifier
                        .size(14.dp)
                        .clip(CircleShape)
                        .background(CineColors.Red),
                )
                Text(
                    buildAnnotatedString {
                        append("Cine")
                        withStyle(SpanStyle(color = CineColors.Red)) { append("Swipe") }
                    },
                    color = Color.White,
                    fontSize = 40.sp,
                    fontWeight = FontWeight.Black,
                )
            }
            Text(stringResource(R.string.welcome_tagline), color = CineColors.TextSoft, fontSize = 18.sp, lineHeight = 25.sp)
            Spacer(Modifier.height(4.dp))
            Feature(CineIcons.Recos, stringResource(R.string.welcome_recos_title), stringResource(R.string.welcome_recos_body))
            Feature(CineIcons.Arena, stringResource(R.string.welcome_arena_title), stringResource(R.string.welcome_arena_body))
            Feature(CineIcons.Grid, stringResource(R.string.welcome_census_title), stringResource(R.string.welcome_census_body))
            Spacer(Modifier.height(8.dp))
            CineButton(
                text = stringResource(if (starting) R.string.welcome_demo_starting else R.string.welcome_demo),
                icon = if (starting) null else CineIcons.Play,
                onClick = onDemo,
                enabled = !starting,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(WelcomeTestTags.DEMO),
            )
            if (starting) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = CineColors.Red, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
                }
            }
            CineSecondaryButton(
                text = stringResource(R.string.welcome_start),
                onClick = onStart,
                enabled = !starting,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(WelcomeTestTags.START),
            )
            Text(stringResource(R.string.welcome_demo_note), color = CineColors.TextMuted, fontSize = 13.sp, lineHeight = 18.sp)
        }
    }
}

@Composable
private fun Feature(icon: ImageVector, title: String, body: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(
            Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(CineColors.Surface)
                .padding(10.dp),
        ) {
            Icon(icon, contentDescription = null, tint = CineColors.Red, modifier = Modifier.size(24.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
            Text(body, color = CineColors.TextSecondary, fontSize = 14.sp, lineHeight = 20.sp)
        }
    }
}
