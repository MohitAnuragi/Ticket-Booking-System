package com.ticketbooking.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/** Sweeper tunables, read from `app.hold.*`. */
data class SweeperConfig(
    /** Minutes between passes. Must be well under the hold TTL to be useful. */
    val intervalMinutes: Long = 5,
    /** Holds processed per pass. */
    val batchLimit: Int = 200,
) {
    val intervalMillis: Long get() = intervalMinutes * 60_000L
}

/**
 * Background job that returns lapsed seat holds to the market.
 *
 * WHY THIS EXISTS
 * A hold marks seats LOCKED for 24 hours. Nothing in a request path is guaranteed
 * to run when that deadline passes - if no one opens that event's seat map again,
 * the seats would sit LOCKED forever and become unsellable. [confirmBooking] and
 * the seat map already treat a lapsed hold as expired when they happen to look at
 * it, but that is opportunistic; this job is what makes release certain.
 *
 * FAILURE POLICY
 * [sweepOnce] never throws. A transient database problem must not kill the loop,
 * because a dead sweeper is silent and its symptom (seats slowly leaking out of
 * inventory) would only surface days later. Failures are logged and the next tick
 * simply tries again - the sweep is idempotent, so a missed pass costs nothing but
 * a delay.
 *
 * The work itself is delegated to [BookingService.sweepExpiredHolds] via [sweep],
 * a plain lambda, which keeps this class testable with no database and no server.
 */
class HoldSweeper(
    private val config: SweeperConfig,
    private val sweep: (batchLimit: Int) -> SweepResult,
) {

    private val log: Logger = LoggerFactory.getLogger(HoldSweeper::class.java)

    /** Passes that ended in an exception. Exposed for the smoke test and diagnostics. */
    @Volatile
    var failureCount: Int = 0
        private set

    /**
     * Runs one pass, swallowing any failure.
     *
     * @return what the pass did, or null if it failed.
     */
    fun sweepOnce(): SweepResult? = try {
        val result = sweep(config.batchLimit)
        // Quiet when there is nothing to do: at a 5-minute interval this would
        // otherwise write 288 uninformative lines a day.
        if (result.didNothing) log.debug("Hold sweep: nothing to release") else log.info("Hold sweep: $result")
        result
    } catch (e: Exception) {
        failureCount++
        log.error("Hold sweep failed (failure #$failureCount); will retry on the next tick", e)
        null
    }

    /**
     * Starts the loop in [scope] and returns its [Job] so the caller can cancel it
     * on shutdown.
     *
     * Sweeps immediately, then every [SweeperConfig.intervalMinutes]. The immediate
     * pass matters: holds that lapsed while the process was down are cleared at boot
     * instead of an interval later.
     *
     * Runs on [Dispatchers.IO] because the sweep is blocking JDBC work and must not
     * occupy a request-serving thread.
     */
    fun start(scope: CoroutineScope): Job = scope.launch(Dispatchers.IO) {
        log.info(
            "Hold sweeper started: every ${config.intervalMinutes} minute(s), " +
                "up to ${config.batchLimit} hold(s) per pass",
        )
        while (isActive) {
            sweepOnce()
            delay(config.intervalMillis)
        }
    }
}
