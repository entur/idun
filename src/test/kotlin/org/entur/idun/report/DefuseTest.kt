package org.entur.idun.report

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import kotlin.system.measureTimeMillis

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

    @Test
    fun `defuse fjerner lenker uansett skjema og store bokstaver`() {
        for (link in listOf("HTTPS://x.example", "Http://x.example", "ftp://x.example", "www.x.example/a", "WWW.x.example")) {
            val out = defuse("se $link her")
            out shouldNotContain "x.example"
            out shouldNotContain "://"
            out.lowercase() shouldNotContain "www."
            out shouldContain "(lenke fjernet)"
        }
    }

    @Test
    fun `defuse lar vanlig tekst med punktum vaere uendret`() {
        defuse("kall foo.bar()") shouldBe "kall foo.bar()"
        defuse("se README.md og a.b.c") shouldBe "se README.md og a.b.c"
    }

    @Test
    fun `defuse fjerner lenker rett etter understrek eller siffer`() {
        for (link in listOf("_https://x.example", "_www.x.example", "1https://x.example")) {
            val out = defuse("see $link x")
            out shouldNotContain "x.example"
            out shouldNotContain "://"
            out.lowercase() shouldNotContain "www."
        }
    }

    @Test
    fun `defuse er rask paa svaert lang input`() {
        var out = ""
        val elapsed = measureTimeMillis { out = defuse("a".repeat(200_000)) }
        (elapsed < 500) shouldBe true
        (out.length <= 300) shouldBe true
    }

    @Test
    fun `defuse lar ingen skjema-markor sta igjen naar lenken krysser forhandskuttet`() {
        // Linjeskiftene slås sammen til ett mellomrom, så «https://» havner innenfor de 20 første tegnene.
        val out = defuse("\n".repeat(72) + "https://x.example", 20)
        out shouldNotContain "://"
        out shouldNotContain "x.example"
    }
}
