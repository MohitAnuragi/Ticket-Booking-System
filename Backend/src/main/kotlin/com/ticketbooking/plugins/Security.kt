package com.ticketbooking.plugins

import com.ticketbooking.dto.ApiError
import com.ticketbooking.util.AuthenticatedUser
import com.ticketbooking.util.ForbiddenException
import com.ticketbooking.util.JwtUtil
import com.ticketbooking.util.UnauthorizedException
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authentication
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.response.respond
import io.ktor.server.routing.RoutingContext

/** Name of the JWT authentication provider, used by `authenticate(JWT_AUTH)`. */
const val JWT_AUTH = "auth-jwt"

/**
 * Installs bearer-token authentication.
 *
 * Verification covers the signature, issuer, audience and expiry. A token that
 * passes all of that but lacks the claims we need is still rejected, so downstream
 * code can rely on a complete [AuthenticatedUser].
 */
fun Application.configureSecurity(jwtUtil: JwtUtil) {
    install(Authentication) {
        jwt(JWT_AUTH) {
            realm = jwtUtil.realm
            verifier(jwtUtil.verifier)

            validate { credential ->
                jwtUtil.extractUser(credential.payload)?.let { JWTPrincipal(credential.payload) }
            }

            // Respond in the standard error envelope rather than letting Ktor emit
            // its default empty 401 body, so clients parse auth failures the same
            // way as every other error.
            challenge { _, _ ->
                call.respond(
                    HttpStatusCode.Unauthorized,
                    ApiError(
                        error = "UNAUTHORIZED",
                        message = "A valid Authorization: Bearer <token> header is required",
                    ),
                )
            }
        }
    }
}

/**
 * The authenticated caller for the current request.
 *
 * @throws UnauthorizedException if the route was not wrapped in `authenticate(JWT_AUTH)`,
 *         or the token lacked usable claims. Throwing beats returning null here: a
 *         protected handler can never accidentally proceed anonymously.
 */
fun RoutingContext.requireUser(jwtUtil: JwtUtil): AuthenticatedUser {
    val principal = call.authentication.principal<JWTPrincipal>()
        ?: throw UnauthorizedException()
    return jwtUtil.extractUser(principal.payload)
        ?: throw UnauthorizedException("Token is missing required claims")
}

/**
 * The authenticated caller, who must be an ADMIN.
 *
 * @throws ForbiddenException with 403 when a valid non-admin token is presented -
 *         distinct from 401, so the client can tell "log in" from "not allowed".
 */
fun RoutingContext.requireAdmin(jwtUtil: JwtUtil): AuthenticatedUser {
    val user = requireUser(jwtUtil)
    if (!user.isAdmin) {
        throw ForbiddenException("This action requires an administrator account")
    }
    return user
}
