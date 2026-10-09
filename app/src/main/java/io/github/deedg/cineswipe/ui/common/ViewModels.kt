package io.github.deedg.cineswipe.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.deedg.cineswipe.AppContainer
import io.github.deedg.cineswipe.CineSwipeApp

/**
 * Crée un ViewModel à partir du conteneur de l'appli. Avec Navigation 3, il est rattaché
 * à l'écran courant et libéré quand on le quitte.
 */
@Composable
inline fun <reified VM : ViewModel> cineViewModel(
    key: String? = null,
    crossinline create: (AppContainer) -> VM,
): VM {
    val container = (LocalContext.current.applicationContext as CineSwipeApp).container
    return viewModel(
        key = key,
        factory = viewModelFactory { initializer { create(container) } },
    )
}
