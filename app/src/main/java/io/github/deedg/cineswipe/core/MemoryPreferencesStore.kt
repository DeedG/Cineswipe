package io.github.deedg.cineswipe.core

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Réglages gardés en mémoire seulement (démo) : rien n'est écrit sur le téléphone. */
class MemoryPreferencesStore : DataStore<Preferences> {
    private val state = MutableStateFlow(emptyPreferences())
    private val mutex = Mutex()

    override val data: Flow<Preferences> = state.asStateFlow()

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences = mutex.withLock {
        val updated = transform(state.value)
        state.value = updated
        updated
    }
}

/** Clés « chiffrées » de la démo : il n'y a rien à protéger, elles restent en clair et en mémoire. */
object PlainSecretCipher : SecretCipher {
    override fun encrypt(plain: String): String = plain

    override fun decrypt(encoded: String): String? = encoded
}
