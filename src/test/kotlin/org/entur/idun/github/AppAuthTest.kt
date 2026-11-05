package org.entur.idun.github

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import org.bouncycastle.openssl.jcajce.JcaPEMWriter
import org.junit.jupiter.api.Test
import java.io.StringWriter
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

class AppAuthTest {
    private val keyPair =
        KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

    // JcaPEMWriter skriver RSAPrivateKey som PKCS#1 (BEGIN RSA PRIVATE KEY), slik GitHub gjør
    private val pem: String =
        StringWriter()
            .also { w -> JcaPEMWriter(w).use { it.writeObject(keyPair.private) } }
            .toString()

    private class MutableClock(
        var now: Instant,
    ) : Clock() {
        override fun getZone() = ZoneOffset.UTC

        override fun withZone(zone: java.time.ZoneId?) = this

        override fun instant(): Instant = now
    }

    private val t0 = Instant.now().truncatedTo(ChronoUnit.SECONDS)

    @Test
    fun `pem er pkcs1`() {
        pem.startsWith("-----BEGIN RSA PRIVATE KEY-----") shouldBe true
    }

    @Test
    fun `jwt er RS256 med riktig iss og levetid`() {
        val auth = AppAuth("12345", pem, MutableClock(t0), githubHttpClient(MockEngine { error("ingen kall") }))
        val algorithm = Algorithm.RSA256(keyPair.public as RSAPublicKey, null)
        val decoded = JWT.require(algorithm).withIssuer("12345").acceptLeeway(0).build().verify(auth.jwt())
        decoded.algorithm shouldBe "RS256"
        decoded.issuer shouldBe "12345"
        decoded.issuedAtAsInstant shouldBe t0.minusSeconds(60)
        decoded.expiresAtAsInstant shouldBe t0.plusSeconds(9 * 60)
        Duration.between(decoded.issuedAtAsInstant, decoded.expiresAtAsInstant).seconds shouldBe 600
    }

    @Test
    fun `jwt signert med feil nokkel avvises`() {
        val auth = AppAuth("12345", pem, MutableClock(t0), githubHttpClient(MockEngine { error("ingen kall") }))
        val other = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        shouldThrow<Exception> {
            JWT.require(Algorithm.RSA256(other.public as RSAPublicKey, null)).build().verify(auth.jwt())
        }
    }

    @Test
    fun `ugyldig pem kaster`() {
        shouldThrow<IllegalArgumentException> {
            AppAuth("1", "ikke en pem", MutableClock(t0), githubHttpClient(MockEngine { error("ingen kall") }))
        }
    }

    @Test
    fun `installasjonstoken hentes med app-jwt og caches til fem minutter for utlop`() =
        runBlocking<Unit> {
            val clock = MutableClock(t0)
            val requests = mutableListOf<Pair<HttpMethod, String>>()
            val auths = mutableListOf<String?>()
            var counter = 0
            val engine =
                MockEngine { req ->
                    requests += req.method to req.url.encodedPath
                    auths += req.headers[HttpHeaders.Authorization]
                    counter++
                    val expires = clock.now.plusSeconds(3600)
                    respond(
                        """{"token":"tok$counter","expires_at":"$expires"}""",
                        HttpStatusCode.Created,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            val auth = AppAuth("12345", pem, clock, githubHttpClient(engine))

            auth.installationToken(7) shouldBe "tok1"
            requests.single() shouldBe (HttpMethod.Post to "/app/installations/7/access_tokens")
            auths.single()!!.startsWith("Bearer ") shouldBe true

            // fortsatt gyldig: 54 min inn, 6 min igjen
            clock.now = t0.plus(Duration.ofMinutes(54))
            auth.installationToken(7) shouldBe "tok1"
            requests.size shouldBe 1

            // 56 min inn: under 5 min igjen, hentes på nytt
            clock.now = t0.plus(Duration.ofMinutes(56))
            auth.installationToken(7) shouldBe "tok2"
            requests.size shouldBe 2

            // annen installasjon har egen cache
            auth.installationToken(8) shouldBe "tok3"
        }

    @Test
    fun `feilrespons ved tokenhenting kaster`() {
        val engine = MockEngine { respond("nei", HttpStatusCode.Unauthorized) }
        val auth = AppAuth("1", pem, MutableClock(t0), githubHttpClient(engine))
        shouldThrow<Exception> { runBlocking { auth.installationToken(1) } }
    }
}
