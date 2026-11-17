package io.github.deedg.cineswipe.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.deedg.cineswipe.ui.theme.CineColors

object BottomBarTestTags {
    fun tab(tab: Tab) = "tab_${tab.name.lowercase()}"
}

/** Barre du bas : fond presque noir, onglet actif en rouge, les Recos au centre dans une pastille. */
@Composable
fun CineBottomBar(
    selected: Tab,
    onSelect: (Tab) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().background(CineColors.NavBar)) {
        HorizontalDivider(thickness = 1.dp, color = CineColors.Border)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .height(64.dp),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Tab.entries.forEach { tab ->
                BottomBarItem(
                    tab = tab,
                    isSelected = tab == selected,
                    onClick = { onSelect(tab) },
                    modifier = Modifier.weight(1f).testTag(BottomBarTestTags.tab(tab)),
                )
            }
        }
    }
}

@Composable
private fun BottomBarItem(
    tab: Tab,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val color = if (isSelected) CineColors.Red else CineColors.TextFaint
    Box(
        modifier = modifier
            .fillMaxHeight()
            .semantics { selected = isSelected }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Tab,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(if (tab == Tab.RECOS) 1.dp else 4.dp)) {
            if (tab == Tab.RECOS) {
                // Le but du parcours : une pastille pleine, toujours visible.
                Box(
                    Modifier
                        .size(width = 46.dp, height = 27.dp)
                        .clip(RoundedCornerShape(15.dp))
                        .background(if (isSelected) CineColors.Red else CineColors.SurfaceHigh),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(tab.icon(), contentDescription = null, tint = if (isSelected) Color.White else CineColors.TextSecondary, modifier = Modifier.size(20.dp))
                }
            } else {
                Icon(tab.icon(), contentDescription = null, tint = color, modifier = Modifier.size(24.dp))
            }
            Text(
                text = stringResource(tab.label),
                color = color,
                fontSize = 11.sp,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
            )
        }
    }
}
