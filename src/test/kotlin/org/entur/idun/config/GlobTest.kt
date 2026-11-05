package org.entur.idun.config

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class GlobTest {
    @Test
    fun `glob uten skråstrek matcher filnavn på alle nivåer`() {
        Glob("*.gradle.kts").matches("sub/build.gradle.kts") shouldBe true
        Glob("*.gradle.kts").matches("build.gradle.kts") shouldBe true
        Glob("**/db/migration/**").matches("db/migration/V1__x.sql") shouldBe true
        Glob("**/db/migration/**").matches("app/src/main/resources/db/migration/V1__x.sql") shouldBe true
        Glob("**/src/main/**/*.kt").matches("src/main/kotlin/org/A.kt") shouldBe true
        Glob("**/src/main/**/*.kt").matches("app/src/main/kotlin/A.kt") shouldBe true
        Glob("**/src/main/**/*.kt").matches("src/main/A.kt") shouldBe true
        Glob("specs/**").matches("src/specs/a.yaml") shouldBe false
    }

    @Test
    fun `glob med skråstrek er forankret i roten`() {
        Glob("specs/**").matches("specs/a.yaml") shouldBe true
        Glob("specs/**").matches("specs/deep/a.yaml") shouldBe true
        Glob("gradle/**").matches("gradle/wrapper/x.jar") shouldBe true
        Glob("gradle/**").matches("sub/gradle/x.jar") shouldBe false
        Glob(".github/**").matches(".github/workflows/ci.yml") shouldBe true
    }

    @Test
    fun `stjerne matcher bare innenfor ett nivå`() {
        Glob("src/*.kt").matches("src/A.kt") shouldBe true
        Glob("src/*.kt").matches("src/sub/A.kt") shouldBe false
        Glob("application*.yml").matches("application-test.yml") shouldBe true
        Glob("application*.yml").matches("src/main/resources/application.yml") shouldBe true
    }

    @Test
    fun `dobbeltstjerne matcher hele mappenivåer, ikke deler av navn`() {
        Glob("**/openapi/**").matches("openapi/a.yaml") shouldBe true
        Glob("**/openapi/**").matches("x/y/openapi/a.yaml") shouldBe true
        Glob("**/openapi/**").matches("xopenapi/a.yaml") shouldBe false
        Glob("**/openapi/**").matches("my-openapi/a.yaml") shouldBe false
    }

    @Test
    fun `filnavn uten mønstertegn matcher eksakt på alle nivåer`() {
        Glob("Dockerfile").matches("Dockerfile") shouldBe true
        Glob("Dockerfile").matches("app/Dockerfile") shouldBe true
        Glob("Dockerfile").matches("Dockerfile.dev") shouldBe false
        Glob("Dockerfile").matches("MyDockerfile") shouldBe false
    }

    @Test
    fun `regex-tegn i mønsteret tolkes bokstavelig, og matching er case-sensitiv`() {
        Glob("*.sql").matches("a_sql") shouldBe false
        Glob("*.sql").matches("a.SQL") shouldBe false
        Glob("a+b.txt").matches("a+b.txt") shouldBe true
        Glob("a+b.txt").matches("aab.txt") shouldBe false
    }

    @Test
    fun `dobbeltstjerne matcher også linjeskift og andre linjeavslutninger i stinavn`() {
        Glob("specs/**").matches("specs/a\nb.yaml") shouldBe true
        Glob("specs/**").matches("specs/a\rb.yaml") shouldBe true
        Glob("specs/**").matches("specs/a\u0085b.yaml") shouldBe true
        Glob(".github/**").matches(".github/a\u2028b.yml") shouldBe true
        Glob(".github/**").matches(".github/a\u2029b.yml") shouldBe true
        Glob("*.sql").matches("a\nb.sql") shouldBe true
    }
}
