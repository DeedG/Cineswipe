package io.github.deedg.cineswipe.ui.tonight

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import io.github.deedg.cineswipe.R
import io.github.deedg.cineswipe.ui.components.openWatchLink
import io.github.deedg.cineswipe.ui.components.ProvidersLine
import io.github.deedg.cineswipe.ui.components.WatchPills
import io.github.deedg.cineswipe.ui.components.WatchButtons
import io.github.deedg.cineswipe.data.repository.TmdbRepository
import io.github.deedg.cineswipe.data.repository.WatchProviders
import io.github.deedg.cineswipe.data.homelab.HomelabService
import io.github.deedg.cineswipe.data.homelab.HomelabRepository
import io.github.deedg.cineswipe.data.homelab.WatchLink
import io.github.deedg.cineswipe.data.repository.LibraryRepository
import io.github.deedg.cineswipe.data.repository.RecoRepository
import io.github.deedg.cineswipe.data.repository.SettingsRepository
import io.github.deedg.cineswipe.data.repository.TonightPick
import io.github.deedg.cineswipe.domain.TmdbImageSize
import io.github.deedg.cineswipe.domain.reco.ReasonKind
import io.github.deedg.cineswipe.domain.reco.RecoReason
import io.github.deedg.cineswipe.ui.common.cineViewModel
import io.github.deedg.cineswipe.ui.components.CineChip
import io.github.deedg.cineswipe.ui.components.CineSecondaryButton
import io.github.deedg.cineswipe.ui.components.EmptyState
import io.github.deedg.cineswipe.ui.components.GlassIconButton
import io.github.deedg.cineswipe.ui.components.LocalToaster
import io.github.deedg.cineswipe.ui.components.PosterImage
import io.github.deedg.cineswipe.ui.components.ScreenTitle
import io.github.deedg.cineswipe.ui.icons.CineIcons
import io.github.deedg.cineswipe.ui.theme.CineColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

object TonightTestTags {
    const val LIST = "tonight_list"
    const val HERO = "tonight_hero"
    const val WATCH = "tonight_watch"
    fun item(tmdbId: Long) = "tonight_item_$tmdbId"
    fun seen(tmdbId: Long) = "tonight_seen_$tmdbId"
}

/** Durée maximale du film. */
enum class TonightLength(val maxMinutes: Int?) { ANY(null), SHORT(100), MEDIUM(130) }

/** D'où viennent les films. */
enum class TonightSource { ALL, SERVER, WATCHLIST }

data class TonightFilters(
    val length: TonightLength = TonightLength.ANY,
    val source: TonightSource = TonightSource.ALL,
)

sealed interface TonightState {
    data object Loading : TonightState

    data class Ready(
        val picks: List<TonightPick>,
        /** Total avant filtres (pour distinguer « rien du tout » de « rien avec ces filtres »). */
        val total: Int,
        val withServer: Boolean,
        /** Serveurs média où regarder chaque film (boutons Plex, Jellyfin). */
        val watchOn: Map<Long, List<HomelabService>>,
        /** Où le regarder ailleurs (streaming), pour les films qui ne sont pas sur le serveur. */
        val providers: Map<Long, WatchProviders>,
        val filters: TonightFilters,
    ) : TonightState {
        /** Les offres de streaming sont utiles quand le film n'est ni sur le serveur ni demandé. */
        fun wantsProviders(pick: TonightPick): Boolean = !pick.available && !pick.requested
    }
}

sealed interface TonightEvent {
    data class Open(val link: WatchLink) : TonightEvent
    data class NotOnServer(val service: HomelabService) : TonightEvent
    data class MarkedWatched(val title: String) : TonightEvent
}

class TonightViewModel(
    private val recos: RecoRepository,
    private val library: LibraryRepository,
    private val homelab: HomelabRepository,
    private val tmdb: TmdbRepository,
    settings: SettingsRepository,
) : ViewModel() {
    private val picks = MutableStateFlow<List<TonightPick>?>(null)
    private val filters = MutableStateFlow(TonightFilters())
    private val withServer = settings.homelabConnected
    private val servers = homelab.mediaServers

    /** Films de chaque serveur média (null : serveur pas encore lu ou injoignable). */
    private val serverFilms = MutableStateFlow<Map<HomelabService, Set<Long>?>>(emptyMap())
    private val providers = MutableStateFlow<Map<Long, WatchProviders>>(emptyMap())
    private val providersAsked = HashSet<Long>()
    private val eventChannel = Channel<TonightEvent>(Channel.BUFFERED)
    val events: Flow<TonightEvent> = eventChannel.receiveAsFlow()

    private val watchOn = combine(picks, servers, serverFilms) { all, servers, films ->
        all.orEmpty().filter { it.available }.associate { pick ->
            pick.movie.tmdbId to servers.filter { service -> films[service]?.contains(pick.movie.tmdbId) ?: true }
        }
    }

    val state: StateFlow<TonightState> = combine(picks, filters, withServer, watchOn, providers) { all, filters, server, watchOn, providers ->
        if (all == null) return@combine TonightState.Loading
        val visible = all.filter { pick ->
            val runtime = pick.movie.runtimeMinutes
            val lengthOk = filters.length.maxMinutes?.let { max -> runtime != null && runtime <= max } ?: true
            val sourceOk = when (filters.source) {
                TonightSource.ALL -> true
                TonightSource.SERVER -> pick.available
                TonightSource.WATCHLIST -> pick.inWatchlist
            }
            lengthOk && sourceOk
        }
        TonightState.Ready(visible, all.size, server, watchOn, providers, filters)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TonightState.Loading)

    fun load() {
        viewModelScope.launch {
            val loaded = try {
                recos.tonight()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                emptyList()
            }
            picks.value = loaded
            val onServer = loaded.filter { it.available }.map { it.movie.tmdbId }
            if (onServer.isNotEmpty()) serverFilms.value = homelab.mediaServersHaving(onServer)
        }
    }

    /** Offres de streaming d'un film (chargées quand sa ligne s'affiche). */
    fun loadProviders(tmdbId: Long) {
        if (!providersAsked.add(tmdbId)) return
        viewModelScope.launch {
            val result = try {
                tmdb.watchProviders(tmdbId)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                null
            } ?: return@launch
            providers.update { it + (tmdbId to result) }
        }
    }

    fun setLength(length: TonightLength) = filters.update { it.copy(length = length) }

    fun setSource(source: TonightSource) = filters.update { it.copy(source = if (it.source == source) TonightSource.ALL else source) }

    fun watch(pick: TonightPick, service: HomelabService) {
        viewModelScope.launch {
            val link = homelab.watchLink(service, pick.movie.tmdbId)
            eventChannel.send(if (link != null) TonightEvent.Open(link) else TonightEvent.NotOnServer(service))
        }
    }

    fun markWatched(pick: TonightPick) {
        viewModelScope.launch {
            library.markWatched(pick.movie.tmdbId)
            picks.update { list -> list?.filter { it.movie.tmdbId != pick.movie.tmdbId } }
            eventChannel.send(TonightEvent.MarkedWatched(pick.movie.title))
        }
    }
}

/**
 * « Ce soir » : que regarder parmi ce qu'on a sous la main (liste À voir, films du serveur),
 * du plus au moins susceptible de plaire. Le premier en grand, les autres en liste.
 */
@Composable
fun TonightRoute(onBack: () -> Unit, onOpenMovie: (Long) -> Unit) {
    val viewModel = cineViewModel { TonightViewModel(it.recos, it.library, it.homelab, it.tmdb, it.settings) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val toaster = LocalToaster.current
    val notOnServer = stringResource(R.string.tonight_not_on_server)
    val markedFormat = stringResource(R.string.tonight_marked_watched)
    val demoWatch = stringResource(R.string.demo_watch)
    // Rechargé à chaque retour sur l'écran (un film a pu être vu depuis sa fiche).
    LaunchedEffect(viewModel) { viewModel.load() }
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is TonightEvent.Open -> openWatchLink(context, uriHandler, event.link) { toaster.show(demoWatch.format(it.service.displayName)) }
                is TonightEvent.NotOnServer -> toaster.show(notOnServer.format(event.service.displayName))
                is TonightEvent.MarkedWatched -> toaster.show(markedFormat.format(event.title))
            }
        }
    }
    TonightScreen(
        state = state,
        onBack = onBack,
        onOpenMovie = onOpenMovie,
        onLength = viewModel::setLength,
        onSource = viewModel::setSource,
        onWatch = viewModel::watch,
        onSeen = viewModel::markWatched,
        onNeedProviders = viewModel::loadProviders,
    )
}

@Composable
fun TonightScreen(
    state: TonightState,
    onBack: () -> Unit,
    onOpenMovie: (Long) -> Unit,
    onLength: (TonightLength) -> Unit,
    onSource: (TonightSource) -> Unit,
    onWatch: (TonightPick, HomelabService) -> Unit,
    onSeen: (TonightPick) -> Unit,
    onNeedProviders: (Long) -> Unit = {},
) {
    Column(Modifier.fillMaxSize().background(CineColors.Black).statusBarsPadding()) {
        ScreenTitle(
            text = stringResource(R.string.tonight_title),
            leading = { GlassIconButton(icon = CineIcons.Back, contentDescription = stringResource(R.string.action_back), onClick = onBack) },
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 4.dp),
        )
        when (state) {
            TonightState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = CineColors.Red, strokeWidth = 2.dp)
            }
            is TonightState.Ready -> if (state.total == 0) {
                EmptyState(
                    icon = CineIcons.Moon,
                    title = stringResource(R.string.tonight_empty_title),
                    message = stringResource(if (state.withServer) R.string.tonight_empty_message_server else R.string.tonight_empty_message),
                )
            } else {
                TonightList(state, onOpenMovie, onLength, onSource, onWatch, onSeen, onNeedProviders)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TonightList(
    state: TonightState.Ready,
    onOpenMovie: (Long) -> Unit,
    onLength: (TonightLength) -> Unit,
    onSource: (TonightSource) -> Unit,
    onWatch: (TonightPick, HomelabService) -> Unit,
    onSeen: (TonightPick) -> Unit,
    onNeedProviders: (Long) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag(TonightTestTags.LIST),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.tonight_intro), color = CineColors.TextSecondary, fontSize = 14.sp, lineHeight = 20.sp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CineChip(stringResource(R.string.tonight_length_any), state.filters.length == TonightLength.ANY, { onLength(TonightLength.ANY) })
                    CineChip(stringResource(R.string.tonight_length_short), state.filters.length == TonightLength.SHORT, { onLength(TonightLength.SHORT) })
                    CineChip(stringResource(R.string.tonight_length_medium), state.filters.length == TonightLength.MEDIUM, { onLength(TonightLength.MEDIUM) })
                    if (state.withServer) {
                        CineChip(stringResource(R.string.tonight_source_server), state.filters.source == TonightSource.SERVER, { onSource(TonightSource.SERVER) })
                        CineChip(stringResource(R.string.tonight_source_watchlist), state.filters.source == TonightSource.WATCHLIST, { onSource(TonightSource.WATCHLIST) })
                    }
                }
            }
        }
        val first = state.picks.firstOrNull()
        if (first == null) {
            item {
                Text(
                    stringResource(R.string.tonight_none_with_filters),
                    color = CineColors.TextMuted,
                    fontSize = 15.sp,
                    modifier = Modifier.padding(top = 24.dp),
                )
            }
        } else {
            item(key = "hero-${first.movie.tmdbId}") {
                WantsProviders(state, first, onNeedProviders)
                Hero(first, state.watchOn[first.movie.tmdbId].orEmpty(), state.providers[first.movie.tmdbId], onOpenMovie, onWatch, onSeen)
            }
            if (state.picks.size > 1) {
                item {
                    Text(
                        stringResource(R.string.tonight_more),
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.ExtraBold,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
            items(state.picks.drop(1), key = { it.movie.tmdbId }) { pick ->
                WantsProviders(state, pick, onNeedProviders)
                PickRow(pick, state.watchOn[pick.movie.tmdbId].orEmpty(), state.providers[pick.movie.tmdbId], onOpenMovie, onWatch, onSeen)
            }
        }
    }
}

/** Charge les offres de streaming d'un film qui n'est ni sur le serveur ni demandé, quand il s'affiche. */
@Composable
private fun WantsProviders(state: TonightState.Ready, pick: TonightPick, onNeedProviders: (Long) -> Unit) {
    if (state.wantsProviders(pick)) LaunchedEffect(pick.movie.tmdbId) { onNeedProviders(pick.movie.tmdbId) }
}

/** Le film le plus susceptible de plaire, en grand. */
@Composable
private fun Hero(
    pick: TonightPick,
    watchOn: List<HomelabService>,
    providers: WatchProviders?,
    onOpenMovie: (Long) -> Unit,
    onWatch: (TonightPick, HomelabService) -> Unit,
    onSeen: (TonightPick) -> Unit,
) {
    val movie = pick.movie
    val shape = RoundedCornerShape(18.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(CineColors.Surface)
            .border(1.dp, CineColors.Border, shape)
            .clickable { onOpenMovie(movie.tmdbId) }
            .testTag(TonightTestTags.HERO),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
            PosterImage(
                path = movie.backdropPath ?: movie.posterPath,
                imageSize = TmdbImageSize.Large,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to CineColors.Surface)),
            )
            Text(
                stringResource(R.string.tonight_top_pick),
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Black,
                modifier = Modifier
                    .padding(12.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(CineColors.Red)
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
        Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(movie.title, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Black, lineHeight = 28.sp)
            MetaLine(pick)
            pick.reason?.let { ReasonText(it) }
            Badges(pick)
            if (!pick.available) ProvidersLine(providers)
            WatchButtons(watchOn, onWatch = { service -> onWatch(pick, service) }, modifier = Modifier.padding(top = 4.dp))
            CineSecondaryButton(
                text = stringResource(R.string.tonight_seen),
                icon = CineIcons.Check,
                onClick = { onSeen(pick) },
                modifier = Modifier.fillMaxWidth().testTag(TonightTestTags.seen(movie.tmdbId)),
            )
        }
    }
}

@Composable
private fun PickRow(
    pick: TonightPick,
    watchOn: List<HomelabService>,
    providers: WatchProviders?,
    onOpenMovie: (Long) -> Unit,
    onWatch: (TonightPick, HomelabService) -> Unit,
    onSeen: (TonightPick) -> Unit,
) {
    val movie = pick.movie
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(CineColors.Surface)
            .border(1.dp, CineColors.Border, shape)
            .clickable { onOpenMovie(movie.tmdbId) }
            .padding(10.dp)
            .testTag(TonightTestTags.item(movie.tmdbId)),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PosterImage(
            path = movie.posterPath,
            imageSize = TmdbImageSize.Thumb,
            contentDescription = null,
            modifier = Modifier.width(64.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(8.dp)),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(movie.title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            MetaLine(pick)
            pick.reason?.let { ReasonText(it) }
            Badges(pick)
            if (!pick.available) ProvidersLine(providers)
            WatchPills(watchOn, onWatch = { service -> onWatch(pick, service) })
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SmallAction(CineIcons.Check, stringResource(R.string.tonight_seen), CineColors.BorderStrong, Modifier.testTag(TonightTestTags.seen(movie.tmdbId))) {
                onSeen(pick)
            }
        }
    }
}

@Composable
private fun SmallAction(icon: ImageVector, label: String, color: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .size(40.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(color)
            .clickable(onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, tint = Color.White, modifier = Modifier.size(18.dp))
    }
}

/** « 2021 · 2 h 35 · Science-Fiction, Aventure ». */
@Composable
private fun MetaLine(pick: TonightPick) {
    val movie = pick.movie
    val parts = listOfNotNull(
        movie.year?.toString(),
        movie.runtimeMinutes?.takeIf { it > 0 }?.let(::formatRuntime),
        movie.genres.take(2).takeIf { it.isNotEmpty() }?.joinToString(", "),
    )
    if (parts.isNotEmpty()) {
        Text(parts.joinToString(" · "), color = CineColors.TextSecondary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

fun formatRuntime(minutes: Int): String = if (minutes < 60) "$minutes min" else "${minutes / 60} h ${"%02d".format(minutes % 60)}"

@Composable
private fun ReasonText(reason: RecoReason) {
    val subject = reason.subject ?: return
    val text = when (reason.kind) {
        ReasonKind.LIKED -> stringResource(R.string.reason_liked, subject)
        ReasonKind.CLOSE_TO -> stringResource(R.string.reason_close_to, subject)
        ReasonKind.DIRECTOR -> stringResource(R.string.reason_director, subject)
        ReasonKind.ACTOR -> stringResource(R.string.reason_actor, subject)
        ReasonKind.THEME -> stringResource(R.string.reason_theme, subject)
        else -> return
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(CineIcons.Heart, contentDescription = null, tint = CineColors.Red, modifier = Modifier.size(13.dp))
        Text(text, color = CineColors.TextSoft, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun Badges(pick: TonightPick) {
    if (!pick.inWatchlist && !pick.available) return
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (pick.available) Badge(CineIcons.Server, stringResource(R.string.tonight_badge_server))
        if (pick.inWatchlist) Badge(CineIcons.Bookmark, stringResource(R.string.tonight_badge_watchlist))
    }
}

@Composable
private fun Badge(icon: ImageVector, label: String) {
    Row(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(CineColors.SurfaceHigh)
            .padding(horizontal = 7.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(icon, contentDescription = null, tint = CineColors.TextSecondary, modifier = Modifier.size(12.dp))
        Text(label, color = CineColors.TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}
