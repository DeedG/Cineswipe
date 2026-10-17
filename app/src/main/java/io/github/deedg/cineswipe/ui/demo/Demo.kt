package io.github.deedg.cineswipe.ui.demo

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.deedg.cineswipe.CineSwipeApp
import io.github.deedg.cineswipe.MainActivity
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/**
 * Entrée et sortie de la démo (accueil, réglages, bandeau). Après le changement, l'écran est
 * relancé de zéro : tous les écrans repartent sur le bon jeu de données.
 */
class DemoViewModel(private val app: CineSwipeApp) : ViewModel() {
    private val mutableStarting = MutableStateFlow(false)

    /** La démo se prépare (base et profil de démo). */
    val starting: StateFlow<Boolean> = mutableStarting.asStateFlow()

    private val restartEvents = Channel<Unit>(Channel.BUFFERED)

    /** L'écran doit être relancé pour passer sur l'autre jeu de données. */
    val restarts: Flow<Unit> = restartEvents.receiveAsFlow()

    fun start() {
        if (mutableStarting.value) return
        mutableStarting.value = true
        viewModelScope.launch {
            try {
                // Démo du monde ouvert : films, ou séries.
                app.startDemo(app.kind)
                restartEvents.send(Unit)
            } finally {
                mutableStarting.value = false
            }
        }
    }

    fun stop() {
        viewModelScope.launch {
            app.stopDemo()
            // Après la démo, l'accueil du premier lancement ne revient pas.
            app.container.settings.setOnboardingDone(true)
            restartEvents.send(Unit)
        }
    }
}

@Composable
fun demoViewModel(): DemoViewModel {
    val app = LocalContext.current.applicationContext as CineSwipeApp
    return viewModel(key = "demo", factory = viewModelFactory { initializer { DemoViewModel(app) } })
}

/** Relance l'écran principal dès que la démo commence ou se termine. */
@Composable
fun DemoRestartEffect(viewModel: DemoViewModel) {
    val activity = LocalActivity.current
    LaunchedEffect(viewModel, activity) {
        viewModel.restarts.collect { activity?.restartMain() }
    }
}

/** Nouvelle activité, ViewModels neufs : l'appli repart sur le conteneur en service. */
fun Activity.restartMain() {
    startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
    finish()
}
