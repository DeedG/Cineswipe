package io.github.deedg.cineswipe.data.homelab

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dns
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.InetAddress
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Erreur d'un service du homelab, traduite en message par l'interface. */
class HomelabException(
    val service: HomelabService,
    val kind: Kind,
    message: String,
    val code: Int? = null,
    cause: Throwable? = null,
    /** Message renvoyé par le service lui-même (« Movie Quota exceeded. »…), s'il y en a un. */
    val serverMessage: String? = null,
) : Exception(message, cause) {
    enum class Kind {
        /** Adresse illisible. */
        BAD_URL,

        /** Serveur injoignable (hors ligne, mauvaise adresse ou mauvais port, Tailscale coupé). */
        UNREACHABLE,

        /** Certificat HTTPS refusé (auto-signé, ou établi pour un autre nom). */
        TLS,

        /** Clé ou jeton refusé. */
        UNAUTHORIZED,
        NOT_FOUND,

        /** Déjà demandé ou déjà présent. */
        CONFLICT,
        HTTP,

        /** Réponse qui ne ressemble pas à ce service (mauvais port ?). */
        INVALID_RESPONSE,

        /** Réglage manquant (utilisateur, profil de qualité…). */
        NOT_CONFIGURED,

        /** Ce mode de connexion est désactivé sur le service (ex. connexion par e-mail dans Seerr). */
        LOGIN_DISABLED,

        /** Connexion plex.tv non validée à temps dans le navigateur. */
        LOGIN_TIMEOUT,
    }

    /** Erreur passagère (serveur éteint, réseau coupé, surcharge) : on réessaiera plus tard. */
    val isTransient: Boolean
        get() = kind == Kind.UNREACHABLE || (kind == Kind.HTTP && (code == null || code >= 500 || code == 429))
}

/** Adresses et en-têtes saisis par l'utilisateur. */
object HomelabUrls {
    /**
     * « 100.64.0.1:8096 » → « http://100.64.0.1:8096/ ». Le schéma http est ajouté s'il manque ;
     * un chemin de base (« https://exemple.fr/jellyfin ») est conservé. Null si l'adresse est invalide.
     */
    fun parse(raw: String): HttpUrl? {
        val trimmed = raw.trim().trimEnd('/')
        if (trimmed.isEmpty() || trimmed.any { it.isWhitespace() }) return null
        val withScheme = if (trimmed.contains("://")) trimmed else "http://$trimmed"
        val url = withScheme.toHttpUrlOrNull() ?: return null
        return url.takeIf { it.host.isNotEmpty() }
    }

    /** Lignes « Nom: valeur » → en-têtes. Les lignes mal formées sont ignorées. */
    fun parseHeaders(raw: String): Map<String, String> =
        raw.lines().mapNotNull { line ->
            val separator = line.indexOf(':')
            if (separator <= 0) return@mapNotNull null
            val name = line.substring(0, separator).trim()
            val value = line.substring(separator + 1).trim()
            if (name.isEmpty() || value.isEmpty() || name.any { it.isWhitespace() }) null else name to value
        }.toMap()

    /**
     * Vrai pour une adresse http qui sort du réseau local et de Tailscale : la clé passerait en
     * clair sur Internet. Le tailnet chiffre déjà le trafic, donc http y suffit.
     */
    fun isInsecurePublic(url: HttpUrl): Boolean = url.scheme == "http" && !isPrivateHost(url.host)

    fun isPrivateHost(host: String): Boolean {
        val lower = host.lowercase().removePrefix("[").removeSuffix("]")
        if (lower.contains(':')) {
            // IPv6 : boucle locale, adresses locales uniques (dont Tailscale, fd7a:115c:a1e0::/48) et lien local.
            return lower == "::1" || lower.startsWith("fc") || lower.startsWith("fd") || lower.startsWith("fe80:")
        }
        if (lower == "localhost" || lower.endsWith(".local") || lower.endsWith(".lan") ||
            lower.endsWith(".home") || lower.endsWith(".ts.net") || !lower.contains('.')
        ) {
            return true
        }
        val parts = lower.split('.').map { it.toIntOrNull() ?: return false }
        if (parts.size != 4 || parts.any { it !in 0..255 }) return false
        val (a, b) = parts
        return a == 10 || a == 127 ||
            (a == 172 && b in 16..31) ||
            (a == 192 && b == 168) ||
            (a == 169 && b == 254) ||
            (a == 100 && b in 64..127) // plage CGNAT utilisée par Tailscale
    }
}

/** Requêtes HTTP vers les services du homelab, avec leur adresse et leurs en-têtes propres. */
class HomelabHttp(private val client: OkHttpClient = defaultClient()) {

    val json: Json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
    }

    class Reply(val code: Int, val body: String, val headers: Headers)

    suspend fun get(
        service: HomelabService,
        base: HttpUrl,
        path: String,
        headers: Map<String, String>,
        query: Map<String, String?> = emptyMap(),
    ): String = send(service, "GET", base, path, headers, query, body = null)

    suspend fun post(
        service: HomelabService,
        base: HttpUrl,
        path: String,
        headers: Map<String, String>,
        body: String,
        query: Map<String, String?> = emptyMap(),
    ): String = send(service, "POST", base, path, headers, query, body)

    suspend fun delete(
        service: HomelabService,
        base: HttpUrl,
        path: String,
        headers: Map<String, String>,
        query: Map<String, String?> = emptyMap(),
    ): String = send(service, "DELETE", base, path, headers, query, body = null)

    /** Envoie la requête ; une réponse hors 2xx devient une [HomelabException]. */
    suspend fun send(
        service: HomelabService,
        method: String,
        base: HttpUrl,
        path: String,
        headers: Map<String, String>,
        query: Map<String, String?>,
        body: String?,
    ): String = exchange(service, method, base, path, headers, query, body).body

    /** Comme [send], avec les en-têtes de la réponse (cookies de session). */
    suspend fun exchange(
        service: HomelabService,
        method: String,
        base: HttpUrl,
        path: String,
        headers: Map<String, String>,
        query: Map<String, String?> = emptyMap(),
        body: String? = null,
    ): Reply {
        val url = base.newBuilder().apply {
            val segments = path.trimStart('/')
            if (segments.isNotEmpty()) addPathSegments(segments)
            query.forEach { (name, value) -> if (value != null) addQueryParameter(name, value) }
        }.build()
        val request = Request.Builder()
            .url(url)
            .apply { headers.forEach { (name, value) -> header(name, value) } }
            .header("Accept", "application/json")
            .method(method, body?.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        val reply = try {
            execute(client, request)
        } catch (error: SSLException) {
            throw HomelabException(service, HomelabException.Kind.TLS, error.message ?: error.javaClass.simpleName, cause = error)
        } catch (error: IOException) {
            throw HomelabException(service, HomelabException.Kind.UNREACHABLE, error.message ?: error.javaClass.simpleName, cause = error)
        }
        if (reply.code in 200..299) return reply
        val kind = when (reply.code) {
            401, 403 -> HomelabException.Kind.UNAUTHORIZED
            404 -> HomelabException.Kind.NOT_FOUND
            409 -> HomelabException.Kind.CONFLICT
            else -> HomelabException.Kind.HTTP
        }
        throw HomelabException(
            service = service,
            kind = kind,
            message = "HTTP ${reply.code} ${reply.body.take(200)}".trim(),
            code = reply.code,
            serverMessage = serverMessage(reply.body),
        )
    }

    /**
     * Noms (subjectAltName) du certificat présenté en https par [url], sans vérifier qu'il
     * correspond à l'adresse : sert à retrouver le nom plex.direct d'un serveur Plex appelé par IP.
     * Liste vide si le serveur ne parle pas https ou si le certificat n'est pas reconnu.
     */
    suspend fun certificateNames(url: HttpUrl): List<String> {
        val names = AtomicReference<List<String>>(emptyList())
        val probe = client.newBuilder()
            .hostnameVerifier { _, session ->
                val certificate = runCatching { session.peerCertificates.firstOrNull() as? X509Certificate }.getOrNull()
                names.set(
                    certificate?.subjectAlternativeNames.orEmpty().mapNotNull { entry ->
                        // Type 2 = nom DNS.
                        if (entry.getOrNull(0) == 2) entry.getOrNull(1) as? String else null
                    },
                )
                false
            }
            .build()
        val target = url.newBuilder().scheme("https").port(url.port).encodedPath("/identity").query(null).build()
        try {
            execute(probe, Request.Builder().url(target).build())
        } catch (error: IOException) {
            // Attendu : la vérification du nom échoue, mais le certificat a été lu.
        }
        return names.get()
    }

    /** « message » ou « error » d'une réponse d'erreur JSON. */
    private fun serverMessage(body: String): String? = runCatching {
        val element = json.parseToJsonElement(body)
        val obj = element as? JsonObject ?: return@runCatching null
        listOf("message", "error").firstNotNullOfOrNull { key ->
            (obj[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
        }
    }.getOrNull()

    fun <T> decode(service: HomelabService, strategy: DeserializationStrategy<T>, body: String): T = try {
        json.decodeFromString(strategy, body)
    } catch (error: SerializationException) {
        throw HomelabException(service, HomelabException.Kind.INVALID_RESPONSE, error.message ?: "Réponse illisible", cause = error)
    } catch (error: IllegalArgumentException) {
        throw HomelabException(service, HomelabException.Kind.INVALID_RESPONSE, error.message ?: "Réponse illisible", cause = error)
    }

    private suspend fun execute(client: OkHttpClient, request: Request): Reply = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                val reply = try {
                    response.use { Reply(it.code, it.body.string(), it.headers) }
                } catch (error: IOException) {
                    continuation.resumeWithException(error)
                    return
                }
                continuation.resume(reply)
            }
        })
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .callTimeout(120, TimeUnit.SECONDS)
            .dns(PlexDirectDns)
            .build()
    }
}

/**
 * Les adresses plex.direct (« 100-64-1-2.<empreinte>.plex.direct ») contiennent l'IP du serveur :
 * elles sont résolues sur le téléphone, sans passer par un DNS qui pourrait les bloquer
 * (protection contre le « DNS rebinding » des box). Les autres noms passent par le DNS du système.
 */
object PlexDirectDns : Dns {
    private val pattern = Regex("^(\\d{1,3})-(\\d{1,3})-(\\d{1,3})-(\\d{1,3})\\.[0-9a-z]+\\.plex\\.direct$", RegexOption.IGNORE_CASE)

    override fun lookup(hostname: String): List<InetAddress> {
        val parts = pattern.matchEntire(hostname)?.groupValues?.drop(1)?.map { it.toInt() }
        if (parts == null || parts.any { it > 255 }) return Dns.SYSTEM.lookup(hostname)
        return listOf(InetAddress.getByAddress(hostname, ByteArray(4) { parts[it].toByte() }))
    }
}
