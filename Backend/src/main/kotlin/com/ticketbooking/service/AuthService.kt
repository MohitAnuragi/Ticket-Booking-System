package com.ticketbooking.service

import com.ticketbooking.dto.AuthResponse
import com.ticketbooking.dto.LoginRequest
import com.ticketbooking.dto.RegisterRequest
import com.ticketbooking.dto.UserResponse
import com.ticketbooking.model.Role
import com.ticketbooking.model.User
import com.ticketbooking.repository.TransactionRunner
import com.ticketbooking.repository.UserRepository
import com.ticketbooking.util.ConflictException
import com.ticketbooking.util.JwtUtil
import com.ticketbooking.util.NotFoundException
import com.ticketbooking.util.PasswordHasher
import com.ticketbooking.util.TimeProvider
import com.ticketbooking.util.UnauthorizedException
import com.ticketbooking.util.Validators
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * Registration, login and account lookup.
 *
 * Depends only on the [UserRepository] interface, so all of it is unit-testable
 * against an in-memory fake with no database.
 */
class AuthService(
    private val users: UserRepository,
    private val transactions: TransactionRunner,
    private val hasher: PasswordHasher,
    private val jwt: JwtUtil,
    private val time: TimeProvider = TimeProvider.SYSTEM,
) {

    private val log = LoggerFactory.getLogger(AuthService::class.java)

    /**
     * Creates a USER account.
     *
     * Self-registration can never mint an ADMIN: the role is hard-coded here rather
     * than taken from the request, so a client cannot escalate by sending
     * `"role": "ADMIN"`.
     *
     * @throws com.ticketbooking.util.ValidationException on bad input.
     * @throws ConflictException if the email is already registered.
     */
    fun register(request: RegisterRequest): UserResponse {
        val name = Validators.validateName(request.name)
        val email = Validators.validateEmail(request.email)
        val password = Validators.validatePassword(request.password)

        // Hash before opening the transaction: bcrypt at cost 12 takes a few hundred
        // milliseconds and holding a pooled connection through it would waste a
        // scarce connection.
        val passwordHash = hasher.hash(password)

        return transactions.inTransaction {
            if (users.existsByEmail(email)) {
                throw ConflictException(
                    errorCode = "EMAIL_ALREADY_REGISTERED",
                    message = "An account with this email already exists",
                )
            }

            val created = users.create(
                id = UUID.randomUUID(),
                name = name,
                email = email,
                passwordHash = passwordHash,
                role = Role.USER,
                createdAt = time.nowUtc(),
            )
            log.info("Registered new user ${created.id}")
            created.toResponse()
        }
    }

    /**
     * Exchanges credentials for a JWT.
     *
     * A wrong password and an unknown email produce the SAME error, so the endpoint
     * cannot be used to discover which addresses have accounts. The password is
     * verified even when no user was found, so the two paths also take comparable
     * time and cannot be distinguished by response latency.
     *
     * @throws UnauthorizedException on any credential failure.
     */
    fun login(request: LoginRequest): AuthResponse {
        val email = Validators.validateEmail(request.email)
        val password = request.password.orEmpty()
        if (password.isEmpty()) throw UnauthorizedException(INVALID_CREDENTIALS)

        val user = transactions.inTransaction { users.findByEmail(email) }

        if (user == null) {
            // Spend roughly the same time as a real verification.
            hasher.verify(password, DUMMY_HASH)
            throw UnauthorizedException(INVALID_CREDENTIALS)
        }

        if (!hasher.verify(password, user.passwordHash)) {
            log.debug("Failed login attempt for user ${user.id}")
            throw UnauthorizedException(INVALID_CREDENTIALS)
        }

        val token = jwt.generateToken(user.id, user.email, user.role)
        log.info("User ${user.id} logged in")
        return AuthResponse(
            token = token,
            expiresIn = jwt.expiresInSeconds,
            user = user.toResponse(),
        )
    }

    /**
     * The account behind a verified token, for GET /api/auth/me.
     *
     * Reads from the database rather than trusting the token's claims, so a client
     * sees its current name and role.
     *
     * @throws NotFoundException if the account has been deleted since the token was issued.
     */
    fun findCurrentUser(userId: UUID): UserResponse {
        val user = transactions.inTransaction { users.findById(userId) }
            ?: throw NotFoundException("Account no longer exists")
        return user.toResponse()
    }

    /**
     * Ensures an ADMIN account exists, since public registration only ever creates
     * USER accounts and the admin endpoints would otherwise be unreachable.
     *
     * Idempotent. If the email already exists the account is left untouched - this
     * never overwrites a password or silently promotes an existing user.
     *
     * @return true if an admin was created by this call.
     */
    fun ensureBootstrapAdmin(email: String?, password: String?, name: String?): Boolean {
        if (email.isNullOrBlank()) {
            log.info("No ADMIN_BOOTSTRAP_EMAIL configured; skipping admin bootstrap")
            return false
        }
        if (password.isNullOrBlank()) {
            log.warn(
                "ADMIN_BOOTSTRAP_EMAIL is set but ADMIN_BOOTSTRAP_PASSWORD is empty; " +
                    "skipping admin bootstrap. Set both to create the initial admin.",
            )
            return false
        }

        val normalisedEmail = Validators.validateEmail(email, "ADMIN_BOOTSTRAP_EMAIL")
        val validatedPassword = Validators.validatePassword(password, "ADMIN_BOOTSTRAP_PASSWORD")
        val adminName = Validators.validateName(
            name?.takeIf { it.isNotBlank() } ?: "Platform Admin",
            "ADMIN_BOOTSTRAP_NAME",
        )

        val existing = transactions.inTransaction { users.findByEmail(normalisedEmail) }
        if (existing != null) {
            if (!existing.isAdmin) {
                log.warn(
                    "ADMIN_BOOTSTRAP_EMAIL $normalisedEmail already belongs to a " +
                        "non-admin account; leaving it unchanged. Use a different email " +
                        "or promote that account directly in the database.",
                )
            } else {
                log.info("Bootstrap admin already present ($normalisedEmail)")
            }
            return false
        }

        val passwordHash = hasher.hash(validatedPassword)
        transactions.inTransaction {
            users.create(
                id = UUID.randomUUID(),
                name = adminName,
                email = normalisedEmail,
                passwordHash = passwordHash,
                role = Role.ADMIN,
                createdAt = time.nowUtc(),
            )
        }
        log.info("Created bootstrap ADMIN account $normalisedEmail")
        return true
    }

    private fun User.toResponse() = UserResponse(
        id = id.toString(),
        name = name,
        email = email,
        role = role.name,
    )

    private companion object {
        const val INVALID_CREDENTIALS = "Invalid email or password"

        /**
         * A real bcrypt hash of a value nobody can supply, used to equalise timing
         * between "no such user" and "wrong password".
         */
        const val DUMMY_HASH = "\$2a\$12\$C6UzMDM.H6dfI/f/IKcEe.uNCEaLBUeHkgcQPjxLcCLuhFtDkDrqO"
    }
}
