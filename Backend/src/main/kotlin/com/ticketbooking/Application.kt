package com.ticketbooking

import com.ticketbooking.plugins.DatabaseFactory
import com.ticketbooking.plugins.buildComponents
import com.ticketbooking.plugins.configureDatabase
import com.ticketbooking.plugins.configureHoldSweeper
import com.ticketbooking.plugins.configureMonitoring
import com.ticketbooking.plugins.configureRouting
import com.ticketbooking.plugins.configureSecurity
import com.ticketbooking.plugins.configureSerialization
import com.ticketbooking.plugins.configureStatusPages
import com.ticketbooking.plugins.stringOrNull
import io.ktor.server.application.Application
import io.ktor.server.application.log

/**
 * Entry point.
 *
 * Startup is driven by Ktor's EngineMain, which reads `application.conf` for the
 * port and this module list, so configuration lives in one file rather than in code.
 *
 * Order matters: the database and the dependency graph are built before any route
 * is installed, so the process fails at boot on a bad configuration rather than at
 * the first request that needs it.
 */
fun Application.module() {
    configureSerialization()
    configureMonitoring()
    configureStatusPages()
    configureDatabase()

    val components = buildComponents(environment.config, DatabaseFactory.db)

    configureSecurity(components.jwtUtil)
    configureRouting(components)

    bootstrapAdminAccount(components)

    // Last: the API must be able to serve even if this background job cannot start.
    configureHoldSweeper(components.holdSweeper)
}

/**
 * Creates the initial ADMIN account if configured.
 *
 * Runs after routing is installed so a bootstrap problem cannot stop the API from
 * serving: a missing admin is logged loudly but is not fatal, since the public
 * endpoints are still perfectly usable without one.
 */
private fun Application.bootstrapAdminAccount(components: com.ticketbooking.plugins.AppComponents) {
    val config = environment.config
    try {
        components.authService.ensureBootstrapAdmin(
            email = config.stringOrNull("app.admin.bootstrapEmail"),
            password = config.stringOrNull("app.admin.bootstrapPassword"),
            name = config.stringOrNull("app.admin.bootstrapName"),
        )
    } catch (e: Exception) {
        log.error(
            "Failed to bootstrap the admin account. The API will still start, but " +
                "the admin endpoints will be unreachable until an ADMIN user exists.",
            e,
        )
    }
}
