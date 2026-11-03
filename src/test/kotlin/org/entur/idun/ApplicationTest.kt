package org.entur.idun

import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Test

class ApplicationTest {
    @Test
    fun `healthz svarer 200`() =
        testApplication {
            application { module(Dependencies.forTest()) }
            client.get("/healthz").status shouldBe HttpStatusCode.OK
        }
}
