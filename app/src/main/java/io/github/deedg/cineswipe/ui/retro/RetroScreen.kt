package io.github.deedg.cineswipe.ui.retro

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import io.github.deedg.cineswipe.R
import io.github.deedg.cineswipe.data.repository.RetroFilm
import io.github.deedg.cineswipe.data.repository.RetroRepository
import io.github.deedg.cineswipe.data.repository.YearInReview
import io.github.deedg.cineswipe.domain.TmdbImageSize
import io.github.deedg.cineswipe.ui.common.cineViewModel
import io.github.deedg.cineswipe.ui.common.shareExport
import io.github.deedg.cineswipe.ui.components.CineButton
import io.github.deedg.cineswipe.ui.components.GlassIconButton
import io.github.deedg.cineswipe.ui.components.LocalOpenPerson
import io.github.deedg.cineswipe.ui.components.LocalToaster
import io.github.deedg.cineswipe.ui.components.PersonRef
import io.github.deedg.cineswipe.ui.components.PosterImage
import io.github.deedg.cineswipe.ui.components.ScreenTitle
import io.github.deedg.cineswipe.ui.components.SectionCard
import io.github.deedg.cineswipe.ui.icons.CineIcons
import io.github.deedg.cineswipe.ui.theme.CineColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormatSymbols
import java.util.Locale

object RetroTestTags {
    const val CARD = "retro_card"
    const val SHARE = "retro_share"
    const val EMPTY = "retro_empty"

    fun year(year: Int) = "retro_year_$year"
    fun top(tmdbId: Long) = "retro_top_$tmdbId"
}

data class RetroUiState(
    val loading: Boolean = true,
    val years: List<Int> = emptyList(),
    val review: YearInReview? = null,
)

class RetroViewModel(private val retro: RetroRepository) : ViewModel() {
    private val _state = MutableStateFlow(RetroUiState())
    val state: StateFlow<RetroUiState> = _state.asStateFlow()

    init {
        select(null)
    }

    fun select(year: Int?) {
        viewModelScope.launch {
            val (years, review) = retro.load(year)
            _state.value = RetroUiState(loading = false, years = years, review = review)
        }
    }
}

@Composable
fun RetroRoute(onBack: () -> Unit, onOpenMovie: (Long) -> Unit) {
    val viewModel = cineViewModel { RetroViewModel(it.retro) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val layer = rememberGraphicsLayer()
    var sharing by remember { mutableStateOf(false) }
    val chooserTitle = stringResource(R.string.retro_share_title)
    val failed = stringResource(R.string.retro_share_failed)

    Column(
        Modifier
            .fillMaxSize()
            .background(CineColors.Black)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ScreenTitle(
            text = stringResource(R.string.retro_title),
            leading = { GlassIconButton(icon = CineIcons.Back, contentDescription = stringResource(R.string.action_back), onClick = onBack) },
        )
        val review = state.review
        when {
            state.loading -> Box(Modifier.fillMaxWidth().height(240.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = CineColors.Red, strokeWidth = 2.dp)
            }
            review == null -> Text(
                stringResource(R.string.retro_no_data),
                color = CineColors.TextSecondary,
                fontSize = 15.sp,
                lineHeight = 21.sp,
                modifier = Modifier.testTag(RetroTestTags.EMPTY),
            )
            else -> {
                if (state.years.size > 1) YearChips(state.years, review.year, onSelect = viewModel::select)
                if (review.empty) {
                    Text(
                        stringResource(R.string.retro_empty, review.year),
                        color = CineColors.TextSecondary,
                        fontSize = 15.sp,
                        lineHeight = 21.sp,
                        modifier = Modifier.testTag(RetroTestTags.EMPTY),
                    )
                } else {
                    // La carte à partager : dessinée dans un calque qu'on peut copier en image.
                    Box(
                        Modifier.drawWithContent {
                            layer.record { this@drawWithContent.drawContent() }
                            drawLayer(layer)
                        },
                    ) {
                        ShareCard(review)
                    }
                    CineButton(
                        text = stringResource(R.string.retro_share),
                        icon = CineIcons.Share,
                        enabled = !sharing,
                        onClick = {
                            sharing = true
                            scope.launch {
                                val file = runCatching { saveImage(context, layer, review.year) }.getOrNull()
                                if (file == null || !context.shareExport(file, chooserTitle, mimeType = "image/png")) toaster.show(failed)
                                sharing = false
                            }
                        },
                        modifier = Modifier.fillMaxWidth().testTag(RetroTestTags.SHARE),
                    )
                    RetroDetails(review, onOpenMovie)
                }
                if (review.backfill > 0) {
                    Text(
                        pluralStringResource(R.plurals.retro_backfill, review.backfill, review.backfill),
                        color = CineColors.TextMuted,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

/** Copie la carte en PNG, dans le dossier des exports (partagé par le FileProvider). */
private suspend fun saveImage(context: Context, layer: GraphicsLayer, year: Int): File {
    val image = layer.toImageBitmap().asAndroidBitmap()
    return withContext(Dispatchers.IO) {
        // Une image matérielle ne s'encode pas telle quelle : copie en mémoire classique.
        val bitmap = if (image.config == Bitmap.Config.HARDWARE) image.copy(Bitmap.Config.ARGB_8888, false) else image
        val directory = File(context.cacheDir, "exports").apply { mkdirs() }
        File(directory, "cineswipe-retro-$year.png").also { file ->
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}

@Composable
private fun YearChips(years: List<Int>, selected: Int, onSelect: (Int) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(years) { year ->
            val active = year == selected
            val shape = RoundedCornerShape(16.dp)
            Text(
                year.toString(),
                color = if (active) Color.White else CineColors.TextSecondary,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                modifier = Modifier
                    .clip(shape)
                    .background(if (active) CineColors.Red else CineColors.Surface)
                    .border(1.dp, if (active) CineColors.Red else CineColors.Border, shape)
                    .clickable(role = Role.Tab) { onSelect(year) }
                    .padding(horizontal = 14.dp, vertical = 7.dp)
                    .testTag(RetroTestTags.year(year)),
            )
        }
    }
}

private fun monthName(month: Int): String =
    DateFormatSymbols.getInstance(Locale.FRANCE).months[month].replaceFirstChar { it.titlecase(Locale.FRANCE) }

/** Carte de la rétro, au format d'une story : année, chiffres, top 3 et coups de cœur. */
@Composable
private fun ShareCard(review: YearInReview) {
    val shape = RoundedCornerShape(22.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Brush.verticalGradient(listOf(Color(0xFF2A0608), Color(0xFF0B0B0B), Color(0xFF000000))))
            .border(1.dp, CineColors.Red.copy(alpha = 0.5f), shape)
            .padding(20.dp)
            .testTag(RetroTestTags.CARD),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(R.string.retro_card_brand), color = CineColors.Red, fontSize = 13.sp, fontWeight = FontWeight.Black, letterSpacing = 4.sp)
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(review.year.toString(), color = Color.White, fontSize = 54.sp, fontWeight = FontWeight.Black, lineHeight = 56.sp)
                Text(
                    stringResource(R.string.retro_card_title),
                    color = CineColors.TextSoft,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 9.dp),
                )
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BigFigure(review.films, stringResource(if (review.films == 1) R.string.retro_film_label else R.string.retro_films_label), Modifier.weight(1f))
            BigFigure(review.hours, stringResource(R.string.retro_hours_label), Modifier.weight(1f))
            if (review.duels > 0) BigFigure(review.duels, stringResource(R.string.retro_duels_label), Modifier.weight(1f))
        }
        if (review.top.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.retro_top_card), color = CineColors.TextSecondary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    review.top.take(3).forEachIndexed { index, film -> RankedPoster(index + 1, film, Modifier.weight(1f)) }
                    repeat(3 - review.top.take(3).size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            review.genre?.let { CardLine(stringResource(R.string.retro_genre), it) }
            review.director?.let {
                CardLine(stringResource(R.string.retro_director), pluralStringResource(R.plurals.retro_director_films, review.directorFilms, it.name, review.directorFilms))
            }
            review.bestMonth?.let { month ->
                val count = review.months[month]
                CardLine(stringResource(R.string.retro_best_month), pluralStringResource(R.plurals.retro_month_films, count, monthName(month), count))
            }
        }
    }
}

@Composable
private fun BigFigure(value: Int, label: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(alpha = 0.06f))
            .padding(vertical = 10.dp, horizontal = 12.dp),
    ) {
        Text(value.toString(), color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Black, maxLines = 1)
        Text(label, color = CineColors.TextSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun RankedPoster(rank: Int, film: RetroFilm, modifier: Modifier = Modifier) {
    Box(modifier) {
        PosterImage(
            path = film.posterPath,
            imageSize = TmdbImageSize.Poster,
            contentDescription = film.title,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(10.dp))
                .background(CineColors.SurfaceHigh),
        )
        Box(
            Modifier
                .padding(5.dp)
                .size(26.dp)
                .clip(CircleShape)
                .background(if (rank == 1) CineColors.Red else Color.Black.copy(alpha = 0.75f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(rank.toString(), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Black)
        }
    }
}

@Composable
private fun CardLine(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("$label :", color = CineColors.TextMuted, fontSize = 13.sp)
        Text(value, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Sous la carte : les mois, le top complet, le premier et le dernier film. */
@Composable
private fun RetroDetails(review: YearInReview, onOpenMovie: (Long) -> Unit) {
    SectionCard {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.retro_months), color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold)
            MonthBars(review.months, review.bestMonth)
        }
    }
    if (review.top.isNotEmpty()) {
        SectionCard {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.retro_top), color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold)
                review.top.forEachIndexed { index, film ->
                    FilmLine("${index + 1}.", film, onClick = { onOpenMovie(film.tmdbId) }, modifier = Modifier.testTag(RetroTestTags.top(film.tmdbId)))
                }
            }
        }
    }
    val first = review.first
    val last = review.last
    if (first != null) {
        SectionCard {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.retro_first), color = CineColors.TextSecondary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                FilmLine(null, first, onClick = { onOpenMovie(first.tmdbId) })
                if (last != null) {
                    Text(stringResource(R.string.retro_last), color = CineColors.TextSecondary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    FilmLine(null, last, onClick = { onOpenMovie(last.tmdbId) })
                }
            }
        }
    }
    val director = review.director
    val openPerson = LocalOpenPerson.current
    if (director != null && openPerson != null) {
        SectionCard(onClick = { openPerson(PersonRef(director.id, director.name)) }) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.retro_director), color = CineColors.TextSecondary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Text(
                    pluralStringResource(R.plurals.retro_director_films, review.directorFilms, director.name, review.directorFilms),
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

/** Un mois = une barre ; le plus chargé en rouge. */
@Composable
private fun MonthBars(months: List<Int>, best: Int?) {
    val max = months.maxOrNull()?.coerceAtLeast(1) ?: 1
    val initials = DateFormatSymbols.getInstance(Locale.FRANCE).shortMonths
    Row(Modifier.fillMaxWidth().height(120.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.Bottom) {
        months.forEachIndexed { month, count ->
            Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                if (count > 0) Text(count.toString(), color = CineColors.TextSecondary, fontSize = 10.sp, maxLines = 1)
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height((80f * count / max).dp.coerceAtLeast(2.dp))
                        .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                        .background(if (month == best) CineColors.Red else CineColors.BorderStrong),
                )
                Text(
                    initials[month].take(1).uppercase(Locale.FRANCE),
                    color = CineColors.TextMuted,
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun FilmLine(rank: String?, film: RetroFilm, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(role = Role.Button, onClickLabel = film.title, onClick = onClick),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (rank != null) Text(rank, color = CineColors.Red, fontSize = 16.sp, fontWeight = FontWeight.Black, modifier = Modifier.width(26.dp))
        PosterImage(
            path = film.posterPath,
            imageSize = TmdbImageSize.Thumb,
            contentDescription = null,
            modifier = Modifier.width(40.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(6.dp)).background(CineColors.SurfaceHigh),
        )
        Column(Modifier.weight(1f)) {
            Text(film.title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            film.year?.let { Text(it.toString(), color = CineColors.TextMuted, fontSize = 12.sp) }
        }
        if (film.stars > 0f) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                Icon(CineIcons.StarFilled, contentDescription = null, tint = CineColors.Red, modifier = Modifier.size(14.dp))
                Text(String.format(Locale.FRANCE, "%.1f", film.stars), color = CineColors.TextSoft, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}
