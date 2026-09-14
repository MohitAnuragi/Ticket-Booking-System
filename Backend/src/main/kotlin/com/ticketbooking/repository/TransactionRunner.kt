package com.ticketbooking.repository

import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/**
 * Runs a block of repository calls inside ONE database transaction.
 *
 * Why this exists: seat holding, confirmation and cancellation each need several
 * repository operations to commit or roll back together. Without this the service
 * layer would either have to import Exposed directly - coupling business logic to
 * the persistence library - or give up atomicity.
 *
 * It also keeps services unit-testable: tests supply [DirectTransactionRunner],
 * which simply invokes the block, so hold and confirm rules can be verified with
 * in-memory fakes and no database.
 */
interface TransactionRunner {
    fun <T> inTransaction(block: () -> T): T
}

/** Production implementation: delegates to an Exposed transaction on [db]. */
class ExposedTransactionRunner(private val db: Database) : TransactionRunner {
    override fun <T> inTransaction(block: () -> T): T = transaction(db) { block() }
}

/**
 * Executes the block with no transaction at all.
 *
 * For unit tests against in-memory fakes, where atomicity is not what is under
 * test. Never use in production.
 */
class DirectTransactionRunner : TransactionRunner {
    override fun <T> inTransaction(block: () -> T): T = block()
}
