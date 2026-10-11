package io.github.deedg.cineswipe.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.deedg.cineswipe.R
import io.github.deedg.cineswipe.data.db.Availability
import io.github.deedg.cineswipe.data.db.CastMember
import io.github.deedg.cineswipe.data.db.WatchStatus
import io.github.deedg.cineswipe.data.homelab.HomelabService
import io.github.deedg.cineswipe.data.model.MovieDetails
import io.github.deedg.cineswipe.domain.TmdbImageSize
import io.github.deedg.cineswipe.ui.common.cineViewModel
import io.github.deedg.cineswipe.ui.components.ActorAvatar
import io.github.deedg.cineswipe.ui.components.BlurredBackdrop
import io.github.deedg.cineswipe.ui.components.CineButton
import io.github.deedg.cineswipe.ui.components.CineSecondaryButton
import io.github.deedg.cineswipe.ui.components.DirectedBy
import io.github.deedg.cineswipe.ui.components.EloBadge
import io.github.deedg.cineswipe.ui.components.EmptyState
import io.github.deedg.cineswipe.ui.components.GlassIconButton
import io.github.deedg.cineswipe.ui.components.LocalToaster
import io.github.deedg.cineswipe.ui.components.PosterImage
import io.github.deedg.cineswipe.ui.components.RatingPill
import io.github.deedg.cineswipe.ui.components.StarRow
import io.github.deedg.cineswipe.ui.components.StatusBadge
import io.github.deedg.cineswipe.ui.components.WatchButtons
import io.github.deedg.cineswipe.ui.components.WatchProvidersSection
import io.github.deedg.cineswipe.ui.components.openWatchLink
import io.github.deedg.cineswipe.ui.icons.CineIcons
import io.github.deedg.cineswipe.ui.lists.AddToListButton
import io.github.deedg.cineswipe.ui.theme.CineColors
import io.github.deedg.cineswipe.ui.tutorial.TutorialTarget
import io.github.deedg.cineswipe.ui.tutorial.tutorialTarget
import java.util.Locale

/** Actions de la fiche film. */
private class DetailActions(
    val onBack: () -> Unit,
    val onMarkWatched: () -> Unit,
    val onUnmarkWatched: () -> Unit,
    val onSetStatus: (WatchStatus) -> Unit,
    val onLightning: () -> Unit,
    val onRequest: () -> Unit,
    val onWatch: (HomelabService) -> Unit,
)

@Composable
fun MovieDetailRoute(tmdbId: Long, onBack: () -> Unit, onLightning: (Long) -> Unit) {
    val viewModel = cineViewModel(key = "movie-$tmdbId") {
        MovieDetailViewModel(tmdbId, it.library, it.tmdb, it.homelab, it.settings)
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val toaster = LocalToaster.current
    val resources = LocalResources.current
    val messages = mapOf(
        WatchStatus.WATCHED to stringResource(R.string.toast_marked_watched),
        WatchStatus.WATCHLIST to stringResource(R.string.toast_added_watchlist),
        WatchStatus.DISMISSED to stringResource(R.string.toast_dismissed),
    )
    val unwatchedMessage = stringResource(R.string.toast_unwatched)
    val removedFromListMessage = stringResource(R.string.detail_removed_watchlist)
    val undismissedMessage = stringResource(R.string.detail_undismissed)
    val currentStatus = state.details?.user?.status ?: WatchStatus.NONE
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val missingFormat = stringResource(R.string.tonight_not_on_server)
    val demoWatch = stringResource(R.string.demo_watch)
    val cantOpen = stringResource(R.string.watch_cant_open)
    LaunchedEffect(viewModel) {
        viewModel.links.collect { result ->
            when (result) {
                is WatchLinkResult.Open -> if (!openWatchLink(context, uriHandler, result.link) { toaster.show(demoWatch.format(it.service.displayName)) }) toaster.show(cantOpen.format(result.link.service.displayName))
                is WatchLinkResult.Missing -> toaster.show(missingFormat.format(result.service.displayName))
            }
        }
    }

    MovieDetailScreen(
        state = state,
        actions = DetailActions(
            onBack = onBack,
            onMarkWatched = {
                viewModel.markWatched()
                messages[WatchStatus.WATCHED]?.let(toaster::show)
            },
            onUnmarkWatched = {
                viewModel.unmarkWatched()
                toaster.show(unwatchedMessage)
            },
            onSetStatus = { status ->
                viewModel.setStatus(status)
                val message = when {
                    status != WatchStatus.NONE -> messages[status]
                    currentStatus == WatchStatus.WATCHLIST -> removedFromListMessage
                    currentStatus == WatchStatus.DISMISSED -> undismissedMessage
                    else -> null
                }
                message?.let(toaster::show)
            },
            onLightning = { onLightning(tmdbId) },
            onRequest = {
                viewModel.request { service -> toaster.show(resources.getString(R.string.toast_requested, service.displayName)) }
            },
            onWatch = viewModel::watch,
        ),
    )
}

@Composable
private fun MovieDetailScreen(state: MovieDetailUiState, actions: DetailActions) {
    Box(Modifier.fillMaxSize().background(CineColors.Black)) {
        val details = state.details
        when {
            state.loading -> CircularProgressIndicator(color = CineColors.Red, modifier = Modifier.align(Alignment.Center))
            details == null -> {
                EmptyState(icon = CineIcons.Film, title = stringResource(R.string.detail_not_found), message = "")
                GlassIconButton(
                    icon = CineIcons.Back,
                    contentDescription = stringResource(R.string.action_back),
                    onClick = actions.onBack,
                    modifier = Modifier.statusBarsPadding().padding(16.dp),
                )
            }
            else -> MovieDetailContent(details, state, actions)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MovieDetailContent(
    details: MovieDetails,
    state: MovieDetailUiState,
    actions: DetailActions,
) {
    val showAvailability = state.showAvailability
    val requestService = state.requestService
    val movie = details.movie
    val status = details.user?.status ?: WatchStatus.NONE
    val uriHandler = LocalUriHandler.current
    var confirmUnwatch by rememberSaveable { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        BlurredBackdrop(path = movie.posterPath, modifier = Modifier.fillMaxSize(), dim = 0.55f)
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.1f),
                        0.45f to Color.Black.copy(alpha = 0.6f),
                        1f to Color.Black,
                    ),
                ),
        )
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                GlassIconButton(
                    icon = CineIcons.Back,
                    contentDescription = stringResource(R.string.action_back),
                    onClick = actions.onBack,
                    modifier = Modifier.tutorialTarget(TutorialTarget.BACK),
                )
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (details.isWatched) StatusBadge(stringResource(R.string.badge_watched), CineColors.Red, icon = CineIcons.Eye)
                    if (status == WatchStatus.WATCHLIST) {
                        StatusBadge(stringResource(R.string.badge_watchlist), CineColors.TmdbBlue, icon = CineIcons.Bookmark)
                    }
                    if (showAvailability && details.user?.availability == Availability.AVAILABLE) {
                        StatusBadge(stringResource(R.string.badge_available), CineColors.TextSecondary, icon = CineIcons.Server)
                    }
                    if (showAvailability && details.user?.availability == Availability.REQUESTED) {
                        StatusBadge(stringResource(R.string.badge_requested), CineColors.Warning, icon = CineIcons.Download)
                    }
                }
            }
            PosterImage(
                path = movie.posterPath,
                imageSize = TmdbImageSize.Poster,
                contentDescription = stringResource(R.string.poster_description, movie.title),
                modifier = Modifier
                    .fillMaxWidth(0.62f)
                    .aspectRatio(2f / 3f)
                    .shadow(30.dp, RoundedCornerShape(12.dp))
                    .clip(RoundedCornerShape(12.dp)),
            )
            Text(
                text = movie.title,
                color = Color.White,
                fontSize = 28.sp,
                lineHeight = 31.sp,
                fontWeight = FontWeight.ExtraBold,
                textAlign = TextAlign.Center,
            )
            YearAndRuntime(year = movie.year, runtimeMinutes = movie.runtimeMinutes)
            DirectedBy(movie, color = CineColors.TextSoft, fontSize = 15.sp, textAlign = TextAlign.Center)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                movie.tmdbRating?.let {
                    RatingPill(label = stringResource(R.string.tmdb_label), value = String.format(Locale.FRANCE, "%.1f", it))
                }
                if (details.isWatched) {
                    details.user?.let { EloBadge(rating = it.rating, large = true) }
                    details.stars?.let { StarRow(stars = it, provisional = details.provisional, starSize = 18.dp) }
                }
            }
            if (movie.cast.isNotEmpty()) CastRow(movie.cast)
            Synopsis(text = movie.overview ?: stringResource(R.string.detail_no_overview))

            // Sur le serveur : un bouton par serveur média qui a le film.
            if (state.watchOn.isNotEmpty()) {
                Text(
                    stringResource(R.string.watch_title),
                    color = CineColors.TextSecondary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.fillMaxWidth(),
                )
                WatchButtons(state.watchOn, onWatch = actions.onWatch)
            }

            // Boutons pleine largeur : côte à côte, « Bande-annonce » et « Marquer comme vu »
            // passaient sur deux lignes.
            movie.trailerKey?.let { key ->
                CineButton(
                    text = stringResource(R.string.detail_trailer),
                    icon = CineIcons.Play,
                    onClick = { runCatching { uriHandler.openUri("https://www.youtube.com/watch?v=$key") } },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (details.isWatched) {
                CineSecondaryButton(
                    text = stringResource(R.string.detail_unmark_watched),
                    icon = CineIcons.EyeOff,
                    onClick = { confirmUnwatch = true },
                    contentColor = CineColors.Warning,
                    borderColor = CineColors.Warning.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                CineSecondaryButton(
                    text = stringResource(R.string.detail_mark_watched),
                    icon = CineIcons.Eye,
                    onClick = actions.onMarkWatched,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (!details.isWatched) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    val inWatchlist = status == WatchStatus.WATCHLIST
                    CineSecondaryButton(
                        text = stringResource(if (inWatchlist) R.string.detail_in_watchlist else R.string.detail_add_watchlist),
                        icon = if (inWatchlist) CineIcons.Check else CineIcons.Bookmark,
                        onClick = { actions.onSetStatus(if (inWatchlist) WatchStatus.NONE else WatchStatus.WATCHLIST) },
                        contentColor = if (inWatchlist) CineColors.TmdbBlue else Color.White,
                        modifier = Modifier.weight(1f),
                    )
                    val dismissed = status == WatchStatus.DISMISSED
                    CineSecondaryButton(
                        text = stringResource(if (dismissed) R.string.detail_undismiss else R.string.detail_dismiss),
                        icon = CineIcons.ThumbsDown,
                        onClick = { actions.onSetStatus(if (dismissed) WatchStatus.NONE else WatchStatus.DISMISSED) },
                        modifier = Modifier.weight(1f),
                    )
                }
                // Homelab : le film n'est ni sur le serveur ni demandé.
                val availability = details.user?.availability ?: Availability.NONE
                if (showAvailability && requestService != null && availability == Availability.NONE) {
                    CineSecondaryButton(
                        text = stringResource(R.string.detail_request, requestService.displayName),
                        icon = CineIcons.Download,
                        onClick = actions.onRequest,
                        contentColor = CineColors.Success,
                        borderColor = CineColors.Success.copy(alpha = 0.5f),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            AddToListButton(tmdbId = movie.tmdbId, modifier = Modifier.fillMaxWidth())
            // Ni sur le serveur ni demandé (ou pas de homelab) : où le regarder ailleurs.
            state.providers?.let { WatchProvidersSection(it, uriHandler) }
            CineSecondaryButton(
                text = stringResource(R.string.detail_lightning),
                icon = CineIcons.Lightning,
                onClick = actions.onLightning,
                modifier = Modifier.fillMaxWidth(),
            )
            if (movie.genres.isNotEmpty()) {
                Text(
                    text = movie.genres.joinToString(", "),
                    color = CineColors.TextMuted,
                    fontSize = 13.sp,
                    fontStyle = FontStyle.Italic,
                    textAlign = TextAlign.End,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (confirmUnwatch) {
        AlertDialog(
            onDismissRequest = { confirmUnwatch = false },
            containerColor = CineColors.Surface,
            title = { Text(stringResource(R.string.detail_unwatch_title), color = Color.White) },
            text = { Text(stringResource(R.string.detail_unwatch_message), color = CineColors.TextSecondary) },
            confirmButton = {
                TextButton(onClick = {
                    confirmUnwatch = false
                    actions.onUnmarkWatched()
                }) {
                    Text(stringResource(R.string.detail_unwatch_confirm), color = CineColors.Red, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmUnwatch = false }) {
                    Text(stringResource(R.string.action_cancel), color = CineColors.TextSecondary)
                }
            },
        )
    }
}

@Composable
private fun YearAndRuntime(year: Int?, runtimeMinutes: Int?) {
    val parts = listOfNotNull(
        year?.let { stringResource(R.string.year_in_parenthesis, it) },
        runtimeMinutes?.let { stringResource(R.string.detail_runtime, it / 60, it % 60) },
    )
    if (parts.isNotEmpty()) {
        Text(parts.joinToString("  ·  "), color = CineColors.TextMuted, fontSize = 18.sp)
    }
}

@Composable
private fun CastRow(cast: List<CastMember>) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(cast) { member -> ActorAvatar(member) }
    }
}

@Composable
private fun Synopsis(text: String) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var overflowing by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Text(
            text = text,
            color = CineColors.TextSoft,
            fontSize = 15.sp,
            lineHeight = 21.sp,
            maxLines = if (expanded) Int.MAX_VALUE else 4,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { if (!expanded) overflowing = it.hasVisualOverflow },
        )
        if (overflowing || expanded) {
            Text(
                text = stringResource(if (expanded) R.string.detail_less else R.string.detail_more),
                color = CineColors.Red,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .padding(top = 4.dp)
                    .clickable { expanded = !expanded },
            )
        }
    }
}
