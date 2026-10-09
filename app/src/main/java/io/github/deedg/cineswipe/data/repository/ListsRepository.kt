package io.github.deedg.cineswipe.data.repository

import io.github.deedg.cineswipe.data.db.CineSwipeDatabase
import io.github.deedg.cineswipe.data.db.ListFilmRow
import io.github.deedg.cineswipe.data.db.UserListEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/** Une liste perso telle que le profil la montre : nom, nombre de films et quelques affiches. */
data class UserListCard(
    val id: Long,
    val name: String,
    val count: Int,
    val covers: List<String>,
)

/** Listes perso (« Films de Noël », « À montrer à quelqu'un »…), en plus de la liste À voir. */
class ListsRepository(
    private val database: CineSwipeDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val dao get() = database.listDao()

    /** Les listes, la dernière modifiée d'abord, avec jusqu'à 4 affiches chacune. */
    val lists: Flow<List<UserListCard>>
        get() = combine(dao.observeLists(), dao.observeCovers()) { lists, covers ->
            val byList = covers.groupBy({ it.listId }, { it.posterPath })
            lists.map { UserListCard(it.id, it.name, it.count, byList[it.id].orEmpty().take(COVERS)) }
        }

    /** Les listes qui contiennent ce film. */
    fun listsOf(tmdbId: Long): Flow<Set<Long>> = dao.observeListsOf(tmdbId).map { it.toSet() }

    fun list(id: Long): Flow<UserListEntity?> = dao.observeList(id)

    fun films(id: Long): Flow<List<ListFilmRow>> = dao.observeItems(id)

    /** Nouvelle liste (nom raccourci au besoin), avec un premier film si elle naît d'une fiche. */
    suspend fun create(name: String, firstFilm: Long? = null): Long? {
        val clean = clean(name) ?: return null
        return dao.create(clean, clock(), firstFilm)
    }

    suspend fun rename(id: Long, name: String) {
        val clean = clean(name) ?: return
        dao.rename(id, clean, clock())
    }

    suspend fun delete(id: Long) = dao.delete(id)

    suspend fun add(listId: Long, tmdbId: Long) = dao.add(listId, tmdbId, clock())

    suspend fun remove(listId: Long, tmdbId: Long) = dao.remove(listId, tmdbId, clock())

    companion object {
        const val MAX_NAME = 40
        const val COVERS = 4

        /** Nom d'une liste : espaces superflus retirés, 40 caractères au plus ; vide → pas de nom. */
        fun clean(name: String): String? = name.trim().replace(Regex("\\s+"), " ").take(MAX_NAME).trim().takeIf { it.isNotEmpty() }
    }
}
