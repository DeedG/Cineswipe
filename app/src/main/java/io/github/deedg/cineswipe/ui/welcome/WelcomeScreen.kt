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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import io.github.deedg.cineswipe.ui.journey.JourneyStepRow
import io.github.deedg.cineswipe.ui.journey.StepStatus
import io.github.deedg.cineswipe.ui.theme.CineColors

object WelcomeTestTags {
    const val START = "welcome_start"
    const val IMPORT = "welcome_import"
}

/**
 * Accueil du premier lancement : ce que fait l'appli, en trois étapes dans l'ordre où on les fait
 * (recenser, noter en duels, recevoir ses recos), puis le départ sur ses vrais films. Qui a déjà
 * une liste (sauvegarde, Letterboxd…) commence par l'import.
 */
@Composable
fun WelcomeScreen(onStart: () -> Unit, onImport: () -> Unit) {
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
            Text(stringResource(R.string.welcome_steps), color = CineColors.TextMuted, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            JourneyStepRow(1, stringResource(R.string.journey_census_title), stringResource(R.string.journey_census_body), StepStatus.CURRENT)
            JourneyStepRow(2, stringResource(R.string.journey_arena_title), stringResource(R.string.journey_arena_body), StepStatus.CURRENT)
            JourneyStepRow(3, stringResource(R.string.journey_recos_title), stringResource(R.string.journey_recos_body), StepStatus.CURRENT)
            Spacer(Modifier.height(8.dp))
            CineButton(
                text = stringResource(R.string.welcome_start),
                onClick = onStart,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(WelcomeTestTags.START),
            )
            TextButton(onClick = onImport, modifier = Modifier.align(Alignment.CenterHorizontally).testTag(WelcomeTestTags.IMPORT)) {
                Text(stringResource(R.string.welcome_import), color = CineColors.TextSecondary, fontSize = 14.sp)
            }
            Text(stringResource(R.string.welcome_note), color = CineColors.TextMuted, fontSize = 13.sp, lineHeight = 18.sp)
        }
    }
}
