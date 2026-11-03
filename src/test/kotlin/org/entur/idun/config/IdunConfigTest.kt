package org.entur.idun.config

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

class IdunConfigTest {
    private fun ok(yaml: String?): IdunConfig = (IdunConfig.fromYaml(yaml) as ConfigResult.Ok).config

    @Test
    fun `ingen konfig gir standard`() {
        IdunConfig.fromYaml(null) shouldBe ConfigResult.Ok(IdunConfig.DEFAULT)
        ok("").maxChangedLines shouldBe 50
        ok("   \n").maxChangedLines shouldBe 50
    }

    @Test
    fun `standardverdier`() {
        val d = IdunConfig.DEFAULT
        d.maxChangedLines shouldBe 50
        d.protectedPaths.size shouldBe 18
        listOf("specs/x.yaml", "api/openapi/a.yaml", "a.openapi.yaml", "src/main/resources/db/migration/V1.sql", "q.sql",
            "src/main/resources/application-prod.yml", "x.properties", "helm/values.yaml", ".entur/a", "sub/build.gradle.kts",
            "gradle/libs.versions.toml", "gradlew.bat", "settings.gradle", "Dockerfile", "compose.yaml", ".github/workflows/a.yml")
            .forEach { p -> d.protectedPaths.any { it.matches(p) } shouldBe true }
        d.protectedPaths.any { it.matches("src/main/kotlin/A.kt") } shouldBe false

        d.protectedContent.any { it.containsMatchIn("object Users : Table(\"users\")") } shouldBe true
        d.protectedContent.any { it.containsMatchIn("object Users : LongIdTable()") } shouldBe true
        d.protectedContent.any { it.containsMatchIn("object Users : exposed.Table(\"users\")") } shouldBe true
        d.protectedContent.any { it.containsMatchIn("object Users : org.jetbrains.exposed.sql.Table(\"users\")") } shouldBe true
        d.protectedContent.any { it.containsMatchIn("object Users : org.jetbrains.exposed.dao.id.LongIdTable(\"users\")") } shouldBe true
        d.protectedContent.any { it.containsMatchIn("@Entity") } shouldBe true
        d.protectedContent.any { it.containsMatchIn("@jakarta.persistence.Table(name = \"x\")") } shouldBe true
        d.protectedContent.any { it.containsMatchIn("@Entityish") } shouldBe false
        d.protectedContent.any { it.containsMatchIn("val x = 1") } shouldBe false
    }

    @Test
    fun `isSource og isTest bruker standard uten innsnevring`() {
        val d = IdunConfig.DEFAULT
        d.isSource("src/main/kotlin/A.kt") shouldBe true
        d.isSource("app/src/main/java/B.java") shouldBe true
        d.isSource("src/test/kotlin/ATest.kt") shouldBe false
        d.isSource("README.md") shouldBe false
        d.isTest("src/test/kotlin/ATest.kt") shouldBe true
        d.isTest("app/src/testFixtures/kotlin/F.kt") shouldBe true
        d.isTest("src/integrationTest/kotlin/I.kt") shouldBe true
        d.isTest("src/main/kotlin/A.kt") shouldBe false
    }

    @Test
    fun `konfig kan bare gjøre reglene strengere`() {
        val c = ok("maxChangedLines: 500\nprotectedPaths: ['docs/**']")
        c.maxChangedLines shouldBe 50
        c.protectedPaths.any { it.matches("docs/a.md") } shouldBe true
        c.protectedPaths.any { it.matches("specs/x.yaml") } shouldBe true
    }

    @Test
    fun `maxChangedLines under 50 brukes, 0 er gyldig`() {
        ok("maxChangedLines: 30").maxChangedLines shouldBe 30
        ok("maxChangedLines: 0").maxChangedLines shouldBe 0
        ok("maxChangedLines: 50").maxChangedLines shouldBe 50
    }

    @Test
    fun `protectedContent legges til standard`() {
        val c = ok("protectedContent: ['FORBIDDEN_\\d+']")
        c.protectedContent.any { it.containsMatchIn("FORBIDDEN_42") } shouldBe true
        c.protectedContent.any { it.containsMatchIn("@Entity") } shouldBe true
    }

    @Test
    fun `sourcePaths snevrer inn og kan aldri utvide`() {
        val c = ok("sourcePaths: ['app/**']")
        c.isSource("app/src/main/kotlin/A.kt") shouldBe true
        c.isSource("src/main/kotlin/A.kt") shouldBe false
        c.isSource("README.md") shouldBe false
        c.isSource("app/README.md") shouldBe false
        c.isSource("app/src/test/kotlin/ATest.kt") shouldBe false
        // standardlistene er urørt
        c.sourcePaths shouldBe IdunConfig.DEFAULT.sourcePaths
        c.sourceNarrowing shouldBe listOf(Glob("app/**"))
        c.testNarrowing shouldBe null
    }

    @Test
    fun `testPaths snevrer inn på samme måte`() {
        val c = ok("testPaths: ['**/src/test/**']")
        c.isTest("src/test/kotlin/ATest.kt") shouldBe true
        c.isTest("src/testFixtures/kotlin/F.kt") shouldBe false
        c.isTest("docs/src/test.md") shouldBe false
        c.isTest("README.md") shouldBe false
        c.testNarrowing shouldBe listOf(Glob("**/src/test/**"))
        c.sourceNarrowing shouldBe null
    }

    @Test
    fun `ugyldig konfig`() {
        IdunConfig.fromYaml("maxChangedLines: -1").shouldBeInstanceOf<ConfigResult.Invalid>()
        IdunConfig.fromYaml("unknownKey: 1").shouldBeInstanceOf<ConfigResult.Invalid>()
        IdunConfig.fromYaml("protectedContent: ['(']").shouldBeInstanceOf<ConfigResult.Invalid>()
    }

    @Test
    fun `ugyldig yaml og feil typer`() {
        IdunConfig.fromYaml("maxChangedLines: [1, 2").shouldBeInstanceOf<ConfigResult.Invalid>()
        IdunConfig.fromYaml("maxChangedLines: abc").shouldBeInstanceOf<ConfigResult.Invalid>()
        IdunConfig.fromYaml("protectedPaths: nope").shouldBeInstanceOf<ConfigResult.Invalid>()
        IdunConfig.fromYaml("- a\n- b").shouldBeInstanceOf<ConfigResult.Invalid>()
        IdunConfig.fromYaml("just a string").shouldBeInstanceOf<ConfigResult.Invalid>()
    }
}
