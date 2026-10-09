package io.github.deedg.cineswipe.ui.homelab

import android.content.res.Resources
import io.github.deedg.cineswipe.R
import io.github.deedg.cineswipe.data.db.OutboxEntity
import io.github.deedg.cineswipe.data.homelab.HomelabEvent
import io.github.deedg.cineswipe.data.homelab.HomelabException
import io.github.deedg.cineswipe.data.homelab.HomelabService
import io.github.deedg.cineswipe.data.homelab.HomelabUrls
import io.github.deedg.cineswipe.data.homelab.ServiceConfig

/** Message d'erreur d'un service, en une phrase. */
fun errorMessage(resources: Resources, service: HomelabService, kind: HomelabException.Kind, code: Int? = null): String =
    when (kind) {
        HomelabException.Kind.BAD_URL -> resources.getString(R.string.homelab_error_bad_url, service.defaultPort)
        HomelabException.Kind.UNREACHABLE -> resources.getString(R.string.homelab_error_unreachable)
        HomelabException.Kind.TLS -> resources.getString(R.string.homelab_error_tls)
        HomelabException.Kind.LOGIN_DISABLED -> resources.getString(R.string.homelab_error_login_disabled, service.displayName)
        HomelabException.Kind.LOGIN_TIMEOUT -> resources.getString(R.string.homelab_error_login_timeout)
        HomelabException.Kind.UNAUTHORIZED -> resources.getString(R.string.homelab_error_unauthorized)
        HomelabException.Kind.NOT_FOUND -> resources.getString(R.string.homelab_error_not_found, service.displayName)
        HomelabException.Kind.CONFLICT -> resources.getString(R.string.homelab_error_conflict)
        HomelabException.Kind.HTTP ->
            if (code != null) resources.getString(R.string.homelab_error_http, code) else resources.getString(R.string.homelab_error_http_unknown)
        HomelabException.Kind.INVALID_RESPONSE -> resources.getString(R.string.homelab_error_invalid, service.displayName)
        HomelabException.Kind.NOT_CONFIGURED -> resources.getString(R.string.homelab_error_not_configured, service.displayName)
    }

/** Toast des suites d'une demande. */
fun eventMessage(resources: Resources, event: HomelabEvent): String {
    val service = event.service.displayName
    return when (event) {
        is HomelabEvent.AlreadyRequested -> resources.getString(R.string.homelab_event_already, service, event.title)
        is HomelabEvent.Queued -> resources.getString(R.string.homelab_event_queued, service, event.title)
        is HomelabEvent.Failed -> resources.getString(
            R.string.homelab_event_failed,
            service,
            event.title,
            // La raison donnée par le service (quota atteint…) parle plus qu'un message générique.
            event.detail ?: errorMessage(resources, event.service, event.kind),
        )
        is HomelabEvent.CancelFailed -> resources.getString(R.string.homelab_event_cancel_failed, service, event.title)
    }
}

/** Raison enregistrée dans la file d'attente (« UNAUTHORIZED » ou « HTTP:400 »). */
fun outboxErrorMessage(resources: Resources, entry: OutboxEntity): String? {
    val raw = entry.lastError ?: return null
    val kind = HomelabException.Kind.entries.firstOrNull { it.name == raw.substringBefore(':') } ?: return null
    return errorMessage(resources, entry.service, kind, raw.substringAfter(':', "").toIntOrNull())
}

/** Ce qui manque à un service activé pour être prêt (null s'il ne manque rien). */
fun missingSettings(resources: Resources, config: ServiceConfig): String? {
    if (config.isReady) return null
    val missing = buildList {
        if (HomelabUrls.parse(config.url) == null) add(resources.getString(R.string.homelab_missing_url))
        if (config.apiKey.isNullOrBlank()) add(resources.getString(R.string.homelab_missing_key))
        if (config.service == HomelabService.JELLYFIN && config.userId.isNullOrBlank()) add(resources.getString(R.string.homelab_missing_user))
        if (config.service == HomelabService.RADARR && (config.qualityProfileId == null || config.rootFolder.isNullOrBlank())) {
            add(resources.getString(R.string.homelab_missing_radarr))
        }
    }
    return missing.joinToString(", ").ifEmpty { null }
}
