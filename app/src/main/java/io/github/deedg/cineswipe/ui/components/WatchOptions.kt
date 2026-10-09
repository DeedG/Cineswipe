package io.github.deedg.cineswipe.ui.components

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.deedg.cineswipe.R
import io.github.deedg.cineswipe.data.homelab.HomelabService
import io.github.deedg.cineswipe.data.homelab.WatchLink
import io.github.deedg.cineswipe.data.repository.WatchProvider
import io.github.deedg.cineswipe.data.repository.WatchProviders
import io.github.deedg.cineswipe.domain.TmdbImageSize
import io.github.deedg.cineswipe.ui.icons.CineIcons
import io.github.deedg.cineswipe.ui.theme.CineColors

object WatchTestTags {
    fun button(service: HomelabService) = "watch_${service.name.lowercase()}"
    const val PROVIDERS = "watch_providers"
    const val PROVIDERS_LINE = "watch_providers_line"
}

/** Couleur de chaque serveur média sur ses boutons. */
val HomelabService.accent: Color
    get() = when (this) {
        HomelabService.PLEX -> Color(0xFFE5A00D)
        HomelabService.JELLYFIN -> Color(0xFFAA5CC3)
        else -> CineColors.TextSecondary
    }

/**
 * Ouvre un film sur son serveur : l'appli du serveur si elle est installée et sait ouvrir le
 * lien, sinon l'interface web dans le navigateur.
 */
fun openWatchLink(context: Context, uriHandler: UriHandler, link: WatchLink, onDemo: (WatchLink) -> Unit = {}) {
    if (link.demo) {
        onDemo(link)
        return
    }
    val target = link.appUri ?: link.webUrl
    val opened = link.appPackage?.let { pkg ->
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(target)).setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (error: ActivityNotFoundException) {
            false
        }
    } ?: false
    if (!opened) runCatching { uriHandler.openUri(link.webUrl) }
}

/** Un bouton « Plex », « Jellyfin »… par serveur qui a le film (aucun sans serveur média). */
@Composable
fun WatchButtons(services: List<HomelabService>, onWatch: (HomelabService) -> Unit, modifier: Modifier = Modifier) {
    if (services.isEmpty()) return
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        services.forEach { service ->
            val shape = RoundedCornerShape(15.dp)
            Row(
                Modifier
                    .weight(1f)
                    .clip(shape)
                    .background(service.accent.copy(alpha = 0.16f))
                    .border(1.dp, service.accent.copy(alpha = 0.7f), shape)
                    .clickable(role = Role.Button, onClick = { onWatch(service) })
                    .padding(horizontal = 14.dp, vertical = 13.dp)
                    .testTag(WatchTestTags.button(service)),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(CineIcons.Play, contentDescription = null, tint = service.accent, modifier = Modifier.size(18.dp))
                Text(
                    stringResource(R.string.watch_on, service.displayName),
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Petites pastilles « ▶ Plex », « ▶ Jellyfin » pour les listes. */
@Composable
fun WatchPills(services: List<HomelabService>, onWatch: (HomelabService) -> Unit) {
    if (services.isEmpty()) return
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        services.forEach { service ->
            val shape = RoundedCornerShape(8.dp)
            Row(
                Modifier
                    .clip(shape)
                    .background(service.accent.copy(alpha = 0.18f))
                    .border(1.dp, service.accent.copy(alpha = 0.6f), shape)
                    .clickable(role = Role.Button, onClick = { onWatch(service) })
                    .padding(horizontal = 9.dp, vertical = 5.dp)
                    .testTag(WatchTestTags.button(service)),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(CineIcons.Play, contentDescription = null, tint = service.accent, modifier = Modifier.size(12.dp))
                Text(service.displayName, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/** « Où le regarder » : abonnements, location, achat (données JustWatch via TMDB). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WatchProvidersSection(providers: WatchProviders, uriHandler: UriHandler, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(15.dp)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(CineColors.Glass)
            .border(1.dp, CineColors.Border, shape)
            .then(providers.link?.let { link -> Modifier.clickable { runCatching { uriHandler.openUri(link) } } } ?: Modifier)
            .padding(16.dp)
            .testTag(WatchTestTags.PROVIDERS),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(stringResource(R.string.providers_title), color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold)
        if (providers.isEmpty) {
            Text(stringResource(R.string.providers_none), color = CineColors.TextSecondary, fontSize = 14.sp)
        }
        ProviderGroup(stringResource(R.string.providers_stream), providers.stream)
        ProviderGroup(stringResource(R.string.providers_rent), providers.rent)
        ProviderGroup(stringResource(R.string.providers_buy), providers.buy)
        Text(stringResource(R.string.providers_source), color = CineColors.TextFaint, fontSize = 11.sp)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProviderGroup(label: String, providers: List<WatchProvider>) {
    if (providers.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, color = CineColors.TextMuted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            providers.take(MAX_PROVIDERS).forEach { provider -> ProviderChip(provider) }
        }
    }
}

@Composable
private fun ProviderChip(provider: WatchProvider) {
    Row(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(CineColors.SurfaceHigh)
            .padding(end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PosterImage(
            path = provider.logoPath,
            imageSize = TmdbImageSize.Logo,
            contentDescription = null,
            modifier = Modifier.size(30.dp).clip(RoundedCornerShape(8.dp)),
        )
        Text(provider.name, color = CineColors.TextSoft, fontSize = 13.sp, maxLines = 1)
    }
}

/** Une ligne pour les listes : « Sur Netflix, Canal+ » (abonnements seulement). */
@Composable
fun ProvidersLine(providers: WatchProviders?) {
    val stream = providers?.stream.orEmpty()
    if (stream.isEmpty()) return
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.testTag(WatchTestTags.PROVIDERS_LINE),
    ) {
        stream.take(3).forEach { provider ->
            PosterImage(
                path = provider.logoPath,
                imageSize = TmdbImageSize.Logo,
                contentDescription = null,
                modifier = Modifier.size(18.dp).clip(RoundedCornerShape(4.dp)),
            )
        }
        Text(
            stringResource(R.string.providers_on, stream.take(3).joinToString(", ") { it.name }),
            color = CineColors.TextSecondary,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
    }
}

private const val MAX_PROVIDERS = 8
