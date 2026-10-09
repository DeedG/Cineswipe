package io.github.deedg.cineswipe.domain

/** Tailles d'image proposées par TMDB. */
enum class TmdbImageSize(val segment: String) {
    Logo("w92"),
    Thumb("w185"),
    Poster("w500"),
    Large("w780"),
}

object TmdbImages {
    private const val BASE_URL = "https://image.tmdb.org/t/p/"

    /** URL complète d'une image TMDB à partir de son chemin (« /abc.jpg »). */
    fun url(path: String?, size: TmdbImageSize): String? =
        path?.takeIf { it.startsWith("/") }?.let { BASE_URL + size.segment + it }
}
