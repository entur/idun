package org.entur.idun.rules

import io.kotest.matchers.shouldBe
import org.entur.idun.config.ConfigResult
import org.entur.idun.config.IdunConfig
import org.entur.idun.config.fromYaml
import org.junit.jupiter.api.Test

class FileRulesTest {
    private val cfg = IdunConfig.DEFAULT

    private fun src(
        path: String,
        add: Int,
        del: Int,
        status: FileStatus = FileStatus.MODIFIED,
        prev: String? = null,
        patch: String? = "@@",
    ) = ChangedFile(path, prev, status, add, del, patch)

    private val kotlinDao = "src/main/kotlin/org/entur/example/ExampleDao.kt"

    @Test
    fun `liten endring i én kildekodefil er ok (som #526)`() {
        evaluateFiles(listOf(src(kotlinDao, 22, 9)), mapOf(kotlinDao to FileContents("class A", "class A")), cfg).eligible shouldBe true
    }

    @Test
    fun `over 50 linjer stoppes (som #525)`() {
        val r = evaluateFiles(listOf(src(kotlinDao, 54, 29)), mapOf(kotlinDao to FileContents("", "")), cfg)
        r.reasons.single().code shouldBe ReasonCode.TOO_LARGE
        r.reasons.single().detail shouldBe "83 av høyst 50 linjer"
    }

    @Test
    fun `nøyaktig 50 linjer er ok`() {
        evaluateFiles(listOf(src(kotlinDao, 30, 20)), mapOf(kotlinDao to FileContents("", "")), cfg).eligible shouldBe true
    }

    @Test
    fun `endring i spec stoppes (som #396)`() {
        val r = evaluateFiles(listOf(src("specs/schemas/Example.yaml", 6, 9)), emptyMap(), cfg)
        r.reasons.map { it.code } shouldBe listOf(ReasonCode.PROTECTED_PATH)
    }

    @Test
    fun `ny testfil er ok og telles ikke`() {
        val r =
            evaluateFiles(
                listOf(src("src/test/kotlin/ATest.kt", 300, 0, FileStatus.ADDED), src(kotlinDao, 10, 0)),
                mapOf(kotlinDao to FileContents("", "")),
                cfg,
            )
        r.eligible shouldBe true
        r.changedLines shouldBe 10
    }

    @Test
    fun `endret eller omdøpt test stoppes`() {
        classify(src("src/test/kotlin/ATest.kt", 1, 1), cfg) shouldBe FileClass.EXISTING_TEST
        classify(
            src("src/test/kotlin/BTest.kt", 0, 0, FileStatus.RENAMED, prev = "src/test/kotlin/ATest.kt"),
            cfg,
        ) shouldBe FileClass.EXISTING_TEST
        evaluateFiles(listOf(src("src/test/kotlin/ATest.kt", 1, 1)), emptyMap(), cfg)
            .reasons.single().code shouldBe ReasonCode.TEST_CHANGED
    }

    @Test
    fun `flytting inn i eller ut av beskyttet sti stoppes`() {
        classify(src("src/main/kotlin/A.kt", 0, 0, FileStatus.RENAMED, prev = "specs/A.kt"), cfg) shouldBe FileClass.PROTECTED
        classify(src("helm/x.kt", 0, 0, FileStatus.RENAMED, prev = "src/main/kotlin/x.kt"), cfg) shouldBe FileClass.PROTECTED
    }

    @Test
    fun `fil som definerer en Exposed-tabell stoppes uansett navn`() {
        val p = "src/main/kotlin/org/entur/products/common/utils/AuthorityResolver.kt"
        evaluateFiles(
            listOf(src(p, 1, 1)),
            mapOf(p to FileContents("""object AuthorityTable : LongIdTable("authority")""", "x")),
            cfg,
        ).reasons.single().code shouldBe ReasonCode.PROTECTED_CONTENT
    }

    @Test
    fun `beskyttet innhold i after stoppes også`() {
        val p = "src/main/kotlin/A.kt"
        evaluateFiles(
            listOf(src(p, 1, 1)),
            mapOf(p to FileContents("x", "@Entity class A")),
            cfg,
        ).reasons.single().code shouldBe ReasonCode.PROTECTED_CONTENT
    }

    @Test
    fun `kildekodefil uten patch stoppes`() {
        evaluateFiles(listOf(src(kotlinDao, 1, 1, patch = null)), mapOf(kotlinDao to FileContents("", "")), cfg)
            .reasons.single().code shouldBe ReasonCode.MISSING_DIFF
    }

    @Test
    fun `kildekodefil uten innhold i contents stoppes (R2)`() {
        evaluateFiles(listOf(src(kotlinDao, 1, 1)), emptyMap(), cfg)
            .reasons.single().code shouldBe ReasonCode.MISSING_DIFF
    }

    @Test
    fun `ressurser og ukjente filer stoppes`() {
        classify(src("src/main/resources/query.json", 1, 0), cfg) shouldBe FileClass.OTHER
        classify(src("README.md", 1, 0), cfg) shouldBe FileClass.OTHER
        evaluateFiles(listOf(src("README.md", 1, 0)), emptyMap(), cfg)
            .reasons.single().code shouldBe ReasonCode.OTHER_FILE
    }

    @Test
    fun `omdøpt kildekodefil krever at begge stier er kilde`() {
        classify(src(kotlinDao, 0, 0, FileStatus.RENAMED, prev = "src/main/kotlin/Old.kt"), cfg) shouldBe FileClass.SOURCE
        classify(src(kotlinDao, 0, 0, FileStatus.RENAMED, prev = "README.md"), cfg) shouldBe FileClass.OTHER
    }

    @Test
    fun `fjernet testfil er EXISTING_TEST`() {
        classify(src("src/test/kotlin/ATest.kt", 0, 5, FileStatus.REMOVED), cfg) shouldBe FileClass.EXISTING_TEST
    }

    @Test
    fun `flere stoppgrunner gir én reason per fil`() {
        val r =
            evaluateFiles(
                listOf(src("helm/values.yaml", 1, 1), src("README.md", 1, 0), src("src/test/kotlin/ATest.kt", 1, 1)),
                emptyMap(),
                cfg,
            )
        r.reasons.map { it.code } shouldBe listOf(ReasonCode.PROTECTED_PATH, ReasonCode.OTHER_FILE, ReasonCode.TEST_CHANGED)
        r.sourceFiles shouldBe emptyList()
    }

    @Test
    fun `sourceFiles og changedLines teller bare kildekodefiler`() {
        val a = src(kotlinDao, 3, 2)
        val r = evaluateFiles(listOf(a, src("README.md", 100, 0)), mapOf(kotlinDao to FileContents("", "")), cfg)
        r.sourceFiles shouldBe listOf(a)
        r.changedLines shouldBe 5
    }

    @Test
    fun `tom innsnevring av sourcePaths gjør alle filer til OTHER`() {
        val narrowed = (IdunConfig.fromYaml("sourcePaths: []") as ConfigResult.Ok).config
        classify(src(kotlinDao, 1, 1), narrowed) shouldBe FileClass.OTHER
    }

    @Test
    fun `innsnevret maxChangedLines brukes`() {
        val narrowed = (IdunConfig.fromYaml("maxChangedLines: 5") as ConfigResult.Ok).config
        val r = evaluateFiles(listOf(src(kotlinDao, 4, 2)), mapOf(kotlinDao to FileContents("", "")), narrowed)
        r.reasons.single().detail shouldBe "6 av høyst 5 linjer"
    }
}
