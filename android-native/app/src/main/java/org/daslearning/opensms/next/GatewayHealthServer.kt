package org.daslearning.opensms.next

import io.ktor.http.ContentType
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing

/** Minimal Phase 0 Ktor probe. The caller owns its lifecycle; it is never auto-started. */
internal class GatewayHealthServer(
    port: Int,
    host: String = "localhost",
) {
    private val server = embeddedServer(
        factory = CIO,
        host = host,
        port = port,
    ) {
        routing {
            get("/health") {
                call.respondText("{\"ok\":true}", ContentType.Application.Json)
            }
        }
    }

    fun start() {
        server.start(wait = false)
    }

    fun stop() {
        server.stop(gracePeriodMillis = 100, timeoutMillis = 500)
    }
}
