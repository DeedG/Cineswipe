package io.github.deedg.cineswipe.ui.lists

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import io.github.deedg.cineswipe.R
import io.github.deedg.cineswipe.data.db.ListFilmRow
import io.github.deedg.cineswipe.data.db.UserListEntity
import io.github.deedg.cineswipe.data.db.WatchStatus
import io.github.deedg.cineswipe.data.repository.ListsRepository
import io.github.deedg.cineswipe.data.repository.UserListCard
import io.github.deedg.cineswipe.domain.TmdbImageSize
import io.github.deedg.cineswipe.ui.common.cineViewModel
import io.github.deedg.cineswipe.ui.components.CineSecondaryButton
import io.github.deedg.cineswipe.ui.components.GlassIconButton
import io.github.deedg.cineswipe.ui.components.GlassPillButton
import io.github.deedg.cineswipe.ui.components.LocalToaster
import io.github.deedg.cineswipe.ui.components.PosterImage
import io.github.deedg.cineswipe.ui.icons.CineIcons
import io.github.deedg.cineswipe.ui.theme.CineColors
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

object ListsTestTags {
    const val SHELF = "lists_shelf"
    const val NEW = "lists_new"
    const val ADD = "lists_add"
    const val SHEET_NEW = "lists_sheet_new"
    const val NAME_FIELD = "lists_name_field"
    const val NAME_CONFIRM = "lists_name_confirm"
    const val HEADER = "lists_header"
    const val EDIT = "lists_edit"
    const val RENAME = "lists_rename"
    const val DELETE = "lists_delete"
    const val DELETE_CONFIRM = "lists_delete_confirm"
    const val RANDOM = "lists_random"

    fun card(id: Long) = "lists_card_$id"
    fun sheetRow(id: Long) = "lists_sheet_$id"
    fun film(tmdbId: Long) = "lists_film_$tmdbId"
    fun remove(tmdbId: Long) = "lists_remove_$tmdbId"
}

// ——— Profil : l'étagère des listes ———

class ListsShelfViewModel(private val lists: ListsRepository) : ViewModel() {
    val cards: StateFlow<List<UserListCard>?> =
        lists.lists.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun create(name: String, onCreated: (Long) -> Unit) {
        viewModelScope.launch { lists.create(name)?.let(onCreated) }
    }
}

/** « Mes listes » dans le profil : une carte par liste (affiches et nombre de films), et « Nouvelle liste ». */
@Composable
fun ListsShelfRoute(onOpenList: (Long) -> Unit, modifier: Modifier = Modifier) {
    val viewModel = cineViewModel { ListsShelfViewModel(it.lists) }
    val cards by viewModel.cards.collectAsStateWithLifecycle()
    var creating by rememberSaveable { mutableStateOf(false) }
    val lists = cards ?: return
    Column(modifier.fillMaxWidth().testTag(ListsTestTags.SHELF), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(CineIcons.ListIcon, contentDescription = null, tint = CineColors.Red, modifier = Modifier.size(22.dp))
            Text(stringResource(R.string.lists_title), color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Black)
        }
        if (lists.isEmpty()) {
            Text(stringResource(R.string.lists_shelf_empty), color = CineColors.TextSecondary, fontSize = 14.sp, lineHeight = 20.sp)
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(lists, key = { it.id }) { list -> ListCard(list, onClick = { onOpenList(list.id) }) }
            item(key = "new") { NewListCard(onClick = { creating = true }) }
        }
        if (creating) {
            ListNameEditor(
                initial = "",
                confirmLabel = stringResource(R.string.lists_create),
                onConfirm = { name ->
                    creating = false
                    viewModel.create(name, onOpenList)
                },
                onCancel = { creating = false },
            )
        }
    }
}

private val CardWidth = 112.dp

@Composable
private fun ListCard(list: UserListCard, onClick: () -> Unit) {
    Column(
        Modifier
            .width(CardWidth)
            .clip(RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClickLabel = list.name, onClick = onClick)
            .testTag(ListsTestTags.card(list.id)),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        CoverMosaic(list.covers)
        Text(list.name, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 17.sp)
        Text(pluralStringResource(R.plurals.lists_count, list.count, list.count), color = CineColors.TextMuted, fontSize = 12.sp)
    }
}

/** Couverture d'une liste : ses quatre dernières affiches en mosaïque. */
@Composable
private fun CoverMosaic(covers: List<String>) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(2f / 3f)
            .clip(shape)
            .background(CineColors.SurfaceHigh)
            .border(1.dp, CineColors.Border, shape),
        contentAlignment = Alignment.Center,
    ) {
        when (covers.size) {
            0 -> Icon(CineIcons.ListIcon, contentDescription = null, tint = CineColors.TextMuted, modifier = Modifier.size(28.dp))
            1, 2, 3 -> PosterImage(covers.first(), TmdbImageSize.Poster, contentDescription = null, modifier = Modifier.fillMaxSize())
            else -> Column(Modifier.fillMaxSize()) {
                covers.chunked(2).forEach { row ->
                    Row(Modifier.weight(1f)) {
                        row.forEach { path ->
                            PosterImage(path, TmdbImageSize.Thumb, contentDescription = null, modifier = Modifier.weight(1f).fillMaxSize())
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NewListCard(onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier
            .width(CardWidth)
            .clip(shape)
            .clickable(role = Role.Button, onClick = onClick)
            .testTag(ListsTestTags.NEW),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(shape)
                .border(1.dp, CineColors.BorderStrong, shape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(CineIcons.Plus, contentDescription = null, tint = CineColors.Red, modifier = Modifier.size(30.dp))
        }
        Text(stringResource(R.string.lists_new), color = CineColors.TextSoft, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}

// ——— Fiche d'un film : « Ajouter à une liste » ———

data class ListChoice(val id: Long, val name: String, val count: Int, val contains: Boolean)

class AddToListViewModel(private val lists: ListsRepository, private val tmdbId: Long) : ViewModel() {
    val choices: StateFlow<List<ListChoice>> = combine(lists.lists, lists.listsOf(tmdbId)) { cards, inside ->
        cards.map { ListChoice(it.id, it.name, it.count, it.id in inside) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun toggle(choice: ListChoice) {
        viewModelScope.launch {
            if (choice.contains) lists.remove(choice.id, tmdbId) else lists.add(choice.id, tmdbId)
        }
    }

    fun create(name: String, onCreated: () -> Unit) {
        viewModelScope.launch { if (lists.create(name, firstFilm = tmdbId) != null) onCreated() }
    }
}

/** Bouton de la fiche : ouvre le choix des listes (une coche par liste, et « Nouvelle liste »). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddToListButton(tmdbId: Long, modifier: Modifier = Modifier) {
    val viewModel = cineViewModel(key = "lists-$tmdbId") { AddToListViewModel(it.lists, tmdbId) }
    val choices by viewModel.choices.collectAsStateWithLifecycle()
    var open by rememberSaveable { mutableStateOf(false) }
    var creating by rememberSaveable { mutableStateOf(false) }
    val toaster = LocalToaster.current
    val addedFormat = stringResource(R.string.lists_added)
    val removedFormat = stringResource(R.string.lists_removed)
    val inside = choices.count { it.contains }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        CineSecondaryButton(
            text = if (inside > 0) pluralStringResource(R.plurals.lists_in, inside, inside) else stringResource(R.string.lists_add),
            icon = if (inside > 0) CineIcons.Check else CineIcons.ListIcon,
            onClick = { if (choices.isEmpty()) creating = true else open = true },
            contentColor = if (inside > 0) CineColors.TmdbBlue else Color.White,
            modifier = Modifier.fillMaxWidth().testTag(ListsTestTags.ADD),
        )
        // Nouvelle liste : le nom se tape ici, sous le bouton (le film y est ajouté d'office).
        if (creating) {
            ListNameEditor(
                initial = "",
                confirmLabel = stringResource(R.string.lists_create),
                onConfirm = { name ->
                    creating = false
                    viewModel.create(name) { toaster.show(addedFormat.format(ListsRepository.clean(name).orEmpty())) }
                },
                onCancel = { creating = false },
            )
        }
    }
    if (open) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = { open = false }, sheetState = sheetState, containerColor = CineColors.Surface) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(start = 20.dp, end = 20.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(stringResource(R.string.lists_sheet_title), color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Black)
                choices.forEach { choice ->
                    ChoiceRow(choice) {
                        viewModel.toggle(choice)
                        toaster.show((if (choice.contains) removedFormat else addedFormat).format(choice.name))
                    }
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(role = Role.Button) {
                            open = false
                            creating = true
                        }
                        .padding(vertical = 12.dp, horizontal = 8.dp)
                        .testTag(ListsTestTags.SHEET_NEW),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(CineIcons.Plus, contentDescription = null, tint = CineColors.Red, modifier = Modifier.size(22.dp))
                    Text(stringResource(R.string.lists_new), color = CineColors.Red, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun ChoiceRow(choice: ListChoice, onToggle: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(role = Role.Checkbox, onClick = onToggle)
            .padding(vertical = 10.dp, horizontal = 8.dp)
            .testTag(ListsTestTags.sheetRow(choice.id)),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val shape = RoundedCornerShape(6.dp)
        Box(
            Modifier
                .size(24.dp)
                .clip(shape)
                .background(if (choice.contains) CineColors.Red else Color.Transparent)
                .border(2.dp, if (choice.contains) CineColors.Red else CineColors.BorderStrong, shape),
            contentAlignment = Alignment.Center,
        ) {
            if (choice.contains) Icon(CineIcons.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(choice.name, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(pluralStringResource(R.plurals.lists_count, choice.count, choice.count), color = CineColors.TextMuted, fontSize = 12.sp)
        }
    }
}

/**
 * Nom d'une liste, à créer ou à renommer : un champ dans la page (pas de fenêtre par-dessus),
 * qui prend le focus pour taper tout de suite.
 */
@Composable
fun ListNameEditor(initial: String, confirmLabel: String, onConfirm: (String) -> Unit, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    var name by rememberSaveable { mutableStateOf(initial) }
    val valid = ListsRepository.clean(name) != null
    val focus = remember { FocusRequester() }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it.take(ListsRepository.MAX_NAME) },
            singleLine = true,
            placeholder = { Text(stringResource(R.string.lists_name_hint), color = CineColors.TextFaint) },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (valid) onConfirm(name) }),
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
            modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag(ListsTestTags.NAME_FIELD),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel), color = CineColors.TextSecondary) }
            TextButton(onClick = { onConfirm(name) }, enabled = valid, modifier = Modifier.testTag(ListsTestTags.NAME_CONFIRM)) {
                Text(confirmLabel, color = if (valid) CineColors.Red else CineColors.TextFaint, fontWeight = FontWeight.Bold)
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}

// ——— Écran d'une liste ———

data class UserListUiState(
    val loading: Boolean = true,
    val list: UserListEntity? = null,
    val films: List<ListFilmRow> = emptyList(),
) {
    val seen: Int get() = films.count { it.status == WatchStatus.WATCHED }
}

class UserListViewModel(private val lists: ListsRepository, private val id: Long) : ViewModel() {
    val state: StateFlow<UserListUiState> = combine(lists.list(id), lists.films(id)) { list, films ->
        UserListUiState(loading = false, list = list, films = films)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UserListUiState())

    fun rename(name: String) {
        viewModelScope.launch { lists.rename(id, name) }
    }

    fun delete(onDeleted: () -> Unit) {
        viewModelScope.launch {
            lists.delete(id)
            onDeleted()
        }
    }

    fun remove(tmdbId: Long) {
        viewModelScope.launch { lists.remove(id, tmdbId) }
    }

    /** Un film au hasard, parmi ceux pas encore vus s'il y en a. */
    fun pick(): Long? {
        val films = state.value.films
        val unseen = films.filter { it.status != WatchStatus.WATCHED }
        return unseen.ifEmpty { films }.randomOrNull()?.tmdbId
    }
}

@Composable
fun UserListRoute(listId: Long, onBack: () -> Unit, onOpenMovie: (Long) -> Unit) {
    val viewModel = cineViewModel(key = "list-$listId") { UserListViewModel(it.lists, listId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    var editing by rememberSaveable { mutableStateOf(false) }
    var renaming by rememberSaveable { mutableStateOf(false) }
    var deleting by rememberSaveable { mutableStateOf(false) }
    val toaster = LocalToaster.current
    val deletedFormat = stringResource(R.string.lists_deleted)
    val list = state.list
    // Liste supprimée (ici ou ailleurs) : retour au profil.
    LaunchedEffect(state.loading, list) { if (!state.loading && list == null) onBack() }
    if (list == null) return

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 104.dp),
        modifier = Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            ListHeader(
                list = list,
                count = state.films.size,
                seen = state.seen,
                editing = editing,
                onBack = onBack,
                onToggleEdit = {
                    editing = !editing
                    renaming = false
                    deleting = false
                },
                onRandom = { viewModel.pick()?.let(onOpenMovie) },
                renaming = renaming,
                onRename = { renaming = true },
                onRenamed = { name ->
                    renaming = false
                    viewModel.rename(name)
                },
                onCancelRename = { renaming = false },
                deleting = deleting,
                onDelete = { deleting = true },
                onConfirmDelete = {
                    deleting = false
                    val name = list.name
                    viewModel.delete { toaster.show(deletedFormat.format(name)) }
                },
                onCancelDelete = { deleting = false },
            )
        }
        if (state.films.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(stringResource(R.string.lists_empty), color = CineColors.TextSecondary, fontSize = 15.sp, lineHeight = 21.sp)
            }
        }
        items(state.films, key = { it.tmdbId }) { film ->
            FilmCell(
                film = film,
                editing = editing,
                onOpen = { onOpenMovie(film.tmdbId) },
                onRemove = { viewModel.remove(film.tmdbId) },
            )
        }
    }

}

@Composable
private fun ListHeader(
    list: UserListEntity,
    count: Int,
    seen: Int,
    editing: Boolean,
    renaming: Boolean,
    onBack: () -> Unit,
    onToggleEdit: () -> Unit,
    onRandom: () -> Unit,
    onRename: () -> Unit,
    onRenamed: (String) -> Unit,
    onCancelRename: () -> Unit,
    deleting: Boolean,
    onDelete: () -> Unit,
    onConfirmDelete: () -> Unit,
    onCancelDelete: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().testTag(ListsTestTags.HEADER), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GlassIconButton(icon = CineIcons.Back, contentDescription = stringResource(R.string.action_back), onClick = onBack)
            Column(Modifier.weight(1f)) {
                Text(list.name, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Black, lineHeight = 27.sp)
                val counts = buildList {
                    add(pluralStringResource(R.plurals.lists_count, count, count))
                    if (seen > 0) add(pluralStringResource(R.plurals.lists_seen, seen, seen))
                }
                Text(counts.joinToString(" · "), color = CineColors.TextMuted, fontSize = 13.sp)
            }
            GlassIconButton(
                icon = if (editing) CineIcons.Check else CineIcons.Edit,
                contentDescription = stringResource(if (editing) R.string.lists_done else R.string.lists_edit),
                onClick = onToggleEdit,
                tint = if (editing) CineColors.Red else Color.White,
                modifier = Modifier.testTag(ListsTestTags.EDIT),
            )
        }
        if (editing && deleting) {
            // Confirmation dans la page : les films restent dans la bibliothèque.
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.lists_delete_confirm, list.name), color = CineColors.TextSoft, fontSize = 15.sp, lineHeight = 21.sp)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onCancelDelete) { Text(stringResource(R.string.action_cancel), color = CineColors.TextSecondary) }
                    TextButton(onClick = onConfirmDelete, modifier = Modifier.testTag(ListsTestTags.DELETE_CONFIRM)) {
                        Text(stringResource(R.string.lists_delete_action), color = CineColors.Red, fontWeight = FontWeight.Bold)
                    }
                }
            }
        } else if (editing && renaming) {
            ListNameEditor(initial = list.name, confirmLabel = stringResource(R.string.lists_save), onConfirm = onRenamed, onCancel = onCancelRename)
        } else if (editing) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CineSecondaryButton(
                    text = stringResource(R.string.lists_rename),
                    icon = CineIcons.Edit,
                    onClick = onRename,
                    modifier = Modifier.weight(1f).testTag(ListsTestTags.RENAME),
                )
                CineSecondaryButton(
                    text = stringResource(R.string.lists_delete),
                    icon = CineIcons.Trash,
                    onClick = onDelete,
                    contentColor = CineColors.Red,
                    borderColor = CineColors.Red.copy(alpha = 0.5f),
                    modifier = Modifier.weight(1f).testTag(ListsTestTags.DELETE),
                )
            }
        } else if (count >= 2) {
            GlassPillButton(
                text = stringResource(R.string.lists_random),
                icon = CineIcons.Shuffle,
                onClick = onRandom,
                modifier = Modifier.testTag(ListsTestTags.RANDOM),
            )
        }
    }
}

@Composable
private fun FilmCell(film: ListFilmRow, editing: Boolean, onOpen: () -> Unit, onRemove: () -> Unit) {
    val removeLabel = stringResource(R.string.lists_remove_film, film.title)
    Column(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(role = Role.Button, onClickLabel = film.title, onClick = if (editing) onRemove else onOpen)
            .testTag(ListsTestTags.film(film.tmdbId)),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Box {
            PosterImage(
                path = film.posterPath,
                imageSize = TmdbImageSize.Poster,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(2f / 3f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(CineColors.SurfaceHigh),
                alpha = if (editing) 0.6f else 1f,
            )
            when (film.status) {
                WatchStatus.WATCHED -> CornerIcon(CineIcons.Eye, CineColors.Red, Modifier.align(Alignment.TopEnd))
                WatchStatus.WATCHLIST -> CornerIcon(CineIcons.Bookmark, CineColors.TmdbBlue, Modifier.align(Alignment.TopEnd))
                else -> Unit
            }
            if (editing) {
                Box(
                    Modifier
                        .align(Alignment.Center)
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(CineColors.Red)
                        .clickable(role = Role.Button, onClickLabel = removeLabel, onClick = onRemove)
                        .testTag(ListsTestTags.remove(film.tmdbId)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(CineIcons.Close, contentDescription = removeLabel, tint = Color.White, modifier = Modifier.size(20.dp))
                }
            }
        }
        Text(
            film.title,
            color = CineColors.TextSoft,
            fontSize = 12.sp,
            lineHeight = 15.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.height(30.dp),
        )
    }
}

@Composable
private fun CornerIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, color: Color, modifier: Modifier) {
    Box(
        modifier
            .padding(5.dp)
            .size(24.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.7f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(14.dp))
    }
}
