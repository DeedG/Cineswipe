package io.github.deedg.cineswipe.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.TextUnit
import io.github.deedg.cineswipe.R
import io.github.deedg.cineswipe.data.db.MovieEntity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.deedg.cineswipe.data.db.CastMember
import io.github.deedg.cineswipe.domain.TmdbImageSize
import io.github.deedg.cineswipe.ui.icons.CineIcons
import io.github.deedg.cineswipe.ui.theme.CineColors

/** Personne à ouvrir (acteur, réalisateur) : sa fiche et ses films. */
data class PersonRef(val id: Long, val name: String)

/** Ouvre la fiche d'une personne ; null quand l'écran ne le permet pas (rien n'est alors cliquable). */
val LocalOpenPerson = staticCompositionLocalOf<((PersonRef) -> Unit)?> { null }

/** Acteur : photo ronde et nom sur deux lignes (`.actor` en V4) ; un toucher ouvre ses films. */
@Composable
fun ActorAvatar(member: CastMember, modifier: Modifier = Modifier) {
    val open = LocalOpenPerson.current
    val id = member.id
    val clickable = if (open != null && id != null) {
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(role = Role.Button, onClickLabel = member.name) { open(PersonRef(id, member.name)) }
            .testTag(PersonTestTags.actor(id))
    } else {
        Modifier
    }
    Column(modifier.then(clickable).width(60.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (member.profilePath != null) {
            PosterImage(
                path = member.profilePath,
                imageSize = TmdbImageSize.Thumb,
                contentDescription = null,
                modifier = Modifier.size(50.dp).clip(CircleShape),
            )
        } else {
            Box(
                Modifier.size(50.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.1f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(CineIcons.Profile, contentDescription = null, tint = CineColors.TextMuted, modifier = Modifier.size(22.dp))
            }
        }
        Text(
            text = member.name,
            color = CineColors.TextSoft,
            fontSize = 11.sp,
            lineHeight = 13.sp,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

object PersonTestTags {
    fun actor(id: Long) = "actor_$id"
    const val DIRECTOR = "directed_by"
}

/**
 * « Réalisé par A et B » : chaque nom souligné ouvre la fiche du réalisateur. Sans identifiant
 * (ancienne fiche), le texte reste simple.
 */
@Composable
fun DirectedBy(movie: MovieEntity, color: Color, fontSize: TextUnit, modifier: Modifier = Modifier, textAlign: TextAlign? = null) {
    val names = movie.director ?: return
    val open = LocalOpenPerson.current
    val directors = movie.directors.filter { it.name.isNotBlank() }
    val template = stringResource(R.string.directed_by, "\u0000")
    val prefix = template.substringBefore("\u0000")
    val suffix = template.substringAfter("\u0000")
    val text = if (open == null || directors.isEmpty()) {
        AnnotatedString(template.replace("\u0000", names))
    } else {
        val separator = stringResource(R.string.directed_by_separator)
        buildAnnotatedString {
            append(prefix)
            directors.forEachIndexed { index, director ->
                if (index > 0) append(if (index == directors.lastIndex) separator else ", ")
                withLink(
                    LinkAnnotation.Clickable(
                        tag = "director-${director.id}",
                        styles = TextLinkStyles(SpanStyle(textDecoration = TextDecoration.Underline, color = Color.White)),
                        linkInteractionListener = { open(PersonRef(director.id, director.name)) },
                    ),
                ) { append(director.name) }
            }
            append(suffix)
        }
    }
    Text(
        text = text,
        color = color,
        fontStyle = FontStyle.Italic,
        fontSize = fontSize,
        textAlign = textAlign,
        modifier = modifier.testTag(PersonTestTags.DIRECTOR),
    )
}
