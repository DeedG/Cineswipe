package io.github.deedg.cineswipe.ui.common

import android.annotation.SuppressLint
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Resources
import android.util.SparseIntArray
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import io.github.deedg.cineswipe.CineSwipeApp

/**
 * Textes du monde des séries : une chaîne (ou un pluriel) « nom_series » remplace « nom »
 * (res/values/strings_series.xml), les autres restent ceux des films. Les écrans n'ont rien à
 * savoir : `stringResource` lit ces ressources dans le mode séries.
 */
@Suppress("DEPRECATION")
class SeriesResources(private val base: Resources, private val packageName: String) :
    Resources(base.assets, base.displayMetrics, base.configuration) {

    private val variants = SparseIntArray()

    /** Identifiant de la variante « séries » de [id], ou [id] lui-même s'il n'y en a pas. */
    @SuppressLint("DiscouragedApi")
    fun variant(id: Int): Int {
        val known = variants.get(id, UNKNOWN)
        if (known != UNKNOWN) return known
        val found = runCatching {
            val type = base.getResourceTypeName(id)
            if (type != "string" && type != "plurals") return@runCatching id
            base.getIdentifier(base.getResourceEntryName(id) + SUFFIX, type, packageName).takeIf { it != 0 } ?: id
        }.getOrDefault(id)
        variants.put(id, found)
        return found
    }

    override fun getText(id: Int): CharSequence = base.getText(variant(id))

    override fun getText(id: Int, def: CharSequence?): CharSequence? = base.getText(variant(id), def)

    override fun getString(id: Int): String = base.getString(variant(id))

    override fun getString(id: Int, vararg formatArgs: Any?): String = base.getString(variant(id), *formatArgs)

    override fun getQuantityText(id: Int, quantity: Int): CharSequence = base.getQuantityText(variant(id), quantity)

    override fun getQuantityString(id: Int, quantity: Int): String = base.getQuantityString(variant(id), quantity)

    override fun getQuantityString(id: Int, quantity: Int, vararg formatArgs: Any?): String =
        base.getQuantityString(variant(id), quantity, *formatArgs)

    companion object {
        const val SUFFIX = "_series"
        private const val UNKNOWN = Int.MIN_VALUE
    }
}

/** Contexte dont les ressources parlent de séries (pour les écrans du monde des séries). */
class SeriesContext(base: Context) : ContextWrapper(base) {
    private val seriesResources by lazy { SeriesResources(base.resources, base.packageName) }

    override fun getResources(): Resources = seriesResources
}

/** Vrai dans le monde des séries (hors démo). */
@Composable
fun isSeriesWorld(): Boolean {
    val app = LocalContext.current.applicationContext as CineSwipeApp
    return app.container.kind.isSeries
}
