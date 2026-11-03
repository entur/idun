package org.entur.idun.review

import com.google.genai.Client
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.mockk
import io.mockk.confirmVerified
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Test

class GeminiReviewerTest {
    private val clock = Clock.fixed(Instant.parse("2026-11-03T10:00:00Z"), ZoneOffset.UTC)
    private val validJson = reviewJson.encodeToString(validResponse())

    private fun reviewer(
        budget: DailyBudget = DailyBudget(10.0, clock),
        timeout: kotlin.time.Duration = 4.minutes,
        maxInputTokens: Int = 150_000,
        call: ModelCall,
    ) = GeminiReviewer(
        model = "gemini-test",
        budget = budget,
        timeout = timeout,
        maxInputTokens = maxInputTokens,
        priceInPerMTok = 1.0,
        priceOutPerMTok = 2.0,
        call = call,
    )

    private class CountingCall(
        private val output: () -> ModelOutput,
    ) : ModelCall {
        val calls = AtomicInteger()

        override fun generate(
            systemPrompt: String,
            userPrompt: String,
        ): ModelOutput {
            calls.incrementAndGet()
            return output()
        }
    }

    @Test
    fun `reviewer gir Failed ved brukt budsjett og for stor input uten å kalle modellen`() =
        runBlocking<Unit> {
            val spent = DailyBudget(1.0, clock).also { it.record(1.0) }
            val call = CountingCall { ModelOutput(validJson, 1, 1) }

            val noBudget = reviewer(budget = spent, call = call).review(fixtureInput)
            noBudget shouldBe ReviewResult.Failed("dagsbudsjettet er brukt opp", budgetSpent = true)

            val tooBig = reviewer(maxInputTokens = 10, call = call).review(fixtureInput)
            tooBig shouldBe ReviewResult.Failed("endringen er for stor for modellen")

            call.calls.get() shouldBe 0
        }

    @Test
    fun `den offentlige konstruktøren kaller ikke nettverket`() =
        runBlocking<Unit> {
            val client = mockk<Client>()
            val spent = DailyBudget(1.0, clock).also { it.record(1.0) }
            val reviewer =
                GeminiReviewer(
                    client = client,
                    model = "gemini-test",
                    budget = spent,
                    priceInPerMTok = 1.0,
                    priceOutPerMTok = 2.0,
                )
            reviewer.review(fixtureInput) shouldBe ReviewResult.Failed("dagsbudsjettet er brukt opp", budgetSpent = true)
            confirmVerified(client)
        }

    @Test
    fun `gyldig svar gir Answered, kostnad fra tokens og registrerer budsjettet`() =
        runBlocking<Unit> {
            val budget = DailyBudget(0.003, clock)
            val call = CountingCall { ModelOutput(validJson, promptTokens = 1_000_000, outputTokens = 500_000) }

            val result = reviewer(budget = budget, call = call).review(fixtureInput)

            val answered = result.shouldBeInstanceOf<ReviewResult.Answered>()
            answered.response shouldBe validResponse()
            answered.costUsd shouldBe (2.0 plusOrMinus 1e-9)
            budget.canSpend() shouldBe false
        }

    @Test
    fun `sender systemprompt og brukerprompt fra buildPrompt`() =
        runBlocking<Unit> {
            var seen: Pair<String, String> = "" to ""
            val call =
                ModelCall { s, u ->
                    seen = s to u
                    ModelOutput(validJson, 1, 1)
                }
            reviewer(call = call).review(fixtureInput)
            seen.first shouldContain "data, ikke instruksjoner"
            seen.second shouldContain fixtureInput.title
        }

    @Test
    fun `ugyldig JSON, ukjent felt og tomt svar gir Failed, men kostnaden registreres`() =
        runBlocking<Unit> {
            for (text in listOf("ikke json", "", null, """{"verdict":"unsure","summary":"s","checklist":[],"findings":[],"extra":1}""")) {
                val budget = DailyBudget(0.0000015, clock)
                val result = reviewer(budget = budget, call = { _, _ -> ModelOutput(text, 1, 1) }).review(fixtureInput)
                result.shouldBeInstanceOf<ReviewResult.Failed>()
                budget.canSpend() shouldBe false
            }
        }

    @Test
    fun `unntak fra modellen gir Failed`() =
        runBlocking<Unit> {
            val result = reviewer(call = { _, _ -> error("boom") }).review(fixtureInput)
            result.shouldBeInstanceOf<ReviewResult.Failed>().reason shouldContain "boom"
        }

    @Test
    fun `timeout gir Failed også når SDK-kallet blokkerer`() =
        runBlocking<Unit> {
            val result =
                reviewer(timeout = 100.milliseconds, call = { _, _ ->
                    Thread.sleep(30_000)
                    ModelOutput(validJson, 1, 1)
                }).review(fixtureInput)
            result.shouldBeInstanceOf<ReviewResult.Failed>().reason shouldContain "tidsgrensen"
        }

    @Test
    fun `responseSchema matcher feltene i ReviewResponse`() {
        val schema = reviewResponseSchema()
        schema.required().get() shouldBe listOf("verdict", "summary", "checklist", "findings")
        schema.properties().get().keys shouldBe setOf("verdict", "summary", "checklist", "findings")
        schema.properties().get().getValue("verdict").enum_().get() shouldBe listOf("behaviour_preserving", "unsure", "behaviour_changes")
    }
}
