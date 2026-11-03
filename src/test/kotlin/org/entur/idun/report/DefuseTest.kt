package org.entur.idun.report

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

class DefuseTest {
    @Test
    fun `defuse fjerner lenker, mentions, referanser, HTML og kodeblokker`() {
        val out = defuse("se https://evil.example @stunor #123 <img src=x> ```kotlin``` [x](y)")
        out shouldNotContain "https://"
        out shouldNotContain "@stunor"
        out shouldNotContain "#123"
        out shouldNotContain "<img"
        out shouldNotContain "```"
        out shouldNotContain "]("
        out shouldContain "(lenke fjernet)"
    }

    @Test
    fun `defuse kutter lengde og gjor teksten til en linje`() {
        defuse("a\nb".repeat(500), 300).let {
            it.length shouldBe 300
            it shouldNotContain "\n"
            it.endsWith("…") shouldBe true
        }
    }

    @Test
    fun `defuse lar kort tekst vaere uendret og slar sammen linjeskift`() {
        defuse("hei\r\n\nverden") shouldBe "hei verden"
    }

    @Test
    fun `defuse fjerner ogsaa http-lenker og entiteter som kan bli mention`() {
        val out = defuse("http://a.b/c?d=1 og &#64;stunor")
        out shouldNotContain "http://"
        out shouldNotContain "&#64;"
    }

    @Test
    fun `defuse lar hash uten siffer vaere`() {
        defuse("# overskrift og C#") shouldBe "# overskrift og C#"
    }

    @Test
    fun `defuse kutter aldri lengre enn maxLength`() {
        defuse("x".repeat(50), 10).length shouldBe 10
        defuse("<".repeat(50), 10).length shouldBe 10
    }
}
