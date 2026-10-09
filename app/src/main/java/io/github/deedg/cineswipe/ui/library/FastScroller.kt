package io.github.deedg.cineswipe.ui.library

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.deedg.cineswipe.ui.theme.CineColors

/**
 * Index latéral de la V4 : on glisse le doigt le long des lettres (ou des paliers d'Elo)
 * pour sauter dans la liste. [onActiveChange] reçoit l'index survolé, null au relâchement.
 */
@Composable
fun FastScroller(
    labels: List<String>,
    onSelect: (Int) -> Unit,
    onActiveChange: (Int?) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (labels.isEmpty()) return
    val currentLabels by rememberUpdatedState(labels)
    val currentOnSelect by rememberUpdatedState(onSelect)
    val currentOnActiveChange by rememberUpdatedState(onActiveChange)
    var height by remember { mutableIntStateOf(1) }
    var active by remember { mutableIntStateOf(-1) }

    fun select(y: Float) {
        val index = ((y / height) * currentLabels.size).toInt().coerceIn(0, currentLabels.lastIndex)
        if (index != active) {
            active = index
            currentOnSelect(index)
            currentOnActiveChange(index)
        }
    }

    Column(
        modifier = modifier
            .width(30.dp)
            .onSizeChanged { height = it.height.coerceAtLeast(1) }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    select(down.position.y)
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: break
                        if (!change.pressed) break
                        change.consume()
                        select(change.position.y)
                    }
                    active = -1
                    currentOnActiveChange(null)
                }
            },
    ) {
        labels.forEachIndexed { index, label ->
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                val isActive = index == active
                Text(
                    text = label,
                    color = CineColors.Red,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.graphicsLayer {
                        val scale = if (isActive) 1.6f else 1f
                        scaleX = scale
                        scaleY = scale
                        translationX = if (isActive) -6.dp.toPx() else 0f
                    },
                )
            }
        }
    }
}
