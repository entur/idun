package org.entur.idun

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing

private const val DEFAULT_PORT = 8080

/**
 * Avhengigheter som injiseres i [module]. Utvides av senere oppgaver
 * (GitHub-klientfabrikk, reviewer, konfigurasjon).
 */
class Dependencies {
    companion object {
        fun forTest(): Dependencies = Dependencies()

        fun fromEnvironment(): Dependencies = Dependencies()
    }
}

fun Application.module(deps: Dependencies) {
    routing {
        get("/healthz") {
            call.respondText("ok", status = HttpStatusCode.OK)
        }
    }
}

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: DEFAULT_PORT
    val deps = Dependencies.fromEnvironment()
    embeddedServer(Netty, port = port, host = "0.0.0.0") { module(deps) }.start(wait = true)
}
