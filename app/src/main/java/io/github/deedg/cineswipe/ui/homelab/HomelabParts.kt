package io.github.deedg.cineswipe.ui.homelab

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.deedg.cineswipe.data.homelab.HomelabUrls
import io.github.deedg.cineswipe.ui.theme.CineColors
import java.util.Locale

/** Titre de carte avec icône rouge. */
@Composable
internal fun CardTitle(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(icon, contentDescription = null, tint = CineColors.Red, modifier = Modifier.size(20.dp))
        Text(text, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold)
    }
}

@Composable
internal fun CardBody(text: String, modifier: Modifier = Modifier, color: Color = CineColors.TextSecondary) {
    Text(text, color = color, fontSize = 14.sp, lineHeight = 20.sp, modifier = modifier)
}

/** Petite aide sous un champ. */
@Composable
internal fun HintText(text: String, color: Color = CineColors.TextMuted) {
    Text(text, color = color, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(horizontal = 4.dp))
}

/** Libellé de groupe en petites capitales grises. */
@Composable
internal fun GroupLabel(text: String) {
    Text(
        text = text.uppercase(Locale.FRENCH),
        color = CineColors.TextMuted,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.5.sp,
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
    )
}

@Composable
internal fun BusyRow(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        CircularProgressIndicator(color = CineColors.Red, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        CardBody(text)
    }
}

/**
 * « 100.64.0.1:8096 » ou « films.exemple.fr:443/jellyfin » : l'adresse telle qu'elle sera appelée.
 * Une adresse plex.direct est raccourcie à son IP (« 100.64.0.1:32400 · https »).
 */
internal fun hostLabel(raw: String): String {
    val url = HomelabUrls.parse(raw) ?: return raw.trim()
    val path = url.encodedPath.takeIf { it != "/" }.orEmpty()
    val plexIp = PLEX_DIRECT_HOST.matchEntire(url.host)?.groupValues?.get(1)?.replace('-', '.')
    return if (plexIp != null) "$plexIp:${url.port}$path · https" else "${url.host}:${url.port}$path"
}

private val PLEX_DIRECT_HOST = Regex("^(\\d{1,3}-\\d{1,3}-\\d{1,3}-\\d{1,3})\\.[0-9a-z]+\\.plex\\.direct$", RegexOption.IGNORE_CASE)
