package io.github.deedg.cineswipe.data.demo

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import java.util.concurrent.atomic.AtomicLong

/**
 * Homelab fictif de la démo : un serveur Plex et un Seerr qui répondent depuis l'appli, sans
 * réseau. Le Plex a les films vus du profil et un film sur trois du catalogue ; Seerr accepte
 * les demandes. Ainsi la démo montre « Sur le serveur », « Regarder sur Plex » et les demandes
 * d'un swipe, comme avec un vrai homelab.
 */
class DemoHomelabInterceptor(private val catalog: DemoCatalog, private val series: Boolean = false) : Interceptor {
    private val nextRequest = AtomicLong(100)

    /** Type Seerr des œuvres du catalogue. */
    private val type = if (series) "tv" else "movie"

    /** Films « sur le serveur » : ceux vus du profil, puis un sur trois du catalogue. */
    val available: Set<Long> by lazy { availableIds(catalog, series) }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url
        val path = url.pathSegments.filter { it.isNotEmpty() }.joinToString("/")
        val body = when (url.host) {
            DemoHomelab.PLEX_HOST -> plex(path, url.queryParameter("X-Plex-Container-Start")?.toIntOrNull() ?: 0)
            DemoHomelab.SEERR_HOST -> seerr(request.method, path, request.body?.let { body -> Buffer().also(body::writeTo).readUtf8() })
            else -> null
        }
        val code = when {
            body == null -> 404
            body.isEmpty() -> 204
            else -> 200
        }
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(if (code == 404) "Not Found" else "OK")
            .body((body ?: "{}").toResponseBody(JSON_TYPE))
            .build()
    }

    private fun plex(path: String, start: Int): String? = when (path) {
        "" -> buildJsonObject {
            putJsonObject("MediaContainer") {
                put("friendlyName", "Plex de la démo")
                put("version", "1.41")
                put("machineIdentifier", "demo")
            }
        }.toString()
        "library/sections" -> buildJsonObject {
            putJsonObject("MediaContainer") {
                putJsonArray("Directory") {
                    addJsonObject {
                        put("key", "1")
                        put("title", "Films")
                        put("type", "movie")
                    }
                }
            }
        }.toString()
        "library/sections/1/all" -> {
            val films = catalog.films.filter { it.id in available }
            buildJsonObject {
                putJsonObject("MediaContainer") {
                    put("size", if (start == 0) films.size else 0)
                    put("totalSize", films.size)
                    putJsonArray("Metadata") {
                        if (start == 0) {
                            films.forEach { film ->
                                addJsonObject {
                                    put("ratingKey", film.id.toString())
                                    put("title", film.title)
                                    film.year?.let { put("year", it) }
                                    if (film.id in DemoSeeder.WATCHED) put("viewCount", 1)
                                    putJsonArray("Guid") { addJsonObject { put("id", "tmdb://${film.id}") } }
                                }
                            }
                        }
                    }
                }
            }.toString()
        }
        else -> null
    }

    private fun seerr(method: String, path: String, body: String?): String? = when {
        path == "api/v1/status" -> """{"version":"démo"}"""
        path == "api/v1/auth/me" -> """{"id":1,"displayName":"Démo"}"""
        path == "api/v1/request" && method == "GET" -> buildJsonObject {
            val pending = (if (series) DemoSeeder.WATCHLIST_SERIES else DemoSeeder.WATCHLIST).take(1)
            putJsonObject("pageInfo") {
                put("pages", 1)
                put("page", 1)
                put("results", pending.size)
            }
            putJsonArray("results") {
                pending.forEachIndexed { index, id ->
                    addJsonObject {
                        put("id", index + 1)
                        put("status", 2)
                        put("type", type)
                        putJsonObject("media") {
                            put("tmdbId", id)
                            put("mediaType", type)
                            put("status", 3)
                        }
                    }
                }
            }
        }.toString()
        path == "api/v1/request" && method == "POST" -> {
            val tmdbId = body?.let { runCatching { Json.parseToJsonElement(it).jsonObject["mediaId"]?.jsonPrimitive?.long }.getOrNull() } ?: 0L
            buildJsonObject {
                put("id", nextRequest.incrementAndGet())
                put("status", 2)
                put("type", type)
                putJsonObject("media") {
                    put("tmdbId", tmdbId)
                    put("mediaType", type)
                    put("status", 3)
                }
            }.toString()
        }
        path.startsWith("api/v1/request/") && method == "DELETE" -> ""
        path == "api/v1/media" -> buildJsonObject {
            putJsonObject("pageInfo") {
                put("pages", 1)
                put("page", 1)
                put("results", available.size)
            }
            putJsonArray("results") {
                available.forEach { id ->
                    add(
                        JsonObject(
                            mapOf(
                                "tmdbId" to Json.parseToJsonElement(id.toString()),
                                "mediaType" to Json.parseToJsonElement("\"$type\""),
                                "status" to Json.parseToJsonElement("5"),
                            ),
                        ),
                    )
                }
            }
        }.toString()
        else -> null
    }

    companion object {
        private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()

        fun availableIds(catalog: DemoCatalog, series: Boolean = false): Set<Long> {
            val known = catalog.films.map { it.id }.toSet()
            val watched = (if (series) DemoSeeder.WATCHED_SERIES else DemoSeeder.WATCHED).filter { it in known }
            val watchlist = if (series) DemoSeeder.WATCHLIST_SERIES else DemoSeeder.WATCHLIST
            val others = catalog.films.map { it.id }.filter { it !in watched && it !in watchlist }
            return (watched + others.filterIndexed { index, _ -> index % 3 == 0 }).toSet()
        }
    }
}

/** Adresses du homelab fictif de la démo. */
object DemoHomelab {
    const val PLEX_HOST = "plex.demo"
    const val SEERR_HOST = "seerr.demo"
    const val PLEX_URL = "https://$PLEX_HOST:32400"
    const val SEERR_URL = "https://$SEERR_HOST:5055"
    const val KEY = "demo"
}
