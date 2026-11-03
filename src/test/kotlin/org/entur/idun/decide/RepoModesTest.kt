package org.entur.idun.decide

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.entur.idun.github.RepoRef
import org.junit.jupiter.api.Test

class RepoModesTest {
    private val sandbox = RepoRef("entur", "idun-sandbox")
    private val products = RepoRef("entur", "products-api")

    @Test
    fun `gyldig liste gir modus per repo`() {
        val modes = parseRepoModes("entur/idun-sandbox=approve,entur/products-api=advise")
        modes.invalidReason.shouldBeNull()
        modes.modeOf(sandbox) shouldBe Mode.APPROVE
        modes.modeOf(products) shouldBe Mode.ADVISE
    }

    @Test
    fun `mellomrom trimmes og reponavn sammenlignes uten store og små bokstaver`() {
        val modes = parseRepoModes("  Entur/IDUN-Sandbox = approve ,  entur/products-api=advise ")
        modes.modeOf(sandbox) shouldBe Mode.APPROVE
        modes.modeOf(RepoRef("ENTUR", "Products-Api")) shouldBe Mode.ADVISE
    }

    @Test
    fun `repo som ikke er listet er OFF`() {
        parseRepoModes("entur/idun-sandbox=approve").modeOf(products) shouldBe Mode.OFF
    }

    @Test
    fun `tom eller manglende verdi gir OFF for alle`() {
        for (value in listOf(null, "", "   ")) {
            val modes = parseRepoModes(value)
            modes.invalidReason.shouldBeNull()
            modes.modeOf(sandbox) shouldBe Mode.OFF
        }
    }

    @Test
    fun `ukjent modus gjør hele verdien ugyldig`() {
        for (value in listOf("entur/idun-sandbox=aprove,entur/products-api=advise", "entur/idun-sandbox=APPROVE")) {
            val modes = parseRepoModes(value)
            modes.invalidReason.shouldNotBeNull()
            modes.modeOf(sandbox) shouldBe Mode.OFF
            modes.modeOf(products) shouldBe Mode.OFF
        }
    }

    @Test
    fun `off er en gyldig modus`() {
        val modes = parseRepoModes("entur/idun-sandbox=off,entur/products-api=advise")
        modes.invalidReason.shouldBeNull()
        modes.modeOf(sandbox) shouldBe Mode.OFF
        modes.modeOf(products) shouldBe Mode.ADVISE
    }

    @Test
    fun `misformet oppføring gjør hele verdien ugyldig og alle repoer OFF`() {
        val malformed =
            listOf(
                "entur/idun-sandbox=approve,entur/products-api", // mangler =
                "entur/idun-sandbox=approve,products-api=advise", // mangler /
                "entur/idun-sandbox=approve,=advise", // tomt repo
                "entur/idun-sandbox=approve,entur/=advise", // tomt navn
                "entur/idun-sandbox=approve,/products-api=advise", // tom eier
                "entur/idun-sandbox=approve,entur/products-api=", // tom modus
                "entur/idun-sandbox=approve,,entur/products-api=advise", // tom oppføring
                "entur/idun-sandbox=approve,", // avsluttende komma
                "entur/idun-sandbox=approve=advise", // to =
                "entur/a/b=approve", // to /
                "entur/idun-sandbox=advise,Entur/Idun-Sandbox=approve", // duplikat
            )
        for (value in malformed) {
            val modes = parseRepoModes(value)
            modes.invalidReason.shouldNotBeNull()
            modes.modeOf(sandbox) shouldBe Mode.OFF
            modes.modeOf(products) shouldBe Mode.OFF
        }
    }
}
