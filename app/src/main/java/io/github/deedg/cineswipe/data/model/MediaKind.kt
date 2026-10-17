package io.github.deedg.cineswipe.data.model

/**
 * Les deux mondes de l'appli. Chacun a sa base, ses recos, son Arène, sa bibliothèque et ses
 * listes ; le switch « Films | Séries » passe de l'un à l'autre.
 */
enum class MediaKind {
    FILMS,
    SERIES,
    ;

    val isSeries: Boolean get() = this == SERIES

    companion object {
        fun fromName(name: String?): MediaKind = entries.firstOrNull { it.name == name } ?: FILMS
    }
}
