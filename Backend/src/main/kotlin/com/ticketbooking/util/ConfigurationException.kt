package com.ticketbooking.util

/**
 * Thrown during startup when the application is misconfigured or a required
 * dependency is unreachable.
 *
 * Deliberately NOT an [ApiException]: this is an operator-facing failure that
 * should stop the process, not an HTTP response. Its message is expected to say
 * exactly which setting to fix.
 */
class ConfigurationException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
