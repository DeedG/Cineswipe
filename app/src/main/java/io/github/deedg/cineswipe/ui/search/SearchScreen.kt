package io.github.deedg.cineswipe.ui.search

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import io.github.deedg.cineswipe.R
import io.github.deedg.cineswipe.data.repository.FeedRepository
import io.github.deedg.cineswipe.data.repository.FeedRequest
import io.github.deedg.cineswipe.data.repository.PeopleRepository
import io.github.deedg.cineswipe.data.repository.PersonHit
import io.github.deedg.cineswipe.data.repository.SettingsRepository
import io.github.deedg.cineswipe.domain.TmdbImageSize
import io.github.deedg.cineswipe.ui.common.cineViewModel
import io.github.deedg.cineswipe.ui.components.GlassIconButton
import io.github.deedg.cineswipe.ui.components.LocalOpenPerson
import io.github.deedg.cineswipe.ui.components.PersonRef
import io.github.deedg.cineswipe.ui.components.PosterImage
import io.github.deedg.cineswipe.ui.feed.CardFeed
import io.github.deedg.cineswipe.ui.feed.CardFeedScreen
import io.github.deedg.cineswipe.ui.feed.FeedTexts
import io.github.deedg.cineswipe.ui.icons.CineIcons
import io.github.deedg.cineswipe.ui.person.knownForLabel
import io.github.deedg.cineswipe.ui.theme.CineColors
import io.github.deedg.cineswipe.ui.tutorial.LocalTutorial
import io.github.deedg.cineswipe.ui.tutorial.TutorialTarget
import io.github.deedg.cineswipe.ui.tutorial.tutorialTarget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

object SearchTestTags {
    fun person(id: Long) = "search_person_$id"
}

/** Personnes proposées pendant la saisie (acteurs, réalisateurs). */
data class PeopleSuggestions(val query: String = "", val hits: List<PersonHit> = emptyList())

/**
 * Recherche TMDB : saisie en bas de l'écran comme en V4, résultats en cartes swipables. Pendant la
 * saisie, les acteurs et réalisateurs dont le nom correspond s'affichent au-dessus du champ.
 */
@OptIn(FlowPreview::class)
class SearchViewModel(
    feedRepository: FeedRepository,
    settings: SettingsRepository,
    private val peopleRepository: PeopleRepository,
) : ViewModel() {
    val feed = CardFeed(viewModelScope, feedRepository, settings, rememberShown = false)

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _people = MutableStateFlow(PeopleSuggestions())
    val people: StateFlow<PeopleSuggestions> = _people.asStateFlow()

    init {
        viewModelScope.launch {
            _query
                .map { it.trim() }
                .distinctUntilChanged()
                .debounce(PEOPLE_DEBOUNCE_MS)
                .collectLatest { query ->
                    if (query.length < MIN_PEOPLE_QUERY) {
                        _people.value = PeopleSuggestions()
                        return@collectLatest
                    }
                    // Sans clé ou sans réseau : pas de personnes, la recherche de films le dira.
                    val hits = try {
                        peopleRepository.search(query)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        emptyList()
                    }
                    _people.value = PeopleSuggestions(query, hits)
                }
        }
    }

    /** Recherche affichée en cartes, null pendant la saisie. */
    private val _submitted = MutableStateFlow<String?>(null)
    val submitted: StateFlow<String?> = _submitted.asStateFlow()

    fun setQuery(value: String) {
        _query.value = value
    }

    fun submit() {
        val query = _query.value.trim()
        if (query.isEmpty()) return
        _submitted.value = query
        feed.start(FeedRequest.Search(query))
    }

    fun closeResults() {
        _submitted.value = null
        feed.reset()
    }

    private companion object {
        const val PEOPLE_DEBOUNCE_MS = 350L
        const val MIN_PEOPLE_QUERY = 2
    }
}

@Composable
fun SearchRoute(
    onOpenMovie: (Long) -> Unit,
    onOpenSettings: () -> Unit,
    onLightning: (Long) -> Unit,
) {
    val viewModel = cineViewModel { SearchViewModel(it.feed, it.settings, it.people) }
    val query by viewModel.query.collectAsStateWithLifecycle()
    val people by viewModel.people.collectAsStateWithLifecycle()
    val openPerson = LocalOpenPerson.current
    val submitted by viewModel.submitted.collectAsStateWithLifecycle()
    val feedState by viewModel.feed.state.collectAsStateWithLifecycle()
    DisposableEffect(viewModel) { onDispose { viewModel.feed.clearUndo() } }
    BackHandler(enabled = submitted != null) { viewModel.closeResults() }

    val results = submitted
    if (results == null) {
        SearchInput(
            query = query,
            // Une personne n'est proposée que pour le texte tapé (pas pour une saisie plus ancienne).
            people = people.hits.takeIf { people.query == query.trim() }.orEmpty(),
            onQueryChange = viewModel::setQuery,
            onSubmit = viewModel::submit,
            onOpenPerson = openPerson,
        )
    } else {
        CardFeedScreen(
            state = feedState,
            events = viewModel.feed.events,
            texts = FeedTexts(
                loading = stringResource(R.string.search_loading),
                emptyTitle = stringResource(R.string.search_empty_title),
                emptyMessage = stringResource(R.string.search_empty_message, results),
                endTitle = stringResource(R.string.search_end),
                endActionLabel = stringResource(R.string.search_new),
            ),
            onAction = viewModel.feed::act,
            onShown = viewModel.feed::onCardShown,
            onLoadMore = viewModel.feed::loadMore,
            onRetry = viewModel.feed::retry,
            onUndo = viewModel.feed::undo,
            onLightning = { onLightning(it.tmdbId) },
            onOpenMovie = onOpenMovie,
            onOpenSettings = onOpenSettings,
            onEndAction = viewModel::closeResults,
            topStart = {
                GlassIconButton(
                    icon = CineIcons.Back,
                    contentDescription = stringResource(R.string.search_close),
                    onClick = viewModel::closeResults,
                )
            },
        )
    }
}

@Composable
private fun SearchInput(
    query: String,
    people: List<PersonHit>,
    onQueryChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onOpenPerson: ((PersonRef) -> Unit)?,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }
    val submit = {
        keyboard?.hide()
        onSubmit()
    }
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .imePadding()
            .padding(20.dp),
    ) {
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            if (people.isNotEmpty() && onOpenPerson != null) {
                PeopleList(
                    people = people,
                    onOpen = { person ->
                        keyboard?.hide()
                        onOpenPerson(PersonRef(person.id, person.name))
                    },
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            } else {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier.widthIn(max = 360.dp),
                ) {
                    Icon(CineIcons.Search, contentDescription = null, tint = CineColors.BorderSoft, modifier = Modifier.size(48.dp))
                    Text(
                        text = stringResource(R.string.search_intro),
                        color = CineColors.TextFaint,
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
        Text(
            text = stringResource(R.string.search_title),
            color = Color.White,
            fontSize = 32.sp,
            fontWeight = FontWeight.Black,
        )
        Spacer(Modifier.height(15.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester)
                    .tutorialTarget(TutorialTarget.SEARCH_FIELD),
                singleLine = true,
                placeholder = { Text(stringResource(R.string.search_hint), color = CineColors.TextFaint) },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { submit() }),
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = CineColors.SurfaceHigh,
                    unfocusedContainerColor = CineColors.SurfaceHigh,
                    focusedBorderColor = CineColors.Red,
                    unfocusedBorderColor = CineColors.BorderStrong,
                    cursorColor = CineColors.Red,
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                ),
            )
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .shadow(12.dp, RoundedCornerShape(12.dp), ambientColor = CineColors.Red, spotColor = CineColors.Red)
                    .clip(RoundedCornerShape(12.dp))
                    .background(CineColors.Red)
                    .clickable(role = Role.Button) { submit() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    CineIcons.Search,
                    contentDescription = stringResource(R.string.search_action),
                    tint = Color.White,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
    }
    // Comme en V4, le champ prend le focus à l'ouverture de l'onglet.
    // Pendant le tuto, pas de clavier : il cacherait les explications.
    val tutorialActive = LocalTutorial.current?.active == true
    LaunchedEffect(Unit) { if (!tutorialActive) runCatching { focusRequester.requestFocus() } }
}

/** Acteurs et réalisateurs dont le nom correspond : un toucher ouvre leurs films. */
@Composable
private fun PeopleList(people: List<PersonHit>, onOpen: (PersonHit) -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            stringResource(R.string.search_people),
            color = CineColors.TextMuted,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        people.forEach { person -> PersonRow(person, onClick = { onOpen(person) }) }
    }
}

@Composable
private fun PersonRow(person: PersonHit, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(CineColors.Surface)
            .clickable(role = Role.Button, onClickLabel = person.name, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .testTag(SearchTestTags.person(person.id)),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (person.profilePath != null) {
            PosterImage(
                path = person.profilePath,
                imageSize = TmdbImageSize.Thumb,
                contentDescription = null,
                modifier = Modifier.size(44.dp).clip(CircleShape).background(CineColors.SurfaceHigh),
            )
        } else {
            Box(Modifier.size(44.dp).clip(CircleShape).background(CineColors.SurfaceHigh), contentAlignment = Alignment.Center) {
                Icon(CineIcons.Profile, contentDescription = null, tint = CineColors.TextMuted, modifier = Modifier.size(20.dp))
            }
        }
        Column(Modifier.weight(1f)) {
            Text(person.name, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val details = listOfNotNull(knownForLabel(person.knownFor), person.works.takeIf { it.isNotEmpty() }?.joinToString(", "))
            if (details.isNotEmpty()) {
                Text(
                    details.joinToString(" · "),
                    color = CineColors.TextSecondary,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Icon(CineIcons.ChevronRight, contentDescription = null, tint = CineColors.TextMuted, modifier = Modifier.size(18.dp))
    }
}
