package org.entur.idun.review

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** En endret kildekodefil slik modellen får se den. `before`/`after` er `null` når filen ikke fantes på den siden. */
data class ReviewFile(
    val path: String,
    val patch: String,
    val before: String?,
    val after: String?,
)

/** Alt modellen får se: tittelen og de endrede filene. Aldri PR-beskrivelsen. */
data class ReviewInput(
    val title: String,
    val files: List<ReviewFile>,
)

@Serializable
enum class Verdict {
    @SerialName("behaviour_preserving")
    BEHAVIOUR_PRESERVING,

    @SerialName("unsure")
    UNSURE,

    @SerialName("behaviour_changes")
    BEHAVIOUR_CHANGES,
}

/** Ett punkt per spørsmål i spec-ens sjekkliste, i samme rekkefølge. */
@Serializable
enum class ChecklistItem {
    @SerialName("signatures")
    SIGNATURES,

    @SerialName("string_literals")
    STRING_LITERALS,

    @SerialName("annotations")
    ANNOTATIONS,

    @SerialName("nulls_exceptions_defaults_order")
    NULLS_EXCEPTIONS_DEFAULTS_ORDER,

    @SerialName("equality")
    EQUALITY,

    @SerialName("initialisation")
    INITIALISATION,

    @SerialName("needs_more_context")
    NEEDS_MORE_CONTEXT,
}

@Serializable
enum class Answer {
    @SerialName("yes")
    YES,

    @SerialName("no")
    NO,

    @SerialName("unsure")
    UNSURE,
}

@Serializable
data class Evidence(
    val file: String,
    val line: Int,
    val note: String,
)

@Serializable
data class ChecklistAnswer(
    val item: ChecklistItem,
    val answer: Answer,
    val evidence: List<Evidence>,
)

@Serializable
data class Finding(
    val file: String,
    val line: Int,
    val text: String,
)

@Serializable
data class ReviewResponse(
    val verdict: Verdict,
    val summary: String,
    val checklist: List<ChecklistAnswer>,
    val findings: List<Finding>,
)

sealed interface ReviewResult {
    data class Answered(
        val response: ReviewResponse,
        val costUsd: Double,
    ) : ReviewResult

    /** Modellen ga ikke et brukbart svar. `budgetSpent` er `true` bare når dagsbudsjettet er brukt opp. */
    data class Failed(
        val reason: String,
        val budgetSpent: Boolean = false,
    ) : ReviewResult
}

fun interface BehaviourReviewer {
    suspend fun review(input: ReviewInput): ReviewResult
}

sealed interface Acceptance {
    data object Accepted : Acceptance

    data class Rejected(
        val why: String,
    ) : Acceptance
}

/** Strengt: ukjente felt i modellens JSON er en feil, ikke noe vi ignorerer. */
internal val reviewJson = Json { ignoreUnknownKeys = false }

/** Navnet en enum-verdi har i JSON (fra `@SerialName`). */
internal fun <E : Enum<E>> KSerializer<E>.wireNames(): List<String> =
    (0 until descriptor.elementsCount).map { descriptor.getElementName(it) }

internal fun ChecklistItem.wireName(): String = ChecklistItem.serializer().wireNames()[ordinal]

/**
 * Fail-closed: `Accepted` bare når modellen aktivt har bekreftet uendret oppførsel, hvert sjekklistepunkt
 * er besvart med nei nøyaktig én gang, og hvert svar har en kilde som peker på en faktisk linje.
 */
fun acceptReview(
    response: ReviewResponse,
    input: ReviewInput,
): Acceptance {
    if (response.verdict != Verdict.BEHAVIOUR_PRESERVING) {
        return Acceptance.Rejected("modellen svarte ${response.verdict.name.lowercase()}")
    }
    if (response.findings.isNotEmpty()) {
        return Acceptance.Rejected("modellen rapporterte ${response.findings.size} funn")
    }

    val answered = response.checklist.groupBy { it.item }
    for (item in ChecklistItem.entries) {
        val answers = answered[item].orEmpty()
        if (answers.size != 1) {
            return Acceptance.Rejected("sjekklistepunktet ${item.wireName()} er besvart ${answers.size} ganger")
        }
    }

    val lineCounts = input.files.associate { it.path to lineCount(it.after ?: it.before) }
    for (answer in response.checklist) {
        if (answer.answer != Answer.NO) {
            return Acceptance.Rejected("sjekklistepunktet ${answer.item.wireName()} er besvart ${answer.answer.name.lowercase()}")
        }
        if (answer.evidence.isEmpty()) {
            return Acceptance.Rejected("sjekklistepunktet ${answer.item.wireName()} mangler kilde")
        }
        for (evidence in answer.evidence) {
            val lines = lineCounts[evidence.file]
                ?: return Acceptance.Rejected("kilden peker på en fil som ikke er endret")
            if (evidence.line !in 1..lines) {
                return Acceptance.Rejected("kilden peker på en linje utenfor filen")
            }
        }
    }
    return Acceptance.Accepted
}

/** Antall linjer, uten å telle den tomme «linjen» etter en avsluttende linjeskift. */
private fun lineCount(text: String?): Int {
    if (text.isNullOrEmpty()) return 0
    val lines = text.split('\n').size
    return if (text.endsWith('\n')) lines - 1 else lines
}
