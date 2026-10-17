package io.github.deedg.cineswipe.ui.census

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.deedg.cineswipe.R
import io.github.deedg.cineswipe.data.repository.CensusItem
import io.github.deedg.cineswipe.data.repository.CensusProgress
import io.github.deedg.cineswipe.domain.TmdbImageSize
import io.github.deedg.cineswipe.domain.census.CensusChoice
import io.github.deedg.cineswipe.domain.census.CensusFilter
import io.github.deedg.cineswipe.ui.common.cineViewModel
import io.github.deedg.cineswipe.ui.common.isSeriesWorld
import io.github.deedg.cineswipe.ui.components.CineButton
import io.github.deedg.cineswipe.ui.components.CineChip
import io.github.deedg.cineswipe.ui.components.EmptyState
import io.github.deedg.cineswipe.ui.components.GlassIconButton
import io.github.deedg.cineswipe.ui.components.GlassPillButton
import io.github.deedg.cineswipe.ui.components.LocalToaster
import io.github.deedg.cineswipe.ui.components.PosterImage
import io.github.deedg.cineswipe.ui.components.SeasonRequestSheet
import io.github.deedg.cineswipe.ui.icons.CineIcons
import io.github.deedg.cineswipe.ui.theme.CineColors
import io.github.deedg.cineswipe.ui.tutorial.LocalTutorial
import io.github.deedg.cineswipe.ui.tutorial.TutorialEvent
import io.github.deedg.cineswipe.ui.tutorial.TutorialTarget
import io.github.deedg.cineswipe.ui.tutorial.tutorialTarget
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.abs

/** Repères pour les tests d'interface. */
object CensusTestTags {
    fun cell(tmdbId: Long) = "census-cell-$tmdbId"
    const val NEXT = "census-next"
    const val UNDO = "census-undo"
    const val MODE = "census-mode"
    const val CARD = "census-card"
    fun action(choice: CensusChoice) = "census-action-${choice.name.lowercase()}"
}

@Composable
fun CensusRoute(onBack: () -> Unit, onOpenMovie: (Long) -> Unit, onOpenSettings: () -> Unit) {
    val viewModel = cineViewModel { CensusViewModel(it.census, it.settings, series = it.kind.isSeries, homelab = it.homelab, tmdb = it.tmdb) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showHelp by rememberSaveable { mutableStateOf(false) }
    val toaster = LocalToaster.current
    val resources = LocalResources.current
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is CensusEvent.PageDone -> toaster.show(
                    resources.getQuantityString(R.plurals.census_page_done, event.answered, event.answered, event.seen),
                )
                CensusEvent.Undone -> toaster.show(resources.getString(R.string.census_undone))
            }
        }
    }
    // Séries mises à voir : quelles saisons demander (une feuille par série).
    state.seasonQueue.firstOrNull()?.let { ask ->
        val done = state.seasonQueueTotal - state.seasonQueue.size
        SeasonRequestSheet(
            request = ask.request,
            service = ask.service,
            onRequest = { seasons ->
                viewModel.answerSeasons(request = true, seasons = seasons)
                toaster.show(resources.getString(R.string.toast_requested, ask.service.displayName))
            },
            onLater = { viewModel.answerSeasons(request = false, seasons = null) },
            position = if (state.seasonQueueTotal > 1) resources.getString(R.string.season_request_queue, done + 1, state.seasonQueueTotal) else null,
        )
    }
    val tutorial = LocalTutorial.current
    // « Montre-moi » du tuto : la main touche l'affiche du milieu, puis « Suivant ».
    LaunchedEffect(tutorial) {
        tutorial?.handle(TutorialEvent.CENSUS_TAPPED, TutorialEvent.CENSUS_NEXT) { request ->
            val current = viewModel.state.value
            if (current.phase != CensusPhase.READY) return@handle
            when (request.event) {
                TutorialEvent.CENSUS_TAPPED -> current.items.getOrNull(minOf(GRID_CENTER, current.items.lastIndex))?.let { item ->
                    tutorial.event(TutorialEvent.CENSUS_TAPPED)
                    viewModel.tap(item.movie.tmdbId)
                }
                TutorialEvent.CENSUS_NEXT -> if (!current.busy) {
                    tutorial.event(TutorialEvent.CENSUS_NEXT)
                    viewModel.nextPage()
                }
                else -> Unit
            }
        }
    }
    CensusScreen(
        state = state,
        onBack = onBack,
        onTap = { id ->
            tutorial?.event(TutorialEvent.CENSUS_TAPPED)
            viewModel.tap(id)
        },
        onChoose = viewModel::choose,
        onNextPage = {
            tutorial?.event(TutorialEvent.CENSUS_NEXT)
            viewModel.nextPage()
        },
        onAnswer = viewModel::answer,
        onUndo = viewModel::undo,
        onFilter = viewModel::setFilter,
        onToggleMode = { viewModel.setCardsMode(!state.cardsMode) },
        onHelp = { showHelp = true },
        onRetry = viewModel::retry,
        onOpenMovie = onOpenMovie,
        onOpenSettings = onOpenSettings,
    )
    if (showHelp) CensusHelpDialog(onDismiss = { showHelp = false })
}

@Composable
fun CensusScreen(
    state: CensusUiState,
    onBack: () -> Unit,
    onTap: (Long) -> Unit,
    onChoose: (Long, CensusChoice) -> Unit,
    onNextPage: () -> Unit,
    onAnswer: (CensusChoice) -> Unit,
    onUndo: () -> Unit,
    onFilter: (CensusFilter) -> Unit,
    onToggleMode: () -> Unit,
    onHelp: () -> Unit,
    onRetry: () -> Unit,
    onOpenMovie: (Long) -> Unit,
    onOpenSettings: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(CineColors.Black)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        CensusHeader(state = state, onBack = onBack, onToggleMode = onToggleMode, onHelp = onHelp)
        FilterRow(selected = state.filter, onFilter = onFilter)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (state.phase) {
                CensusPhase.LOADING -> Column(
                    Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
                ) {
                    CircularProgressIndicator(color = CineColors.Red)
                    Text(stringResource(R.string.census_loading), color = CineColors.TextMuted)
                }
                CensusPhase.EMPTY -> EmptyState(
                    icon = CineIcons.Check,
                    title = stringResource(R.string.census_empty_title),
                    message = stringResource(R.string.census_empty_message),
                    actionLabel = stringResource(R.string.census_refresh),
                    onAction = onRetry,
                )
                CensusPhase.NEEDS_KEY -> EmptyState(
                    icon = CineIcons.Film,
                    title = stringResource(R.string.feed_needs_key_title),
                    message = stringResource(R.string.feed_needs_key_message),
                    actionLabel = stringResource(R.string.action_open_settings),
                    onAction = onOpenSettings,
                )
                CensusPhase.ERROR -> EmptyState(
                    icon = CineIcons.Alert,
                    title = stringResource(R.string.census_error_title),
                    message = stringResource(R.string.census_error_message),
                    actionLabel = stringResource(R.string.feed_retry),
                    onAction = onRetry,
                )
                CensusPhase.READY -> if (state.cardsMode) {
                    CardsContent(state = state, onAnswer = onAnswer, onOpenMovie = onOpenMovie)
                } else {
                    GridContent(state = state, onTap = onTap, onChoose = onChoose, onOpenMovie = onOpenMovie)
                }
            }
        }
        if (state.phase == CensusPhase.READY) {
            if (state.cardsMode) {
                CardsActions(state = state, onAnswer = onAnswer, onUndo = onUndo)
            } else {
                GridActions(state = state, onNextPage = onNextPage, onUndo = onUndo)
            }
        }
    }
}


@Composable
private fun CensusHeader(state: CensusUiState, onBack: () -> Unit, onToggleMode: () -> Unit, onHelp: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(start = 15.dp, end = 15.dp, top = 10.dp, bottom = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GlassIconButton(
                icon = CineIcons.Back,
                contentDescription = stringResource(R.string.action_back),
                onClick = onBack,
                modifier = Modifier.tutorialTarget(TutorialTarget.BACK),
            )
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.census_title), color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Black)
                state.progress?.let { ProgressText(it, state) }
            }
            GlassIconButton(
                icon = if (state.cardsMode) CineIcons.Grid else CineIcons.Layers,
                contentDescription = stringResource(if (state.cardsMode) R.string.census_mode_grid else R.string.census_mode_cards),
                onClick = onToggleMode,
                modifier = Modifier
                    .testTag(CensusTestTags.MODE)
                    .tutorialTarget(TutorialTarget.CENSUS_MODE),
            )
            GlassIconButton(icon = CineIcons.Help, contentDescription = stringResource(R.string.census_help), onClick = onHelp)
        }
        val progress = state.progress
        val universe = progress?.universe
        if (progress != null && universe != null && universe > 0) {
            LinearProgressIndicator(
                progress = { (progress.answered.toFloat() / universe).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp).height(3.dp).clip(RoundedCornerShape(2.dp)),
                color = CineColors.Red,
                trackColor = CineColors.BorderStrong,
                drawStopIndicator = {},
            )
        }
    }
}

@Composable
private fun ProgressText(progress: CensusProgress, state: CensusUiState) {
    val format = remember { NumberFormat.getIntegerInstance(Locale.FRANCE) }
    val main = if (progress.universe != null && progress.universe > 0) {
        stringResource(R.string.census_progress_of, format.format(progress.answered), format.format(progress.universe), format.format(progress.watched))
    } else {
        stringResource(R.string.census_progress, format.format(progress.answered), format.format(progress.watched))
    }
    val session = if (state.sessionAnswered > 0) " · " + stringResource(R.string.census_session, state.sessionSeen) else ""
    Text(main + session, color = CineColors.TextMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun FilterRow(selected: CensusFilter, onFilter: (CensusFilter) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 15.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CensusFilter.entries.forEach { filter ->
            CineChip(label = stringResource(filter.label), selected = filter == selected, onClick = { onFilter(filter) })
        }
    }
}

private val CensusFilter.label: Int
    get() = when (this) {
        CensusFilter.ALL -> R.string.census_filter_all
        CensusFilter.FRENCH -> R.string.census_filter_french
        CensusFilter.Y2020 -> R.string.census_filter_2020
        CensusFilter.Y2010 -> R.string.census_filter_2010
        CensusFilter.Y2000 -> R.string.census_filter_2000
        CensusFilter.Y1990 -> R.string.census_filter_1990
        CensusFilter.Y1980 -> R.string.census_filter_1980
        CensusFilter.BEFORE_1980 -> R.string.census_filter_before_1980
    }

// --- Grille ---

/**
 * Une page tient à l'écran, sans défiler : trois rangées de trois affiches, dont la taille
 * s'adapte à la place disponible.
 */
@Composable
private fun GridContent(
    state: CensusUiState,
    onTap: (Long) -> Unit,
    onChoose: (Long, CensusChoice) -> Unit,
    onOpenMovie: (Long) -> Unit,
) {
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        val byWidth = (maxWidth - GridSpacing * (GRID_COLUMNS - 1)) / GRID_COLUMNS
        // Hauteur réelle de la légende, taille de police du téléphone comprise.
        val caption = with(LocalDensity.current) { CaptionLine.toDp() } + CaptionGap + 2.dp
        val byHeight = ((maxHeight - GridRowSpacing * (GRID_ROWS - 1)) / GRID_ROWS - caption) * (2f / 3f)
        val cellWidth = minOf(byWidth, byHeight).coerceAtLeast(MinCellWidth)
        // Écran très court (écran externe d'un pliant) : la grille défile au lieu de se chevaucher.
        val scroll = if (cellWidth > minOf(byWidth, byHeight)) Modifier.verticalScroll(rememberScrollState()) else Modifier
        Column(
            verticalArrangement = Arrangement.spacedBy(GridRowSpacing),
            modifier = scroll.tutorialTarget(TutorialTarget.CENSUS_GRID),
        ) {
            state.items.chunked(GRID_COLUMNS).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(GridSpacing)) {
                    row.forEach { item ->
                        key(item.movie.tmdbId) {
                            CensusCell(
                                item = item,
                                choice = state.choiceOf(item),
                                onTap = { onTap(item.movie.tmdbId) },
                                onChoose = { onChoose(item.movie.tmdbId, it) },
                                onOpenMovie = { onOpenMovie(item.movie.tmdbId) },
                                modifier = Modifier.width(cellWidth),
                            )
                        }
                    }
                }
            }
        }
    }
}

private val CellShape = RoundedCornerShape(10.dp)

@Composable
private fun CensusCell(
    item: CensusItem,
    choice: CensusChoice,
    onTap: () -> Unit,
    onChoose: (CensusChoice) -> Unit,
    onOpenMovie: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    var menu by remember { mutableStateOf(false) }
    val movie = item.movie
    val style = choice.style()
    val series = isSeriesWorld()
    Column(modifier) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(CellShape)
                .border(style.borderWidth, style.borderColor, CellShape)
                .testTag(CensusTestTags.cell(movie.tmdbId))
                .combinedClickable(
                    role = Role.Button,
                    onClickLabel = stringResource(R.string.census_cell_tap),
                    onLongClickLabel = stringResource(R.string.census_cell_menu),
                    onLongClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        menu = true
                    },
                    onClick = {
                        haptics.performHapticFeedback(if (choice.next(series) == CensusChoice.NONE) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn)
                        onTap()
                    },
                ),
        ) {
            PosterImage(
                path = movie.posterPath,
                imageSize = TmdbImageSize.Thumb,
                contentDescription = stringResource(R.string.poster_description, movie.title),
                modifier = Modifier.fillMaxSize(),
                alpha = if (choice == CensusChoice.DISMISSED) 0.3f else 1f,
            )
            if (choice.seen) Box(Modifier.fillMaxSize().background(CineColors.Red.copy(alpha = 0.16f)))
            // Séries : l'avancement en toutes lettres (quatre états à distinguer d'un coup d'œil).
            if (choice in SERIES_CHOICES) {
                Text(
                    text = stringResource(style.label).uppercase(Locale.FRENCH),
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Black,
                    maxLines = 1,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                        .background(style.badgeColor, RoundedCornerShape(6.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
            style.icon?.let { icon ->
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(28.dp)
                        .background(style.badgeColor, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(icon, contentDescription = stringResource(style.label), tint = Color.White, modifier = Modifier.size(16.dp))
                }
            }
            movie.year?.let { year ->
                Text(
                    text = year.toString(),
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(5.dp)
                        .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 6.dp, vertical = 1.dp),
                )
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = CineColors.SurfaceHigh) {
                val seenChoices = if (series) SERIES_CHOICES else listOf(CensusChoice.SEEN, CensusChoice.LOVED, CensusChoice.DISLIKED)
                seenChoices.forEach { option ->
                    val optionStyle = option.style()
                    MenuItem(stringResource(optionStyle.label), optionStyle.icon ?: CineIcons.Eye) { onChoose(option); menu = false }
                }
                MenuItem(stringResource(R.string.census_choice_watchlist), CineIcons.Bookmark) { onChoose(CensusChoice.WATCHLIST); menu = false }
                MenuItem(stringResource(R.string.census_choice_dismissed), CineIcons.ThumbsDown) { onChoose(CensusChoice.DISMISSED); menu = false }
                MenuItem(stringResource(R.string.census_open_movie), CineIcons.Info) { menu = false; onOpenMovie() }
                if (choice != CensusChoice.NONE) {
                    MenuItem(stringResource(R.string.census_choice_clear), CineIcons.Close) { onChoose(CensusChoice.NONE); menu = false }
                }
            }
        }
        // Une seule ligne sous l'affiche (l'année est posée sur l'affiche) : la page tient à l'écran.
        Text(
            text = movie.title,
            color = if (choice == CensusChoice.NONE) CineColors.TextSoft else Color.White,
            fontSize = 12.sp,
            lineHeight = CaptionLine,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = CaptionGap),
        )
    }
}

@Composable
private fun MenuItem(text: String, icon: ImageVector, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(text, color = Color.White) },
        leadingIcon = { Icon(icon, contentDescription = null, tint = CineColors.TextSecondary, modifier = Modifier.size(18.dp)) },
        onClick = onClick,
    )
}

private class ChoiceStyle(
    val borderWidth: Dp,
    val borderColor: Color,
    val badgeColor: Color,
    val icon: ImageVector?,
    val label: Int,
)

private fun CensusChoice.style(): ChoiceStyle = when (this) {
    CensusChoice.NONE -> ChoiceStyle(1.dp, CineColors.Border, Color.Transparent, null, R.string.census_choice_none)
    CensusChoice.SEEN -> ChoiceStyle(3.dp, CineColors.Red, CineColors.Red, CineIcons.Eye, R.string.census_choice_seen)
    CensusChoice.LOVED -> ChoiceStyle(3.dp, CineColors.Red, CineColors.Red, CineIcons.Heart, R.string.census_choice_loved)
    CensusChoice.DISLIKED -> ChoiceStyle(3.dp, CineColors.TextMuted, CineColors.BorderSoft, CineIcons.ThumbsDown, R.string.census_choice_disliked)
    CensusChoice.WATCHLIST -> ChoiceStyle(3.dp, CineColors.TmdbBlue, CineColors.TmdbBlue, CineIcons.Bookmark, R.string.census_choice_watchlist)
    CensusChoice.DISMISSED -> ChoiceStyle(1.dp, CineColors.Border, CineColors.SurfaceHigh, CineIcons.Close, R.string.census_choice_dismissed)
    CensusChoice.FINISHED -> ChoiceStyle(3.dp, CineColors.Red, CineColors.Red, CineIcons.Check, R.string.series_progress_finished)
    CensusChoice.IN_PROGRESS -> ChoiceStyle(3.dp, CineColors.RedLight, CineColors.RedLight, CineIcons.Play, R.string.series_progress_in_progress)
    CensusChoice.EPISODES -> ChoiceStyle(3.dp, CineColors.TmdbBlue, CineColors.TmdbBlue, CineIcons.Shuffle, R.string.series_progress_episodes)
    CensusChoice.ABANDONED -> ChoiceStyle(3.dp, CineColors.TextMuted, CineColors.BorderSoft, CineIcons.Skip, R.string.series_progress_abandoned)
}

/** Réponses « vue » d'une série, dans l'ordre des touchers. */
private val SERIES_CHOICES = listOf(CensusChoice.FINISHED, CensusChoice.IN_PROGRESS, CensusChoice.EPISODES, CensusChoice.ABANDONED)

@Composable
private fun GridActions(state: CensusUiState, onNextPage: () -> Unit, onUndo: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 15.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        UndoPill(visible = state.canUndo, onUndo = onUndo)
        val unseen = state.unanswered
        CineButton(
            text = if (unseen > 0) pluralStringResource(R.plurals.census_next_unseen, unseen, unseen) else stringResource(R.string.census_next),
            onClick = onNextPage,
            enabled = !state.busy,
            modifier = Modifier
                .weight(1f)
                .testTag(CensusTestTags.NEXT)
                .tutorialTarget(TutorialTarget.CENSUS_NEXT),
        )
    }
}

/** Bouton « Annuler », visible tant qu'une réponse peut être annulée. */
@Composable
private fun UndoPill(visible: Boolean, onUndo: () -> Unit) {
    AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut()) {
        GlassPillButton(
            text = stringResource(R.string.action_cancel),
            icon = CineIcons.Undo,
            onClick = onUndo,
            modifier = Modifier.testTag(CensusTestTags.UNDO),
        )
    }
}

// --- Cartes ---

@Composable
private fun CardsContent(state: CensusUiState, onAnswer: (CensusChoice) -> Unit, onOpenMovie: (Long) -> Unit) {
    val item = state.items.firstOrNull()
    Box(Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
        if (item == null) {
            CircularProgressIndicator(color = CineColors.Red)
        } else {
            key(item.movie.tmdbId) {
                SwipeCensusCard(item = item, onAnswer = onAnswer, onOpen = { onOpenMovie(item.movie.tmdbId) })
            }
        }
    }
}

/**
 * Carte du recensement, dans le sens des recos : gauche = vu, droite = à voir ; vers le haut =
 * pas vu (comme passer à la carte suivante). Un toucher ouvre la fiche.
 */
@Composable
private fun SwipeCensusCard(item: CensusItem, onAnswer: (CensusChoice) -> Unit, onOpen: () -> Unit) {
    val series = isSeriesWorld()
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val threshold = with(LocalDensity.current) { 100.dp.toPx() }
    val offsetX = remember { Animatable(0f) }
    val offsetY = remember { Animatable(0f) }
    val currentOnAnswer by rememberUpdatedState(onAnswer)
    val movie = item.movie

    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val widthPx = with(LocalDensity.current) { maxWidth.toPx() }
        SwipeUnderlay(offsetX.value, offsetY.value, threshold, Modifier.fillMaxSize())
        Column(
            Modifier
                .testTag(CensusTestTags.CARD)
                .graphicsLayer {
                    translationX = offsetX.value
                    translationY = offsetY.value
                    rotationZ = offsetX.value / widthPx * 12f
                }
                .pointerInput(movie.tmdbId) {
                    detectDragGestures(
                        onDrag = { change, drag ->
                            change.consume()
                            scope.launch {
                                offsetX.snapTo(offsetX.value + drag.x)
                                offsetY.snapTo(offsetY.value + drag.y)
                            }
                        },
                        onDragEnd = {
                            scope.launch {
                                val x = offsetX.value
                                val y = offsetY.value
                                val choice = when {
                                    x <= -threshold -> if (series) CensusChoice.FINISHED else CensusChoice.SEEN
                                    x >= threshold -> CensusChoice.WATCHLIST
                                    y <= -threshold && abs(y) > abs(x) -> CensusChoice.NONE
                                    else -> null
                                }
                                if (choice == null) {
                                    launch { offsetX.animateTo(0f, spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMedium)) }
                                    offsetY.animateTo(0f, spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMedium))
                                } else {
                                    haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                                    if (choice == CensusChoice.NONE) {
                                        offsetY.animateTo(-widthPx * 2f, tween(200, easing = FastOutLinearInEasing))
                                    } else {
                                        offsetX.animateTo(if (x < 0) -widthPx * 1.5f else widthPx * 1.5f, tween(200, easing = FastOutLinearInEasing))
                                    }
                                    currentOnAnswer(choice)
                                }
                            }
                        },
                    )
                },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier
                    .weight(1f, fill = false)
                    .aspectRatio(2f / 3f, matchHeightConstraintsFirst = true)
                    .clip(RoundedCornerShape(16.dp))
                    .clickable(onClick = onOpen),
            ) {
                PosterImage(
                    path = movie.posterPath,
                    imageSize = TmdbImageSize.Poster,
                    contentDescription = stringResource(R.string.poster_description, movie.title),
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Text(
                text = movie.title,
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.ExtraBold,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 12.dp),
            )
            val meta = listOfNotNull(movie.year?.toString(), movie.director).joinToString(" · ")
            if (meta.isNotEmpty()) Text(meta, color = CineColors.TextMuted, fontSize = 14.sp, textAlign = TextAlign.Center)
        }
    }
}

/** Ce que le geste en cours révèle sous la carte. */
private class UnderlayKind(
    val color: Color,
    val icon: ImageVector,
    val label: Int,
    val alignment: Alignment,
    val distance: Float,
)

/**
 * Fond révélé pendant le geste, comme dans les recos : bleu « Vu » à droite quand la carte part à
 * gauche, vert « À voir » à gauche quand elle part à droite, « Pas vu » en bas quand elle monte.
 */
@Composable
private fun SwipeUnderlay(x: Float, y: Float, threshold: Float, modifier: Modifier = Modifier) {
    val kind = when {
        x < 0 && abs(x) >= abs(y) -> UnderlayKind(CineColors.SwipeLeft, CineIcons.Eye, R.string.swipe_watched, Alignment.CenterEnd, -x)
        x > 0 && abs(x) >= abs(y) -> UnderlayKind(CineColors.SwipeRight, CineIcons.Bookmark, R.string.swipe_watchlist, Alignment.CenterStart, x)
        y < 0 -> UnderlayKind(CineColors.SurfaceHigh, CineIcons.Skip, R.string.census_choice_none, Alignment.BottomCenter, -y)
        else -> return
    }
    Box(
        modifier
            .graphicsLayer { alpha = (kind.distance / threshold).coerceIn(0f, 1f) }
            .clip(RoundedCornerShape(20.dp))
            .background(kind.color),
    ) {
        Column(
            Modifier
                .align(kind.alignment)
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(kind.icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(40.dp))
            Text(stringResource(kind.label), color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Black)
        }
    }
}

@Composable
private fun CardsActions(state: CensusUiState, onAnswer: (CensusChoice) -> Unit, onUndo: () -> Unit) {
    val enabled = state.items.isNotEmpty()
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Dans l'ordre des gestes : les « vu » à gauche, « pas vu » au milieu, « à voir » à droite.
            if (isSeriesWorld()) {
                // Séries : finie (comme un swipe à gauche), en cours, par épisodes.
                RoundAction(CensusChoice.FINISHED, CineIcons.Check, R.string.series_progress_finished, 60.dp, CineColors.SwipeLeft, enabled, onAnswer)
                RoundAction(CensusChoice.IN_PROGRESS, CineIcons.Play, R.string.series_progress_in_progress, 48.dp, CineColors.Red, enabled, onAnswer)
                RoundAction(CensusChoice.EPISODES, CineIcons.Shuffle, R.string.series_progress_episodes, 48.dp, CineColors.BorderSoft, enabled, onAnswer)
            } else {
                RoundAction(CensusChoice.SEEN, CineIcons.Eye, R.string.census_choice_seen, 60.dp, CineColors.SwipeLeft, enabled, onAnswer)
                RoundAction(CensusChoice.LOVED, CineIcons.Heart, R.string.census_choice_loved, 48.dp, CineColors.Red, enabled, onAnswer)
                RoundAction(CensusChoice.DISLIKED, CineIcons.ThumbsDown, R.string.census_choice_disliked, 48.dp, CineColors.BorderSoft, enabled, onAnswer)
            }
            RoundAction(CensusChoice.NONE, CineIcons.Skip, R.string.census_choice_none, 60.dp, CineColors.SurfaceHigh, enabled, onAnswer)
            RoundAction(CensusChoice.WATCHLIST, CineIcons.Bookmark, R.string.census_choice_watchlist, 54.dp, CineColors.SwipeRight, enabled, onAnswer)
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Box(Modifier.width(120.dp)) {
                UndoPill(visible = state.canUndo, onUndo = onUndo)
            }
            TextButton(onClick = { onAnswer(CensusChoice.DISMISSED) }, enabled = enabled) {
                Text(stringResource(R.string.census_choice_dismissed), color = CineColors.TextMuted, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun RoundAction(
    choice: CensusChoice,
    icon: ImageVector,
    label: Int,
    size: Dp,
    color: Color,
    enabled: Boolean,
    onAnswer: (CensusChoice) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(size)
                .clip(CircleShape)
                .background(if (enabled) color else CineColors.Border)
                .testTag(CensusTestTags.action(choice))
                .clickable(enabled = enabled, role = Role.Button, onClickLabel = stringResource(label)) {
                    haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                    onAnswer(choice)
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(size * 0.42f))
        }
        Text(stringResource(label), color = CineColors.TextSecondary, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun CensusHelpDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CineColors.Surface,
        title = { Text(stringResource(R.string.census_help_title), color = Color.White, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf(
                    R.string.census_help_grid,
                    R.string.census_help_menu,
                    R.string.census_help_next,
                    R.string.census_help_cards,
                    R.string.census_help_order,
                    R.string.census_help_impression,
                ).forEach { Text(stringResource(it), color = CineColors.TextSecondary, fontSize = 14.sp) }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.elo_info_ok), color = CineColors.Red, fontWeight = FontWeight.Bold)
            }
        },
    )
}

private const val GRID_COLUMNS = 3
private const val GRID_ROWS = 3

/** Affiche du milieu de la grille, que touche la main du tuto. */
private const val GRID_CENTER = GRID_COLUMNS * GRID_ROWS / 2
private val GridSpacing = 8.dp
private val GridRowSpacing = 10.dp

/** Titre sous l'affiche, sur une ligne. */
private val CaptionLine = 16.sp
private val CaptionGap = 4.dp
private val MinCellWidth = 64.dp
