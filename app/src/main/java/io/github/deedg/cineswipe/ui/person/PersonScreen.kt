package io.github.deedg.cineswipe.ui.person

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import io.github.deedg.cineswipe.R
import io.github.deedg.cineswipe.data.db.WatchStatus
import io.github.deedg.cineswipe.data.repository.PeopleRepository
import io.github.deedg.cineswipe.data.repository.PersonFilm
import io.github.deedg.cineswipe.data.repository.PersonFilmStatus
import io.github.deedg.cineswipe.data.repository.PersonPage
import io.github.deedg.cineswipe.data.repository.TmdbException
import io.github.deedg.cineswipe.domain.TmdbImageSize
import io.github.deedg.cineswipe.ui.common.cineViewModel
import io.github.deedg.cineswipe.ui.components.CineChip
import io.github.deedg.cineswipe.ui.components.EmptyState
import io.github.deedg.cineswipe.ui.components.GlassIconButton
import io.github.deedg.cineswipe.ui.components.PosterImage
import io.github.deedg.cineswipe.ui.components.StarRow
import io.github.deedg.cineswipe.ui.icons.CineIcons
import io.github.deedg.cineswipe.ui.theme.CineColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

object PersonScreenTags {
    const val HEADER = "person_header"
    fun film(id: Long) = "person_film_$id"
    fun role(id: Long, directed: Boolean) = "person_role_${id}_${if (directed) "dir" else "act"}"
}

sealed interface PersonState {
    data object Loading : PersonState
    data class Ready(val page: PersonPage) : PersonState
    data object NeedsKey : PersonState
    data object Failed : PersonState
}

/** Films montrés : tous, ceux qu'elle a réalisés, ceux où elle joue. */
enum class PersonRole { ALL, DIRECTED, ACTED }

enum class PersonSort { POPULAR, RECENT }

class PersonViewModel(private val people: PeopleRepository, private val personId: Long) : ViewModel() {
    private val mutableState = MutableStateFlow<PersonState>(PersonState.Loading)
    val state: StateFlow<PersonState> = mutableState

    @OptIn(ExperimentalCoroutinesApi::class)
    val statuses: StateFlow<Map<Long, PersonFilmStatus>> = mutableState
        .flatMapLatest { state ->
            if (state is PersonState.Ready) people.statuses(state.page.films.map { it.tmdbId }) else flowOf(emptyMap())
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    init {
        load()
    }

    fun load() {
        mutableState.value = PersonState.Loading
        viewModelScope.launch {
            mutableState.value = try {
                PersonState.Ready(people.page(personId))
            } catch (error: CancellationException) {
                throw error
            } catch (error: TmdbException.MissingKey) {
                PersonState.NeedsKey
            } catch (error: TmdbException.InvalidKey) {
                PersonState.NeedsKey
            } catch (error: Exception) {
                PersonState.Failed
            }
        }
    }
}

/**
 * Fiche d'un acteur ou d'un réalisateur : ses films (populaires ou récents), ce qu'on en a vu,
 * et, pour qui joue et réalise, ce qu'il a fait dans chaque film.
 */
@Composable
fun PersonRoute(personId: Long, name: String, onBack: () -> Unit, onOpenMovie: (Long) -> Unit, onOpenKeyGuide: () -> Unit) {
    val viewModel = cineViewModel(key = "person-$personId") { PersonViewModel(it.people, personId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val statuses by viewModel.statuses.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().background(CineColors.Black).statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GlassIconButton(icon = CineIcons.Back, contentDescription = stringResource(R.string.action_back), onClick = onBack)
        }
        when (val current = state) {
            PersonState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = CineColors.Red, strokeWidth = 2.dp)
            }
            PersonState.NeedsKey -> EmptyState(
                icon = CineIcons.Profile,
                title = name,
                message = stringResource(R.string.person_needs_key),
                actionLabel = stringResource(R.string.action_open_settings),
                onAction = onOpenKeyGuide,
            )
            PersonState.Failed -> EmptyState(
                icon = CineIcons.Profile,
                title = name,
                message = stringResource(R.string.person_failed),
                actionLabel = stringResource(R.string.feed_retry),
                onAction = viewModel::load,
            )
            is PersonState.Ready -> PersonContent(current.page, statuses, onOpenMovie)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PersonContent(page: PersonPage, statuses: Map<Long, PersonFilmStatus>, onOpenMovie: (Long) -> Unit) {
    var role by rememberSaveable { mutableStateOf(PersonRole.ALL) }
    var sort by rememberSaveable { mutableStateOf(PersonSort.POPULAR) }
    var showBio by rememberSaveable { mutableStateOf(false) }
    val both = page.actsAndDirects
    val films = page.films
        .filter {
            when (role) {
                PersonRole.ALL -> true
                PersonRole.DIRECTED -> it.directed
                PersonRole.ACTED -> it.acted
            }
        }
        .let { list ->
            when (sort) {
                PersonSort.POPULAR -> list.sortedWith(compareByDescending<PersonFilm> { it.voteCount }.thenByDescending { it.popularity })
                PersonSort.RECENT -> list.sortedWith(compareByDescending<PersonFilm> { it.releaseDate ?: "9999" })
            }
        }
    val seen = page.films.count { statuses[it.tmdbId]?.status == WatchStatus.WATCHED }
    val person = page.person
    LazyColumn(
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(
                Modifier.fillMaxWidth().testTag(PersonScreenTags.HEADER),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PosterImage(
                    path = person.profilePath,
                    imageSize = TmdbImageSize.Thumb,
                    contentDescription = null,
                    modifier = Modifier.size(96.dp).clip(CircleShape).background(CineColors.SurfaceHigh),
                )
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(person.name, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Black, lineHeight = 29.sp)
                    val dates = when {
                        person.birthYear != null && person.deathYear != null -> "${person.birthYear} – ${person.deathYear}"
                        person.birthYear != null -> stringResource(R.string.person_born, person.birthYear)
                        else -> null
                    }
                    listOfNotNull(knownForLabel(person.knownFor), dates).joinToString(" · ").takeIf { it.isNotEmpty() }?.let {
                        Text(it, color = CineColors.TextSecondary, fontSize = 14.sp)
                    }
                    Text(
                        stringResource(R.string.person_seen, seen, page.films.size),
                        color = CineColors.Red,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
        person.biography?.let { bio ->
            item {
                Text(
                    bio,
                    color = CineColors.TextSoft,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    maxLines = if (showBio) Int.MAX_VALUE else 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clickable { showBio = !showBio },
                )
            }
        }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CineChip(stringResource(R.string.person_sort_popular), sort == PersonSort.POPULAR, { sort = PersonSort.POPULAR })
                CineChip(stringResource(R.string.person_sort_recent), sort == PersonSort.RECENT, { sort = PersonSort.RECENT })
                if (both) {
                    CineChip(stringResource(R.string.person_role_all), role == PersonRole.ALL, { role = PersonRole.ALL })
                    CineChip(stringResource(R.string.person_role_directed), role == PersonRole.DIRECTED, { role = PersonRole.DIRECTED })
                    CineChip(stringResource(R.string.person_role_acted), role == PersonRole.ACTED, { role = PersonRole.ACTED })
                }
            }
        }
        if (films.isEmpty()) {
            item { Text(stringResource(R.string.person_no_films), color = CineColors.TextMuted, fontSize = 15.sp) }
        }
        items(films, key = { it.tmdbId }) { film ->
            FilmRow(film, statuses[film.tmdbId], showRoles = both, onClick = { onOpenMovie(film.tmdbId) })
        }
    }
}

@Composable
internal fun knownForLabel(department: String?): String? = when (department) {
    "Acting" -> stringResource(R.string.person_known_acting)
    "Directing" -> stringResource(R.string.person_known_directing)
    "Writing" -> stringResource(R.string.person_known_writing)
    "Production" -> stringResource(R.string.person_known_production)
    else -> null
}

@Composable
private fun FilmRow(film: PersonFilm, status: PersonFilmStatus?, showRoles: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    val watched = status?.status == WatchStatus.WATCHED
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(CineColors.Surface)
            .border(1.dp, if (watched) CineColors.Red.copy(alpha = 0.45f) else CineColors.Border, shape)
            .clickable(onClick = onClick)
            .padding(10.dp)
            .testTag(PersonScreenTags.film(film.tmdbId)),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PosterImage(
            path = film.posterPath,
            imageSize = TmdbImageSize.Thumb,
            contentDescription = null,
            modifier = Modifier.width(56.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(8.dp)).background(CineColors.SurfaceHigh),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(film.title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val meta = listOfNotNull(
                film.year?.toString() ?: stringResource(R.string.person_upcoming),
                film.character?.let { stringResource(R.string.person_as, it) },
            ).joinToString(" · ")
            Text(meta, color = CineColors.TextSecondary, fontSize = 13.sp, fontStyle = FontStyle.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
            when {
                watched -> {
                    val stars = status?.stars
                    if (stars != null) StarRow(stars = stars, starSize = 14.dp) else StatusText(stringResource(R.string.badge_watched), CineColors.Red)
                }
                status?.status == WatchStatus.WATCHLIST -> StatusText(stringResource(R.string.badge_watchlist), CineColors.TmdbBlue)
                status?.available == true -> StatusText(stringResource(R.string.badge_available), CineColors.TextSecondary)
            }
        }
        if (showRoles) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (film.directed) RoleIcon(CineIcons.Clapper, stringResource(R.string.person_role_directed_short), Modifier.testTag(PersonScreenTags.role(film.tmdbId, true)))
                if (film.acted) RoleIcon(CineIcons.Profile, stringResource(R.string.person_role_acted_short), Modifier.testTag(PersonScreenTags.role(film.tmdbId, false)))
            }
        }
    }
}

@Composable
private fun RoleIcon(icon: ImageVector, label: String, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, contentDescription = label, tint = CineColors.TextSoft, modifier = Modifier.size(18.dp))
        Text(label, color = CineColors.TextMuted, fontSize = 10.sp)
    }
}

@Composable
private fun StatusText(text: String, color: Color) {
    Text(text, color = color, fontSize = 12.sp, fontWeight = FontWeight.Bold)
}
