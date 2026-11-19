package io.github.deedg.cineswipe.ui.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.deedg.cineswipe.R
import io.github.deedg.cineswipe.domain.TmdbImageSize
import io.github.deedg.cineswipe.domain.reco.RecoQuality
import io.github.deedg.cineswipe.domain.reco.RecoQualityAdvice
import io.github.deedg.cineswipe.domain.reco.RecoQualityLevel
import io.github.deedg.cineswipe.ui.common.cineViewModel
import io.github.deedg.cineswipe.ui.components.CineButton
import io.github.deedg.cineswipe.ui.components.CineHeader
import io.github.deedg.cineswipe.ui.components.CineSecondaryButton
import io.github.deedg.cineswipe.ui.components.GlassIconButton
import io.github.deedg.cineswipe.ui.components.PosterImage
import io.github.deedg.cineswipe.ui.components.ScreenTitle
import io.github.deedg.cineswipe.ui.components.StarRow
import io.github.deedg.cineswipe.ui.components.WorldSwitch
import io.github.deedg.cineswipe.ui.icons.CineIcons
import io.github.deedg.cineswipe.ui.library.LibraryFilter
import io.github.deedg.cineswipe.ui.lists.ListsShelfRoute
import io.github.deedg.cineswipe.ui.navigation.Tab
import io.github.deedg.cineswipe.ui.theme.CineColors
import io.github.deedg.cineswipe.ui.update.UpdateBanner

@Composable
fun ProfileRoute(
    onOpenLibrary: (LibraryFilter) -> Unit,
    onOpenDuels: () -> Unit,
    onOpenCensus: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenMovie: (Long) -> Unit,
    onOpenStats: () -> Unit = {},
    onOpenList: (Long) -> Unit = {},
    onOpenRetro: () -> Unit = {},
    onOpenFriends: () -> Unit = {},
    onOpenArena: () -> Unit = {},
) {
    val viewModel = cineViewModel { ProfileViewModel(it.library, it.settings, it.arena.observeReliability(), it.kind.isSeries) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    ProfileScreen(
        state = state,
        onOpenLibrary = onOpenLibrary,
        onOpenDuels = onOpenDuels,
        onOpenCensus = onOpenCensus,
        onOpenSettings = onOpenSettings,
        onOpenMovie = onOpenMovie,
        onOpenStats = onOpenStats,
        onOpenRetro = onOpenRetro,
        onOpenFriends = onOpenFriends,
        onOpenArena = onOpenArena,
        listsSection = { ListsShelfRoute(onOpenList = onOpenList, modifier = Modifier.padding(top = 6.dp)) },
        updateBanner = { UpdateBanner() },
    )
}

/** Repères pour les tests d'interface. */
object ProfileTestTags {
    const val FRIENDS = "profile_friends"
    const val WATCHED = "profile-watched"
    const val DUELS = "profile-duels"
    const val STATS = "profile-stats"
    const val RETRO = "profile-retro"
    const val RECO_QUALITY = "profile-reco-quality"
    const val RECO_QUALITY_ACTION = "profile-reco-quality-action"
}

@Composable
fun ProfileScreen(
    state: ProfileUiState,
    onOpenLibrary: (LibraryFilter) -> Unit,
    onOpenDuels: () -> Unit,
    onOpenCensus: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenMovie: (Long) -> Unit,
    onOpenStats: () -> Unit = {},
    onOpenRetro: () -> Unit = {},
    onOpenFriends: () -> Unit = {},
    onOpenArena: () -> Unit = {},
    /** « Mes listes » (listes perso), sous les stats. */
    listsSection: @Composable () -> Unit = {},
    /** Nouvelle version disponible (bandeau sous le titre). */
    updateBanner: @Composable () -> Unit = {},
) {
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(15.dp),
    ) {
        CineHeader(
            title = stringResource(R.string.profile_title),
            worldTab = Tab.PROFILE.name,
            actions = {
                GlassIconButton(
                    icon = CineIcons.Settings,
                    contentDescription = stringResource(R.string.profile_settings),
                    onClick = onOpenSettings,
                )
            },
        )
        updateBanner()
        // Les compteurs en une rangée : un toucher ouvre la bibliothèque filtrée ou l'historique.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile(
                icon = CineIcons.Eye,
                label = stringResource(R.string.profile_stat_watched),
                value = state.stats.watched,
                loading = state.loading,
                onClick = { onOpenLibrary(LibraryFilter.WATCHED) },
                modifier = Modifier.weight(1f).testTag(ProfileTestTags.WATCHED),
            )
            StatTile(
                icon = CineIcons.Arena,
                label = stringResource(R.string.profile_stat_duels),
                value = state.stats.duels,
                loading = state.loading,
                onClick = onOpenDuels,
                modifier = Modifier.weight(1f).testTag(ProfileTestTags.DUELS),
            )
            StatTile(
                icon = CineIcons.Bookmark,
                label = stringResource(R.string.profile_stat_watchlist),
                value = state.stats.watchlist,
                loading = state.loading,
                onClick = { onOpenLibrary(LibraryFilter.WATCHLIST) },
                modifier = Modifier.weight(1f),
            )
            if (state.showAvailability) {
                StatTile(
                    icon = CineIcons.Server,
                    label = stringResource(R.string.profile_stat_available_short),
                    value = state.stats.available,
                    loading = state.loading,
                    onClick = { onOpenLibrary(LibraryFilter.AVAILABLE) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        state.recoQuality?.let { quality ->
            RecoQualityCard(quality, onAct = if (quality.advice == RecoQualityAdvice.DUELS) onOpenArena else onOpenCensus)
        }

        // Mes films : la bibliothèque, le haut du classement, les listes perso.
        SectionLabel(stringResource(R.string.profile_section_mine))
        CineButton(
            text = stringResource(R.string.profile_open_library),
            icon = CineIcons.Film,
            onClick = { onOpenLibrary(LibraryFilter.ALL) },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            Modifier.padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(CineIcons.Trophy, contentDescription = null, tint = CineColors.Red, modifier = Modifier.size(20.dp))
            Text(stringResource(R.string.profile_hall_of_fame), color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Black)
        }
        if (state.hallOfFame.isEmpty() && !state.loading) {
            Text(
                stringResource(R.string.profile_hall_of_fame_empty),
                color = CineColors.TextFaint,
                fontStyle = FontStyle.Italic,
                fontSize = 14.sp,
            )
        }
        state.hallOfFame.forEach { entry ->
            HallOfFameCard(entry = entry, onClick = { onOpenMovie(entry.tmdbId) })
        }
        listsSection()

        // Pour aller plus loin : une liste, pas une pile de boutons.
        SectionLabel(stringResource(R.string.profile_section_more))
        val shape = RoundedCornerShape(15.dp)
        Column(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(CineColors.Surface)
                .border(1.dp, CineColors.Border, shape),
        ) {
            LinkRow(CineIcons.BarChart, stringResource(R.string.stats_open), onOpenStats, Modifier.testTag(ProfileTestTags.STATS))
            LinkDivider()
            LinkRow(CineIcons.Calendar, stringResource(R.string.retro_open), onOpenRetro, Modifier.testTag(ProfileTestTags.RETRO))
            LinkDivider()
            LinkRow(CineIcons.QrCode, stringResource(R.string.friends_open), onOpenFriends, Modifier.testTag(ProfileTestTags.FRIENDS))
        }
    }
}

/** Titre de bloc du profil. */
@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        color = CineColors.TextMuted,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp,
        modifier = Modifier.padding(top = 10.dp),
    )
}

/** Compteur du profil, en tuile. */
@Composable
private fun StatTile(icon: ImageVector, label: String, value: Int, loading: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(15.dp)
    Column(
        modifier
            .clip(shape)
            .background(CineColors.Surface)
            .border(1.dp, CineColors.Border, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(icon, contentDescription = null, tint = CineColors.TextSecondary, modifier = Modifier.size(18.dp))
        Text(if (loading) "–" else value.toString(), color = CineColors.Red, fontSize = 26.sp, fontWeight = FontWeight.Black, maxLines = 1)
        Text(label, color = CineColors.TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, lineHeight = 15.sp)
    }
}

/** Ligne d'une liste de liens : icône, texte, chevron. */
@Composable
private fun LinkRow(icon: ImageVector, text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
        Text(text, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        Icon(CineIcons.ChevronRight, contentDescription = null, tint = CineColors.TextFaint, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun LinkDivider() {
    Box(Modifier.fillMaxWidth().padding(start = 50.dp).height(1.dp).background(CineColors.Border))
}

/**
 * Finesse des recos : un niveau, ce sur quoi il repose (titres vus, fiabilité du classement) et ce
 * qui le ferait monter. Donne une raison de continuer à recenser et à noter, sans insister.
 */
@Composable
private fun RecoQualityCard(quality: RecoQuality, onAct: () -> Unit) {
    val shape = RoundedCornerShape(15.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(CineColors.Surface)
            .border(1.dp, CineColors.Border, shape)
            .padding(horizontal = 18.dp, vertical = 16.dp)
            .testTag(ProfileTestTags.RECO_QUALITY),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val level = when (quality.level) {
            RecoQualityLevel.STARTING -> R.string.reco_quality_starting
            RecoQualityLevel.FAIR -> R.string.reco_quality_fair
            RecoQualityLevel.GOOD -> R.string.reco_quality_good
            RecoQualityLevel.SHARP -> R.string.reco_quality_sharp
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(CineIcons.Recos, contentDescription = null, tint = CineColors.RedLight, modifier = Modifier.size(20.dp))
            Text(stringResource(level), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }
        LinearProgressIndicator(
            progress = { quality.score },
            modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)),
            color = CineColors.Red,
            trackColor = CineColors.BorderStrong,
            drawStopIndicator = {},
        )
        Text(
            stringResource(R.string.reco_quality_detail, quality.watched, quality.reliability),
            color = CineColors.TextSecondary,
            fontSize = 13.sp,
        )
        val advice = when (quality.advice) {
            RecoQualityAdvice.CENSUS -> R.string.reco_quality_advice_census
            RecoQualityAdvice.DUELS -> R.string.reco_quality_advice_duels
            RecoQualityAdvice.NONE -> R.string.reco_quality_advice_none
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(advice), color = CineColors.TextSoft, fontSize = 14.sp, lineHeight = 19.sp, modifier = Modifier.weight(1f))
            if (quality.advice != RecoQualityAdvice.NONE) {
                Text(
                    stringResource(if (quality.advice == RecoQualityAdvice.DUELS) R.string.reco_quality_action_duels else R.string.reco_quality_action_census),
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(11.dp))
                        .background(CineColors.Red)
                        .clickable(role = Role.Button, onClick = onAct)
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                        .testTag(ProfileTestTags.RECO_QUALITY_ACTION),
                )
            }
        }
    }
}

@Composable
private fun HallOfFameCard(entry: HallOfFameEntry, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(CineColors.Surface)
            .border(1.dp, CineColors.Border, shape)
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            PosterImage(
                path = entry.posterPath,
                imageSize = TmdbImageSize.Thumb,
                contentDescription = stringResource(R.string.poster_description, entry.title),
                modifier = Modifier.width(60.dp).height(90.dp),
            )
            Box(
                Modifier
                    .size(26.dp)
                    .clip(RoundedCornerShape(bottomEnd = 10.dp))
                    .background(CineColors.Red),
                contentAlignment = Alignment.Center,
            ) {
                Text(entry.rank.toString(), color = Color.White, fontWeight = FontWeight.Black, fontSize = 13.sp)
            }
        }
        Column(
            Modifier
                .weight(1f)
                .padding(horizontal = 15.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = entry.title,
                color = Color.White,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 17.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(CineIcons.Trophy, contentDescription = null, tint = CineColors.Red, modifier = Modifier.size(14.dp))
                Text(stringResource(R.string.elo_value, entry.rating), color = CineColors.TextSecondary, fontSize = 13.sp)
                StarRow(stars = entry.stars, provisional = entry.provisional)
            }
        }
    }
}
