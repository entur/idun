package org.entur.idun.review

import com.google.genai.Client
import com.google.genai.types.Content
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.HttpOptions
import com.google.genai.types.Part
import com.google.genai.types.Schema
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory

/** Det SDK-kallet gir tilbake, redusert til det Idun bruker. */
internal data class ModelOutput(
    val text: String?,
    val promptTokens: Int,
    val outputTokens: Int,
)

/** Sømmen rundt det blokkerende SDK-kallet, slik at tester kan telle kall uten å mocke SDK-et. */
internal fun interface ModelCall {
    fun generate(
        systemPrompt: String,
        userPrompt: String,
    ): ModelOutput
}

/**
 * Vurderer en refaktorering med Gemini via Vertex AI. Feiler lukket: alt som ikke er et gyldig, typet svar
 * (budsjett, størrelse, timeout, unntak, ugyldig JSON) blir [ReviewResult.Failed].
 *
 * Konstruksjonen kaller ikke nettverket.
 */
class GeminiReviewer internal constructor(
    private val model: String,
    private val budget: DailyBudget,
    private val timeout: Duration,
    private val maxInputTokens: Int,
    private val priceInPerMTok: Double,
    private val priceOutPerMTok: Double,
    private val call: ModelCall,
) : BehaviourReviewer {
    constructor(
        client: Client,
        model: String,
        budget: DailyBudget,
        timeout: Duration = 4.minutes,
        maxInputTokens: Int = 150_000,
        priceInPerMTok: Double,
        priceOutPerMTok: Double,
    ) : this(model, budget, timeout, maxInputTokens, priceInPerMTok, priceOutPerMTok, vertexCall(client, model, timeout))

    override suspend fun review(input: ReviewInput): ReviewResult {
        try {
            if (!budget.canSpend()) {
                return ReviewResult.Failed("dagsbudsjettet er brukt opp", budgetSpent = true)
            }
            val (system, user) = buildPrompt(input)
            if (estimatedTokens(system, user) > maxInputTokens) {
                return ReviewResult.Failed("endringen er for stor for modellen")
            }

            val output =
                try {
                    withTimeout(timeout) {
                        // runInterruptible avbryter tråden når tiden er ute, så det blokkerende kallet henger ikke igjen.
                        runInterruptible(Dispatchers.IO) { call.generate(system, user) }
                    }
                } catch (e: TimeoutCancellationException) {
                    log.warn("Gemini-kallet overskred tidsgrensen på {}", timeout)
                    return ReviewResult.Failed("modellen svarte ikke innen tidsgrensen på $timeout")
                }

            val cost = cost(output)
            budget.record(cost)

            val text = output.text
            if (text.isNullOrBlank()) return ReviewResult.Failed("modellen ga et tomt svar")
            val response =
                try {
                    reviewJson.decodeFromString(ReviewResponse.serializer(), text)
                } catch (e: IllegalArgumentException) {
                    // Også SerializationException. Bare typen: meldingen siterer modellens svar.
                    log.warn("Modellens svar er ikke gyldig JSON etter skjemaet: {}", e::class.qualifiedName)
                    return ReviewResult.Failed("modellens svar kunne ikke tolkes")
                }
            return ReviewResult.Answered(response, cost)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Gemini-vurderingen feilet", e)
            return ReviewResult.Failed("kallet til modellen feilet: ${e.message ?: e::class.simpleName}")
        }
    }

    private fun cost(output: ModelOutput): Double =
        output.promptTokens / MILLION * priceInPerMTok + output.outputTokens / MILLION * priceOutPerMTok

    private fun estimatedTokens(
        system: String,
        user: String,
    ): Int = (system.length + user.length) / CHARS_PER_TOKEN

    private companion object {
        val log = LoggerFactory.getLogger(GeminiReviewer::class.java)
        const val MILLION = 1_000_000.0
        const val CHARS_PER_TOKEN = 4
    }
}

private fun vertexCall(
    client: Client,
    model: String,
    timeout: Duration,
): ModelCall {
    // HTTP-tidsgrensen ligger litt over korutine-tidsgrensen. Den er en sikkerhetsline hvis avbruddet ikke når fram.
    val httpTimeoutMs = (timeout + 10.seconds).inWholeMilliseconds.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    return ModelCall { system, user ->
        val config =
            GenerateContentConfig
                .builder()
                .systemInstruction(Content.fromParts(Part.fromText(system)))
                .responseMimeType("application/json")
                .responseSchema(reviewResponseSchema())
                .temperature(0f)
                .httpOptions(HttpOptions.builder().timeout(httpTimeoutMs))
                .build()
        val response = client.models.generateContent(model, user, config)
        val usage = response.usageMetadata().orElse(null)
        ModelOutput(
            text = runCatching { response.text() }.getOrNull(),
            promptTokens = usage?.promptTokenCount()?.orElse(0) ?: 0,
            // Tenketokens faktureres som utdata.
            outputTokens = (usage?.candidatesTokenCount()?.orElse(0) ?: 0) + (usage?.thoughtsTokenCount()?.orElse(0) ?: 0),
        )
    }
}

/** JSON-skjemaet for [ReviewResponse], bygget fra serialiseringsbeskrivelsene så navnene aldri kan drive fra hverandre. */
internal fun reviewResponseSchema(): Schema {
    fun str() = Schema.builder().type("STRING").build()

    fun int() = Schema.builder().type("INTEGER").build()

    fun <E : Enum<E>> enumOf(names: List<String>) = Schema.builder().type("STRING").enum_(names).build()

    fun obj(vararg fields: Pair<String, Schema>) =
        Schema
            .builder()
            .type("OBJECT")
            .properties(linkedMapOf(*fields))
            .required(fields.map { it.first })
            .build()

    fun array(items: Schema) = Schema.builder().type("ARRAY").items(items).build()

    val evidence = obj("file" to str(), "line" to int(), "note" to str())
    val answer =
        obj(
            "item" to enumOf<ChecklistItem>(ChecklistItem.serializer().wireNames()),
            "answer" to enumOf<Answer>(Answer.serializer().wireNames()),
            "evidence" to array(evidence),
        )
    val finding = obj("file" to str(), "line" to int(), "text" to str())
    return obj(
        "verdict" to enumOf<Verdict>(Verdict.serializer().wireNames()),
        "summary" to str(),
        "checklist" to array(answer),
        "findings" to array(finding),
    )
}
