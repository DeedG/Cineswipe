package io.github.deedg.cineswipe.ui.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import io.github.deedg.cineswipe.R
import io.github.deedg.cineswipe.data.db.SeriesProgress
import io.github.deedg.cineswipe.data.repository.StatsFilm
import io.github.deedg.cineswipe.data.repository.StatsGroup
import io.github.deedg.cineswipe.data.repository.StatsRepository
import io.github.deedg.cineswipe.data.repository.TasteStats
import io.github.deedg.cineswipe.domain.TmdbImageSize
import io.github.deedg.cineswipe.ui.common.cineViewModel
import io.github.deedg.cineswipe.ui.components.EmptyState
import io.github.deedg.cineswipe.ui.components.GlassIconButton
import io.github.deedg.cineswipe.ui.components.LocalOpenPerson
import io.github.deedg.cineswipe.ui.components.PersonRef
import io.github.deedg.cineswipe.ui.components.PosterImage
import io.github.deedg.cineswipe.ui.components.ScreenTitle
import io.github.deedg.cineswipe.ui.components.SectionCard
import io.github.deedg.cineswipe.ui.components.StarRow
import io.github.deedg.cineswipe.ui.icons.CineIcons
import io.github.deedg.cineswipe.ui.theme.CineColors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.util.Locale

object StatsTestTags {
    const val CONTENT = "stats_content"
    fun director(id: Long) = "stats_director_$id"
}

class StatsViewModel(private val repository: StatsRepository) : ViewModel() {
    private val mutableStats = MutableStateFlow<TasteStats?>(null)
    val stats: StateFlow<TasteStats?> = mutableStats

    fun load() {
        viewModelScope.launch { mutableStats.value = repository.load() }
    }
}

/** Stats de goûts : ce que le classement de l'Arène dit des genres, réalisateurs, acteurs… */
@Composable
fun StatsRoute(onBack: () -> Unit, onOpenMovie: (Long) -> Unit) {
    val viewModel = cineViewModel { StatsViewModel(it.stats) }
    val stats by viewModel.stats.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) { viewModel.load() }
    Column(Modifier.fillMaxSize().background(CineColors.Black).statusBarsPadding()) {
        ScreenTitle(
            text = stringResource(R.string.stats_title),
            leading = { GlassIconButton(icon = CineIcons.Back, contentDescription = stringResource(R.string.action_back), onClick = onBack) },
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 4.dp),
        )
        val current = stats
        when {
            current == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = CineColors.Red, strokeWidth = 2.dp)
            }
            current.watched < MIN_FILMS -> EmptyState(
                icon = CineIcons.BarChart,
                title = stringResource(R.string.stats_empty_title),
                message = stringResource(R.string.stats_empty_message, MIN_FILMS),
            )
            else -> StatsContent(current, onOpenMovie)
        }
    }
}

private const val MIN_FILMS = 10

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StatsContent(stats: TasteStats, onOpenMovie: (Long) -> Unit) {
    val format = NumberFormat.getIntegerInstance(Locale.FRANCE)
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 28.dp)
            .testTag(StatsTestTags.CONTENT),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(R.string.stats_intro), color = CineColors.TextSecondary, fontSize = 14.sp, lineHeight = 20.sp)

        // Chiffres clés.
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Tile(format.format(stats.watched), stringResource(R.string.stats_tile_watched), Modifier.weight(1f))
            Tile(format.format(stats.hours), stringResource(R.string.stats_tile_hours, stats.hours / 24), Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Tile(format.format(stats.duels), stringResource(R.string.stats_tile_duels), Modifier.weight(1f))
            Tile(format.format(stats.directorCount), stringResource(R.string.stats_tile_directors), Modifier.weight(1f))
        }
        // Séries : épisodes vus (estimés) et avancement.
        if (stats.progress.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Tile(format.format(stats.episodes), stringResource(R.string.stats_tile_episodes), Modifier.weight(1f))
                Tile(format.format(stats.progress[SeriesProgress.FINISHED] ?: 0), stringResource(R.string.stats_tile_finished), Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Tile(format.format(stats.progress[SeriesProgress.IN_PROGRESS] ?: 0), stringResource(R.string.stats_tile_in_progress), Modifier.weight(1f))
                Tile(
                    format.format((stats.progress[SeriesProgress.ABANDONED] ?: 0)),
                    stringResource(R.string.stats_tile_abandoned, stats.progress[SeriesProgress.EPISODES] ?: 0),
                    Modifier.weight(1f),
                )
            }
        }

        // En une phrase.
        val headlines = buildList {
            stats.genres.firstOrNull()?.let { add(stringResource(R.string.stats_headline_genre, it.name, stars(it.averageStars))) }
            stats.favoriteDecade?.let { add(stringResource(R.string.stats_headline_decade, decadeLabel(it.key), it.films)) }
            stats.mostWatchedDirector?.let { add(stringResource(R.string.stats_headline_director, it.name, it.films)) }
            stats.actors.firstOrNull()?.let { add(stringResource(R.string.stats_headline_actor, it.name, stars(it.averageStars))) }
        }
        if (headlines.isNotEmpty()) {
            SectionCard {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    headlines.forEach { line ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                            Icon(CineIcons.Heart, contentDescription = null, tint = CineColors.Red, modifier = Modifier.size(16.dp).padding(top = 2.dp))
                            Text(line, color = CineColors.TextSoft, fontSize = 15.sp, lineHeight = 21.sp)
                        }
                    }
                }
            }
        }

        if (stats.genres.isNotEmpty()) {
            Section(stringResource(R.string.stats_genres), stringResource(R.string.stats_genres_hint))
            StarBars(stats.genres)
        }

        if (stats.directors.isNotEmpty()) {
            Section(stringResource(R.string.stats_directors), stringResource(R.string.stats_people_hint))
            PeopleList(stats.directors, withPhotos = false)
        }

        if (stats.actors.isNotEmpty()) {
            Section(stringResource(R.string.stats_actors), stringResource(R.string.stats_people_hint))
            ActorsRow(stats.actors)
        }

        if (stats.decades.size >= 2) {
            Section(stringResource(R.string.stats_decades), stringResource(R.string.stats_decades_hint))
            DecadeColumns(stats.decades, stats.favoriteDecade?.key)
        }

        if (stats.languages.size >= 2) {
            Section(stringResource(R.string.stats_languages), null)
            CountBars(stats.languages.map { it.copy(name = languageName(it.key)) })
        }

        if (stats.themes.isNotEmpty()) {
            Section(stringResource(R.string.stats_themes), stringResource(R.string.stats_themes_hint))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                stats.themes.forEach { theme ->
                    Row(
                        Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(CineColors.Surface)
                            .border(1.dp, CineColors.Border, RoundedCornerShape(10.dp))
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(theme.name, color = Color.White, fontSize = 13.sp)
                        Text("★ ${stars(theme.averageStars)}", color = CineColors.TextMuted, fontSize = 12.sp)
                    }
                }
            }
        }

        if (stats.lovedAgainst.isNotEmpty()) {
            Section(stringResource(R.string.stats_loved_against), stringResource(R.string.stats_loved_against_hint))
            FilmsRow(stats.lovedAgainst, onOpenMovie)
        }
        if (stats.dislikedAgainst.isNotEmpty()) {
            Section(stringResource(R.string.stats_disliked_against), stringResource(R.string.stats_disliked_against_hint))
            FilmsRow(stats.dislikedAgainst, onOpenMovie)
        }
        Text(stringResource(R.string.stats_footer), color = CineColors.TextFaint, fontSize = 12.sp, lineHeight = 17.sp)
    }
}

private fun stars(value: Double): String = String.format(Locale.FRANCE, "%.1f", value)

@Composable
private fun decadeLabel(key: String): String = stringResource(R.string.stats_decade_label, key.takeLast(2).let { if (key.startsWith("20")) key else it })

private fun languageName(code: String): String =
    Locale.forLanguageTag(code).getDisplayLanguage(Locale.FRENCH).takeIf { it.isNotBlank() && it != code }
        ?.replaceFirstChar { it.titlecase(Locale.FRENCH) } ?: code.uppercase(Locale.ROOT)

@Composable
private fun Tile(value: String, label: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(15.dp))
            .background(CineColors.Surface)
            .border(1.dp, CineColors.Border, RoundedCornerShape(15.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(value, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Black)
        Text(label, color = CineColors.TextSecondary, fontSize = 13.sp, lineHeight = 17.sp)
    }
}

@Composable
private fun Section(title: String, hint: String?) {
    Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
        hint?.let { Text(it, color = CineColors.TextMuted, fontSize = 13.sp, lineHeight = 18.sp) }
    }
}

/** Barres horizontales : étoiles moyennes (1 à 5), avec le nombre de films. */
@Composable
private fun StarBars(groups: List<StatsGroup>) {
    var all by rememberSaveable { mutableStateOf(false) }
    val shown = if (all) groups else groups.take(8)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        shown.forEach { group ->
            BarRow(
                label = group.name,
                fraction = ((group.averageStars - 1.0) / 4.0).toFloat(),
                value = "★ ${stars(group.averageStars)}",
                detail = pluralStringResource(R.plurals.stats_films, group.films, group.films),
            )
        }
        if (groups.size > 8) {
            TextButton(onClick = { all = !all }) {
                Text(stringResource(if (all) R.string.stats_show_less else R.string.stats_show_all), color = CineColors.Red)
            }
        }
    }
}

/** Barres horizontales : nombre de films. */
@Composable
private fun CountBars(groups: List<StatsGroup>) {
    val max = groups.maxOfOrNull { it.films }?.coerceAtLeast(1) ?: 1
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        groups.forEach { group ->
            BarRow(
                label = group.name,
                fraction = group.films.toFloat() / max,
                value = group.films.toString(),
                detail = "★ ${stars(group.averageStars)}",
            )
        }
    }
}

@Composable
private fun BarRow(label: String, fraction: Float, value: String, detail: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(label, color = CineColors.TextSoft, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(110.dp))
        Box(Modifier.weight(1f).height(10.dp).clip(RoundedCornerShape(5.dp)).background(CineColors.SurfaceHigh)) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fraction.coerceIn(0.03f, 1f))
                    .clip(RoundedCornerShape(5.dp))
                    .background(CineColors.Red),
            )
        }
        Column(horizontalAlignment = Alignment.End, modifier = Modifier.width(62.dp)) {
            Text(value, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Text(detail, color = CineColors.TextMuted, fontSize = 11.sp, maxLines = 1)
        }
    }
}

/** Réalisateurs : nom, étoiles moyennes, nombre de films ; un toucher ouvre ses films. */
@Composable
private fun PeopleList(groups: List<StatsGroup>, withPhotos: Boolean) {
    val open = LocalOpenPerson.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        groups.forEachIndexed { index, group ->
            val id = group.personId
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(CineColors.Surface)
                    .then(if (open != null && id != null) Modifier.clickable { open(PersonRef(id, group.name)) } else Modifier)
                    .padding(horizontal = 14.dp, vertical = 10.dp)
                    .then(if (id != null) Modifier.testTag(StatsTestTags.director(id)) else Modifier),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("${index + 1}", color = CineColors.Red, fontSize = 16.sp, fontWeight = FontWeight.Black, modifier = Modifier.width(22.dp))
                if (withPhotos) {
                    PosterImage(group.imagePath, TmdbImageSize.Thumb, null, Modifier.size(36.dp).clip(CircleShape))
                }
                Column(Modifier.weight(1f)) {
                    Text(group.name, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(pluralStringResource(R.plurals.stats_films_seen, group.films, group.films), color = CineColors.TextMuted, fontSize = 12.sp)
                }
                StarRow(stars = (Math.round(group.averageStars * 2) / 2.0).toFloat(), starSize = 13.dp)
            }
        }
    }
}

@Composable
private fun ActorsRow(groups: List<StatsGroup>) {
    val open = LocalOpenPerson.current
    LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        items(groups, key = { it.key }) { group ->
            val id = group.personId
            Column(
                Modifier
                    .width(78.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .then(if (open != null && id != null) Modifier.clickable { open(PersonRef(id, group.name)) } else Modifier),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                PosterImage(group.imagePath, TmdbImageSize.Thumb, null, Modifier.size(64.dp).clip(CircleShape).background(CineColors.SurfaceHigh))
                Text(group.name, color = CineColors.TextSoft, fontSize = 12.sp, lineHeight = 14.sp, maxLines = 2, textAlign = TextAlign.Center, overflow = TextOverflow.Ellipsis)
                Text("★ ${stars(group.averageStars)} · ${group.films}", color = CineColors.TextMuted, fontSize = 11.sp)
            }
        }
    }
}

/** Colonnes : films vus par décennie ; la décennie préférée est soulignée. */
@Composable
private fun DecadeColumns(decades: List<StatsGroup>, favorite: String?) {
    val max = decades.maxOf { it.films }.coerceAtLeast(1)
    Row(
        Modifier.fillMaxWidth().height(150.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        decades.forEach { decade ->
            Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                Text(decade.films.toString(), color = CineColors.TextSecondary, fontSize = 10.sp, maxLines = 1)
                Box(
                    Modifier
                        .padding(top = 2.dp)
                        .fillMaxWidth(0.7f)
                        .fillMaxHeight((decade.films.toFloat() / max * 0.78f).coerceAtLeast(0.02f))
                        .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                        .background(if (decade.key == favorite) CineColors.Red else CineColors.Red.copy(alpha = 0.45f)),
                )
                Text("'${decade.key.takeLast(2)}", color = if (decade.key == favorite) Color.White else CineColors.TextMuted, fontSize = 10.sp, maxLines = 1)
            }
        }
    }
}

@Composable
private fun FilmsRow(films: List<StatsFilm>, onOpenMovie: (Long) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(films, key = { it.tmdbId }) { film ->
            Column(
                Modifier.width(100.dp).clickable { onOpenMovie(film.tmdbId) },
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                PosterImage(film.posterPath, TmdbImageSize.Thumb, null, Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(8.dp)))
                Text(film.title, color = Color.White, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 14.sp)
                StarRow(stars = film.stars, starSize = 11.dp)
                Text(stringResource(R.string.stats_tmdb_rating, String.format(Locale.FRANCE, "%.1f", film.tmdbRating)), color = CineColors.TextMuted, fontSize = 11.sp)
            }
        }
    }
}
