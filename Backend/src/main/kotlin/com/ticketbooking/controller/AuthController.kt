package com.ticketbooking.controller

import com.ticketbooking.dto.LoginRequest
import com.ticketbooking.dto.RegisterRequest
import com.ticketbooking.plugins.JWT_AUTH
import com.ticketbooking.plugins.requireUser
import com.ticketbooking.service.AuthService
import com.ticketbooking.util.JwtUtil
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

/**
 * Auth endpoints (spec section 6.1).
 *
 *   POST /api/auth/register  - create an account
 *   POST /api/auth/login     - exchange credentials for a JWT
 *   GET  /api/auth/me        - the current account (requires a token)
 *
 * Handlers stay thin on purpose: parse, delegate, respond. All rules live in
 * [AuthService], which keeps the business logic testable without HTTP.
 */
fun Route.authRoutes(authService: AuthService, jwtUtil: JwtUtil) {
    route("/auth") {

        post("/register") {
            val request = call.receive<RegisterRequest>()
            val user = authService.register(request)
            call.respond(HttpStatusCode.Created, user)
        }

        post("/login") {
            val request = call.receive<LoginRequest>()
            val auth = authService.login(request)
            call.respond(HttpStatusCode.OK, auth)
        }

        authenticate(JWT_AUTH) {
            /**
             * Confirms a token is valid and returns the live account, which is how
             * the frontend restores a session on page load.
             */
            get("/me") {
                val caller = requireUser(jwtUtil)
                call.respond(HttpStatusCode.OK, authService.findCurrentUser(caller.userId))
            }
        }
    }
}
