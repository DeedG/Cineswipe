package io.github.deedg.cineswipe.data.update

import io.github.deedg.cineswipe.data.repository.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** Version publiée sur le dépôt public, plus récente que celle installée. */
@Serializable
data class AvailableUpdate(
    val versionCode: Int,
    val versionName: String,
    /** L'APK à télécharger (null : seulement la page de la version). */
    val apkUrl: String? = null,
    val pageUrl: String,
    val notes: String? = null,
)

@Serializable
private data class GithubRelease(
    @SerialName("tag_name") val tag: String = "",
    @SerialName("html_url") val page: String = "",
    val body: String? = null,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val assets: List<GithubAsset> = emptyList(),
)

@Serializable
private data class GithubAsset(
    val name: String = "",
    @SerialName("browser_download_url") val url: String = "",
)

/**
 * Mises à jour : l'appli n'est pas sur le Play Store, elle regarde (une fois par jour au plus) la
 * dernière version publiée sur le dépôt public GitHub. Rien d'autre n'est envoyé qu'une requête
 * anonyme à l'API de GitHub.
 */
class UpdateRepository(
    private val settings: SettingsRepository,
    private val installed: Int,
    /** API des versions du dépôt public ; null : pas de vérification (tests, démo). */
    private val latestUrl: String?,
    private val clock: () -> Long = System::currentTimeMillis,
    private val client: OkHttpClient = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build(),
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** Mise à jour disponible (plus récente que la version installée), ou null. */
    val available: Flow<AvailableUpdate?> = settings.latestUpdate.map { saved ->
        saved?.let { runCatching { json.decodeFromString(AvailableUpdate.serializer(), it) }.getOrNull() }?.takeIf { it.versionCode > installed }
    }

    /** Mise à jour à signaler sur le profil : pas encore écartée par l'utilisateur. */
    val toAnnounce: Flow<AvailableUpdate?> = combine(available, settings.dismissedUpdate) { update, dismissed ->
        update?.takeIf { it.versionCode != dismissed }
    }

    suspend fun dismiss(update: AvailableUpdate) = settings.setDismissedUpdate(update.versionCode)

    /** Vérifie la dernière version publiée ([force] : même si la dernière vérification est récente). */
    suspend fun check(force: Boolean = false): AvailableUpdate? {
        val url = latestUrl ?: return null
        val now = clock()
        if (!force && now - settings.updateCheckedAt.first() < CHECK_INTERVAL_MS) return available.first()
        val release = try {
            withContext(Dispatchers.IO) {
                val request = Request.Builder().url(url).header("Accept", "application/vnd.github+json").build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    json.decodeFromString(GithubRelease.serializer(), response.body.string())
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            null
        } ?: return available.first()
        settings.setUpdateCheckedAt(now)
        val update = parse(release) ?: return available.first()
        settings.setLatestUpdate(json.encodeToString(AvailableUpdate.serializer(), update))
        return update.takeIf { it.versionCode > installed }
    }

    private fun parse(release: GithubRelease): AvailableUpdate? {
        if (release.draft || release.prerelease) return null
        val code = VERSION.find(release.tag)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        return AvailableUpdate(
            versionCode = code,
            versionName = "0.1.$code",
            apkUrl = release.assets.firstOrNull { it.name.endsWith(".apk") }?.url,
            pageUrl = release.page,
            notes = release.body?.take(MAX_NOTES),
        )
    }

    companion object {
        const val LATEST_URL = "https://api.github.com/repos/DeedG/Cineswipe/releases/latest"
        const val RELEASES_PAGE = "https://github.com/DeedG/Cineswipe/releases"
        private const val CHECK_INTERVAL_MS = 20L * 60 * 60 * 1000
        private const val MAX_NOTES = 2_000
        private val VERSION = Regex("""0\.1\.(\d+)""")
    }
}
