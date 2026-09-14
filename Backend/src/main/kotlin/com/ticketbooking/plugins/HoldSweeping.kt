package com.ticketbooking.plugins

import com.ticketbooking.service.HoldSweeper
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.application.log

/**
 * Starts the hold sweeper and stops it with the server.
 *
 * The job is launched in the application's own [kotlinx.coroutines.CoroutineScope],
 * so it inherits the server's lifecycle, and is cancelled explicitly on
 * [ApplicationStopping] so a shutdown is not delayed by an in-flight sleep.
 *
 * Called last in [com.ticketbooking.module]: a background job must never be the
 * reason the API fails to come up.
 */
fun Application.configureHoldSweeper(sweeper: HoldSweeper) {
    val job = sweeper.start(this)

    monitor.subscribe(ApplicationStopping) {
        log.info("Stopping hold sweeper")
        job.cancel()
    }
}
