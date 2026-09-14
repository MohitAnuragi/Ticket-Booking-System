package com.ticketbooking.util

import at.favre.lib.crypto.bcrypt.BCrypt

/**
 * BCrypt password hashing.
 *
 * BCrypt is deliberately slow and salts every hash internally, so two users with
 * the same password get different stored values and brute-forcing a stolen hash
 * is expensive.
 *
 * @param cost work factor, log2 of the number of rounds. 12 is a sensible modern
 *        default (roughly a few hundred milliseconds per hash). Tests pass a low
 *        cost so a suite that hashes many passwords stays fast - never do that in
 *        production.
 */
class PasswordHasher(private val cost: Int = DEFAULT_COST) {

    init {
        require(cost in MIN_COST..MAX_COST) { "bcrypt cost must be between $MIN_COST and $MAX_COST" }
    }

    /**
     * @param plain the password. Must be at most [MAX_PASSWORD_BYTES] bytes;
     *        [Validators.validatePassword] enforces that before we get here.
     * @return a self-describing hash string that embeds the salt and cost.
     */
    fun hash(plain: String): String {
        require(plain.toByteArray(Charsets.UTF_8).size <= MAX_PASSWORD_BYTES) {
            "password exceeds bcrypt's $MAX_PASSWORD_BYTES byte limit"
        }
        return BCrypt.withDefaults().hashToString(cost, plain.toCharArray())
    }

    /**
     * Constant-time-ish comparison of [plain] against [hash].
     *
     * Returns false rather than throwing on a malformed or empty stored hash, so a
     * corrupt row denies access instead of returning a 500.
     */
    fun verify(plain: String, hash: String): Boolean {
        if (hash.isBlank()) return false
        return try {
            BCrypt.verifyer().verify(plain.toCharArray(), hash).verified
        } catch (_: IllegalArgumentException) {
            false
        }
    }

    companion object {
        const val DEFAULT_COST = 12
        const val MIN_COST = 4
        const val MAX_COST = 31

        /** BCrypt silently ignores input past 72 bytes, so longer inputs are rejected. */
        const val MAX_PASSWORD_BYTES = 72

        /** Production instance. */
        val DEFAULT = PasswordHasher()
    }
}
