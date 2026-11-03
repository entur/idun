package org.entur.idun.server

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.entur.idun.github.RepoRef

/** Webhook-payloads, bare med feltene Idun bruker. Aldri PR-tittel eller -beskrivelse: de hentes fra API-et. */
internal val eventJson = Json { ignoreUnknownKeys = true }

/** `pull_request`-handlingene som kan endre vurderingen. */
internal val HANDLED_PR_ACTIONS =
    setOf("opened", "reopened", "edited", "synchronize", "ready_for_review", "converted_to_draft", "closed")

@Serializable
internal data class InstallationPayload(
    val id: Long,
)

@Serializable
internal data class OwnerPayload(
    val login: String,
)

@Serializable
internal data class RepositoryPayload(
    val name: String,
    val owner: OwnerPayload,
) {
    fun toRef() = RepoRef(owner.login, name)
}

@Serializable
internal data class PullRequestEvent(
    val action: String,
    val number: Int,
    val repository: RepositoryPayload,
    val installation: InstallationPayload? = null,
)

@Serializable
internal data class AppPayload(
    val id: Long,
)

@Serializable
internal data class CheckSuitePayload(
    @SerialName("head_sha") val headSha: String,
    val app: AppPayload? = null,
)

@Serializable
internal data class CheckSuiteEvent(
    val action: String,
    @SerialName("check_suite") val checkSuite: CheckSuitePayload,
    val repository: RepositoryPayload,
    val installation: InstallationPayload? = null,
)

@Serializable
internal data class StatusEvent(
    val sha: String,
    val repository: RepositoryPayload,
    val installation: InstallationPayload? = null,
)

/**
 * Jobben en signert hendelse skal starte, eller `null` når Idun ikke bryr seg om den (ping, ukjente hendelser,
 * uinteressante handlinger, egne check suites, manglende installasjon). Kaster [IllegalArgumentException]
 * (også `SerializationException`) når payloaden ikke har feltene Idun trenger.
 */
internal fun jobFor(
    event: String?,
    body: ByteArray,
    ownAppId: Long,
    assessor: Assessor,
): (suspend () -> Unit)? {
    val text = body.decodeToString()
    return when (event) {
        "pull_request" -> {
            val e = eventJson.decodeFromString<PullRequestEvent>(text)
            val installation = e.installation?.id ?: return null
            if (e.action !in HANDLED_PR_ACTIONS) return null
            suspend { assessor.onPullRequest(installation, e.repository.toRef(), e.number, e.action) }
        }
        "check_suite" -> {
            val e = eventJson.decodeFromString<CheckSuiteEvent>(text)
            val installation = e.installation?.id ?: return null
            // Egne check suites ville ellers gitt en ny vurdering hver gang Idun skriver checken sin.
            if (e.action != "completed" || e.checkSuite.app?.id == ownAppId) return null
            suspend { assessor.onCommit(installation, e.repository.toRef(), e.checkSuite.headSha) }
        }
        "status" -> {
            val e = eventJson.decodeFromString<StatusEvent>(text)
            val installation = e.installation?.id ?: return null
            suspend { assessor.onCommit(installation, e.repository.toRef(), e.sha) }
        }
        else -> null
    }
}
