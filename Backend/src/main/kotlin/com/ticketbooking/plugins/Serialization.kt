package com.ticketbooking.plugins

import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import kotlinx.serialization.json.Json

/**
 * JSON in, JSON out for every route.
 *
 * - `ignoreUnknownKeys` keeps the API forgiving of extra client fields.
 * - `explicitNulls = false` omits null properties instead of emitting `"field": null`,
 *   which is what keeps [com.ticketbooking.dto.ApiError] compact.
 */
fun Application.configureSerialization() {
    install(ContentNegotiation) {
        json(
            Json {
                prettyPrint = true
                ignoreUnknownKeys = true
                encodeDefaults = true
                explicitNulls = false
            },
        )
    }
}
