package org.entur.idun.github

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.url
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo
import org.bouncycastle.openssl.PEMKeyPair
import org.bouncycastle.openssl.PEMParser
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter
import java.io.IOException
import java.io.StringReader
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.time.Clock
import java.time.Duration
import java.time.Instant

private val JWT_BACKDATE = Duration.ofSeconds(60)
private val JWT_LIFETIME = Duration.ofMinutes(9)
private val TOKEN_REFRESH_MARGIN = Duration.ofMinutes(5)

/**
 * Autentiserer som GitHub App: signerer app-JWT og henter (og cacher) installasjonstokens.
 * [client] bør komme fra [githubHttpClient].
 */
class AppAuth(
    private val appId: String,
    privateKeyPem: String,
    private val clock: Clock,
    private val client: HttpClient = githubHttpClient(),
    private val baseUrl: String = GITHUB_API_URL,
) {
    private val privateKey: RSAPrivateKey = parsePrivateKey(privateKeyPem)
    private val mutex = Mutex()
    private val cache = mutableMapOf<Long, CachedToken>()

    private data class CachedToken(
        val token: String,
        val expiresAt: Instant,
    )

    fun jwt(): String {
        val now = clock.instant()
        return JWT
            .create()
            .withIssuer(appId)
            .withIssuedAt(now.minus(JWT_BACKDATE))
            .withExpiresAt(now.plus(JWT_LIFETIME))
            .sign(Algorithm.RSA256(null as RSAPublicKey?, privateKey))
    }

    /** Gir et installasjonstoken som er gyldig i minst fem minutter til. */
    suspend fun installationToken(installationId: Long): String =
        mutex.withLock {
            val cached = cache[installationId]
            if (cached != null && cached.expiresAt.minus(TOKEN_REFRESH_MARGIN).isAfter(clock.instant())) {
                return@withLock cached.token
            }
            val response: HttpResponse =
                client.post {
                    url("$baseUrl/app/installations/$installationId/access_tokens")
                    bearerAuth(jwt())
                    header("Accept", GITHUB_ACCEPT)
                    header("X-GitHub-Api-Version", GITHUB_API_VERSION)
                }
            if (!response.status.isSuccess()) {
                throw GitHubApiException(
                    response.status.value,
                    "POST /app/installations/$installationId/access_tokens feilet med ${response.status.value}: " +
                        response.bodyText(),
                )
            }
            val dto = response.body<AccessTokenDto>()
            val fresh = CachedToken(dto.token, Instant.parse(dto.expiresAt))
            cache[installationId] = fresh
            fresh.token
        }

    @Serializable
    private class AccessTokenDto(
        val token: String,
        @SerialName("expires_at") val expiresAt: String,
    )

    private suspend fun HttpResponse.bodyText(): String = runCatching { bodyAsText().take(500) }.getOrDefault("")

    private companion object {
        fun parsePrivateKey(pem: String): RSAPrivateKey {
            val parsed =
                try {
                    PEMParser(StringReader(pem)).use { it.readObject() }
                } catch (e: IOException) {
                    throw IllegalArgumentException("Kan ikke lese privat nøkkel (PEM)", e)
                }
            val converter = JcaPEMKeyConverter()
            val key =
                when (parsed) {
                    is PEMKeyPair -> converter.getKeyPair(parsed).private // PKCS#1
                    is PrivateKeyInfo -> converter.getPrivateKey(parsed) // PKCS#8
                    else -> throw IllegalArgumentException("Ugyldig PEM: forventet RSA privat nøkkel")
                }
            return key as? RSAPrivateKey
                ?: throw IllegalArgumentException("Ugyldig nøkkel: forventet RSA")
        }
    }
}
