package com.ticketbooking.plugins

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.defaultheaders.DefaultHeaders
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import org.slf4j.event.Level

/**
 * Request logging plus the CORS policy that lets the static frontend (served
 * from a different origin/port) call this API.
 *
 * The allowed origins come from `app.cors.allowedHosts` in application.conf and
 * can be overridden with the CORS_ALLOWED_HOSTS environment variable. A single
 * "*" entry switches on [CORS.Configuration.anyHost], which is convenient for
 * local development but should be replaced with explicit origins in production.
 */
fun Application.configureMonitoring() {
    // Captured up front: inside install(...) blocks the plugin config becomes the
    // receiver, which shadows Application.log.
    val appLog = log

    install(CallLogging) {
        level = Level.INFO
        // Health checks would otherwise flood the log.
        filter { call -> !call.request.path().startsWith("/api/health") }
        format { call ->
            "${call.request.httpMethod.value} ${call.request.path()} -> ${call.response.status()?.value ?: "no status"}"
        }
    }

    install(DefaultHeaders)

    val allowedHosts = environment.config
        .propertyOrNull("app.cors.allowedHosts")
        ?.getList()
        ?.filter { it.isNotBlank() }
        ?: emptyList()

    install(CORS) {
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Delete)
        allowMethod(HttpMethod.Options)

        allowHeader(HttpHeaders.Authorization)
        allowHeader(HttpHeaders.ContentType)

        if (allowedHosts.isEmpty() || allowedHosts.contains("*")) {
            appLog.warn(
                "CORS is configured to allow ANY origin. This is fine for local " +
                    "development but set CORS_ALLOWED_HOSTS to explicit origins before deploying.",
            )
            anyHost()
        } else {
            allowedHosts.forEach { entry ->
                // Entries look like "localhost:3000" or "http://localhost:3000".
                val withoutScheme = entry.substringAfter("://", entry)
                val schemes = when {
                    entry.startsWith("https://") -> listOf("https")
                    entry.startsWith("http://") -> listOf("http")
                    else -> listOf("http", "https")
                }
                allowHost(withoutScheme, schemes = schemes)
            }
            appLog.info("CORS restricted to: ${allowedHosts.joinToString(", ")}")
        }
    }
}
